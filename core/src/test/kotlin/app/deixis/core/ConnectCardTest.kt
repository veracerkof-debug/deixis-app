package app.deixis.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** «QR и код на экран»: пульт выводит заставку подключения поверх показа и убирает её; новый слайд убирает сам. */
class ConnectCardTest {
    private val dir = createTempDirectory("connect-card").toFile()
    private val core = ReceiverCore(ReceiverInfo("pc", "Компьютер", "test", "0"), FileStore(File(dir, "files")), Pairing(MemoryTokenStore()))
    private val server = ReceiverServer(core, port = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var client: SenderClient
    private var port = 0

    @BeforeTest
    fun start() = runBlocking<Unit> {
        port = server.start()
        core.pairingUri = { "deixis://pair?pin=${core.pairing.pin}" }
        client = SenderClient("127.0.0.1", port, "Пульт", scope).apply { sameDevice = true }
        client.pair(core.pairing.pin)
    }

    @AfterTest
    fun stop() { client.close(); scope.cancel(); server.stop() }

    @Test
    fun pultReadsTheCurrentPinAndQr() = runBlocking<Unit> {
        val info = assertNotNull(client.pairingInfo())
        assertEquals(core.pairing.pin, info.pin)
        assertEquals("deixis://pair?pin=${core.pairing.pin}", info.uri)
    }

    @Test
    fun cardGoesOnAndOffAndTheNextSlideRemovesIt() = runBlocking<Unit> {
        core.apply(Show(TextContent("Первый слайд")))
        assertTrue(client.connectCard(true))
        assertTrue(core.state.value.connectCard)
        assertEquals(TextContent("Первый слайд"), core.state.value.content, "слайд под заставкой остаётся")
        assertTrue(client.connectCard(false))
        assertFalse(core.state.value.connectCard)
        assertTrue(client.connectCard(true))
        core.apply(Show(TextContent("Второй слайд")))
        assertFalse(core.state.value.connectCard, "новый слайд убирает заставку")
    }

    @Test
    fun strangerCannotPutTheCardOnScreen() = runBlocking<Unit> {
        val stranger = SenderClient("127.0.0.1", port, "Чужой", scope)
        assertFalse(stranger.connectCard(true))
        assertFalse(core.state.value.connectCard)
        stranger.close()
    }
}
