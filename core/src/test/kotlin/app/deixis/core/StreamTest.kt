package app.deixis.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readFully
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamUnitTest {
    private val sample = javaClass.getResourceAsStream("/screen-sample.h264")!!.readBytes()

    @Test
    fun annexBRecordingSplitsIntoFrames() {
        val frames = annexBSamples(sample)
        assertEquals(90, frames.size)
        val keys = frames.filter { it.key }
        assertEquals(3, keys.size)
        assertTrue(keys.all { it.config != null })
        assertTrue(frames.first().key)
        val sps = splitNalUnits(keys[0].config!!).first { nalType(it) == NAL_SPS }
        assertEquals("avc1.42C00D", avcCodecString(sps))
        assertEquals(320 to 240, avcFrameSize(sps))
    }

    @Test
    fun frameSizeHonoursCropping() {
        // 1080 не делится на 16: кодировщик пишет 1088 и обрезку, размер должен быть 1080.
        assertEquals(1920 to 1080, avcFrameSize(byteArrayOf(103, 66, -64, 40, -39, 0, 120, 2, 39, -27, -64, 68, 0, 0, 3, 0, 4, 0, 0, 3, 0, -16, 60, 96, -55, 32)))
        assertEquals(1080 to 2400, avcFrameSize(byteArrayOf(103, 66, -64, 50, -39, 0, 68, 1, 45, -27, -16, 17, 0, 0, 3, 0, 1, 0, 0, 3, 0, 60, 15, 24, 50, 72)))
        assertEquals(1L shl 30, FILE_LIMIT_BYTES)
    }

    @Test
    fun frameRoundTrips() {
        val f = StreamFrame(true, false, 42, 123_456, byteArrayOf(0, 0, 0, 1, 0x65, 7))
        val back = StreamFrame.decode(f.encode())!!
        assertEquals(true, back.key)
        assertEquals(false, back.config)
        assertEquals(42, back.seq)
        assertEquals(123_456, back.ptsUs)
        assertTrue(back.data.contentEquals(f.data))
    }

    @Test
    fun subscriberStartsOnKeyFrameWithConfig() = runBlocking<Unit> {
        val hub = StreamHub()
        hub.start()
        val got = async(Dispatchers.Default) { hub.subscribe().take(3).toList() }
        delay(100)
        hub.publish(StreamFrame(false, true, 0, 0, byteArrayOf(1, 2)))
        hub.publish(StreamFrame(false, false, 0, 0, byteArrayOf(9))) // не ключевой — пропускается
        hub.publish(StreamFrame(true, false, 1, 33, byteArrayOf(5)))
        hub.publish(StreamFrame(false, false, 2, 66, byteArrayOf(6)))
        hub.publish(StreamFrame(false, false, 3, 99, byteArrayOf(7)))
        val list = withTimeout(2_000) { got.await() }
        assertTrue(list[0].key)
        assertTrue(list[0].config!!.contentEquals(byteArrayOf(1, 2)))
        assertEquals(listOf(33L, 66L, 99L), list.map { it.ptsUs })
    }

    @Test
    fun slowSubscriberSkipsToNextKeyFrame() = runBlocking<Unit> {
        val hub = StreamHub()
        hub.start()
        hub.publish(StreamFrame(false, true, 0, 0, byteArrayOf(1)))
        val got = mutableListOf<StreamSample>()
        val slow = launch(Dispatchers.Default) {
            hub.subscribe().collect {
                got += it
                delay(if (got.size == 1) 300 else 0) // застрял на первом кадре
            }
        }
        delay(100)
        // Пока подписчик стоит, проходит 60 кадров; ключевые — каждый 30-й.
        hub.publish(StreamFrame(true, false, 0, 0, byteArrayOf(0)))
        delay(50)
        for (i in 1 until 60) hub.publish(StreamFrame(i % 30 == 0, false, i.toLong(), i * 33L, byteArrayOf(i.toByte())))
        delay(600)
        hub.publish(StreamFrame(true, false, 60, 60 * 33L, byteArrayOf(60)))
        hub.publish(StreamFrame(false, false, 61, 61 * 33L, byteArrayOf(61)))
        delay(200)
        slow.cancel()
        assertTrue(got.first().key)
        // После застревания он не проигрывает старое подряд, а продолжает со свежего ключевого кадра.
        val afterStall = got.drop(1)
        assertTrue(afterStall.isNotEmpty())
        assertTrue(afterStall.first().key, "после отставания первым идёт ключевой кадр")
        assertEquals(60, afterStall.first().data[0].toInt())
        assertTrue(afterStall.size < 40, "старые кадры выброшены: ${afterStall.size}")
    }

    @Test
    fun fmp4HasInitAndFragments() {
        val frames = annexBSamples(sample)
        val mux = Fmp4Muxer(320, 240)
        val init = mux.init(frames[0].config!!)!!
        assertEquals("ftyp", String(init, 4, 4))
        val frag = mux.fragment(frames[0])
        assertEquals("moof", String(frag, 4, 4))
        val moofSize = java.nio.ByteBuffer.wrap(frag).getInt(0)
        assertEquals("mdat", String(frag, moofSize + 4, 4))
    }

    /** Время во фрагменте (tfdt) из moof. */
    private fun tfdt(frag: ByteArray): Long {
        val i = String(frag, Charsets.ISO_8859_1).indexOf("tfdt")
        return java.nio.ByteBuffer.wrap(frag).getLong(i + 8)
    }

    @Test
    fun fmp4TimelineNeverGoesBack() {
        val frames = annexBSamples(sample)
        val mux = Fmp4Muxer(320, 240)
        val first = (0 until 30).map { tfdt(mux.fragment(frames[it])) }
        // Телефон переподключился: его время началось с нуля.
        val again = (0 until 30).map { tfdt(mux.fragment(frames[it])) }
        val all = first + again
        assertTrue(all.zipWithNext().all { (a, b) -> b > a }, "время растёт: $all")
        assertEquals(0, all.first())
    }
}

