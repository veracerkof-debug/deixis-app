package app.deixis.core.quiz

import app.deixis.core.Content
import app.deixis.core.QuizContent
import app.deixis.core.QuizPhase
import app.deixis.core.TextContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QuizHubTest {
    private val shown = mutableListOf<Content>()
    private val scope = TestScope()
    private var now = 5_000_000L
    private val hub = QuizHub(scope, { shown += it }, { now })

    private val json = """{"format":"deixis-quiz","version":1,"title":"Урок","language":"ru","settings":{"defaultTimeLimitSec":20},"questions":[
      {"id":"q1","type":"single","text":"Река?","points":100,"explanation":"Волга","options":[{"id":"a","text":"Волга","correct":true},{"id":"b","text":"Дон"}]},
      {"id":"q2","type":"truefalse","text":"Верно?","points":100,"answer":true}]}"""

    private class Player(val id: Long, val out: Channel<PlayOut>) {
        fun drain(): List<PlayOut> = buildList { while (true) add(out.tryReceive().getOrNull() ?: break) }
    }

    private fun player(name: String): Player {
        val (id, out) = hub.connect()
        return Player(id, out).also { hub.handle(id, PlayIn.Join(name)) }
    }

    @Test
    fun badFileDoesNotOpenAnythingAndNothingIsShown() {
        val r = hub.open("не json", null)
        assertFalse(r.ok)
        assertFalse(hub.active)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun lobbyShowsJoinAddressAndPlayerCountInQuizLanguage() {
        hub.open(json, "http://192.168.1.5:47820/play?k=abc")
        val t = assertIs<TextContent>(shown.last())
        assertTrue(t.text.contains("http://192.168.1.5:47820/play?k=abc") && t.text.contains("Присоединяйтесь") && t.text.contains("0"), t.text)
        val p = player("Аня")
        val got = p.drain()
        assertIs<PlayOut.Joined>(got.first())
        assertTrue(got.any { it is PlayOut.Lobby && it.players == 1 })
        assertTrue((shown.last() as TextContent).text.endsWith("1"))
    }

    @Test
    fun questionGoesToHallAndPlayersWithoutCorrectAnswers() {
        hub.open(json, null)
        val a = player("Аня"); val b = player("Борис")
        a.drain(); b.drain()
        assertTrue(hub.start())
        val hall = assertIs<QuizContent>(shown.last())
        assertEquals(QuizPhase.QUESTION, hall.phase)
        assertTrue(hall.correct.isEmpty() && hall.running && hall.totalMs == 20_000L)
        val q = a.drain().filterIsInstance<PlayOut.Question>().single()
        assertEquals(listOf("A", "B"), q.options.map { it.mark })
        assertEquals(20_000L, q.totalMs)
        assertTrue(DeixisJsonText.encode(q).none { it == "correct" }, "в вопросе игроку нет верных ответов")
    }

    @Test
    fun answersCloseByTimerAndRevealIsPersonal() {
        hub.open(json, null)
        val a = player("Аня"); val b = player("Борис")
        hub.start(); a.drain(); b.drain()
        now += 2_000
        hub.handle(a.id, PlayIn.Answer(listOf("a")))
        hub.handle(b.id, PlayIn.Answer(listOf("b")))
        assertIs<PlayOut.Accepted>(a.drain().single())
        assertEquals(1, hub.hostState()!!.counts["a"])
        scope.advanceTimeBy(20_000 + 500); scope.runCurrent()
        val ra = a.drain().filterIsInstance<PlayOut.Reveal>().single()
        val rb = b.drain().filterIsInstance<PlayOut.Reveal>().single()
        assertTrue(ra.right && ra.earned > 0 && ra.rank == 1 && ra.correct == listOf("a") && ra.explanation == "Волга")
        assertFalse(rb.right); assertEquals(0, rb.earned); assertEquals(2, rb.rank)
        assertEquals(QuizPhase.REVEAL, (shown.last() as QuizContent).phase)
        assertEquals(setOf("a"), (shown.last() as QuizContent).correct)
    }

    @Test
    fun hostClosesEarlyAndTimerDoesNotCloseTheNextQuestion() {
        hub.open(json, null)
        val a = player("Аня"); hub.start(); a.drain()
        scope.advanceTimeBy(10_000); scope.runCurrent()
        assertTrue(hub.closeQuestion())
        assertFalse(hub.closeQuestion(), "второй раз нельзя")
        assertTrue(hub.next())
        a.drain()
        // Таймер первого вопроса сработал бы на 20,4 с; второй — только на 30,4 с.
        scope.advanceTimeBy(11_000); scope.runCurrent()
        assertEquals(GamePhase.QUESTION, hub.hostState()!!.phase, "таймер первого вопроса не закрыл второй")
        assertTrue(a.drain().none { it is PlayOut.Reveal })
    }

    @Test
    fun gameEndsWithBoardForHallAndPlayers() {
        hub.open(json, null)
        val a = player("Аня"); hub.start()
        hub.handle(a.id, PlayIn.Answer(listOf("a"))); hub.closeQuestion(); hub.next()
        hub.handle(a.id, PlayIn.Answer(listOf("true"))); hub.closeQuestion()
        a.drain()
        assertTrue(hub.board())
        assertTrue((shown.last() as TextContent).text.startsWith("Таблица"))
        assertIs<PlayOut.Board>(a.drain().single())
        assertTrue(hub.next())
        assertTrue((shown.last() as TextContent).text.startsWith("Игра окончена"))
        val end = a.drain().filterIsInstance<PlayOut.End>().single()
        assertEquals(1, end.me!!.rank)
        assertFalse(hub.next(), "дальше некуда")
    }

    @Test
    fun reconnectGetsCurrentStateByToken() {
        hub.open(json, null)
        val a = player("Аня"); val token = (a.drain().first() as PlayOut.Joined).token
        hub.start()
        hub.handle(a.id, PlayIn.Answer(listOf("b")))
        hub.disconnect(a.id)
        val (id2, out2) = hub.connect()
        val p2 = Player(id2, out2)
        hub.handle(id2, PlayIn.Rejoin(token))
        val got = p2.drain()
        assertIs<PlayOut.Joined>(got[0])
        val q = got.filterIsInstance<PlayOut.Question>().single()
        assertEquals(listOf("b"), q.picked, "выбор сохранился")
        hub.closeQuestion()
        val rejoinInReveal = hub.connect().let { Player(it.first, it.second) }
        hub.handle(rejoinInReveal.id, PlayIn.Rejoin(token))
        val rev = rejoinInReveal.drain().filterIsInstance<PlayOut.Reveal>().single()
        assertEquals("Река?", rev.text, "вернувшийся на результате видит вопрос")
        val bad = hub.connect().let { Player(it.first, it.second) }
        hub.handle(bad.id, PlayIn.Rejoin("чужой"))
        assertEquals(PlayOut.Error("unknown_token"), bad.drain().single())
    }

    @Test
    fun joinErrorsAndEndedGame() {
        hub.open(json, null)
        player("Аня")
        val dup = player("аня")
        assertEquals(PlayOut.Error("name_taken"), dup.drain().single())
        assertEquals(PlayOut.Error("bad_name"), player("").drain().single())
        hub.start()
        assertEquals(PlayOut.Error("closed"), player("Поздний").drain().single(), "после старта новые не входят")
        val a = hub.connect().let { Player(it.first, it.second) }
        hub.end()
        assertFalse(hub.active)
        assertNull(hub.hostState())
        hub.handle(a.id, PlayIn.Join("Х"))
        assertEquals(PlayOut.Closed, a.drain().last())
    }

    @Test
    fun openingANewGameClosesThePreviousOne() {
        hub.open(json, null)
        val a = player("Аня"); a.drain()
        hub.open(json, null)
        assertTrue(a.drain().any { it is PlayOut.Closed })
        assertEquals(0, hub.hostState()!!.players.size)
    }
}

/** Ключи верхнего уровня JSON сообщения: проверка, что поле «correct» не уходит игроку до результата. */
private object DeixisJsonText {
    fun encode(m: PlayOut): List<String> =
        app.deixis.core.DeixisJson.parseToJsonElement(app.deixis.core.DeixisJson.encodeToString(PlayOut.serializer(), m)).let { (it as kotlinx.serialization.json.JsonObject).keys.toList() }
}
