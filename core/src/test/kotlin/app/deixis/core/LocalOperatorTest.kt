package app.deixis.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Окно пульта на самом компьютере: закрытие убирает зал, перезагрузка — нет; второй запуск возвращает зал. */
class LocalOperatorTest {
    private val dir = createTempDirectory("local-op").toFile()
    private val core = ReceiverCore(ReceiverInfo("pc", "Компьютер", "test", "0"), FileStore(File(dir, "files")), Pairing(MemoryTokenStore()))
    private val server = ReceiverServer(core, port = 0)
    private val http = HttpClient(CIO) { install(WebSockets) }
    private val closed = LinkedBlockingQueue<Boolean>()
    private var port = 0
    private lateinit var token: String

    @BeforeTest
    fun start() = runBlocking {
        port = server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        token = SenderClient("127.0.0.1", port, "Этот компьютер", scope).let { c -> c.pair(core.pairing.pin).also { c.close() } }
        scope.cancel()
        core.onOperatorClosed = { closed += it }
    }

    @AfterTest
    fun stop() { http.close(); server.stop() }

    private suspend fun beacon(cookie: String? = token) =
        http.post("http://127.0.0.1:$port/remote/closed") { cookie?.let { header("Cookie", "deixis=$it") } }.status

    @Test
    fun closingTheWindowLeavesTheScreens() = runBlocking<Unit> {
        assertEquals(HttpStatusCode.NoContent, beacon())
        assertEquals(false, closed.poll(ReceiverServer.OPERATOR_CLOSE_GRACE_MS + 3_000, TimeUnit.MILLISECONDS))
    }

    @Test
    fun beaconWithoutAccessIsIgnored() = runBlocking<Unit> {
        assertEquals(HttpStatusCode.Forbidden, beacon(null))
        assertEquals(HttpStatusCode.Forbidden, beacon("чужой"))
        assertNull(closed.poll(ReceiverServer.OPERATOR_CLOSE_GRACE_MS + 1_000, TimeUnit.MILLISECONDS))
    }

    /** Перезагрузка страницы: сигнал о закрытии пришёл, но пульт снова подключился — зал остаётся. */
    @Test
    fun reloadDoesNotLeave() = runBlocking<Unit> {
        assertEquals(HttpStatusCode.NoContent, beacon())
        delay(500)
        http.webSocket("ws://127.0.0.1:$port/ws") {
            send(Frame.Text(Hello(PROTOCOL_VERSION, "Этот компьютер", token).encode()))
            assertNull(closed.poll(ReceiverServer.OPERATOR_CLOSE_GRACE_MS + 1_000, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun secondLaunchReturnsToScreens() = runBlocking<Unit> {
        var activated = 0
        core.onActivate = { core.returnToScreens(); activated++ }
        core.apply(SetMode(Mode.AWAY))
        assertEquals(Mode.AWAY, core.state.value.mode)
        val r = http.post("http://127.0.0.1:$port/api/activate") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.NoContent, r.status)
        assertEquals(1, activated)
        assertEquals(Mode.NORMAL, core.state.value.mode)
        assertEquals(HttpStatusCode.Forbidden, http.post("http://127.0.0.1:$port/api/activate").status)
    }
}
