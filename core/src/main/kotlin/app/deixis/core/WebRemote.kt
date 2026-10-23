package app.deixis.core

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/*
 * Веб-пульт: телефон без приложения (iPhone) сканирует QR на экране приёмника и управляет им из браузера.
 * Страницу отдаёт сам приёмник. Пульт говорит тем же протоколом WebSocket, что и приложение, а файлы,
 * модули Библии и Holyrics получает через HTTP ниже. Всё, кроме самой страницы, — только после сопряжения по PIN.
 */

/**
 * Приём файла из браузера. На странице HTTP браузер не умеет считать SHA-256 (WebCrypto только на HTTPS),
 * поэтому файл принимается по номеру загрузки, а хэш считает приёмник. Докачка — с того же места.
 */
class UploadArea(private val files: FileStore, private val dir: File) {
    init { dir.mkdirs() }

    private fun part(id: String): File {
        require(ID.matches(id)) { "Неверный номер загрузки" }
        return File(dir, "$id.part")
    }

    /** Брошенные загрузки старше [maxAgeMs] удаляются, как и файлы показа. */
    fun purgeOlderThan(maxAgeMs: Long, now: Long = System.currentTimeMillis()) {
        dir.listFiles()?.forEach { if (it.isFile && now - it.lastModified() > maxAgeMs) it.delete() }
    }

    fun received(id: String): Long = part(id).takeIf { it.isFile }?.length() ?: 0

    /** Дописывает часть; когда собран весь файл, кладёт его в хранилище и возвращает ссылку. */
    @Synchronized
    fun append(id: String, name: String, size: Long, offset: Long, input: InputStream): FileRef? {
        val p = part(id)
        val have = received(id)
        if (offset != have) throw StoreException("offset", "Ожидалось смещение $have, получено $offset")
        if (size > FILE_LIMIT_BYTES) throw StoreException("size", "Файл больше 1 ГБ: покажите его с компьютера")
        FileOutputStream(p, true).use { out ->
            val buf = ByteArray(64 * 1024)
            var total = have
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > size) { p.delete(); throw StoreException("size", "Получено больше заявленного размера") }
                out.write(buf, 0, n)
            }
        }
        if (p.length() < size) return null
        val sha = sha256Of(p)
        if (!files.status(sha).complete) p.inputStream().use { files.append(sha, size, 0, it) }
        p.delete()
        val safe = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        return FileRef(sha, safe, size, mimeOf(safe))
    }

    private companion object {
        val ID = Regex("^[A-Za-z0-9_-]{8,64}$")
    }
}

/** Модуль Библии на приёмнике, как его видит пульт. */
@Serializable
data class BibleModuleInfo(val file: String, val title: String, val abbreviation: String, val language: String)

/** Запрос слайдов Писания с пульта: адрес строкой («Ин 3:16-18») или номерами книги, главы и стихов. */
@Serializable
data class ScriptureRequest(
    val ref: String = "",
    /** Файлы модулей: первый — основной перевод, второй — параллельный. */
    val modules: List<String>,
    val options: ScriptureOptions = ScriptureOptions(),
    val style: TextStyle = TextStyle(),
    /** Номер книги MyBible (500 — От Иоанна); вместе с [chapter] заменяет [ref]. */
    val book: Int? = null,
    val chapter: Int? = null,
    val from: Int? = null,
    val to: Int? = null,
)

/** Книга перевода для выбора на пульте: номер, названия и число глав. */
@Serializable
data class BibleBookInfo(val number: Int, val shortName: String, val longName: String, val chapters: Int)

@Serializable
data class VerseInfo(val number: Int, val text: String)

/** Открытая глава: стихи основного перевода и, если выбран, второго. */
@Serializable
data class ChapterInfo(
    val book: Int,
    val chapter: Int,
    /** Сколько глав в книге (у основного перевода). */
    val chapters: Int,
    val shortName: String,
    val longName: String,
    val primary: List<VerseInfo>,
    val second: List<VerseInfo>? = null,
)

