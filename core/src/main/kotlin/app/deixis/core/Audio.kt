package app.deixis.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/*
 * Звук трансляции экрана. Без сжатия: 48 кГц, стерео, 16 бит, кусками по 10 мс (~1,5 Мбит/с — по Wi-Fi это
 * меньше четверти видеопотока). Сжатие сэкономило бы канал, но добавило бы 20–26 мс; здесь задержки на сжатие нет.
 *
 * Звук идёт своим соединением (/audio), отдельно от картинки: старый приёмник его просто не принимает,
 * а картинка у него не страдает. Ничего не копится: у телефона очередь в несколько кусков, у приёмника —
 * запас ~40 мс против неровного Wi-Fi, а всё, что накопилось сверх [AudioFormat.MAX_BUFFER_MS], выбрасывается.
 * Так задержка не растёт, даже когда часы телефона и компьютера идут чуть по-разному.
 */
object AudioFormat {
    const val RATE = 48_000
    const val CHANNELS = 2
    const val BYTES_PER_FRAME = 4
    /** Длина куска: 10 мс. */
    const val CHUNK_FRAMES = RATE / 100
    const val CHUNK_BYTES = CHUNK_FRAMES * BYTES_PER_FRAME
    /** Запас у приёмника перед началом и после обрыва: сглаживает неровный Wi-Fi. */
    const val START_BUFFER_MS = 40
    /** Больше этого в запасе — лишнее выбрасывается: звук не отстаёт от картинки. */
    const val MAX_BUFFER_MS = 100
    /** Самый длинный кусок, который приёмник играет (40 мс); длиннее — отбрасывается, иначе звук отстал бы. */
    const val MAX_CHUNK_BYTES = CHUNK_BYTES * 4

    fun ms(bytes: Int): Int = bytes * 1000 / (RATE * BYTES_PER_FRAME)
    fun bytes(ms: Int): Int = ms * RATE / 1000 * BYTES_PER_FRAME
}

/** Кусок звука: метка времени (мкс, часы телефона) и PCM 16 бит little-endian, каналы чередуются. */
class AudioChunk(val ptsUs: Long, val pcm: ByteArray) {
    fun encode(): ByteArray = ByteBuffer.allocate(8 + pcm.size).putLong(ptsUs).put(pcm).array()

    companion object {
        fun decode(bytes: ByteArray): AudioChunk? {
            if (bytes.size < 8 || (bytes.size - 8) % AudioFormat.BYTES_PER_FRAME != 0) return null
            if (bytes.size - 8 > AudioFormat.MAX_CHUNK_BYTES) return null
            val b = ByteBuffer.wrap(bytes)
            return AudioChunk(b.long, bytes.copyOfRange(8, bytes.size))
        }
    }
}

/**
 * Раздача звука на приёмнике: подписчики (колонки компьютера, ТВ) независимы; опоздавший подписчик
 * теряет старые куски, а не копит задержку.
 */
class AudioHub {
    private val _active = MutableStateFlow(false)
    /** Идёт ли сейчас звук от телефона. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val chunks = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 20, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun start() { _active.value = true }
    fun stop() { _active.value = false }
    fun publish(c: AudioChunk) { chunks.tryEmit(c) }
    fun subscribe(): Flow<AudioChunk> = chunks.asSharedFlow()
}

/**
 * Решает, что делать с очередным куском, по тому, сколько звука уже ждёт в выводе: после опустошения сначала
 * подложить тишину до запаса [AudioFormat.START_BUFFER_MS], при переполнении сверх [AudioFormat.MAX_BUFFER_MS] —
 * выбросить кусок. Общая для компьютера и Android, проверяется тестом.
 */
object AudioPacer {
    enum class Step { PLAY, PAD_THEN_PLAY, DROP }

    fun step(bufferedMs: Int): Step = when {
        bufferedMs <= 0 -> Step.PAD_THEN_PLAY
        bufferedMs > AudioFormat.MAX_BUFFER_MS -> Step.DROP
        else -> Step.PLAY
    }

    /** Тишина, которую подложить после опустошения. */
    fun padding(): ByteArray = ByteArray(AudioFormat.bytes(AudioFormat.START_BUFFER_MS))
}

/**
 * Отправка звука на приёмник. Не блокирует захват: если сеть не успевает, старые куски выбрасываются.
 * Приёмник, который звука не знает (старая версия), отказывает — тогда после нескольких попыток звук молча
 * не отправляется, а картинка идёт как обычно.
 */
class AudioSender internal constructor(
    scope: CoroutineScope,
    private val open: suspend (suspend (io.ktor.client.plugins.websocket.DefaultClientWebSocketSession) -> Unit) -> Unit,
) {
    private val queue = Channel<AudioChunk>(QUEUE_CHUNKS, BufferOverflow.DROP_OLDEST)
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    /** Приёмник звука не принимает (старая версия): нужна новая версия Deixis на экране. */
    @Volatile var unsupported = false
        private set

    private val job = scope.launch(Dispatchers.IO) {
        var refused = 0
        var pause = RETRY_MS
        while (isActive) {
            try {
                open { ws ->
                    refused = 0
                    _connected.value = true
                    while (queue.tryReceive().isSuccess) Unit // старое не нужно: звук должен идти с картинкой
                    for (c in queue) {
                        ws.send(Frame.Binary(true, c.encode()))
                        pause = RETRY_MS // пауза сбрасывается только у живой связи, а не у сразу закрытой
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: io.ktor.client.plugins.websocket.WebSocketException) {
                // Приёмник ответил, но /audio не знает (старая версия): после нескольких таких ответов — без звука.
                // Обрыв Wi-Fi или перезапуск приёмника сюда не попадает: тогда звук возвращается вместе с картинкой.
                if (++refused >= GIVE_UP_AFTER) { _connected.value = false; unsupported = true; return@launch }
            } catch (_: Exception) {
            }
            _connected.value = false
            delay(pause)
            pause = (pause * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    fun send(ptsUs: Long, pcm: ByteArray) {
        if (!unsupported) queue.trySend(AudioChunk(ptsUs, pcm))
    }

    fun close() {
        job.cancel()
        queue.close()
        _connected.value = false
    }

    private companion object {
        /** 50 мс: больше — уже отставание, меньше — щелчки на неровной сети. */
        const val QUEUE_CHUNKS = 5
        const val GIVE_UP_AFTER = 3
        const val RETRY_MS = 500L
        const val MAX_RETRY_MS = 5_000L
    }
}

internal fun audioSender(scope: CoroutineScope, http: HttpClient, host: String, port: Int, token: String) =
    AudioSender(scope) { session ->
        http.webSocket(method = HttpMethod.Get, host = host, port = port, path = "/audio", request = {
            header("Authorization", "Bearer $token")
        }) { session(this) }
    }
