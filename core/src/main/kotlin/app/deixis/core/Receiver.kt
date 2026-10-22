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
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
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

/**
 * Логика приёмника без экрана: что показано, какие файлы есть, кто сопряжён.
 * Платформа (Deixis TV, компьютер) рисует [state] и исполняет [videoCommands].
 */
class ReceiverCore(
    val info: ReceiverInfo,
    val files: FileStore,
    val pairing: Pairing,
) {
    private val _state = MutableStateFlow(ReceiverState())
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    private val _videoCommands = MutableSharedFlow<VideoCommand>(extraBufferCapacity = 16)
    val videoCommands: SharedFlow<VideoCommand> = _videoCommands.asSharedFlow()

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
        val ref = content.file
        if (ref != null) {
            if (files.file(ref.sha256) == null) return ErrorMsg("missing_file", "На приёмнике нет файла «${ref.name}»")
            files.touch(ref.sha256)
        }
        _state.update { ReceiverState(content = content, mode = Mode.NORMAL, video = null) }
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

/** Сетевой сервер приёмника: WebSocket для команд и HTTP для файлов. */
class ReceiverServer(private val core: ReceiverCore, private val port: Int = DEFAULT_PORT) {

    private var server: EmbeddedServer<*, *>? = null

    /** Запускает сервер и возвращает фактический порт (при port = 0 система выбирает свободный). */
    suspend fun start(): Int {
        val s = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                pingPeriodMillis = 1_000
                timeoutMillis = 5_000
                maxFrameSize = 1L * 1024 * 1024
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
                webSocket("/ws") { handleSession(this) }
            }
        }
        s.start(wait = false)
        server = s
        return s.engine.resolvedConnectors().first().port
    }

    fun stop() {
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

        send(Frame.Text(Welcome(PROTOCOL_VERSION, core.info, core.state.value).encode()))
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
    }
}
