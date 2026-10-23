package app.deixis.core

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.writeFully
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.SecureRandom

/**
 * Логика приёмника без экрана: что показано, какие файлы есть, кто сопряжён.
 * Платформа (Deixis TV, компьютер) рисует [state] и исполняет [videoCommands].
 */
class ReceiverCore(
    val info: ReceiverInfo,
    val files: FileStore,
    val pairing: Pairing,
    /** Ключ страницы для зрителей. Хранится между запусками: перезагрузка ТВ не ломает уже открытые ссылки. */
    val viewKey: String = newViewKey(),
) {
    private val _state = MutableStateFlow(ReceiverState())
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    private val _videoCommands = MutableSharedFlow<VideoCommand>(extraBufferCapacity = 16)
    val videoCommands: SharedFlow<VideoCommand> = _videoCommands.asSharedFlow()

    /**
     * Платформа рисует страницу PDF в PNG для браузеров зрителей (Safari не показывает PDF как картинку).
     * Без него страницы PDF зрителям недоступны.
     */
    @Volatile
    var pageRenderer: PageRenderer? = null

    /** Мост к файлам Holyrics на этом же компьютере; null — не поддерживается (например, на ТВ). */
    @Volatile
    var holyrics: HolyricsBridge? = null

    /** Поток демонстрации экрана от источника; экран зала и зрители подписываются на него сами. */
    val stream = StreamHub()

    internal fun viewerJoined() = _state.update { it.copy(viewers = it.viewers + 1) }
    internal fun viewerLeft() = _state.update { it.copy(viewers = (it.viewers - 1).coerceAtLeast(0)) }

    /** Применяет команду источника. Возвращает ошибку, если команду выполнить нельзя; показ при этом не меняется. */
    fun apply(msg: Msg): ErrorMsg? = when (msg) {
        is Show -> show(msg.content)
        is SetMode -> { _state.update { it.copy(mode = msg.mode) }; null }
        is VideoCommand -> {
            if (_state.value.content !is VideoContent) ErrorMsg("no_video", "Сейчас не идёт видео")
            else { _videoCommands.tryEmit(msg); null }
        }
        else -> null
    }

    private fun show(content: Content): ErrorMsg? {
        for (ref in content.files) {
            if (files.file(ref.sha256) == null) return ErrorMsg("missing_file", "На приёмнике нет файла «${ref.name}»")
            files.touch(ref.sha256)
        }
        _state.update { ReceiverState(content = content, mode = Mode.NORMAL, video = null, viewers = it.viewers) }
        return null
    }

    /** Платформа сообщает о ходе видео. */
    fun reportVideo(video: VideoState?) = _state.update { it.copy(video = video) }

    /**
     * Платформа сообщает, что показать не удалось (например, файл не декодируется).
     * В зал ошибка не выводится: экран остаётся чёрным, а сообщение уходит на пульт.
     */
    fun reportError(message: String?) = _state.update { it.copy(error = message) }
}

/** Рисует страницу [page] PDF-файла шириной не больше [maxWidth] и возвращает PNG. */
fun interface PageRenderer {
    fun render(file: File, page: Int, maxWidth: Int): ByteArray
}

/** Читает ключ зрителей из файла или создаёт новый и сохраняет его. */
fun loadViewKey(file: File): String {
    file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length >= 8 }?.let { return it }
    return newViewKey().also { file.parentFile?.mkdirs(); file.writeText(it) }
}

fun newViewKey(): String {
    val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
    val r = SecureRandom()
    return (1..12).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
}

