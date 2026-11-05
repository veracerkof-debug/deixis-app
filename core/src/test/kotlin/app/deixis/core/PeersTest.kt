package app.deixis.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Подключения: новое устройство с верным PIN ждёт «Разрешить» — на самом экране или на уже подключённом пульте
 * оператора; оператор видит, кто на связи, и отключает лишних. Подтверждать некому — хватает PIN.
 */
class PeersTest {
    private val dir = createTempDirectory("peers").toFile()
    private val core = ReceiverCore(ReceiverInfo("pc", "Компьютер", "test", "0"), FileStore(File(dir, "files")), Pairing(MemoryTokenStore()))
    private val server = ReceiverServer(core, port = 0)
    private val http = HttpClient(CIO) { install(WebSockets) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun stop() { scope.cancel(); http.close(); server.stop() }

    @Test
    fun deskAsksOnlyWhenOperatorIsThere() = runBlocking<Unit> {
        val d = PeerDesk()
        assertNull(d.ask("Телефон", "10.0.0.5"), "без пульта оператора хватает PIN")
        d.approverJoined()
        val a = async { d.ask("Алексей · Redmi 9T", "10.0.0.5") }
        withTimeout(2_000) { while (d.state.value.requests.isEmpty()) delay(10) }
        val r = d.state.value.requests.single()
        assertEquals("Алексей · Redmi 9T", r.sender)
        d.answer(r.id, true)
        assertEquals(true, a.await())
        assertTrue(d.state.value.requests.isEmpty())
        assertEquals(false, d.ask("Чужой", "10.0.0.9", timeoutMs = 200), "без ответа — отказ")
    }

    @Test
    fun screenWindowAsksToo() = runBlocking<Unit> {
        val d = PeerDesk()
        d.screenApprover = true
        assertTrue(d.approvalNeeded, "окно подтверждения на мониторе оператора")
        assertEquals(false, d.ask("Чужой", "10.0.0.9", timeoutMs = 200))
        d.screenApprover = false
        assertNull(d.ask("Телефон", "10.0.0.5"))
    }

    /**
     * Компьютер, где все мониторы — зал (или ТВ): первое устройство входит по PIN, следующее пускает уже
     * подключённый телефон. Пересылка показа с другого экрана (не сказала, что подтверждает) ничего не меняет.
     */
    @Test
    fun connectedPhoneApprovesTheNextOne() = runBlocking<Unit> {
        val lan = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: return@runBlocking
        val port = server.start()
        val relay = SenderClient(lan, port, "Экран в холле", scope)
        relay.connect(relay.pair(core.pairing.pin))
        withTimeout(5_000) { while (relay.connection.value !is Connection.Connected) delay(20) }
        assertFalse(core.peers.approvalNeeded, "пересылка показа не подтверждает")

        val first = SenderClient(lan, port, "Оператор · Pixel", scope).apply { approver = true }
        first.connect(first.pair(core.pairing.pin))
        withTimeout(5_000) { while (!core.peers.approvalNeeded) delay(20) }
        val answering = scope.launch {
            first.peers.collect { p -> p?.requests?.forEach { first.answer(it.id, it.sender.startsWith("Свой")) } }
        }
        var waited = false
        val good = SenderClient(lan, port, "Свой · iPhone", scope)
        assertTrue(core.pairing.isValid(good.pair(core.pairing.pin) { waited = true }))
        assertTrue(waited)
        val bad = SenderClient(lan, port, "Чужой", scope)
        assertEquals(DenyReason.NOT_APPROVED, (runCatching { bad.pair(core.pairing.pin) }.exceptionOrNull() as? PairingException)?.reason)
        // Оператор видит всех на связи, и пересылку показа тоже.
        withTimeout(5_000) { while (first.peers.value?.connected.orEmpty().none { it.sender == "Экран в холле" }) delay(20) }
        answering.cancel()
        listOf(relay, first, good, bad).forEach { it.close() }
        withTimeout(5_000) { while (core.peers.approvalNeeded) delay(20) }
    }

    @Test
    fun kickForgetsTokenAndClosesAllItsSessions() {
        val d = PeerDesk()
        val closed = mutableListOf<String>()
        val forgotten = mutableListOf<String>()
        val a = d.joined("iPhone", "10.0.0.5", here = false, token = "t1") { closed += "a" }
        d.joined("iPhone", "10.0.0.5", here = false, token = "t1") { closed += "b" }
        val me = d.joined("Этот компьютер", "", here = true, token = "t0") { closed += "me" }
        d.kick(me) { forgotten += it }
        assertTrue(closed.isEmpty(), "пульт этого компьютера не отключается")
        d.kick(a) { forgotten += it }
        assertEquals(listOf("t1"), forgotten)
        assertEquals(setOf("a", "b"), closed.toSet())
    }

    /** Весь путь по сети: нужен адрес не 127.0.0.1, иначе устройство считается «этим компьютером». */
    @Test
    fun phoneWaitsForOperatorApproval() = runBlocking<Unit> {
        val lan = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: return@runBlocking
        val port = server.start()
        val opToken = SenderClient("127.0.0.1", port, "Этот компьютер", scope).let { c -> c.pair(core.pairing.pin).also { c.close() } }
        // Пульт оператора на компьютере: получает список подключений и запросы.
        val requests = kotlinx.coroutines.channels.Channel<ConnectRequest>(8)
        val op = scope.launch {
            http.webSocket("ws://127.0.0.1:$port/ws") {
                send(Frame.Text(Hello(PROTOCOL_VERSION, "Этот компьютер", opToken).encode()))
                for (f in incoming) {
                    val m = (f as? Frame.Text)?.readText()?.let(::decodeMsg) as? Peers ?: continue
                    m.state.requests.firstOrNull()?.let { r ->
                        requests.trySend(r)
                        send(Frame.Text(ConnectAnswer(r.id, r.sender.startsWith("Свой")).encode()))
                    }
                }
            }
        }
        withTimeout(5_000) { while (!core.peers.approvalNeeded) delay(20) }

        var waited = false
        val good = SenderClient(lan, port, "Свой · Redmi 9T", scope)
        val token = good.pair(core.pairing.pin) { waited = true }
        good.close()
        assertTrue(waited, "телефон узнал, что ждёт подтверждения")
        assertTrue(core.pairing.isValid(token))
        assertEquals("Свой · Redmi 9T", requests.receive().sender)

        val bad = SenderClient(lan, port, "Чужой", scope)
        val err = runCatching { bad.pair(core.pairing.pin) }.exceptionOrNull()
        bad.close()
        assertEquals(DenyReason.NOT_APPROVED, (err as? PairingException)?.reason)
        assertEquals(1, core.pairing.senders().count { it.sender != "Этот компьютер" }, "отклонённому токен не остался")
        op.cancel()
        assertFalse(core.peers.state.value.requests.isNotEmpty())
    }
}