@Serializable
data class VerseHitInfo(val book: Int, val chapter: Int, val verse: Int, val text: String, val label: String)

@Serializable
data class BibleRefInfo(val book: Int, val chapter: Int, val from: Int? = null, val to: Int? = null, val label: String)

/** Поиск на пульте: запрос похож на адрес — [ref], иначе стихи со всеми словами запроса. */
@Serializable
data class BibleSearchResult(val ref: BibleRefInfo? = null, val hits: List<VerseHitInfo> = emptyList(), val more: Boolean = false)

/**
 * Модули MyBible на самом приёмнике: загружаются с любого пульта один раз и доступны всем.
 * [open] — чтение SQLite на этой платформе (sqlite-jdbc на компьютере, SQLite Android на ТВ).
 */
class BibleStore(private val dir: File, private val open: (File) -> SqlSource) {
    private val cache = mutableMapOf<String, BibleModule>()

    init { dir.mkdirs(); DefaultBibles.install(dir) }

    /** Встроенные переводы (RST, KJV), если ещё не ставились; удалённые не возвращаются. */
    @Synchronized
    fun installDefaults(): List<String> = DefaultBibles.install(dir)

    @Synchronized
    fun modules(): List<BibleModule> = dir.listFiles { f -> f.isFile && f.name.endsWith(".SQLite3", ignoreCase = true) }
        .orEmpty().sortedBy { it.name.lowercase() }
        .mapNotNull { f -> cache[f.name] ?: runCatching { BibleModule(open(f), f.name) }.getOrNull()?.also { cache[f.name] = it } }

    /** Ставит модуль из уже принятого файла. Не модуль Библии — понятная ошибка. */
    @Synchronized
    fun install(source: File, name: String): BibleModule {
        if (listOf(".commentaries", ".dictionary", ".crossreferences").any { name.contains(it, ignoreCase = true) }) {
            throw StoreException("bible", "«$name» — не перевод Библии. Нужен основной файл модуля, например RST.SQLite3")
        }
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").let { if (it.endsWith(".SQLite3", true)) it else "$it.SQLite3" }
        val tmp = File(dir, "$safe.tmp")
        source.copyTo(tmp, overwrite = true)
        try {
            BibleModule(open(tmp), safe).use { if (it.books.isEmpty()) throw StoreException("bible", "В «$name» нет книг: это не модуль Библии MyBible") }
        } catch (e: StoreException) {
            tmp.delete(); throw e
        } catch (e: Exception) {
            tmp.delete(); throw StoreException("bible", "«$name» не открывается как модуль MyBible")
        }
        cache.remove(safe)?.close()
        synchronized(searchers) { searchers.remove(safe) }
        synchronized(chapters) { chapters.remove(safe) }
        File(dir, safe).delete()
        tmp.renameTo(File(dir, safe))
        return modules().first { it.fileName == safe }
    }

    @Synchronized
    fun remove(file: String) {
        if (file.contains('/') || file.contains('\\') || file.startsWith(".")) return
        cache.remove(file)?.close()
        synchronized(searchers) { searchers.remove(file) }
        synchronized(chapters) { chapters.remove(file) }
        File(dir, file).delete()
    }

    fun slides(req: ScriptureRequest): List<ScriptureContent> {
        val mods = pick(req.modules)
        val ref = if (req.book != null && req.chapter != null) BibleRef(req.book, req.chapter, req.from, req.to ?: req.from)
        else parseBibleRef(req.ref, mods.first().books) ?: throw StoreException("bible", "Не понял место «${req.ref}». Пример: Ин 3:16-18")
        return buildScriptureSlides(ref, mods, req.options, req.style).ifEmpty { throw StoreException("bible", "В переводе нет такого места") }
    }

