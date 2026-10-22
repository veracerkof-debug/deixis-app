package app.deixis.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.InputStream
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewerTest {
    private val core = ReceiverCore(
        ReceiverInfo("tv-1", "ТВ", "test", "0"),
        FileStore(File(createTempDirectory("viewer").toFile(), "files")),
        Pairing(MemoryTokenStore()),
    )
    private val server = ReceiverServer(core, port = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = HttpClient(CIO) { install(WebSockets) }
    private var port = 0

    @BeforeTest
    fun start() = runBlocking { port = server.start() }

    @AfterTest
    fun stop() {
        http.close()
        scope.cancel()
        server.stop()
    }

    private fun base() = "http://127.0.0.1:$port"

    private suspend fun connectedSender(): SenderClient {
        val c = SenderClient("127.0.0.1", port, "Телефон", scope)
        c.connect(c.pair(core.pairing.pin))
        withTimeout(10_000) { c.connection.first { it is Connection.Connected } }
        return c
    }

    private fun source(bytes: ByteArray, name: String, mime: String) = object : UploadSource {
        override val name = name
        override val size = bytes.size.toLong()
        override val mime = mime
        override fun open(): InputStream = bytes.inputStream()
    }

    @Test
    fun pageAndFontsAreServed() = runBlocking {
        val page = http.get("${base()}/view")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("ΔΕΙΞΙΣ"))
        assertEquals(HttpStatusCode.OK, http.get("${base()}/view/assets/GFSDidot-Regular.ttf").status)
        assertEquals(HttpStatusCode.NotFound, http.get("${base()}/view/assets/..%2Fview.html").status)
    }

    @Test
    fun senderLearnsViewerLink() = runBlocking {
        val c = connectedSender()
        assertEquals("http://127.0.0.1:$port/view?k=${core.viewKey}", c.viewUrl())
        c.close()
    }

    @Test
    fun wrongKeyIsRejected() = runBlocking {
        var reason: Short? = null
        http.webSocket(method = HttpMethod.Get, host = "127.0.0.1", port = port, path = "/view/ws?k=wrong") {
            reason = closeReason.await()?.code
        }
        assertEquals(VIEW_BAD_KEY, reason)
        assertEquals(0, core.state.value.viewers)
    }

    @Test
    fun viewerSeesShowLiveAndIsCounted() = runBlocking {
        val sender = connectedSender()
        http.webSocket(method = HttpMethod.Get, host = "127.0.0.1", port = port, path = "/view/ws?k=${core.viewKey}") {
            suspend fun next() = DeixisJson.decodeFromString(ViewerState.serializer(), (incoming.receive() as Frame.Text).readText())
            assertNull(withTimeout(5_000) { next() }.content)
            withTimeout(5_000) { sender.state.first { it?.viewers == 1 } }
            sender.show(TextContent("Слайд для зала"))
            assertEquals(TextContent("Слайд для зала"), withTimeout(5_000) { next() }.content)
            sender.mode(Mode.BLACK)
            assertEquals(Mode.BLACK, withTimeout(5_000) { next() }.mode)
        }
        withTimeout(5_000) { sender.state.first { it?.viewers == 0 } }
        sender.close()
    }

    @Test
    fun onlyCurrentFileIsServedAndRangeWorks() = runBlocking {
        val sender = connectedSender()
        val video = Random(5).nextBytes(50_000)
        val other = Random(6).nextBytes(1_000)
        val ref = sender.upload(source(video, "film.mp4", "video/mp4"))
        val otherRef = sender.upload(source(other, "secret.jpg", "image/jpeg"))
        sender.show(VideoContent(ref))
        withTimeout(5_000) { core.state.first { it.content is VideoContent } }

        val k = core.viewKey
        assertEquals(HttpStatusCode.Forbidden, http.get("${base()}/view/file/${ref.sha256}?k=bad").status)
        assertEquals(HttpStatusCode.NotFound, http.get("${base()}/view/file/${otherRef.sha256}?k=$k").status, "чужой файл из кэша не отдаётся")
        val part = http.get("${base()}/view/file/${ref.sha256}?k=$k") { header("Range", "bytes=0-9") }
        assertEquals(HttpStatusCode.PartialContent, part.status, "Safari требует отдачу частями для видео")
        assertTrue(part.bodyAsBytes().contentEquals(video.copyOfRange(0, 10)))
        assertEquals("video/mp4", part.headers["Content-Type"])
        sender.close()
    }

    @Test
    fun pdfPagesAreRenderedForBrowsers() = runBlocking {
        val sender = connectedSender()
        val pdf = Random(8).nextBytes(2_000)
        val ref = sender.upload(source(pdf, "slides.pdf", "application/pdf"))
        sender.show(PdfContent(ref, 1))
        withTimeout(5_000) { core.state.first { it.content is PdfContent } }
        val url = "${base()}/view/page/${ref.sha256}/1?k=${core.viewKey}&w=800"
        assertEquals(HttpStatusCode.NotImplemented, http.get(url).status)
        val calls = mutableListOf<Pair<Int, Int>>()
        core.pageRenderer = PageRenderer { _, page, w -> calls += page to w; byteArrayOf(1, 2, 3) }
        assertTrue(http.get(url).bodyAsBytes().contentEquals(byteArrayOf(1, 2, 3)))
        http.get(url)
        assertEquals(listOf(1 to 800), calls, "страница рисуется один раз и берётся из кэша")
        sender.close()
    }
}