/** Сетевой сервер приёмника: WebSocket для команд, HTTP для файлов и страница для зрителей. */
/** [host] = "127.0.0.1" — только для этого устройства (вывод по кабелю с телефона), иначе вся локальная сеть. */
class ReceiverServer(
    private val core: ReceiverCore,
    private val port: Int = DEFAULT_PORT,
    private val host: String = "0.0.0.0",
    /** Файлы хранятся сутки после последнего показа, потом удаляются: приёмник не копит чужие фото и видео. */
    private val fileTtlMs: Long = FILE_TTL_MS,
) {

    private var server: EmbeddedServer<*, *>? = null
    private var cleaner: kotlinx.coroutines.Job? = null

    /** Запускает сервер и возвращает фактический порт (при port = 0 система выбирает свободный). */
    suspend fun start(): Int {
        val s = embeddedServer(CIO, port = port, host = host) {
            install(io.ktor.server.plugins.partialcontent.PartialContent)
            install(io.ktor.server.plugins.autohead.AutoHeadResponse)
            install(WebSockets) {
                pingPeriodMillis = 1_000
                timeoutMillis = 5_000
                maxFrameSize = 8L * 1024 * 1024
            }
            routing {
                get("/info") {
                    call.respondText(DeixisJson.encodeToString(ReceiverInfo.serializer(), core.info), ContentType.Application.Json)
                }
                get("/files/{sha}") {
                    if (!call.authorized()) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
                    val sha = call.parameters["sha"].orEmpty()
                    try {
                        call.respondJson(core.files.status(sha))
                    } catch (e: StoreException) {
                        call.respondText(e.message.orEmpty(), status = HttpStatusCode.BadRequest)
                    }
                }
                put("/files/{sha}") {
                    if (!call.authorized()) return@put call.respondText("", status = HttpStatusCode.Unauthorized)
                    val sha = call.parameters["sha"].orEmpty()
                    val size = call.request.header(HEADER_SIZE)?.toLongOrNull()
                    val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0
                    if (size == null) return@put call.respondText("Нет $HEADER_SIZE", status = HttpStatusCode.BadRequest)
                    try {
                        val input = call.receiveChannel().toInputStream()
                        val status = withContext(Dispatchers.IO) { core.files.append(sha, size, offset, input) }
                        call.respondJson(status)
                    } catch (e: StoreException) {
                        val code = when (e.code) {
                            "offset" -> HttpStatusCode.Conflict
                            "busy" -> HttpStatusCode.Conflict
                            "space" -> HttpStatusCode.InsufficientStorage
                            else -> HttpStatusCode.BadRequest
                        }
                        call.respondText(e.code + ": " + e.message, status = code)
                    }
                }
                post("/holyrics/media") {
                    if (!call.authorized()) return@post call.respondText("", status = HttpStatusCode.Unauthorized)
                    val bridge = core.holyrics ?: return@post call.respondText("Этот приёмник не берёт файлы из Holyrics", status = HttpStatusCode.NotImplemented)
                    try {
                        val req = DeixisJson.decodeFromString(HolyricsMediaRequest.serializer(), call.receiveText())
                        val ref = withContext(Dispatchers.IO) { bridge.import(req) }
                        call.respondText(DeixisJson.encodeToString(FileRef.serializer(), ref), ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respondText(e.message ?: "Не удалось взять файл из Holyrics", status = HttpStatusCode.UnprocessableEntity)
                    }
                }
                webSocket("/ws") { handleSession(this) }
                webSocket("/stream") { handleStream(this) }
                // Поток экрана для своего же проигрывателя (VLC на компьютере): тот же MP4, что у зрителей,
                // с метками времени — без них VLC играл бы с угаданной частотой кадров и копил отставание.
                get("/stream/mp4") {
                    if (call.request.local.remoteAddress !in LOOPBACK) return@get call.respondText("", status = HttpStatusCode.Forbidden)
                    call.response.header("Cache-Control", "no-store")
                    call.respondBytesWriter(ContentType("video", "mp4")) {
                        fmp4Stream(core, onCodec = {}, onData = { writeFully(it); flush() }, endOnNewConfig = true)
                    }
                }
                viewerRoutes(core)
            }
        }
        s.start(wait = false)
        server = s
        cleaner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            while (true) {
                val keep = core.state.value.content?.files.orEmpty().map { it.sha256 }.toSet()
                runCatching { core.files.purgeOlderThan(fileTtlMs, keep) }
                kotlinx.coroutines.delay(CLEAN_EVERY_MS)
            }
        }
        return s.engine.resolvedConnectors().first().port
    }

    /** Адрес потока экрана для проигрывателя на этом же устройстве. */
    fun localStreamUrl(port: Int) = "http://127.0.0.1:$port/stream/mp4"

    fun stop() {
        cleaner?.cancel()
        cleaner = null
        server?.stop(500, 2_000)
        server = null
    }

    private suspend fun handleSession(ws: io.ktor.server.websocket.DefaultWebSocketServerSession) = with(ws) {
        // Первое сообщение — hello с токеном или pair с PIN. Без него сессия закрывается.
        val first = withTimeoutOrNull(10_000) {
            (incoming.receiveCatching().getOrNull() as? Frame.Text)?.readText()?.let(::decodeMsg)
        }
        val ok = when (first) {
            is Hello -> when {
                first.version != PROTOCOL_VERSION -> deny(DenyReason.INCOMPATIBLE_VERSION)
                !core.pairing.isValid(first.token) -> deny(DenyReason.UNKNOWN_TOKEN)
                else -> true
            }
            is PairRequest -> when {
                first.version != PROTOCOL_VERSION -> deny(DenyReason.INCOMPATIBLE_VERSION)
                else -> when (val r = core.pairing.pair(first.pin, first.sender)) {
                    is Pairing.Result.Ok -> { send(Frame.Text(Paired(r.token).encode())); true }
                    is Pairing.Result.Denied -> deny(r.reason)
                }
            }
            else -> false
        }
        if (!ok) return@with close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "denied"))

        send(Frame.Text(Welcome(PROTOCOL_VERSION, core.info, core.state.value, core.viewKey).encode()))
        val updates = launch {
            core.state.drop(1).collect { send(Frame.Text(Status(it).encode())) }
        }
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                when (val msg = decodeMsg(frame.readText())) {
                    null -> Unit
                    is Ping -> send(Frame.Text(Pong(msg.t).encode()))
                    else -> core.apply(msg)?.let { send(Frame.Text(it.encode())) }
                }
            }
        } finally {
            updates.cancel()
        }
    }

    private val streamSource = java.util.concurrent.atomic.AtomicInteger()

    /** Источник шлёт кадры экрана двоичными сообщениями. Новый источник вытесняет прежний. */
    private suspend fun handleStream(ws: io.ktor.server.websocket.DefaultWebSocketServerSession) = with(ws) {
        if (!call.authorized()) return@with close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "denied"))
        val me = streamSource.incrementAndGet()
        core.stream.start()
        val keys = launch { core.stream.keyRequests.collect { send(Frame.Text(STREAM_KEY_REQUEST)) } }
        try {
            for (frame in incoming) {
                if (streamSource.get() != me) break
                if (frame !is Frame.Binary) continue
                val f = StreamFrame.decode(frame.readBytes()) ?: continue
                core.stream.publish(f)
                // Подтверждение: по нему телефон видит настоящее отставание, которое прячут буферы сети.
                if (!f.config) send(Frame.Text("""{"ack":${f.seq}}"""))
            }
        } finally {
            keys.cancel()
            if (streamSource.get() == me) core.stream.stop()
        }
    }

    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.deny(reason: DenyReason): Boolean {
        send(Frame.Text(Denied(reason).encode()))
        return false
    }

    private fun ApplicationCall.authorized(): Boolean =
        core.pairing.isValid(request.header("Authorization")?.removePrefix("Bearer ")?.trim())

    private suspend fun ApplicationCall.respondJson(status: FileStatus) =
        respondText(DeixisJson.encodeToString(FileStatus.serializer(), status), ContentType.Application.Json)

    companion object {
        const val DEFAULT_PORT = 47820
        const val HEADER_SIZE = "Deixis-Size"
        const val FILE_TTL_MS = 24L * 60 * 60 * 1000
        private val LOOPBACK = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost")
        /** Приёмник просит у источника ключевой кадр: подключился новый зритель. */
        const val STREAM_KEY_REQUEST = """{"key":1}"""
        private const val CLEAN_EVERY_MS = 60L * 60 * 1000
    }
}