    private fun pick(files: List<String>): List<BibleModule> {
        val all = modules()
        return files.mapNotNull { f -> all.firstOrNull { it.fileName == f } }.ifEmpty { throw StoreException("bible", "Выберите перевод") }
    }

    private fun module(file: String): BibleModule = pick(listOf(file)).first()

    /** Число глав каждой книги: один запрос на модуль, потом из памяти. */
    private val chapters = mutableMapOf<String, Map<Int, Int>>()

    private fun chapterCounts(m: BibleModule): Map<Int, Int> = synchronized(chapters) {
        chapters.getOrPut(m.fileName) {
            m.query("SELECT book_number, MAX(chapter) FROM verses GROUP BY book_number").mapNotNull { r ->
                val b = (r[0] as? Number)?.toInt() ?: r[0]?.toString()?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
                val n = (r[1] as? Number)?.toInt() ?: return@mapNotNull null
                b to n
            }.toMap()
        }
    }

    /** Книги перевода с числом глав; книги без стихов не показываются. */
    fun books(file: String): List<BibleBookInfo> {
        val m = module(file)
        val counts = chapterCounts(m)
        return m.books.mapNotNull { b -> counts[b.number]?.takeIf { it > 0 }?.let { BibleBookInfo(b.number, b.shortName, b.longName, it) } }
    }

    /** Глава одного или двух переводов; второй берёт те же номера книги и главы. */
    fun chapter(files: List<String>, book: Int, chapter: Int): ChapterInfo {
        val mods = pick(files)
        val p = mods.first()
        val b = p.book(book) ?: throw StoreException("bible", "В переводе ${p.abbreviation} нет этой книги")
        val verses = p.verses(book, chapter)
        if (verses.isEmpty()) throw StoreException("bible", "В переводе ${p.abbreviation} нет главы $chapter")
        return ChapterInfo(
            book, chapter, chapterCounts(p)[book] ?: chapter, b.shortName, b.longName,
            verses.map { VerseInfo(it.number, it.text) },
            mods.getOrNull(1)?.verses(book, chapter)?.map { VerseInfo(it.number, it.text) },
        )
    }

    private val searchers = mutableMapOf<String, BibleSearch>()

    /** Поиск по тексту перевода; адрес вида «Ин 3:16» возвращается как место, а не как поиск. */
    fun search(file: String, query: String, limit: Int = SEARCH_LIMIT): BibleSearchResult {
        val m = module(file)
        if (query.isBlank()) return BibleSearchResult()
        parseBibleRef(query, m.books)?.let { r ->
            val label = refLabel(m, r.book, r.chapter, r.fromVerse ?: 1, r.toVerse ?: r.fromVerse ?: 1, full = false, withModule = false)
                .let { if (r.fromVerse == null) it.substringBeforeLast(':') else it }
            return BibleSearchResult(ref = BibleRefInfo(r.book, r.chapter, r.fromVerse, r.toVerse, label))
        }
        val s = synchronized(searchers) { searchers.getOrPut(file) { BibleSearch(m) } }
        val found = s.search(query, limit + 1)
        return BibleSearchResult(
            hits = found.take(limit).map { h -> VerseHitInfo(h.book, h.chapter, h.verse, h.text, refLabel(m, h.book, h.chapter, h.verse, h.verse, full = false, withModule = false)) },
            more = found.size > limit,
        )
    }

    companion object {
        const val SEARCH_LIMIT = 200
    }
}

/** Запрос к Holyrics на компьютере приёмника через пульт в браузере (сам браузер к Holyrics не достучится). */
@Serializable
data class HolyricsCall(val port: Int = HolyricsClient.DEFAULT_PORT, val token: String, val action: String, val body: JsonObject = JsonObject(emptyMap()))

/** Песня или текст Holyrics слайдами Deixis в теме Holyrics. */
@Serializable
data class HolyricsSlidesRequest(val port: Int = HolyricsClient.DEFAULT_PORT, val token: String, val song: String? = null, val text: String? = null, val theme: String? = null)

