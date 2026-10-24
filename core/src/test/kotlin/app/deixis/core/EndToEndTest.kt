package app.deixis.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.InputStream
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Приёмник и источник на одном компьютере, через настоящую сеть (127.0.0.1). */
class EndToEndTest {
    private val dir = createTempDirectory("e2e").toFile()
    private val core = ReceiverCore(
        ReceiverInfo("tv-1", "ТВ в холле", "test", "0"),
        FileStore(File(dir, "files")),
        Pairing(MemoryTokenStore()),
    )
    private val server = ReceiverServer(core, port = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var port = 0

    @BeforeTest
    fun start() = runBlocking { port = server.start() }

    @AfterTest
    fun stop() {
        scope.cancel()
        server.stop()
    }

    /** Что происходило со связью каждого источника: при падении видно, рвалась ли она. */
    private val history = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private fun client() = SenderClient("127.0.0.1", port, "Телефон", scope).also { c ->
        val t0 = System.currentTimeMillis()
        scope.launch { c.connection.collect { history += "${System.currentTimeMillis() - t0} мс: $it" } }
    }

    private suspend fun <T> step(what: String, block: suspend () -> T): T = try {
        withTimeout(5_000) { block() }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("не дождались: $what; связь: ${history.joinToString(" → ")}; экран: ${core.state.value}", e)
    }

    private fun source(bytes: ByteArray, name: String = "photo.jpg", mime: String = "image/jpeg") = object : UploadSource {
        override val name = name
        override val size = bytes.size.toLong()
        override val mime = mime
        override fun open(): InputStream = bytes.inputStream()
    }

    private suspend fun SenderClient.connected() = withTimeout(10_000) {
        connection.first { it is Connection.Connected }
    }

    @Test
    fun wrongPinIsDeniedAndCorrectPinPairs() = runBlocking {
        val c = client()
        val wrong = if (core.pairing.pin == "1000") "1001" else "1000"
        val e = assertFailsWith<PairingException> { c.pair(wrong) }
        assertEquals(DenyReason.WRONG_PIN, e.reason)
        val token = c.pair(core.pairing.pin)
        assertTrue(core.pairing.isValid(token))
        c.close()
    }

    @Test
    fun unknownTokenIsDenied() = runBlocking {
        val c = client()
        c.connect("0".repeat(64))
        val state = withTimeout(10_000) { c.connection.first { it is Connection.Denied } }
        assertEquals(Connection.Denied(DenyReason.UNKNOWN_TOKEN), state)
        c.close()
    }

    @Test
    fun uploadsShowsAndReportsState() = runBlocking {
        val c = client()
        val token = c.pair(core.pairing.pin)
        c.connect(token)
        c.connected()

        val bytes = Random(7).nextBytes(3_000_000)
        val progress = mutableListOf<Long>()
        val ref = c.upload(source(bytes)) { sent, _ -> progress += sent }
        assertEquals(bytes.size.toLong(), progress.last())
        assertTrue(core.files.file(ref.sha256)!!.readBytes().contentEquals(bytes))

        assertTrue(c.show(ImageContent(ref)))
        val shown = withTimeout(5_000) { c.state.filterNotNull().first { it.content == ImageContent(ref) } }
        assertEquals(Mode.NORMAL, shown.mode)
        assertEquals(ImageContent(ref), core.state.value.content)

        c.mode(Mode.BLACK)
        withTimeout(5_000) { c.state.filterNotNull().first { it.mode == Mode.BLACK } }
        c.close()
    }

    @Test
    fun uploadResumesFromPartialFile() = runBlocking {
        val c = client()
        val token = c.pair(core.pairing.pin)
        val bytes = Random(3).nextBytes(1_000_000)
        val sha = sha256Of(bytes.inputStream())
        // Как будто прошлая передача оборвалась на 400 КБ.
        core.files.append(sha, bytes.size.toLong(), 0, bytes.copyOfRange(0, 400_000).inputStream())
        val firstSent = mutableListOf<Long>()
        val counting = object : UploadSource by source(bytes) {
            override fun open(): InputStream = bytes.inputStream()
        }
        c.upload(counting) { sent, _ -> firstSent += sent }
        assertEquals(400_000, firstSent.first(), "передача начинается с уже полученного места")
        assertTrue(core.files.file(sha)!!.readBytes().contentEquals(bytes))
        assertTrue(core.pairing.isValid(token))
        c.close()
    }

    @Test
    fun showWithMissingFileReturnsErrorAndKeepsScreen() = runBlocking {
        val c = client()
        c.connect(c.pair(core.pairing.pin))
        c.connected()
        assertTrue(c.show(TextContent("Сначала текст")), "команда ушла")
        step("текст на экране") { c.state.filterNotNull().first { it.content is TextContent } }
        // Подписка до отправки: ошибка не повторяется для опоздавших слушателей.
        val pending = async(start = CoroutineStart.UNDISPATCHED) { c.errors.first() }
        assertTrue(c.show(ImageContent(FileRef("b".repeat(64), "нет.jpg", 1, "image/jpeg"))), "команда ушла")
        val err = step("ошибка «нет файла»") { pending.await() }
        assertEquals("missing_file", err.code)
        assertIs<TextContent>(core.state.value.content, "экран не меняется из-за ошибки")
        c.close()
    }

    @Test
    fun reconnectRestoresStateFromReceiver() = runBlocking {
        val first = client()
        val token = first.pair(core.pairing.pin)
        first.connect(token)
        first.connected()
        first.show(TextContent("Слайд 3"))
        withTimeout(5_000) { first.state.filterNotNull().first { it.content == TextContent("Слайд 3") } }
        first.close()

        val second = client()
        assertNull(second.state.value)
        second.connect(token)
        second.connected()
        assertEquals(TextContent("Слайд 3"), second.state.value?.content)
        second.close()
    }

    @Test
    fun clientReconnectsAfterReceiverRestart() = runBlocking<Unit> {
        val c = client()
        c.connect(c.pair(core.pairing.pin))
        c.connected()
        server.stop()
        withTimeout(10_000) { c.connection.first { it is Connection.Lost } }
        val again = ReceiverServer(core, port)
        again.start()
        try {
            c.connected()
        } finally {
            c.close()
            again.stop()
        }
    }

    @Test
    fun videoCommandsReachThePlayer() = runBlocking {
        val c = client()
        c.connect(c.pair(core.pairing.pin))
        c.connected()
        val ref = c.upload(source(Random(9).nextBytes(10_000), "film.mkv", "video/x-matroska"))
        c.show(VideoContent(ref))
        withTimeout(5_000) { c.state.filterNotNull().first { it.content is VideoContent } }
        val received = async { withTimeout(5_000) { core.videoCommands.first() } }
        delay(100)
        c.video(VideoCommand(VideoAction.PAUSE))
        assertEquals(VideoAction.PAUSE, received.await().action)
        core.reportVideo(VideoState(playing = false, positionMs = 1_000, durationMs = 60_000))
        withTimeout(5_000) { c.state.filterNotNull().first { it.video?.positionMs == 1_000L } }
        c.close()
    }

    @Test
    fun commandsAreRefusedWhileDisconnected() {
        val c = client()
        assertEquals(false, c.show(TextContent("в пустоту")))
        c.close()
    }

    @Test
    fun uploadWithoutPairingIsRejected() = runBlocking {
        val c = client()
        assertFailsWith<IllegalStateException> { c.upload(source(byteArrayOf(1, 2, 3))) }
        c.close()
    }
}
