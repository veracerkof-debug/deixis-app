package app.deixis.core

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Что получает браузер зрителя: только то, что видит зал. Ошибки и служебные сведения сюда не попадают. */
@Serializable
data class ViewerState(val content: Content?, val mode: Mode, val video: VideoState?)

/** Код закрытия WebSocket, если ключ в ссылке неверный или устарел. */
const val VIEW_BAD_KEY: Short = 4001

/** Сигнал «связь жива» раз в 2 секунды: браузер переподключается, если его нет 6 секунд. */
internal const val VIEW_TICK_MS = 2_000L
const val VIEW_TICK = """{"tick":1}"""

/**
 * Сколько зрителей одновременно получают видеофайл. Каждый тянет поток с приёмника по Wi-Fi;
 * без предела полсотни телефонов забьют сеть. Остальные видят «Видео идёт на основном экране».
 */
const val MAX_VIDEO_STREAMS = 20

/**
 * Страница для зрителей: любой телефон в той же сети открывает ссылку из QR-кода
 * и смотрит показ в браузере без установки приложения. Только просмотр.
 */
internal fun Route.viewerRoutes(core: ReceiverCore) {
    val videoStreams = java.util.concurrent.atomic.AtomicInteger()
    val pages = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 12
    }

    get("/view") {
        call.response.header("Cache-Control", "no-store")
        call.respondText(resource("/web/view.html").decodeToString(), ContentType.Text.Html)
    }
    get("/view/assets/{name}") {
        val name = call.parameters["name"].orEmpty()
        val type = when {
            name.endsWith(".ttf") -> ContentType("font", "ttf")
            else -> return@get call.respondText("", status = HttpStatusCode.NotFound)
        }
        if (name !in ASSETS) return@get call.respondText("", status = HttpStatusCode.NotFound)
        call.response.header("Cache-Control", "public, max-age=86400")
        call.respondBytes(resource("/web/$name"), type)
    }

    webSocket("/view/ws") {
        if (call.request.queryParameters["k"] != core.viewKey) {
            close(CloseReason(VIEW_BAD_KEY, "bad key"))
            return@webSocket
        }
        core.viewerJoined()
        val updates = launch {
            core.state
                .map { ViewerState(it.content, it.mode, it.video) }
                .distinctUntilChanged()
                .collect { send(Frame.Text(DeixisJson.encodeToString(ViewerState.serializer(), it))) }
        }
        val ticks = launch {
            while (true) {
                kotlinx.coroutines.delay(VIEW_TICK_MS)
                send(Frame.Text(VIEW_TICK))
            }
        }
        try {
            for (frame in incoming) Unit // зритель ничего не отправляет
        } finally {
            ticks.cancel()
            updates.cancel()
            core.viewerLeft()
        }
    }

    // Отдаётся только файл, который сейчас на экране: по ссылке зрителя нельзя скачать остальной кэш.
    get("/view/file/{sha}") {
        val ref = call.currentFile(core) ?: return@get
        val file = core.files.file(ref.sha256) ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
        val type = runCatching { ContentType.parse(ref.mime) }.getOrDefault(ContentType.Application.OctetStream)
        val isStream = type.contentType == "video" || type.contentType == "audio"
        if (isStream && videoStreams.incrementAndGet() > MAX_VIDEO_STREAMS) {
            videoStreams.decrementAndGet()
            return@get call.respondText("", status = HttpStatusCode.ServiceUnavailable)
        }
        try {
            call.response.header("Cache-Control", "private, max-age=3600")
            call.respond(LocalFileContent(file, type))
        } finally {
            if (isStream) videoStreams.decrementAndGet()
        }
    }

    get("/view/page/{sha}/{page}") {
        val ref = call.currentFile(core) ?: return@get
        val page = call.parameters["page"]?.toIntOrNull() ?: return@get call.respondText("", status = HttpStatusCode.BadRequest)
        val width = (call.request.queryParameters["w"]?.toIntOrNull() ?: 1600).coerceIn(320, 2400)
        val renderer = core.pageRenderer ?: return@get call.respondText("", status = HttpStatusCode.NotImplemented)
        val file = core.files.file(ref.sha256) ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
        val key = "${ref.sha256}:$page:$width"
        val png = synchronized(pages) { pages[key] } ?: try {
            withContext(Dispatchers.IO) { renderer.render(file, page, width) }.also { synchronized(pages) { pages[key] = it } }
        } catch (e: Exception) {
            return@get call.respondText("", status = HttpStatusCode.UnprocessableEntity)
        }
        call.response.header("Cache-Control", "private, max-age=3600")
        call.respondBytes(png, ContentType.Image.PNG)
    }
}

private val ASSETS = setOf("GFSDidot-Regular.ttf", "NotoSans-SemiBold.ttf")

private suspend fun ApplicationCall.currentFile(core: ReceiverCore): FileRef? {
    if (request.queryParameters["k"] != core.viewKey) {
        respondText("", status = HttpStatusCode.Forbidden)
        return null
    }
    val ref = core.state.value.content?.file
    if (ref == null || ref.sha256 != parameters["sha"]) {
        respondText("", status = HttpStatusCode.NotFound)
        return null
    }
    return ref
}

private fun resource(path: String): ByteArray =
    ReceiverCore::class.java.getResourceAsStream(path)?.use { it.readBytes() } ?: error("Нет ресурса $path")