class StreamEndToEndTest {
    private val core = ReceiverCore(
        ReceiverInfo("tv-1", "ТВ", "test", "0"),
        FileStore(File(createTempDirectory("stream").toFile(), "files")),
        Pairing(MemoryTokenStore()),
    )
    private val server = ReceiverServer(core, port = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = HttpClient(CIO) { install(WebSockets) }
    private var port = 0
    private val frames = annexBSamples(javaClass.getResourceAsStream("/screen-sample.h264")!!.readBytes())

    @BeforeTest
    fun start() = runBlocking { port = server.start() }

    @AfterTest
    fun stop() {
        http.close()
        scope.cancel()
        server.stop()
    }

    /** Шлёт запись как кодировщик телефона: SPS/PPS отдельным кадром перед ключевым. */
    private fun CoroutineScope.pump(stream: ScreenStream, loops: Int = 3) = launch {
        repeat(loops) { loop ->
            for (f in frames) {
                f.config?.let { stream.send(false, true, 0, it) }
                stream.send(f.key, false, f.ptsUs + loop * 3_000_000L, f.data)
                delay(33)
            }
        }
    }

    private suspend fun sender(): SenderClient {
        val c = SenderClient("127.0.0.1", port, "Телефон", scope)
        c.connect(c.pair(core.pairing.pin))
        withTimeout(10_000) { c.connection.first { it is Connection.Connected } }
        return c
    }

    @Test
    fun screenReachesHallAndViewers() = runBlocking<Unit> {
        val c = sender()
        val stream = c.openStream { }
        c.show(ScreenContent(320, 240))
        withTimeout(5_000) { core.state.first { it.content is ScreenContent } }
        val pump = pump(stream)

        // Экран зала: первым приходит ключевой кадр с SPS/PPS.
        val hall = withTimeout(5_000) { core.stream.subscribe().take(10).toList() }
        assertTrue(hall[0].key)
        assertNotNull(hall[0].config)
        assertTrue(core.stream.active.value)

        val droppedBefore = stream.dropped
        // Зритель в браузере: кодек, начальный сегмент, затем фрагменты по кадру.
        val received = ByteArrayOutputStream()
        var codec: String? = null
        var fragments = 0
        http.webSocket(method = HttpMethod.Get, host = "127.0.0.1", port = port, path = "/view/stream?k=${core.viewKey}") {
            withTimeout(5_000) {
                while (fragments < 40) {
                    when (val f = incoming.receive()) {
                        is Frame.Text -> codec = f.readText()
                        is Frame.Binary -> {
                            val b = f.readBytes()
                            send(Frame.Text("a")) // как браузер: подтверждение каждого сообщения
                            if (String(b, 4, 4) == "moof") fragments++ else assertEquals("ftyp", String(b, 4, 4))
                            received.write(b)
                        }
                        else -> Unit
                    }
                }
            }
        }
        assertEquals("""{"codec":"avc1.42C00D"}""", codec)
        assertEquals(0, stream.dropped - droppedBefore, "на быстрой связи кадры не выбрасываются")
        System.getProperty("deixis.dumpStream")?.let { File(it).writeBytes(received.toByteArray()) }

        // Поток для своего проигрывателя: тот же MP4, начинается с ftyp.
        val local = http.prepareGet("http://127.0.0.1:$port/stream/mp4").execute { r ->
            val ch = r.bodyAsChannel()
            val buf = ByteArray(8)
            withTimeout(5_000) { ch.readFully(buf) }
            buf
        }
        assertEquals("ftyp", String(local, 4, 4))

        pump.cancel()
        stream.close()
        withTimeout(5_000) { core.stream.active.first { !it } }
    }

    @Test
    fun viewerThatStopsReadingIsNotFedOldFrames() = runBlocking<Unit> {
        val c = sender()
        val stream = c.openStream { }
        c.show(ScreenContent(320, 240))
        val pump = pump(stream)
        // Зритель не подтверждает получение: приёмник шлёт не больше окна и ждёт, а не копит отставание.
        var got = 0
        http.webSocket(method = HttpMethod.Get, host = "127.0.0.1", port = port, path = "/view/stream?k=${core.viewKey}") {
            withTimeoutOrNull(2_000) { while (true) if (incoming.receive() is Frame.Binary) got++ }
        }
        assertTrue(got in 2..VIEW_STREAM_WINDOW + 1, "получено $got")
        pump.cancel()
        stream.close()
    }

    @Test
    fun streamNeedsPairing() = runBlocking<Unit> {
        var closed = false
        http.webSocket(method = HttpMethod.Get, host = "127.0.0.1", port = port, path = "/stream") {
            closed = incoming.receiveCatching().isClosed
        }
        assertTrue(closed)
        assertTrue(!core.stream.active.value)
    }
}
