package app.deixis.receiver

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import app.deixis.core.NAL_PPS
import app.deixis.core.NAL_SPS
import app.deixis.core.StreamHub
import app.deixis.core.StreamSample
import app.deixis.core.avcFrameSize
import app.deixis.core.joinAnnexB
import app.deixis.core.nalType
import app.deixis.core.splitNalUnits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Демонстрация экрана на Android TV: аппаратный декодер H.264 рисует прямо на поверхность.
 * Кадр показывается, как только декодирован, без буфера. Декодер не успевает — кадры до ключевого
 * выбрасываются. При сбое декодер пересоздаётся со следующего ключевого кадра, а на экране
 * остаётся последний показанный кадр.
 */
class ScreenDecoder(private val hub: StreamHub, private val surface: Surface, private val onError: (String) -> Unit) {
    private var job: Job? = null
    private var codec: MediaCodec? = null
    private var codecConfig: ByteArray? = null
    private var drain: Thread? = null

    fun start(scope: CoroutineScope) {
        // Декодер трогает только эта корутина; освобождается он здесь же, при её завершении.
        job = scope.launch(Dispatchers.IO) {
            var waitingKey = true
            try {
                hub.subscribe().collect { s ->
                    if (!isActive) return@collect
                    if (waitingKey && !s.key) return@collect
                    val cfg = s.config
                    if (s.key && cfg != null && (codec == null || !cfg.contentEquals(codecConfig))) {
                        if (!open(cfg)) return@collect
                    }
                    val c = codec ?: return@collect
                    waitingKey = !feed(c, s)
                }
            } finally {
                release()
            }
        }
    }

    /** Возвращает false, если кадр не принят: тогда ждём ключевой. */
    private fun feed(c: MediaCodec, s: StreamSample): Boolean = try {
        val index = c.dequeueInputBuffer(INPUT_WAIT_US)
        if (index < 0) {
            false
        } else {
            val buf: ByteBuffer = c.getInputBuffer(index)!!
            buf.clear()
            if (buf.capacity() < s.data.size) {
                c.queueInputBuffer(index, 0, 0, s.ptsUs, 0)
                false
            } else {
                buf.put(s.data)
                c.queueInputBuffer(index, 0, s.data.size, s.ptsUs, if (s.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                true
            }
        }
    } catch (e: Exception) {
        release()
        false
    }

    private fun open(config: ByteArray): Boolean {
        release()
        val nals = splitNalUnits(config)
        val sps = nals.firstOrNull { nalType(it) == NAL_SPS } ?: return false
        val pps = nals.firstOrNull { nalType(it) == NAL_PPS } ?: return false
        val (w, h) = avcFrameSize(sps) ?: (1920 to 1080)
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(joinAnnexB(listOf(sps))))
                setByteBuffer("csd-1", ByteBuffer.wrap(joinAnnexB(listOf(pps))))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, w * h)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // реальное время
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, surface, null, 0)
            c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            c.start()
            codec = c
            codecConfig = config
            drain = Thread({ drainLoop(c) }, "deixis-screen").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            onError("Телевизор не смог открыть трансляцию экрана ${w}×$h")
            release()
            false
        }
    }

    /** Готовые кадры сразу на экран: без ожидания по меткам времени. */
    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (codec === c) {
            try {
                val index = c.dequeueOutputBuffer(info, OUTPUT_WAIT_US)
                if (index >= 0) c.releaseOutputBuffer(index, true)
            } catch (e: Exception) {
                return
            }
        }
    }

    private fun release() {
        val c = codec ?: return
        codec = null
        codecConfig = null
        drain?.join(500)
        drain = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    /** Ждёт, пока декодер освобождён: поверхность после этого можно уничтожать. */
    fun stop() {
        val j = job ?: return
        job = null
        runBlocking { j.cancelAndJoin() }
    }

    private companion object {
        const val INPUT_WAIT_US = 20_000L
        const val OUTPUT_WAIT_US = 10_000L
    }
}
