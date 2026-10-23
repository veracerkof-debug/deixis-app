package app.deixis.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** /api/outputs с подставным набором выводов, а также данные для экрана в браузере. */
class OutputsTest {
    private val core = ReceiverCore(
        ReceiverInfo("pc-1", "Компьютер", "test", "0"),
        FileStore(File(createTempDirectory("outputs").toFile(), "files")),
        Pairing(MemoryTokenStore()),
    )
    private val server = ReceiverServer(core, port = 0)
    private val http = HttpClient(CIO) { install(WebSockets) }
    private var port = 0
    private lateinit var token: String

    private class FakeHost : OutputsHost {
        var monitors = listOf(
            MonitorOutput("d0", "Монитор 1", 1920, 1080, primary = true),
            MonitorOutput("d1", "Монитор 2", 1280, 720, primary = false, enabled = true),
        )
        val unlinked = mutableListOf<String>()
        var lastLink: LinkRequest? = null
        override fun state() = OutputsState(monitors, listOf(NetworkOutput("tv", "ТВ", "10.0.0.5", 47820, state = "online", linked = true)), "http://10.0.0.2:47820/view?k=x&screen=1")
        override fun setMonitor(req: MonitorRequest) {
            monitors = monitors.map { if (it.id == req.id) it.copy(enabled = req.enabled, layout = req.layout) else it }
        }
        override suspend fun link(req: LinkRequest): LinkResult {
            lastLink = req
            return if (req.pin == null) LinkResult(false, needPin = true) else LinkResult(true)
        }
        override fun unlink(id: String) { unlinked += id }
    }

    @BeforeTest
    fun start() = runBlocking {
        port = server.start()
        token = (core.pairing.pair(core.pairing.pin, "Этот компьютер") as Pairing.Result.Ok).token
    }

    @AfterTest
    fun stop() {
        http.close()
        server.stop()
    }

    private fun url(p: String) = "http://127.0.0.1:$port$p"

    private suspend fun post(p: String, body: String) = http.post(url(p)) {
        header("Authorization", "Bearer $token")
        setBody(body)
    }

    @Test
    fun withoutHostOutputsAreNotImplemented() = runBlocking {
        assertEquals(HttpStatusCode.Unauthorized, http.get(url("/api/outputs")).status)
        assertEquals(HttpStatusCode.NotImplemented, http.get(url("/api/outputs")) { header("Authorization", "Bearer $token") }.status)
    }

    @Test
    fun routesReachTheHost() = runBlocking {
        val host = FakeHost()
        core.outputs = host
        assertEquals(HttpStatusCode.Unauthorized, http.get(url("/api/outputs")).status)
        assertEquals(HttpStatusCode.Unauthorized, http.post(url("/api/outputs/monitor")) { setBody("""{"id":"d0","enabled":true}""") }.status)

        val state = DeixisJson.decodeFromString(OutputsState.serializer(), http.get(url("/api/outputs")) { header("Authorization", "Bearer $token") }.bodyAsText())
        assertEquals(2, state.monitors.size)
        assertEquals("online", state.network.single().state)
        assertTrue(state.webScreenUrl!!.endsWith("screen=1"))

        val after = DeixisJson.decodeFromString(OutputsState.serializer(), post("/api/outputs/monitor", """{"id":"d0","enabled":true,"layout":"STAGE"}""").bodyAsText())
        assertEquals(MonitorOutput("d0", "Монитор 1", 1920, 1080, primary = true, layout = OutputLayout.STAGE, enabled = true), after.monitors[0])

        val needPin = DeixisJson.decodeFromString(LinkResult.serializer(), post("/api/outputs/link", """{"id":"tv"}""").bodyAsText())
        assertTrue(needPin.needPin)
        val ok = DeixisJson.decodeFromString(LinkResult.serializer(), post("/api/outputs/link", """{"id":"tv","pin":"1234"}""").bodyAsText())
        assertTrue(ok.ok)
        assertEquals("1234", host.lastLink?.pin)

        assertEquals(HttpStatusCode.OK, post("/api/outputs/unlink", """{"id":"tv"}""").status)
        assertEquals(listOf("tv"), host.unlinked)
    }

    @Test
    fun webScreenGetsNextSlideAndIsNotAViewer() = runBlocking {
        core.apply(Show(TextContent("Сейчас"), next = TextContent("Потом")))
        suspend fun read(path: String): ViewerState {
            var out: ViewerState? = null
            http.webSocket(method = HttpMethod.Get, host = "127.0.0.1", port = port, path = path) {
                while (out == null) {
                    val text = withTimeout(5_000) { (incoming.receive() as Frame.Text).readText() }
                    if (text != VIEW_TICK) out = DeixisJson.decodeFromString(ViewerState.serializer(), text)
                }
                if (path.contains("screen=1")) assertEquals(0, core.state.value.viewers, "экран в браузере — не зритель")
            }
            return out!!
        }
        val screen = read("/view/ws?screen=1&k=${core.viewKey}")
        assertEquals(TextContent("Потом"), screen.next)
        assertTrue(screen.startedAt != null && screen.now != null)
        // Обычный зритель следующего слайда не видит.
        assertNull(read("/view/ws?k=${core.viewKey}").next)
    }

    @Test
    fun screenPageIsTheViewerPage() = runBlocking {
        val page = http.get(url("/view?k=${core.viewKey}&screen=1&layout=stage")).bodyAsText()
        assertTrue(page.contains("screenMode"))
        assertEquals(Regex("<style>").findAll(page).count(), Regex("</style>").findAll(page).count(), "стили закрыты")
    }
}