@Serializable
data class SlideSet(val title: String, val slides: List<String>, val labels: List<String?>, val style: TextStyle?)

/** Что пульту в браузере можно делать с Holyrics: чтение библиотеки и показ, без правки и удаления. */
private val HOLYRICS_ALLOWED = setOf(
    "GetVersion", "GetSongs", "GetSong", "GetSongPlaylist", "GetLyricsPlaylist", "GetTexts", "GetText", "GetThemes",
    "GetImages", "GetVideos", "GetCurrentPresentation", "ActionNext", "ActionPrevious", "ActionGoToIndex",
    "ShowSong", "ShowLyrics", "ShowText", "ShowImage", "PlayVideo", "CloseCurrentPresentation",
)

internal fun Route.remoteRoutes(core: ReceiverCore, authorized: ApplicationCall.() -> Boolean) {
    get("/remote") {
        call.response.header("Cache-Control", "no-store")
        call.respondText(remoteResource("/web/remote.html").decodeToString(), ContentType.Text.Html)
    }

    // Значок и описание для «На экран Домой»: пульт открывается как приложение, без строки адреса.
    get("/remote/icon.png") {
        call.response.header("Cache-Control", "public, max-age=86400")
        call.respondBytes(remoteResource("/web/remote-icon.png"), ContentType.Image.PNG)
    }
    get("/remote/manifest.webmanifest") {
        call.respondText(
            """{"name":"Deixis","short_name":"Deixis","start_url":"/remote","display":"standalone","background_color":"#000000","theme_color":"#000000","icons":[{"src":"/remote/icon.png","sizes":"180x180","type":"image/png"}]}""",
            ContentType("application", "manifest+json"),
        )
    }
    // Беззвучное видео 16×16: на старых iPhone (без Wake Lock) держит экран включённым, пока открыт пульт.
    get("/remote/keepawake.mp4") {
        call.response.header("Cache-Control", "public, max-age=86400")
        call.respondBytes(remoteResource("/web/keepawake.mp4"), ContentType.Video.MP4)
    }

    get("/upload/{id}") {
        if (!call.authorized()) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        val area = core.uploads
        call.respondText("""{"bytes":${area.received(call.parameters["id"].orEmpty())}}""", ContentType.Application.Json)
    }
    put("/upload/{id}") {
        if (!call.authorized()) return@put call.respondText("", status = HttpStatusCode.Unauthorized)
        val area = core.uploads
        val id = call.parameters["id"].orEmpty()
        val size = call.request.header(ReceiverServer.HEADER_SIZE)?.toLongOrNull() ?: return@put call.respondText("Нет размера", status = HttpStatusCode.BadRequest)
        val name = call.request.header(HEADER_NAME)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "file"
        val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0
        try {
            val input = call.receiveChannel().toInputStream()
            val ref = withContext(Dispatchers.IO) { area.append(id, name, size, offset, input) }
            if (ref == null) call.respondText("""{"bytes":${area.received(id)}}""", ContentType.Application.Json)
            else call.respondText("""{"file":${DeixisJson.encodeToString(FileRef.serializer(), ref)}}""", ContentType.Application.Json)
        } catch (e: StoreException) {
            call.respondText(e.message.orEmpty(), status = if (e.code == "offset") HttpStatusCode.Conflict else HttpStatusCode.BadRequest)
        }
    }

    get("/pdf/{sha}/pages") {
        if (!call.authorized()) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        val file = core.files.file(call.parameters["sha"].orEmpty()) ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
        val counter = core.pageCounter ?: return@get call.respondText("", status = HttpStatusCode.NotImplemented)
        val n = runCatching { withContext(Dispatchers.IO) { counter(file) } }.getOrElse { return@get call.respondText("PDF не открывается", status = HttpStatusCode.UnprocessableEntity) }
        call.respondText("""{"pages":$n}""", ContentType.Application.Json)
    }

    // Презентация → PDF на этом приёмнике. Источник сначала передаёт файл как обычно, потом просит перевод.
    post("/convert") {
        if (!call.authorized()) return@post call.respondText("", status = HttpStatusCode.Unauthorized)
        val conv = core.conversions ?: return@post call.respondText("На этом экране нет LibreOffice: сохраните презентацию как PDF", status = HttpStatusCode.NotImplemented)
        try {
            val ref = DeixisJson.decodeFromString(FileRef.serializer(), call.receiveText())
            val result = withContext(Dispatchers.IO) { conv.convert(ref) }
            call.respondText(DeixisJson.encodeToString(Converted.serializer(), result), ContentType.Application.Json)
        } catch (e: StoreException) {
            call.respondText(e.message.orEmpty(), status = HttpStatusCode.UnprocessableEntity)
        }
    }

    get("/bible") {
        if (!call.authorized()) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        val store = core.bible ?: return@get call.respondText("[]", ContentType.Application.Json)
        val list = withContext(Dispatchers.IO) { store.modules() }.map { BibleModuleInfo(it.fileName, it.title, it.abbreviation, it.language) }
        call.respondText(DeixisJson.encodeToString(ListSerializer(BibleModuleInfo.serializer()), list), ContentType.Application.Json)
    }
    post("/bible/install") {
        if (!call.authorized()) return@post call.respondText("", status = HttpStatusCode.Unauthorized)
        val store = core.bible ?: return@post call.respondText("Этот экран не хранит модули Библии", status = HttpStatusCode.NotImplemented)
        try {
            val ref = DeixisJson.decodeFromString(FileRef.serializer(), call.receiveText())
            val file = core.files.file(ref.sha256) ?: throw StoreException("bible", "Файл модуля не получен")
            val m = withContext(Dispatchers.IO) { store.install(file, ref.name) }
            call.respondText(DeixisJson.encodeToString(BibleModuleInfo.serializer(), BibleModuleInfo(m.fileName, m.title, m.abbreviation, m.language)), ContentType.Application.Json)
        } catch (e: StoreException) {
            call.respondText(e.message.orEmpty(), status = HttpStatusCode.UnprocessableEntity)
        }
    }
    delete("/bible/{file}") {
        if (!call.authorized()) return@delete call.respondText("", status = HttpStatusCode.Unauthorized)
        core.bible?.remove(call.parameters["file"].orEmpty())
        call.respondText("{}", ContentType.Application.Json)
    }
    post("/bible/slides") {
        if (!call.authorized()) return@post call.respondText("", status = HttpStatusCode.Unauthorized)
        val store = core.bible ?: return@post call.respondText("Этот экран не хранит модули Библии", status = HttpStatusCode.NotImplemented)
        try {
            val req = DeixisJson.decodeFromString(ScriptureRequest.serializer(), call.receiveText())
            val slides = withContext(Dispatchers.IO) { store.slides(req) }
            call.respondText(DeixisJson.encodeToString(ListSerializer(ScriptureContent.serializer()), slides), ContentType.Application.Json)
        } catch (e: StoreException) {
            call.respondText(e.message.orEmpty(), status = HttpStatusCode.UnprocessableEntity)
        }
    }

    // Вкладка «Библия» на пульте: книги, глава одного или двух переводов, поиск по тексту.
    get("/bible/books") {
        if (!call.authorized()) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        val store = core.bible ?: return@get call.respondText("Этот экран не хранит модули Библии", status = HttpStatusCode.NotImplemented)
        try {
            val list = withContext(Dispatchers.IO) { store.books(call.request.queryParameters["module"].orEmpty()) }
            call.respondText(DeixisJson.encodeToString(ListSerializer(BibleBookInfo.serializer()), list), ContentType.Application.Json)
        } catch (e: StoreException) {
            call.respondText(e.message.orEmpty(), status = HttpStatusCode.UnprocessableEntity)
        }
    }
    get("/bible/chapter") {
        if (!call.authorized()) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        val store = core.bible ?: return@get call.respondText("Этот экран не хранит модули Библии", status = HttpStatusCode.NotImplemented)
        val q = call.request.queryParameters
        val book = q["book"]?.toIntOrNull()
        val chapter = q["chapter"]?.toIntOrNull()
        if (book == null || chapter == null) return@get call.respondText("Нужны book и chapter", status = HttpStatusCode.BadRequest)
        val modules = q["modules"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(2)
        try {
            val ch = withContext(Dispatchers.IO) { store.chapter(modules, book, chapter) }
            call.respondText(DeixisJson.encodeToString(ChapterInfo.serializer(), ch), ContentType.Application.Json)
        } catch (e: StoreException) {
            call.respondText(e.message.orEmpty(), status = HttpStatusCode.UnprocessableEntity)
        }
    }
    get("/bible/search") {
        if (!call.authorized()) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        val store = core.bible ?: return@get call.respondText("Этот экран не хранит модули Библии", status = HttpStatusCode.NotImplemented)
        val q = call.request.queryParameters
        try {
            val r = withContext(Dispatchers.Default) { store.search(q["module"].orEmpty(), q["q"].orEmpty().take(200)) }
            call.respondText(DeixisJson.encodeToString(BibleSearchResult.serializer(), r), ContentType.Application.Json)
        } catch (e: StoreException) {
            call.respondText(e.message.orEmpty(), status = HttpStatusCode.UnprocessableEntity)
        }
    }

    // QR-код рисует сам приёмник: страница пульта в сеть не ходит и библиотек не грузит.
    get("/remote/qr.svg") {
        val text = call.request.queryParameters["text"].orEmpty()
        if (text.isEmpty() || text.length > 1024) return@get call.respondText("", status = HttpStatusCode.BadRequest)
        call.response.header("Cache-Control", "private, max-age=3600")
        call.respondText(qrSvg(text), ContentType.Image.SVG)
    }

    // Файлы и страницы PDF на этом экране — для превью на пульте. Картинке и видео браузер заголовок не пришлёт,
    // поэтому токен можно передать и параметром t.
    get("/remote/file/{sha}") {
        if (!call.authorized() && !core.pairing.isValid(call.request.queryParameters["t"])) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        val file = core.files.file(call.parameters["sha"].orEmpty()) ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
        val type = runCatching { ContentType.parse(mimeOf(call.request.queryParameters["n"].orEmpty())) }.getOrDefault(ContentType.Application.OctetStream)
        call.response.header("Cache-Control", "private, max-age=3600")
        call.respond(LocalFileContent(file, type))
    }
    get("/remote/page/{sha}/{page}") {
        if (!call.authorized() && !core.pairing.isValid(call.request.queryParameters["t"])) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        val file = core.files.file(call.parameters["sha"].orEmpty()) ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
        val page = call.parameters["page"]?.toIntOrNull() ?: return@get call.respondText("", status = HttpStatusCode.BadRequest)
        val renderer = core.pageRenderer ?: return@get call.respondText("", status = HttpStatusCode.NotImplemented)
        val width = (call.request.queryParameters["w"]?.toIntOrNull() ?: 640).coerceIn(160, 1600)
        val key = "${file.name}:$page:$width"
        val png = synchronized(thumbs) { thumbs[key] } ?: try {
            withContext(Dispatchers.IO) { renderer.render(file, page, width) }.also { synchronized(thumbs) { thumbs[key] = it } }
        } catch (e: Exception) {
            return@get call.respondText("", status = HttpStatusCode.UnprocessableEntity)
        }
        call.response.header("Cache-Control", "private, max-age=3600")
        call.respondBytes(png, ContentType.Image.PNG)
    }

    // Holyrics на этом же компьютере. Только там, где есть мост (Deixis для компьютера).
    post("/holyrics/call") {
        if (!call.authorized()) return@post call.respondText("", status = HttpStatusCode.Unauthorized)
        if (core.holyrics == null) return@post call.respondText("Holyrics доступен через Deixis для компьютера рядом с ним", status = HttpStatusCode.NotImplemented)
        try {
            val req = DeixisJson.decodeFromString(HolyricsCall.serializer(), call.receiveText())
            if (req.action !in HOLYRICS_ALLOWED) return@post call.respondText("Действие недоступно", status = HttpStatusCode.Forbidden)
            val data: JsonElement = withContext(Dispatchers.IO) { HolyricsClient("127.0.0.1", req.port, req.token).use { it.call(req.action, req.body) } } ?: JsonNull
            call.respondText(data.toString(), ContentType.Application.Json)
        } catch (e: Exception) {
            call.respondText(e.message ?: "Holyrics не ответил", status = HttpStatusCode.BadGateway)
        }
    }
    post("/holyrics/slides") {
        if (!call.authorized()) return@post call.respondText("", status = HttpStatusCode.Unauthorized)
        val bridge = core.holyrics ?: return@post call.respondText("Holyrics доступен через Deixis для компьютера рядом с ним", status = HttpStatusCode.NotImplemented)
        try {
            val req = DeixisJson.decodeFromString(HolyricsSlidesRequest.serializer(), call.receiveText())
            val set = withContext(Dispatchers.IO) {
                HolyricsClient("127.0.0.1", req.port, req.token).use { h ->
                    val (title, slides, labels, itemTheme) = when {
                        req.song != null -> h.song(req.song)?.let { s -> val o = s.orderedSlides(); Quad(s.title, o.map { it.text }, o.map { it.description }, s.theme) }
                        req.text != null -> h.text(req.text)?.let { t -> Quad(t.title, t.slides.map { it.text }, t.slides.map { null }, t.theme) }
                        else -> null
                    } ?: throw HolyricsException("Не найдено в Holyrics")
                    val theme = h.themes().firstOrNull { it.id == (req.theme ?: itemTheme) }
                    val bg = theme?.backgroundImageName?.let { name ->
                        runCatching { bridge.import(HolyricsMediaRequest(req.port, req.token, HolyricsMedia.IMAGE, name)) }.getOrNull()
                    }
                    SlideSet(title, slides, labels, theme?.toTextStyle(bg))
                }
            }
            call.respondText(DeixisJson.encodeToString(SlideSet.serializer(), set), ContentType.Application.Json)
        } catch (e: Exception) {
            call.respondText(e.message ?: "Holyrics не ответил", status = HttpStatusCode.BadGateway)
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** Превью страниц PDF для пульта: последние 64 картинки в памяти. */
private val thumbs = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 64
}

/** QR-код как SVG: тёмные модули одним путём, поле в четыре модуля. */
internal fun qrSvg(text: String): String {
    val m = qrMatrix(text)
    val n = m.size
    val q = 4
    val d = StringBuilder()
    for (y in 0 until n) for (x in 0 until n) if (m[y][x]) d.append("M${x + q} ${y + q}h1v1h-1z")
    val size = n + 2 * q
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $size $size\" shape-rendering=\"crispEdges\">" +
        "<rect width=\"$size\" height=\"$size\" fill=\"#fff\"/><path d=\"$d\" fill=\"#000\"/></svg>"
}

/** Имя файла при загрузке из браузера (в URL-кодировке: заголовки не терпят кириллицу). */
const val HEADER_NAME = "Deixis-Name"

private fun remoteResource(path: String): ByteArray =
    ReceiverCore::class.java.getResourceAsStream(path)?.use { it.readBytes() } ?: error("Нет ресурса $path")
