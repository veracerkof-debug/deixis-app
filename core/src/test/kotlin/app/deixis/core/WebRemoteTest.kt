package app.deixis.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URLEncoder
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Веб-пульт (iPhone): страница, загрузка файлов, Библия на приёмнике, Holyrics через приёмник. */
class WebRemoteTest {
    private val dir = createTempDirectory("remote").toFile()
    private val core = ReceiverCore(
        ReceiverInfo("pc", "Компьютер", "test", "0", holyrics = true),
        FileStore(File(dir, "files")),
        Pairing(MemoryTokenStore()),
    )
    private val server = ReceiverServer(core, port = 0)
    private val http = HttpClient(CIO)
    private val fake = FakeHolyrics()
    private var port = 0
    private var hPort = 0
    private lateinit var token: String

    @BeforeTest
    fun start() = runBlocking {
        core.bible = BibleStore(File(dir, "bible")) { JdbcSql(it) }
        core.pageCounter = { 7 }
        core.holyrics = HolyricsBridge(core.files)
        hPort = fake.start()
        port = server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        token = SenderClient("127.0.0.1", port, "iPhone", scope).let { c -> c.pair(core.pairing.pin).also { c.close() } }
        scope.cancel()
    }

    @AfterTest
    fun stop() { http.close(); server.stop(); fake.stop() }

    private fun url(p: String) = "http://127.0.0.1:$port$p"

    private suspend fun put(id: String, name: String, size: Long, offset: Long, body: ByteArray, auth: Boolean = true): HttpResponse =
        http.put(url("/upload/$id?offset=$offset")) {
            if (auth) header("Authorization", "Bearer $token")
            header(ReceiverServer.HEADER_SIZE, size.toString())
            header(HEADER_NAME, URLEncoder.encode(name, "UTF-8"))
            setBody(body)
        }

    private suspend fun postJson(p: String, body: String): HttpResponse = http.post(url(p)) {
        header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun pageIsServedAndQrOpensIt() = runBlocking<Unit> {
        val page = http.get(url("/remote"))
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("ΔΕΙΞΙΣ"))
        val code = PairingCode("192.168.1.5", 47820, "1234", "pc", "Компьютер")
        assertTrue(code.toUri().startsWith("http://192.168.1.5:47820/remote?pin=1234"))
        assertEquals(code, PairingCode.parse(code.toUri()), "приложение понимает веб-ссылку из QR")
        assertEquals(code, PairingCode.parse(code.toAppUri()))
        assertEquals(null, PairingCode.parse("http://192.168.1.5:47820/view?pin=1234"))
    }

    @Test
    fun uploadResumesAndReceiverHashes() = runBlocking<Unit> {
        val data = ByteArray(300_000) { (it * 7).toByte() }
        assertEquals(HttpStatusCode.Unauthorized, put("abcdef123456", "фото.jpg", data.size.toLong(), 0, data, auth = false).status)
        // Первая часть, потом обрыв: пульт спрашивает, сколько дошло, и продолжает.
        assertEquals("""{"bytes":100000}""", put("abcdef123456", "фото.jpg", data.size.toLong(), 0, data.copyOfRange(0, 100_000)).bodyAsText())
        val have = http.get(url("/upload/abcdef123456")) { header("Authorization", "Bearer $token") }.bodyAsText()
        assertEquals("""{"bytes":100000}""", have)
        assertEquals(HttpStatusCode.Conflict, put("abcdef123456", "фото.jpg", data.size.toLong(), 50_000, data.copyOfRange(50_000, 60_000)).status)
        val done = put("abcdef123456", "фото.jpg", data.size.toLong(), 100_000, data.copyOfRange(100_000, data.size)).bodyAsText()
        val ref = DeixisJson.decodeFromString(FileRef.serializer(), DeixisJson.parseToJsonElement(done).jsonObject["file"].toString())
        assertEquals(sha256Of(data.inputStream()), ref.sha256)
        assertEquals("фото.jpg", ref.name)
        assertEquals("image/jpeg", ref.mime)
        assertTrue(core.files.status(ref.sha256).complete)
        // Тот же файл можно сразу показать.
        assertEquals(null, core.apply(Show(ImageContent(ref))))
        val pages = http.get(url("/pdf/${ref.sha256}/pages")) { header("Authorization", "Bearer $token") }.bodyAsText()
        assertEquals("""{"pages":7}""", pages)
    }

    @Test
    fun bibleModulesLiveOnTheReceiver() = runBlocking<Unit> {
        val rst = TestBibles.rst(createTempDirectory("b").toFile()).readBytes()
        val up = put("module0001", "RST.SQLite3", rst.size.toLong(), 0, rst).bodyAsText()
        val ref = DeixisJson.parseToJsonElement(up).jsonObject["file"].toString()
        val installed = postJson("/bible/install", ref)
        assertEquals(HttpStatusCode.OK, installed.status, installed.bodyAsText())
        val list = DeixisJson.decodeFromString(ListSerializer(BibleModuleInfo.serializer()), http.get(url("/bible")) { header("Authorization", "Bearer $token") }.bodyAsText())
        assertEquals(listOf("RST.SQLite3"), list.map { it.file })
        val slides = postJson("/bible/slides", """{"ref":"Ин 3:16-17","modules":["RST.SQLite3"],"options":{"versesPerSlide":2}}""")
        assertEquals(HttpStatusCode.OK, slides.status, slides.bodyAsText())
        val arr = DeixisJson.parseToJsonElement(slides.bodyAsText()).jsonArray
        assertEquals(1, arr.size)
        assertTrue(arr[0].toString().contains("Ибо так возлюбил"))
        // Не модуль Библии — понятная ошибка, а не сбой.
        val junk = "не база".toByteArray()
        val jref = DeixisJson.parseToJsonElement(put("module0002", "notes.SQLite3", junk.size.toLong(), 0, junk).bodyAsText()).jsonObject["file"].toString()
        assertEquals(HttpStatusCode.UnprocessableEntity, postJson("/bible/install", jref).status)
        assertEquals(HttpStatusCode.UnprocessableEntity, postJson("/bible/slides", """{"ref":"Ин 3:16","modules":[]}""").status)
    }

    @Test
    fun holyricsGoesThroughTheReceiver() = runBlocking<Unit> {
        val songs = postJson("/holyrics/call", """{"port":$hPort,"token":"secret","action":"GetSongs"}""")
        assertEquals(HttpStatusCode.OK, songs.status, songs.bodyAsText())
        assertTrue(songs.bodyAsText().contains("Великий Бог"))
        // Правка и удаление в Holyrics с пульта не разрешены.
        assertEquals(HttpStatusCode.Forbidden, postJson("/holyrics/call", """{"port":$hPort,"token":"secret","action":"DeleteSong","body":{"id":"1"}}""").status)
        val set = postJson("/holyrics/slides", """{"port":$hPort,"token":"secret","song":"1"}""")
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
        val s = DeixisJson.decodeFromString(SlideSet.serializer(), set.bodyAsText())
        assertEquals(3, s.slides.size, "порядок исполнения: куплет, припев, куплет")
        assertEquals("Verse 1", s.labels.first())
        assertEquals(HAlign.LEFT, s.style!!.align, "тема песни из Holyrics")
        assertEquals(HttpStatusCode.BadGateway, postJson("/holyrics/call", """{"port":$hPort,"token":"wrong","action":"GetSongs"}""").status)
    }
}
