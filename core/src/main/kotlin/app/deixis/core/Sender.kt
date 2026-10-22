package app.deixis.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream

/** Файл для отправки: телефон отдаёт его из своего хранилища, компьютер — с диска. */
interface UploadSource {
    val name: String
    val size: Long
    val mime: String
    fun open(): InputStream
}

sealed interface Connection {
    data object Idle : Connection
    data object Connecting : Connection
    data class Connected(val receiver: ReceiverInfo) : Connection
    data class Lost(val reason: String) : Connection
    data class Denied(val reason: DenyReason) : Connection
}

class PairingException(val reason: DenyReason) : Exception("Сопряжение отклонено: $reason")

/**
 * Связь источника с одним приёмником. Держит управляющий канал, сама переподключается
 * с нарастающей паузой и передаёт файлы с докачкой.
 */
class SenderClient(
    val host: String,
    val port: Int,
    private val senderName: String,
    private val scope: CoroutineScope,
) {
    private val http = HttpClient(CIO) {
        install(WebSockets)
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 30_000
        }
    }

    private val _connection = MutableStateFlow<Connection>(Connection.Idle)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _state = MutableStateFlow<ReceiverState?>(null)
    /** Состояние приёмника, как он его прислал. */
    val state: StateFlow<ReceiverState?> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<ErrorMsg>(extraBufferCapacity = 16)
    val errors: SharedFlow<ErrorMsg> = _errors.asSharedFlow()

    @Volatile
    var token: String? = null
        private set

    private val _viewKey = MutableStateFlow<String?>(null)
    /** Ключ страницы для зрителей этого приёмника; null, пока нет связи или приёмник старой версии. */
    val viewKey: StateFlow<String?> = _viewKey.asStateFlow()

    @Volatile
    private var receiverId: String? = null

    /** Ссылка для зрителей по IP-адресу (её показывает QR-код на телефоне). */
    fun viewUrl(): String? = _viewKey.value?.let { "http://$host:$port/view?k=$it" }

    /** Та же ссылка по имени в локальной сети (deixis-ab12.local): работает, даже если у приёмника сменился IP. */
    fun viewLocalUrl(): String? {
        val key = _viewKey.value ?: return null
        val id = receiverId ?: return null
        return "http://${localHostName(id)}.local:$port/view?k=$key"
    }

    private var loop: Job? = null
    private var outbox = Channel<Msg>(Channel.BUFFERED)

    /** Первое подключение: PIN с экрана приёмника. Возвращает токен для следующих подключений. */
    suspend fun pair(pin: String): String {
        var result: Result<String>? = null
        http.webSocket(method = HttpMethod.Get, host = host, port = port, path = "/ws") {
            send(Frame.Text(PairRequest(PROTOCOL_VERSION, senderName, pin).encode()))
            val reply = withTimeout(10_000) { (incoming.receive() as? Frame.Text)?.readText()?.let(::decodeMsg) }
            result = when (reply) {
                is Paired -> Result.success(reply.token)
                is Denied -> Result.failure(PairingException(reply.reason))
                else -> Result.failure(IOException("Неожиданный ответ приёмника"))
            }
        }
        val t = result!!.getOrThrow()
        token = t
        return t
    }

    /** Подключается по токену и держит связь, пока не вызван [close]. */
    fun connect(token: String) {
        this.token = token
        loop?.cancel()
        outbox = Channel(Channel.BUFFERED)
        loop = scope.launch {
            var pause = 500L
            while (isActive) {
                _connection.value = Connection.Connecting
                try {
                    http.webSocket(method = HttpMethod.Get, host = host, port = port, path = "/ws") {
                        if (runSession(token)) pause = 500L
                    }
                    if (_connection.value is Connection.Denied) return@launch
                    _connection.value = Connection.Lost("Связь закрыта")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _connection.value = Connection.Lost(e.message ?: e::class.simpleName.orEmpty())
                }
                delay(pause)
                pause = (pause * 2).coerceAtMost(5_000)
            }
        }
    }

    /** Возвращает true, если сессия дошла до welcome. */
    private suspend fun DefaultClientWebSocketSession.runSession(token: String): Boolean = coroutineScope {
        send(Frame.Text(Hello(PROTOCOL_VERSION, senderName, token).encode()))
        when (val first = withTimeout(10_000) { (incoming.receive() as? Frame.Text)?.readText()?.let(::decodeMsg) }) {
            is Welcome -> {
                _state.value = first.state
                receiverId = first.receiver.id
                _viewKey.value = first.viewKey
                _connection.value = Connection.Connected(first.receiver)
            }
            is Denied -> {
                _connection.value = Connection.Denied(first.reason)
                return@coroutineScope false
            }
            else -> return@coroutineScope false
        }
        // Своя проверка связи: при пропаже Wi-Fi TCP молчит долго, а зал ждать не может.
        var lastPong = System.currentTimeMillis()
        val pinger = launch {
            while (isActive) {
                delay(1_000)
                // Исключение рушит сессию, и цикл в connect() переподключается.
                if (System.currentTimeMillis() - lastPong > 5_000) throw IOException("Нет ответа приёмника")
                send(Frame.Text(Ping(System.currentTimeMillis()).encode()))
            }
        }
        val writer = launch {
            for (msg in outbox) send(Frame.Text(msg.encode()))
        }
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                when (val msg = decodeMsg(frame.readText())) {
                    is Status -> _state.value = msg.state
                    is Pong -> lastPong = System.currentTimeMillis()
                    is ErrorMsg -> _errors.tryEmit(msg)
                    else -> Unit
                }
            }
        } finally {
            pinger.cancel()
            writer.cancel()
        }
        true
    }

    /**
     * Отправляет команду. Без связи команда не копится: устаревший слайд,
     * показанный через минуту после переподключения, хуже, чем ничего.
     */
    fun send(msg: Msg): Boolean {
        if (_connection.value !is Connection.Connected) return false
        return outbox.trySend(msg).isSuccess
    }

    fun show(content: Content) = send(Show(content))
    fun mode(mode: Mode) = send(SetMode(mode))
    fun video(command: VideoCommand) = send(command)

    /**
     * Передаёт файл на приёмник. Если часть уже есть, докачивает с того места.
     * Сетевые сбои повторяет до [attempts] раз.
     */
    suspend fun upload(
        source: UploadSource,
        sha256: String? = null,
        attempts: Int = 5,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): FileRef {
        val t = token ?: error("Нет сопряжения")
        val sha = sha256 ?: withContext(Dispatchers.IO) { source.open().use(::sha256Of) }
        val ref = FileRef(sha, source.name, source.size, source.mime)
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                val status = fileStatus(sha, t)
                onProgress(status.bytes, source.size)
                if (status.complete) return ref
                val response = http.put("http://$host:$port/files/$sha?offset=${status.bytes}") {
                    header("Authorization", "Bearer $t")
                    header(ReceiverServer.HEADER_SIZE, source.size.toString())
                    setBody(StreamBody(source, status.bytes, onProgress))
                }
                if (response.status == HttpStatusCode.OK) {
                    val after = DeixisJson.decodeFromString(FileStatus.serializer(), response.bodyAsText())
                    if (after.complete) return ref
                } else if (response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.InsufficientStorage) {
                    throw IOException(response.bodyAsText())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            delay(500L * (attempt + 1))
        }
        throw IOException("Не удалось передать «${source.name}»: ${lastError?.message}", lastError)
    }

    private suspend fun fileStatus(sha: String, token: String): FileStatus {
        val r = http.get("http://$host:$port/files/$sha") { header("Authorization", "Bearer $token") }
        if (r.status != HttpStatusCode.OK) throw IOException("Приёмник ответил ${r.status}")
        return DeixisJson.decodeFromString(FileStatus.serializer(), r.bodyAsText())
    }

    fun close() {
        loop?.cancel()
        loop = null
        outbox.close()
        _connection.value = Connection.Idle
        http.close()
    }

    private class StreamBody(
        private val source: UploadSource,
        private val offset: Long,
        private val onProgress: (Long, Long) -> Unit,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long = source.size - offset
        override val contentType: ContentType = ContentType.Application.OctetStream

        override suspend fun writeTo(channel: ByteWriteChannel) {
            withContext(Dispatchers.IO) {
                source.open().use { input ->
                    var skipped = 0L
                    while (skipped < offset) {
                        val n = input.skip(offset - skipped)
                        if (n <= 0) throw IOException("Не удалось перейти к позиции $offset")
                        skipped += n
                    }
                    val buf = ByteArray(64 * 1024)
                    var sent = offset
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        channel.writeFully(buf, 0, n)
                        sent += n
                        onProgress(sent, source.size)
                    }
                }
            }
        }
    }
}
