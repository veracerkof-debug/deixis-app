package app.deixis.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import kotlinx.coroutines.withTimeoutOrNull
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
    tlsPort: Int = 0,
    fingerprint: String? = null,
) {
    /**
     * Шифрованный вход экрана и отпечаток его сертификата (TlsIdentity). Известен — вся связь идёт по TLS
     * с проверкой отпечатка и на открытый HTTP уже не откатывается. Неизвестен (старое сопряжение) — пульт
     * узнаёт его у экрана и запоминает ([onPinned]); дальше — только по TLS.
     */
    @Volatile var tlsPort: Int = tlsPort
        private set
    @Volatile var fingerprint: String? = fingerprint?.takeIf { it.isNotEmpty() }
        private set

    /** Отпечаток экрана узнан впервые: пульт запоминает его рядом с токеном. */
    @Volatile var onPinned: (tlsPort: Int, fingerprint: String) -> Unit = { _, _ -> }

    private val secure: Boolean get() = fingerprint != null && tlsPort > 0
    private fun base() = if (secure) "https://$host:$tlsPort" else "http://$host:$port"
    private fun wsBase() = if (secure) "wss://$host:$tlsPort" else "ws://$host:$port"

    @Volatile private var http = client(this.fingerprint)

    private fun client(fp: String?) = HttpClient(CIO) {
        install(WebSockets)
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 30_000
        }
        if (fp != null) engine { https { trustManager = PinnedTrustManager(fp) } }
    }

    /** Запомненный ранее отпечаток экрана: связь сразу по шифрованному каналу (до [pair] или [connect]). */
    fun trust(tlsPort: Int, fingerprint: String) {
        if (secure || tlsPort <= 0 || fingerprint.length != FINGERPRINT_LENGTH) return
        this.tlsPort = tlsPort
        this.fingerprint = fingerprint
        val old = http
        http = client(fingerprint)
        old.close()
    }

    /**
     * Экран сообщил свой шифрованный вход: связь переходит на него. Отпечаток, раз запомненный, не меняется:
     * другой сертификат по тому же адресу — это другое устройство.
     */
    private fun adopt(info: ReceiverInfo) {
        if (secure || info.tlsPort <= 0 || info.fingerprint.length != FINGERPRINT_LENGTH) return
        tlsPort = info.tlsPort
        fingerprint = info.fingerprint
        val old = http
        http = client(info.fingerprint)
        scope.launch { delay(OLD_CLIENT_GRACE_MS); old.close() }
        onPinned(info.tlsPort, info.fingerprint)
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

    /**
     * Этот пульт подтверждает подключения к экрану: после входа экран шлёт ему, кто ждёт «Разрешить»
     * и кто на связи ([peers]). Пересылка показа между экранами этого не включает.
     */
    @Volatile var approver: Boolean = false

    private val _peers = MutableStateFlow<PeersState?>(null)
    /** Кто подключён к экрану и кто ждёт подтверждения; null — экран не прислал (нет связи, старая версия). */
    val peers: StateFlow<PeersState?> = _peers.asStateFlow()

    /** Пустить или нет устройство, которое ждёт подтверждения. */
    fun answer(id: String, allow: Boolean) = send(ConnectAnswer(id, allow))

    /** Отключить пульт и забыть его: вернуться он сможет только по PIN и с подтверждением. */
    fun kick(id: String) = send(Kick(id))

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
    /** [onAwaiting] — PIN верный, экран ждёт «Разрешить» у своего оператора (до минуты). */
    suspend fun pair(pin: String, onAwaiting: () -> Unit = {}): String {
        // Шифрованный вход экрана — до PIN: тогда PIN и токен не видны в сети.
        if (!secure) runCatching { info() }.getOrNull()?.let(::adopt)
        var result: Result<String>? = null
        try {
            pairSession(pin, onAwaiting) { result = it }
        } catch (e: Exception) {
            if (e.isUntrusted()) throw PairingException(DenyReason.UNTRUSTED_SCREEN)
            throw e
        }
        val t = result!!.getOrThrow()
        token = t
        return t
    }

    private suspend fun pairSession(pin: String, onAwaiting: () -> Unit, done: (Result<String>) -> Unit) {
        http.webSocket(urlString = "${wsBase()}/ws") {
            send(Frame.Text(PairRequest(PROTOCOL_VERSION, senderName, pin).encode()))
            suspend fun next(ms: Long) = withTimeoutOrNull(ms) { (incoming.receiveCatching().getOrNull() as? Frame.Text)?.readText()?.let(::decodeMsg) }
            // Без ответа за 10 с — обычная ошибка связи, а не отмена (иначе её проглотил бы обработчик отмены выше).
            var reply = next(10_000)
            // Экран ждёт решения оператора: ответ придёт, когда тот нажмёт «Разрешить» или «Отклонить».
            if (reply is AwaitingApproval) { onAwaiting(); reply = next(reply.timeoutMs + 10_000) }
            done(when (reply) {
                is Paired -> Result.success(reply.token)
                is Denied -> Result.failure(PairingException(reply.reason))
                else -> Result.failure(IOException("Неожиданный ответ приёмника"))
            })
        }
    }

    /** Подключается по токену и держит связь, пока не вызван [close]. */
    fun connect(token: String) {
        this.token = token
        loop?.cancel()
        loop = scope.launch {
            var pause = 500L
            while (isActive) {
                // Своя очередь на каждую сессию: команды, застрявшие в оборванной, не приходят потом
                // в новую — в зал не возвращается слайд, который давно пролистали.
                outbox.close()
                outbox = Channel(Channel.BUFFERED)
                _connection.value = Connection.Connecting
                try {
                    // Старое сопряжение без шифрования: узнать у экрана его шифрованный вход и перейти на него.
                    if (!secure) runCatching { info() }.getOrNull()?.let(::adopt)
                    http.webSocket(urlString = "${wsBase()}/ws") {
                        if (runSession(token)) pause = 500L
                    }
                    if (_connection.value is Connection.Denied) return@launch
                    _connection.value = Connection.Lost("Связь закрыта")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.isUntrusted()) {
                        // По адресу экрана — другой сертификат: токен туда не отправляется, связь не повторяется.
                        _connection.value = Connection.Denied(DenyReason.UNTRUSTED_SCREEN)
                        return@launch
                    }
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
        // withTimeoutOrNull: истёкшее ожидание — обрыв связи, после него цикл подключается заново. withTimeout бросил бы
        // отмену, и цикл переподключения молча закончился бы навсегда.
        when (val first = withTimeoutOrNull(10_000) { (incoming.receiveCatching().getOrNull() as? Frame.Text)?.readText()?.let(::decodeMsg) }) {
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
        val box = outbox
        val writer = launch {
            for (msg in box) send(Frame.Text(msg.encode()))
        }
        if (approver) send(Frame.Text(ApproverReady().encode()))
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                when (val msg = decodeMsg(frame.readText())) {
                    is Status -> _state.value = msg.state
                    is Pong -> lastPong = System.currentTimeMillis()
                    is ErrorMsg -> _errors.tryEmit(msg)
                    is Peers -> _peers.value = msg.state
                    else -> Unit
                }
            }
        } finally {
            pinger.cancel()
            writer.cancel()
            _peers.value = null
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

    fun show(content: Content, next: Content? = null) = send(Show(content, next))
    fun mode(mode: Mode, logo: LogoStyle? = null, logoImage: FileRef? = null) = send(SetMode(mode, logo, logoImage))
    fun video(command: VideoCommand) = send(command)

    /**
     * Передаёт файл на приёмник. Если часть уже есть, докачивает с того места.
     * Сетевые сбои повторяет до [attempts] раз.
     */
    suspend fun upload(
        source: UploadSource,
        sha256: String? = null,
        /** Сколько неудач подряд без продвижения терпится; докачка, которая сдвинулась, сбрасывает счёт. */
        attempts: Int = 6,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): FileRef {
        val t = token ?: error("Нет сопряжения")
        val sha = sha256 ?: withContext(Dispatchers.IO) { source.open().use(::sha256Of) }
        val ref = FileRef(sha, source.name, source.size, source.mime)
        var lastError: Exception? = null
        var failures = 0
        var reached = -1L
        // Большой файл по Wi-Fi рвётся: передача продолжается с места обрыва, пока она движется.
        while (failures < attempts) {
            try {
                val status = fileStatus(sha, t)
                onProgress(status.bytes, source.size)
                if (status.complete) return ref
                if (status.bytes > reached) { reached = status.bytes; failures = 0 }
                val response = http.put("${base()}/files/$sha?offset=${status.bytes}") {
                    header("Authorization", "Bearer $t")
                    header(ReceiverServer.HEADER_SIZE, source.size.toString())
                    setBody(StreamBody(source, status.bytes, onProgress))
                }
                if (response.status == HttpStatusCode.OK) {
                    val after = DeixisJson.decodeFromString(FileStatus.serializer(), response.bodyAsText())
                    if (after.complete) return ref
                } else if (response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.InsufficientStorage) {
                    throw UploadRejected(response.bodyAsText())
                }
                // 409: экран ещё дописывает прежний кусок или ждёт другого смещения — подождать и спросить снова.
            } catch (e: CancellationException) {
                throw e
            } catch (e: UploadRejected) {
                throw IOException(e.message, e)
            } catch (e: Exception) {
                lastError = e
            }
            failures++
            delay(minOf(UPLOAD_RETRY_MS shl failures.coerceAtMost(4), UPLOAD_RETRY_MAX_MS))
        }
        throw IOException(uploadError(source.name, lastError), lastError)
    }

    private class UploadRejected(message: String) : Exception(message)

    /**
     * Просит приёмник взять файл из Holyrics на его компьютере (фото, видео, фон темы).
     * Работает, если приёмник — Deixis для компьютера на том же ПК, что и Holyrics ([ReceiverInfo.holyrics]).
     */
    suspend fun importFromHolyrics(req: HolyricsMediaRequest): FileRef {
        val t = token ?: error("Нет сопряжения")
        val r = http.post("${base()}/holyrics/media") {
            header("Authorization", "Bearer $t")
            setBody(DeixisJson.encodeToString(HolyricsMediaRequest.serializer(), req))
        }
        if (r.status != HttpStatusCode.OK) throw IOException(r.bodyAsText().ifBlank { "Приёмник не взял файл из Holyrics" })
        return DeixisJson.decodeFromString(FileRef.serializer(), r.bodyAsText())
    }

    /** Просит приёмник перевести переданную презентацию в PDF ([ReceiverInfo.convert]). */
    suspend fun convert(ref: FileRef): Converted {
        val t = token ?: error("Нет сопряжения")
        val r = http.post("${base()}/convert") {
            header("Authorization", "Bearer $t")
            setBody(DeixisJson.encodeToString(FileRef.serializer(), ref))
        }
        if (r.status != HttpStatusCode.OK) throw IOException(r.bodyAsText().ifBlank { "Приёмник не перевёл презентацию" })
        return DeixisJson.decodeFromString(Converted.serializer(), r.bodyAsText())
    }

    /**
     * Кто этот приёмник: постоянный id, настоящее имя, платформа (без сопряжения). По id один и тот же экран,
     * введённый по IP и найденный в сети, остаётся одним устройством.
     */
    suspend fun info(): ReceiverInfo {
        val r = http.get("${base()}/info")
        if (r.status != HttpStatusCode.OK) throw IOException("Приёмник ответил ${r.status}")
        return DeixisJson.decodeFromString(ReceiverInfo.serializer(), r.bodyAsText())
    }

    /**
     * Обновления приёмника: без [req] — состояние, с ним — действие («check», «install») или настройки.
     * null — приёмник не обновляется с пульта (старая версия или телефон со своими настройками).
     */
    suspend fun update(req: UpdateRequest? = null): UpdateState? {
        val t = token ?: return null
        val url = "${base()}/api/update"
        val r = if (req == null) http.get(url) { header("Authorization", "Bearer $t") }
        else http.post(url) {
            header("Authorization", "Bearer $t")
            setBody(DeixisJson.encodeToString(UpdateRequest.serializer(), req))
        }
        if (r.status != HttpStatusCode.OK) return null
        return DeixisJson.decodeFromString(UpdateState.serializer(), r.bodyAsText())
    }

    /** Приём AirPlay на экране: без [enabled] — состояние, с ним — включить или выключить. null — экран без AirPlay. */
    suspend fun airPlay(enabled: Boolean? = null): Boolean? {
        val t = token ?: return null
        val url = "${base()}/api/airplay"
        val r = if (enabled == null) http.get(url) { header("Authorization", "Bearer $t") }
        else http.post(url) {
            header("Authorization", "Bearer $t")
            setBody(DeixisJson.encodeToString(app.deixis.core.airplay.AirPlayState.serializer(), app.deixis.core.airplay.AirPlayState(enabled)))
        }
        if (r.status != HttpStatusCode.OK) return null
        return DeixisJson.decodeFromString(app.deixis.core.airplay.AirPlayState.serializer(), r.bodyAsText()).enabled
    }

    /**
     * Трансляция экрана компьютера: без [select] — список источников, с ним — начать источник (id) или
     * остановить (пустая строка). null — экран не компьютер или старая версия.
     */
    suspend fun capture(select: String? = null): CaptureState? {
        val t = token ?: return null
        val url = "${base()}/api/capture"
        val r = if (select == null) http.get(url) { header("Authorization", "Bearer $t") }
        else http.post(url) {
            header("Authorization", "Bearer $t")
            setBody(DeixisJson.encodeToString(CaptureRequest.serializer(), CaptureRequest(select.ifEmpty { null })))
        }
        if (r.status != HttpStatusCode.OK) return null
        return DeixisJson.decodeFromString(CaptureState.serializer(), r.bodyAsText())
    }

    /**
     * Мониторы компьютера (выходы): без [set] — список, с ним — включить монитор или сменить раскладку.
     * null — экран не компьютер или старая версия.
     */
    suspend fun outputs(set: MonitorRequest? = null): OutputsState? {
        val t = token ?: return null
        val r = if (set == null) http.get("${base()}/api/outputs") { header("Authorization", "Bearer $t") }
        else http.post("${base()}/api/outputs/monitor") {
            header("Authorization", "Bearer $t")
            setBody(DeixisJson.encodeToString(MonitorRequest.serializer(), set))
        }
        if (r.status != HttpStatusCode.OK) return null
        return DeixisJson.decodeFromString(OutputsState.serializer(), r.bodyAsText())
    }

    /** Диагностика экрана на языке [lang]; null — старая версия экрана. */
    suspend fun diagnostics(lang: String?): app.deixis.diag.DiagReport? {
        val t = token ?: return null
        val r = http.get("${base()}/api/diagnostics?lang=${lang.orEmpty()}") { header("Authorization", "Bearer $t") }
        if (r.status != HttpStatusCode.OK) return null
        return DeixisJson.decodeFromString(app.deixis.diag.DiagReport.serializer(), r.bodyAsText())
    }

    /** Журнал экрана целиком (для отправки автору); null — старая версия экрана. */
    suspend fun screenLog(): String? {
        val t = token ?: return null
        val r = http.get("${base()}/api/diagnostics/log") { header("Authorization", "Bearer $t") }
        return if (r.status == HttpStatusCode.OK) r.bodyAsText() else null
    }

    /** Сколько файла уже на экране: целиком, частью (передача оборвалась) или нисколько. */
    suspend fun fileStatus(sha: String): FileStatus = fileStatus(sha, token ?: error("Нет сопряжения"))

    private suspend fun fileStatus(sha: String, token: String): FileStatus {
        val r = http.get("${base()}/files/$sha") { header("Authorization", "Bearer $token") }
        if (r.status != HttpStatusCode.OK) throw IOException("Приёмник ответил ${r.status}")
        return DeixisJson.decodeFromString(FileStatus.serializer(), r.bodyAsText())
    }

    /**
     * Открывает демонстрацию экрана на приёмнике. [onKeyRequest] вызывается, когда нужен ключевой кадр:
     * подключился зритель, оборвалась связь или кадры пришлось выбросить.
     */
    fun openStream(onKeyRequest: () -> Unit = {}): ScreenStream {
        val t = token ?: error("Нет сопряжения")
        return ScreenStream(scope, onKeyRequest) { session ->
            http.webSocket(urlString = "${wsBase()}/stream", request = {
                header("Authorization", "Bearer $t")
            }) { session(this) }
        }
    }

    /** Звук трансляции экрана — своим соединением, рядом с картинкой ([openStream]). */
    fun openAudio(): AudioSender = audioSender(scope, http, "${wsBase()}/audio", token ?: error("Нет сопряжения"))

    private fun Throwable.isUntrusted(): Boolean = generateSequence(this) { it.cause }.take(10).any { it is UntrustedScreenException }

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

/**
 * Поток кадров экрана на приёмник. Кадры не копятся: если сеть не успевает, всё до следующего
 * ключевого кадра выбрасывается. Зал видит короткую остановку, но не отстаёт от телефона.
 */
class ScreenStream internal constructor(
    scope: CoroutineScope,
    /** Нужен ключевой кадр. Кодировщик подставляет сюда свой обработчик, когда создан. */
    @Volatile var onKeyRequest: () -> Unit,
    private val open: suspend (suspend (DefaultClientWebSocketSession) -> Unit) -> Unit,
) {
    private val queue = Channel<StreamFrame>(QUEUE_FRAMES)
    private val queued = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var config: StreamFrame? = null
    @Volatile private var dropping = true
    private var seq = 0L
    /** Последний кадр, о котором приёмник сообщил, что получил его. */
    @Volatile private var acked = -1L

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Сколько кадров выброшено из-за медленной сети: для подсказки «слабый Wi-Fi». */
    @Volatile var dropped = 0L
        private set

    private val job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            try {
                open { ws ->
                    synchronized(this@ScreenStream) { acked = seq - 1 }
                    config?.let { ws.send(Frame.Binary(true, it.encode())) }
                    _connected.value = true
                    val keys = launch {
                        for (f in ws.incoming) {
                            if (f !is Frame.Text) continue
                            val text = f.readText()
                            if (text.contains("\"key\"")) onKeyRequest()
                            ACK.find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { a -> if (a > acked) acked = a }
                        }
                    }
                    try {
                        for (f in queue) {
                            queued.decrementAndGet()
                            ws.send(Frame.Binary(true, f.encode()))
                        }
                    } finally {
                        keys.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            _connected.value = false
            // После обрыва приёмник начинает с ключевого кадра.
            dropping = true
            while (queue.tryReceive().isSuccess) queued.decrementAndGet()
            onKeyRequest()
            delay(500)
        }
    }

    /** Кадр от кодировщика (Annex-B). Не блокирует: кодировщик никогда не ждёт сеть. */
    @Synchronized
    fun send(key: Boolean, isConfig: Boolean, ptsUs: Long, data: ByteArray) {
        if (isConfig) {
            val f = StreamFrame(false, true, 0, ptsUs, data)
            config = f
            if (!queue.trySend(f).isSuccess) dropping = true else queued.incrementAndGet()
            return
        }
        if (dropping && !key) { dropped++; return }
        // В пути больше LAG_FRAMES кадров — сеть не успевает, даже если очередь пуста: данные осели в буферах.
        if (queued.get() >= LAG_FRAMES || seq - 1 - acked >= LAG_FRAMES || !queue.trySend(StreamFrame(key, false, seq++, ptsUs, data)).isSuccess) {
            dropping = true
            dropped++
            onKeyRequest()
            return
        }
        queued.incrementAndGet()
        dropping = false
    }

    fun close() {
        job.cancel()
        queue.close()
        _connected.value = false
    }

    private companion object {
        val ACK = Regex("\"ack\":(\\d+)")
        const val QUEUE_FRAMES = 64
        /** Больше этого в очереди — сеть не успевает (около трети секунды при 30 кадрах). */
        const val LAG_FRAMES = 10
    }
}

private const val UPLOAD_RETRY_MS = 250L
/** Длина отпечатка сертификата экрана (SHA-256, base64url). */
private const val FINGERPRINT_LENGTH = 43
/** Старый клиент закрывается не сразу: идущая передача файла успевает закончиться. */
private const val OLD_CLIENT_GRACE_MS = 30_000L
private const val UPLOAD_RETRY_MAX_MS = 5_000L

/** Понятная причина, без адресов и названий классов: её читает ведущий во время показа. */
internal fun uploadError(name: String, e: Exception?): String {
    val text = generateSequence(e as Throwable?) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
    val why = when {
        "timeout" in text || "timed out" in text || "connect" in text || "refused" in text || "unreachable" in text ->
            "экран не отвечает. Проверьте, что он включён и в той же сети; уже переданное не пропадёт"
        "busy" in text -> "экран ещё принимает этот файл"
        else -> "связь оборвалась; уже переданное не пропадёт"
    }
    return "«$name» не передан: $why"
}
