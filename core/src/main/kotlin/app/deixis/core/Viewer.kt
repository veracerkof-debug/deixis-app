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
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Что получает браузер зрителя: только то, что видит зал. Ошибки и служебные сведения сюда не попадают. */
@Serializable
data class ViewerState(
    val content: Content?,
    val mode: Mode,
    val video: VideoState?,
    /** Следующий слайд — только экрану в браузере (`screen=1`) для вида «Сцена». */
    val next: Content? = null,
    /** Когда начался показ, по часам приёмника: таймер на виде «Сцена». */
    val startedAt: Long? = null,
    /** Часы приёмника в момент отправки: браузер поправляет по ним свои. */
    val now: Long? = null,
    /** Слой поверх показа: и зрителям, и экрану в браузере. */
    val overlays: Overlays = Overlays(),
    /** Вид заставки «Логотип». */
    val logo: LogoStyle = LogoStyle.WORDMARK,
    /** Своя картинка на «Логотипе»: страница берёт её как /view/file/хэш. */
    val logoImage: FileRef? = null,
)

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

/** Сколько кадров трансляции экрана может быть в пути к браузеру без подтверждения (около трети секунды). */
internal const val VIEW_STREAM_WINDOW = 10

/**
 * Страница для зрителей: любой телефон в той же сети открывает ссылку из QR-кода
 * и смотрит показ в браузере без установки приложения. Только просмотр.
 */
internal fun Route.viewerRoutes(core: ReceiverCore) {
    val videoStreams = java.util.concurrent.atomic.AtomicInteger()
    val pages = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 12
    }

    // `?screen=1` — та же страница в роли экрана вывода (телевизор с браузером, ноутбук, планшет).
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
        // Превью в пульте и экран в браузере (`screen=1`) — не зрители: их не считаем.
        val screen = call.request.queryParameters["screen"] == "1"
        val preview = call.request.queryParameters["preview"] == "1" || screen
        if (!preview) core.viewerJoined()
        val updates = launch {
            core.state
                .map { ViewerState(it.content, it.mode, it.video, next = if (screen) it.next else null, startedAt = core.showStartedAt, overlays = it.overlays, logo = it.logo, logoImage = it.logoImage) }
                .distinctUntilChanged()
                .collect { send(Frame.Text(DeixisJson.encodeToString(ViewerState.serializer(), it.copy(now = System.currentTimeMillis())))) }
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
            if (!preview) core.viewerLeft()
        }
    }

    // Демонстрация экрана для браузера: фрагментированный MP4, кадр за кадром. Перед каждым новым набором
    // SPS/PPS идёт текстовое сообщение с кодеком и начальный сегмент.
    webSocket("/view/stream") {
        if (call.request.queryParameters["k"] != core.viewKey) {
            close(CloseReason(VIEW_BAD_KEY, "bad key"))
            return@webSocket
        }
        // Браузер подтверждает каждое полученное сообщение. Буферы сети и браузера способны спрятать
        // секунды видео, поэтому отставание считается по неподтверждённым кадрам, а не по скорости отправки.
        val sent = java.util.concurrent.atomic.AtomicLong()
        val acked = java.util.concurrent.atomic.AtomicLong()
        val acks = launch { for (frame in incoming) if (frame is Frame.Text) acked.incrementAndGet() }
        try {
            fmp4Stream(core,
                onCodec = { send(Frame.Text("""{"codec":"$it"}""")) },
                onData = { sent.incrementAndGet(); send(Frame.Binary(true, it)) },
                canSend = { sent.get() - acked.get() < VIEW_STREAM_WINDOW })
        } finally {
            acks.cancel()
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

/**
 * Поток экрана в виде фрагментированного MP4 для одного подписчика. Перед каждым новым набором SPS/PPS
 * вызывается [onCodec] (строка вроде avc1.42E01F) и отдаётся начальный сегмент, затем — по фрагменту на кадр.
 */
internal suspend fun fmp4Stream(
    core: ReceiverCore,
    onCodec: suspend (String) -> Unit,
    onData: suspend (ByteArray) -> Unit,
    /** Закончить поток при смене SPS/PPS (поворот экрана): проигрыватель откроет его заново с новым размером. */
    endOnNewConfig: Boolean = false,
    /** false — подписчик не успевает: кадры выбрасываются до следующего ключевого. */
    canSend: () -> Boolean = { true },
) {
    var skipping = false
    var mux: Fmp4Muxer? = null
    var sentConfig: ByteArray? = null
    core.stream.subscribe().takeWhile { s ->
        !(endOnNewConfig && sentConfig != null && s.key && s.config != null && !s.config.contentEquals(sentConfig))
    }.collect { s ->
        if (skipping && !s.key) return@collect
        if (!canSend()) { skipping = true; return@collect }
        skipping = false
        val cfg = s.config
        if (s.key && cfg != null && !cfg.contentEquals(sentConfig)) {
            val sps = splitNalUnits(cfg).firstOrNull { nalType(it) == NAL_SPS } ?: return@collect
            val screen = core.state.value.content as? ScreenContent
            val (w, h) = avcFrameSize(sps) ?: ((screen?.width ?: 1280) to (screen?.height ?: 720))
            val m = mux?.takeIf { it.width == w && it.height == h } ?: Fmp4Muxer(w, h)
            val init = m.init(cfg) ?: return@collect
            onCodec(avcCodecString(sps))
            onData(init)
            mux = m
            sentConfig = cfg
        }
        mux?.let { onData(it.fragment(s)) }
    }
}

private val ASSETS = setOf("GFSDidot-Regular.ttf", "NotoSans-SemiBold.ttf")

private suspend fun ApplicationCall.currentFile(core: ReceiverCore): FileRef? {
    if (request.queryParameters["k"] != core.viewKey) {
        respondText("", status = HttpStatusCode.Forbidden)
        return null
    }
    // Файл показа или картинка фона его оформления; для вида «Сцена» — и следующего слайда; логотип слоя;
    // своя картинка «Логотипа».
    val state = core.state.value
    val ref = (state.content?.files.orEmpty() + state.next?.files.orEmpty() + state.overlays.files + listOfNotNull(state.logoImage))
        .firstOrNull { it.sha256 == parameters["sha"] }
    if (ref == null) {
        respondText("", status = HttpStatusCode.NotFound)
        return null
    }
    return ref
}

private fun resource(path: String): ByteArray =
    ReceiverCore::class.java.getResourceAsStream(path)?.use { it.readBytes() } ?: error("Нет ресурса $path")
