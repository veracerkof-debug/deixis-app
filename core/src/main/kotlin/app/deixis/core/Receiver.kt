package app.deixis.core

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.connector
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

    /** Загрузки из браузера пульта: хэш считает приёмник. */
    val uploads = UploadArea(files, File(files.root, "uploads"))

    /** Шифрованный вход, когда он работает: его порт и отпечаток сертификата — в /info, приветствии, QR и mDNS. */
    @Volatile var tls: TlsEndpoint? = null

    /** Сведения о приёмнике для пультов: с шифрованным входом, если он есть. */
    fun publicInfo(): ReceiverInfo = tls?.let { info.copy(tlsPort = it.port, fingerprint = it.fingerprint) } ?: info

    /** Файлы, которые сейчас нужны экрану: показ, следующий слайд, наложения, логотип. Очистка кэша их не трогает. */
    fun onScreen(): Set<String> {
        val s = _state.value
        return (s.content?.files.orEmpty() + s.next?.files.orEmpty() + s.overlays.files + listOfNotNull(s.logoImage))
            .map { it.sha256.lowercase() }.toSet()
    }

    init { files.protect = ::onScreen }

    /** Модули Библии на приёмнике для веб-пульта; null — платформа не читает SQLite. */
    @Volatile
    var bible: BibleStore? = null

    /** Число страниц PDF (веб-пульту, чтобы листать); null — не умеет. */
    @Volatile
    var pageCounter: ((File) -> Int)? = null

    /** Перевод презентаций в PDF; null — не умеет (нет LibreOffice, ТВ). */
    @Volatile
    var conversions: Conversions? = null

    /** Несколько выводов (мониторы, пересылка на экраны в сети); null — один вывод (ТВ, телефон). */
    @Volatile
    var outputs: OutputsHost? = null
    /** Обновления этого приёмника; null — пульт не управляет ими (обновляется сам, как телефон). */
    var updates: UpdateHost? = null

    /** Мост к файлам Holyrics на этом же компьютере; null — не поддерживается (например, на ТВ). */
    @Volatile
    var holyrics: HolyricsBridge? = null

    /** Поток демонстрации экрана от источника; экран зала и зрители подписываются на него сами. */
    val stream = StreamHub()

    /** Звук трансляции экрана от источника; колонки зала подписываются сами. */
    val audio = AudioHub()

    /** Когда пошёл первый слайд после запуска: от него считается время на виде «Сцена». */
    @Volatile
    var showStartedAt: Long? = null
        private set

    internal fun viewerJoined() = _state.update { it.copy(viewers = it.viewers + 1) }
    internal fun viewerLeft() = _state.update { it.copy(viewers = (it.viewers - 1).coerceAtLeast(0)) }

    /** Применяет команду источника. Возвращает ошибку, если команду выполнить нельзя; показ при этом не меняется. */
    fun apply(msg: Msg): ErrorMsg? = when (msg) {
        is Show -> show(msg.content, msg.next)
        is SetMode -> {
            val missing = msg.logoImage?.takeIf { files.file(it.sha256) == null }
            if (missing != null) ErrorMsg("missing_file", "На приёмнике нет файла «${missing.name}»")
            else {
                msg.logoImage?.let { files.touch(it.sha256) }
                // Картинка логотипа меняется вместе с его видом: старый пульт вид не шлёт — картинка остаётся.
                _state.update { if (msg.logo != null) it.copy(mode = msg.mode, logo = msg.logo, logoImage = msg.logoImage) else it.copy(mode = msg.mode) }
                null
            }
        }
        is SetTimer -> {
            val t = msg.timer?.anchored(System.currentTimeMillis())
            _state.update { s ->
                val c = s.content
                s.copy(timer = t, content = if (c is CountdownContent && t != null && c.id == t.id) t else c)
            }
            null
        }
        is SetOverlays -> {
            val missing = msg.overlays.files.firstOrNull { files.file(it.sha256) == null }
            if (missing != null) ErrorMsg("missing_file", "На приёмнике нет файла «${missing.name}»")
            else { _state.update { it.copy(overlays = msg.overlays) }; null }
        }
        is VideoCommand -> {
            if (_state.value.content !is VideoContent) ErrorMsg("no_video", "Сейчас не идёт видео")
            else { _videoCommands.tryEmit(msg); null }
        }
        else -> null
    }

    private fun show(content: Content, next: Content? = null): ErrorMsg? {
        for (ref in content.files) {
            if (files.file(ref.sha256) == null) return ErrorMsg("missing_file", "На приёмнике нет файла «${ref.name}»")
            files.touch(ref.sha256)
        }
        if (showStartedAt == null) showStartedAt = System.currentTimeMillis()
        // Отсчёт привязывается к часам этого экрана; уйдя из зала, он остаётся в «Сцене».
        val shown = (content as? CountdownContent)?.anchored(System.currentTimeMillis()) ?: content
        _state.update { ReceiverState(content = shown, mode = Mode.NORMAL, video = null, viewers = it.viewers, next = next, timer = (shown as? CountdownContent) ?: it.timer, overlays = it.overlays, logo = it.logo, logoImage = it.logoImage) }
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
/** Шифрованный вход приёмника: порт и отпечаток сертификата. */
data class TlsEndpoint(val port: Int, val fingerprint: String)

class ReceiverServer(
    private val core: ReceiverCore,
    private val port: Int = DEFAULT_PORT,
    private val host: String = "0.0.0.0",
    /** Файлы хранятся сутки после последнего показа, потом удаляются: приёмник не копит чужие фото и видео. */
    private val fileTtlMs: Long = FILE_TTL_MS,
    /** Ключ экрана для шифрованного входа; null — только открытый HTTP (проверки, этот же телефон по кабелю). */
    private val tls: TlsIdentity? = null,
    private val tlsPort: Int = DEFAULT_TLS_PORT,
) {

    private var server: EmbeddedServer<*, *>? = null
    private var gateway: TlsGateway? = null
    /** Внутренний вход только для шифрованного: запрос на нём пришёл через TLS. 0 — шифрования нет. */
    @Volatile private var internalPort = 0
    private var cleaner: kotlinx.coroutines.Job? = null

    /** Запускает сервер и возвращает фактический порт (при port = 0 система выбирает свободный). */
    suspend fun start(): Int {
        val s = embeddedServer(CIO, configure = {
            connector { this.port = this@ReceiverServer.port; this.host = this@ReceiverServer.host }
            if (tls != null) connector { this.port = 0; this.host = "127.0.0.1" }
        }) {
            install(io.ktor.server.plugins.partialcontent.PartialContent)
            install(io.ktor.server.plugins.autohead.AutoHeadResponse)
            install(WebSockets) {
                pingPeriodMillis = 1_000
                timeoutMillis = 5_000
                maxFrameSize = 8L * 1024 * 1024
            }
            routing {
                get("/info") {
                    call.respondText(DeixisJson.encodeToString(ReceiverInfo.serializer(), core.publicInfo()), ContentType.Application.Json)
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
                webSocket("/audio") { handleAudio(this) }
                // Поток экрана для своего же проигрывателя (VLC на компьютере): тот же MP4, что у зрителей,
                // с метками времени — без них VLC играл бы с угаданной частотой кадров и копил отставание.
                get("/stream/mp4") {
                    if (call.request.local.remoteAddress !in LOOPBACK || call.secure()) return@get call.respondText("", status = HttpStatusCode.Forbidden)
                    call.response.header("Cache-Control", "no-store")
                    call.respondBytesWriter(ContentType("video", "mp4")) {
                        fmp4Stream(core, onCodec = {}, onData = { writeFully(it); flush() }, endOnNewConfig = true)
                    }
                }
                viewerRoutes(core)
                remoteRoutes(core) { authorized() }
                outputsRoutes(core) { authorized() }
                updateRoutes(core) { authorized() }
            }
        }
        s.start(wait = false)
        server = s
        cleaner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            while (true) {
                runCatching { core.files.purgeOlderThan(fileTtlMs, core.onScreen()) }
                runCatching { core.uploads.purgeOlderThan(fileTtlMs) }
                kotlinx.coroutines.delay(CLEAN_EVERY_MS)
            }
        }
        val connectors = s.engine.resolvedConnectors()
        tls?.let { id -> startTls(id, connectors.getOrNull(1)?.port ?: 0) }
        return connectors.first().port
    }

    /**
     * Шифрованный вход на [tlsPort]; занят (два приложения Deixis на одном устройстве) — любой свободный порт:
     * он всё равно есть в QR, /info и mDNS. Не запустился вовсе — приёмник работает по HTTP, как раньше.
     */
    private fun startTls(id: TlsIdentity, inner: Int) {
        if (inner == 0) return
        internalPort = inner
        for (p in listOf(tlsPort, 0)) {
            val g = TlsGateway(id, host, p, { inner })
            val actual = runCatching { g.start() }.getOrNull() ?: continue
            gateway = g
            core.tls = TlsEndpoint(actual, id.fingerprint)
            return
        }
        internalPort = 0
    }

    /** Адрес пульта: для шифрованных запросов — настоящий, со шлюза, а не 127.0.0.1. */
    private fun ApplicationCall.clientAddress(): String =
        if (secure()) gateway?.origins?.get(request.local.remotePort) ?: "tls" else request.local.remoteAddress

    /** Адрес потока экрана для проигрывателя на этом же устройстве. */
    fun localStreamUrl(port: Int) = "http://127.0.0.1:$port/stream/mp4"

    fun stop() {
        cleaner?.cancel()
        cleaner = null
        // Открытые сессии пультов закрываются сразу: пульт видит обрыв, а не ждёт, пока сервер дождётся их.
        sessions.forEach { it.cancel() }
        sessions.clear()
        gateway?.stop()
        gateway = null
        internalPort = 0
        core.tls = null
        server?.stop(500, 2_000)
        server = null
    }

    private val sessions = java.util.concurrent.ConcurrentHashMap.newKeySet<kotlinx.coroutines.Job>()

    private suspend fun handleSession(ws: io.ktor.server.websocket.DefaultWebSocketServerSession) = with(ws) {
        val job = coroutineContext[kotlinx.coroutines.Job]
        // До входа по токену большие кадры не нужны: чужое соединение не займёт 8 МБ памяти.
        maxFrameSize = PRE_AUTH_FRAME_BYTES
        job?.let(sessions::add)
        try { session(ws) } finally { job?.let(sessions::remove) }
    }

    private suspend fun session(ws: io.ktor.server.websocket.DefaultWebSocketServerSession) = with(ws) {
        // Первое сообщение — hello с токеном или pair с PIN. Без него сессия закрывается.
        val first = withTimeoutOrNull(10_000) {
            (incoming.receiveCatching().getOrNull() as? Frame.Text)?.readText()?.let(::decodeMsg)
        }
        val ok = when (first) {
            is Hello -> when {
                first.version != PROTOCOL_VERSION -> deny(DenyReason.INCOMPATIBLE_VERSION)
                !core.pairing.isValid(first.token, call.secure()) -> deny(DenyReason.UNKNOWN_TOKEN)
                else -> true
            }
            is PairRequest -> when {
                first.version != PROTOCOL_VERSION -> deny(DenyReason.INCOMPATIBLE_VERSION)
                else -> when (val r = core.pairing.pair(first.pin, first.sender, call.clientAddress(), call.secure())) {
                    is Pairing.Result.Ok -> { send(Frame.Text(Paired(r.token).encode())); true }
                    is Pairing.Result.Denied -> deny(r.reason)
                }
            }
            else -> false
        }
        if (!ok) return@with close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "denied"))
        maxFrameSize = SESSION_FRAME_BYTES

        // Подписка — до приветствия, и состояние в приветствии — то же, что первое в подписке. Иначе
        // команда, пришедшая сразу после входа (переподключение шлёт слайд мгновенно), меняла экран
        // раньше, чем начиналась подписка, и пульт так и не узнавал об этом.
        var welcomeState: ReceiverState? = null
        val welcomed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val updates = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            core.state.collect { s ->
                if (welcomeState == null) { welcomeState = s; return@collect }
                welcomed.await()
                send(Frame.Text(Status(s).encode()))
            }
        }
        send(Frame.Text(Welcome(PROTOCOL_VERSION, core.publicInfo(), welcomeState ?: core.state.value, core.viewKey).encode()))
        welcomed.complete(Unit)
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

    private val audioSource = java.util.concurrent.atomic.AtomicInteger()

    /** Звук трансляции: двоичные куски PCM. Новый источник вытесняет прежний. */
    private suspend fun handleAudio(ws: io.ktor.server.websocket.DefaultWebSocketServerSession) = with(ws) {
        if (!call.authorized()) return@with close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "denied"))
        val me = audioSource.incrementAndGet()
        core.audio.start()
        try {
            for (frame in incoming) {
                if (audioSource.get() != me) break
                if (frame !is Frame.Binary) continue
                // Кусок длиннее положенного (больше 40 мс) не играется: он задержал бы звук в зале.
                AudioChunk.decode(frame.readBytes())?.takeIf { it.pcm.size <= AudioFormat.MAX_CHUNK_BYTES }?.let(core.audio::publish)
            }
        } finally {
            if (audioSource.get() == me) core.audio.stop()
        }
    }

    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.deny(reason: DenyReason): Boolean {
        send(Frame.Text(Denied(reason).encode()))
        return false
    }

    private fun ApplicationCall.authorized(): Boolean =
        core.pairing.isValid(request.header("Authorization")?.removePrefix("Bearer ")?.trim(), secure())

    /** Запрос пришёл через шифрованный вход (TLS на этом же устройстве передаёт его на внутренний порт). */
    private fun ApplicationCall.secure(): Boolean = internalPort != 0 && request.local.localPort == internalPort

    private suspend fun ApplicationCall.respondJson(status: FileStatus) =
        respondText(DeixisJson.encodeToString(FileStatus.serializer(), status), ContentType.Application.Json)

    companion object {
        const val DEFAULT_PORT = 47820
        /** Шифрованный вход пультов. */
        const val DEFAULT_TLS_PORT = 47821
        private const val PRE_AUTH_FRAME_BYTES = 64L * 1024
        private const val SESSION_FRAME_BYTES = 8L * 1024 * 1024
        const val HEADER_SIZE = "Deixis-Size"
        const val FILE_TTL_MS = 24L * 60 * 60 * 1000
        private val LOOPBACK = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost")
        /** Приёмник просит у источника ключевой кадр: подключился новый зритель. */
        const val STREAM_KEY_REQUEST = """{"key":1}"""
        private const val CLEAN_EVERY_MS = 60L * 60 * 1000
    }
}
