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
        assertEquals(listOf("KJV.SQLite3", "RST.SQLite3"), list.map { it.file }, "встроенный KJV и загруженный RST вместо встроенного")
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

    private suspend fun authedGet(p: String, auth: Boolean = true): HttpResponse = http.get(url(p)) { if (auth) header("Authorization", "Bearer $token") }

    @Test
    fun bibleTabReadsBooksChaptersAndSearch() = runBlocking<Unit> {
        // Встроенные RST и KJV: книги с числом глав.
        assertEquals(HttpStatusCode.Unauthorized, authedGet("/bible/books?module=RST.SQLite3", auth = false).status)
        val books = DeixisJson.decodeFromString(ListSerializer(BibleBookInfo.serializer()), authedGet("/bible/books?module=RST.SQLite3").bodyAsText())
        val john = books.first { it.number == 500 }
        assertEquals(21, john.chapters)
        assertEquals(66, books.size)
        assertEquals(151, books.first { it.number == 230 }.chapters, "в Синодальном 151 псалом")
        assertEquals(HttpStatusCode.UnprocessableEntity, authedGet("/bible/books?module=NONE.SQLite3").status)

        // Глава сразу на двух переводах: стихи идут парами по номеру.
        assertEquals(HttpStatusCode.Unauthorized, authedGet("/bible/chapter?modules=RST.SQLite3&book=500&chapter=3", auth = false).status)
        val r = authedGet("/bible/chapter?modules=RST.SQLite3,KJV.SQLite3&book=500&chapter=3")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val ch = DeixisJson.decodeFromString(ChapterInfo.serializer(), r.bodyAsText())
        assertEquals(21, ch.chapters)
        assertEquals("От Иоанна", ch.longName)
        assertTrue(ch.primary.first { it.number == 16 }.text.startsWith("Ибо так возлюбил Бог"))
        assertTrue(ch.second!!.first { it.number == 16 }.text.startsWith("For God so loved"))
        val one = DeixisJson.decodeFromString(ChapterInfo.serializer(), authedGet("/bible/chapter?modules=KJV.SQLite3&book=500&chapter=3").bodyAsText())
        assertEquals(null, one.second)
        assertEquals(HttpStatusCode.UnprocessableEntity, authedGet("/bible/chapter?modules=RST.SQLite3&book=500&chapter=99").status)
        assertEquals(HttpStatusCode.BadRequest, authedGet("/bible/chapter?modules=RST.SQLite3&book=500").status)

        // Поиск: все слова, без учёта регистра; адрес — переход, а не поиск.
        assertEquals(HttpStatusCode.Unauthorized, authedGet("/bible/search?module=RST.SQLite3&q=x", auth = false).status)
        val found = DeixisJson.decodeFromString(BibleSearchResult.serializer(), authedGet("/bible/search?module=RST.SQLite3&q=" + URLEncoder.encode("так возлюбил", "UTF-8")).bodyAsText())
        assertEquals(null, found.ref)
        val hit = found.hits.first { it.book == 500 && it.chapter == 3 && it.verse == 16 }
        assertEquals("Ин 3:16", hit.label)
        val ref = DeixisJson.decodeFromString(BibleSearchResult.serializer(), authedGet("/bible/search?module=RST.SQLite3&q=" + URLEncoder.encode("Ин 3:16-18", "UTF-8")).bodyAsText()).ref!!
        assertEquals(BibleRefInfo(500, 3, 16, 18, "Ин 3:16–18"), ref)
        val many = DeixisJson.decodeFromString(BibleSearchResult.serializer(), authedGet("/bible/search?module=RST.SQLite3&q=" + URLEncoder.encode("бог", "UTF-8")).bodyAsText())
        assertEquals(BibleStore.SEARCH_LIMIT, many.hits.size)
        assertTrue(many.more)

        // Слайды по номерам книги и главы, без разбора адреса.
        val slides = postJson("/bible/slides", """{"modules":["RST.SQLite3","KJV.SQLite3"],"book":500,"chapter":3,"from":16,"to":16,"options":{"sideBySide":true}}""")
        assertEquals(HttpStatusCode.OK, slides.status, slides.bodyAsText())
        val sc = DeixisJson.decodeFromString(ListSerializer(ScriptureContent.serializer()), slides.bodyAsText()).single()
        assertEquals(2, sc.blocks.size)
        assertTrue(sc.sideBySide)
        assertEquals("Ин 3:16", sc.ref)
    }

    @Test
    fun remoteGetsQrAndFilePreviews() = runBlocking<Unit> {
        val qr = http.get(url("/remote/qr.svg?text=" + URLEncoder.encode("http://192.168.1.5:47820/view?k=abc", "UTF-8")))
        assertEquals(HttpStatusCode.OK, qr.status)
        assertTrue(qr.bodyAsText().startsWith("<svg"))
        assertEquals(HttpStatusCode.BadRequest, http.get(url("/remote/qr.svg")).status)

        val data = ByteArray(1000) { it.toByte() }
        val up = put("preview0001", "фото.jpg", data.size.toLong(), 0, data).bodyAsText()
        val ref = DeixisJson.decodeFromString(FileRef.serializer(), DeixisJson.parseToJsonElement(up).jsonObject["file"].toString())
        assertEquals(HttpStatusCode.Unauthorized, http.get(url("/remote/file/${ref.sha256}")).status)
        // Картинка в превью: токен параметром, раз браузер не шлёт заголовок для <img>.
        val f = http.get(url("/remote/file/${ref.sha256}?t=$token&n=a.jpg"))
        assertEquals(HttpStatusCode.OK, f.status)
        assertEquals("image/jpeg", f.headers["Content-Type"])
        assertEquals(HttpStatusCode.NotFound, http.get(url("/remote/file/${"0".repeat(64)}?t=$token")).status)
        // Страница PDF: без рисовальщика на приёмнике — 501, с ним — PNG.
        assertEquals(HttpStatusCode.NotImplemented, http.get(url("/remote/page/${ref.sha256}/0?t=$token")).status)
        core.pageRenderer = PageRenderer { _, page, w -> byteArrayOf(page.toByte(), (w / 10).toByte()) }
        val png = http.get(url("/remote/page/${ref.sha256}/2?w=320")) { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, png.status)
        assertEquals("image/png", png.headers["Content-Type"])
        assertEquals(HttpStatusCode.Unauthorized, http.get(url("/remote/page/${ref.sha256}/2?t=wrong")).status)
    }

    @Test
    fun presentationBecomesPdfOnTheReceiver() = runBlocking<Unit> {
        var runs = 0
        // Вместо LibreOffice — переводчик, который «печатает» PDF; важна обвязка: расширение, хранилище, повтор.
        core.conversions = Conversions(core, DocConverter { input, dir ->
            runs++
            assertEquals("pptx", input.extension, "LibreOffice получает файл с родным расширением")
            PdfResult(File(dir, "out.pdf").apply { writeText("%PDF-1.4 " + input.readText()) })
        }, File(dir, "convert"))
        val pptx = "слайды".toByteArray()
        val src = DeixisJson.parseToJsonElement(put("pptx000001", "Лекция 3.pptx", pptx.size.toLong(), 0, pptx).bodyAsText()).jsonObject["file"].toString()
        val r = postJson("/convert", src)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val conv = DeixisJson.decodeFromString(Converted.serializer(), r.bodyAsText())
        assertEquals("Лекция 3.pdf", conv.file.name)
        assertEquals("application/pdf", conv.file.mime)
        assertEquals(7, conv.pages)
        assertEquals(null, core.apply(Show(PdfContent(conv.file, 3))), "PDF сразу можно показывать")
        assertEquals(conv, DeixisJson.decodeFromString(Converted.serializer(), postJson("/convert", src).bodyAsText()))
        assertEquals(1, runs, "повторно файл не переводится")
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
