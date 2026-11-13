package app.deixis.core

import app.deixis.core.quiz.PlayIn
import app.deixis.core.quiz.PlayOut
import app.deixis.core.quiz.QuizHostState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Живая игра по сети: страница `/play`, канал игроков, команды ведущего. */
class QuizPlayRoutesTest {
    private val dir = createTempDirectory("quizplay").toFile()
    private val core = ReceiverCore(ReceiverInfo("pc", "Компьютер", "test", "0"), FileStore(File(dir, "files")), Pairing(MemoryTokenStore()))
    private val server = ReceiverServer(core, port = 0)
    private val http = HttpClient(CIO) { install(WebSockets) }
    private var port = 0
    private lateinit var token: String

    private val quiz = """{"title":"Урок","language":"ru","questions":[
      {"id":"q1","type":"single","text":"Река?","points":100,"options":[{"id":"a","text":"Волга","correct":true},{"id":"b","text":"Дон"}]}]}"""

    @BeforeTest
    fun start() = runBlocking {
        port = server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        token = SenderClient("127.0.0.1", port, "пульт", scope).let { c -> c.pair(core.pairing.pin).also { c.close() } }
        scope.cancel()
    }

    @AfterTest
    fun stop() { http.close(); server.stop() }

    private fun url(p: String) = "http://127.0.0.1:$port$p"
    private suspend fun host(path: String, body: String? = null) = http.post(url(path)) {
        header("Authorization", "Bearer $token")
        body?.let { setBody(it) }
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.next(): PlayOut =
        withTimeout(5_000) { DeixisJson.decodeFromString(PlayOut.serializer(), (incoming.receive() as Frame.Text).readText()) }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.say(m: PlayIn) =
        send(Frame.Text(DeixisJson.encodeToString(PlayIn.serializer(), m)))

    @Test
    fun pageIsServedWithoutKeyAndHostCommandsNeedAToken() = runBlocking<Unit> {
        val page = http.get(url("/play"))
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("/play/ws"))
        assertEquals(HttpStatusCode.Unauthorized, http.post(url("/api/quiz/open")) { setBody(quiz) }.status)
        assertEquals(HttpStatusCode.Unauthorized, http.post(url("/api/quiz/start")).status)
        assertEquals(HttpStatusCode.Unauthorized, http.get(url("/api/quiz/state")).status)
        assertEquals(HttpStatusCode.UnprocessableEntity, host("/api/quiz/open", "не json").status)
        assertEquals(HttpStatusCode.Conflict, host("/api/quiz/start").status, "игры нет")
    }

    @Test
    fun playersNeedTheViewKey() = runBlocking<Unit> {
        var closedWithBadKey = false
        try {
            http.webSocket("ws://127.0.0.1:$port/play/ws?k=bad") {
                val r = withTimeout(5_000) { closeReason.await() }
                closedWithBadKey = r?.code == VIEW_BAD_KEY
            }
        } catch (e: Exception) { closedWithBadKey = true }
        assertTrue(closedWithBadKey)
    }

    @Test
    fun fullGameOverTheNetwork() = runBlocking<Unit> {
        assertEquals(HttpStatusCode.OK, host("/api/quiz/open?join=http%3A%2F%2Fhost%2Fplay", quiz).status)
        assertTrue((core.state.value.content as TextContent).text.contains("http://host/play"))
        http.webSocket("ws://127.0.0.1:$port/play/ws?k=${core.viewKey}") {
            say(PlayIn.Join("Аня"))
            assertIs<PlayOut.Joined>(next())
            assertEquals(1, assertIs<PlayOut.Lobby>(next()).players)

            assertEquals(HttpStatusCode.NoContent, host("/api/quiz/start").status)
            val q = assertIs<PlayOut.Question>(next())
            assertEquals("Река?", q.text)
            assertIs<QuizContent>(core.state.value.content)

            say(PlayIn.Answer(listOf("a")))
            assertIs<PlayOut.Accepted>(next())
            val st = DeixisJson.decodeFromString(QuizHostState.serializer(), http.get(url("/api/quiz/state")) { header("Authorization", "Bearer $token") }.bodyAsText())
            assertEquals(listOf("Аня"), st.answered)

            assertEquals(HttpStatusCode.NoContent, host("/api/quiz/close").status)
            val r = assertIs<PlayOut.Reveal>(next())
            assertTrue(r.right)
            assertEquals(setOf("a"), (core.state.value.content as QuizContent).correct)

            assertEquals(HttpStatusCode.NoContent, host("/api/quiz/next").status)
            val end = assertIs<PlayOut.End>(next())
            assertEquals(100, end.me!!.score)
            assertEquals(HttpStatusCode.NoContent, host("/api/quiz/end").status)
            assertEquals(PlayOut.Closed, next())
        }
    }

    @Test
    fun senderClientHostsAGame() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = SenderClient("127.0.0.1", port, "пульт-2", scope)
        try {
            client.pair(core.pairing.pin)
            assertEquals(null, client.quizState(), "игры нет")
            val bad = client.quizOpen("не json", null)
            assertIs<SenderClient.QuizOpenResult.Invalid>(bad)
            assertEquals("bad_json", bad.issues.first().code)
            assertEquals(SenderClient.QuizOpenResult.Ok, client.quizOpen(quiz, "http://host/play?k=x"))
            assertEquals(false, client.quizCommand("close"), "вопрос ещё не открыт")
            assertEquals(true, client.quizCommand("start"))
            val st = client.quizState()!!
            assertEquals("Урок", st.title)
            assertEquals("http://host/play?k=x", st.joinUrl)
            assertEquals(true, client.quizCommand("end"))
            assertEquals(null, client.quizState())
        } finally { client.close(); scope.cancel() }
    }

    @Test
    fun noGameMeansClosedNotSilence() = runBlocking<Unit> {
        http.webSocket("ws://127.0.0.1:$port/play/ws?k=${core.viewKey}") {
            assertEquals(PlayOut.Closed, next())
        }
    }
}
