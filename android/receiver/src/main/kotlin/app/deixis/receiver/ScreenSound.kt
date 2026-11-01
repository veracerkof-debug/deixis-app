package app.deixis.receiver

import android.media.AudioAttributes
import android.media.AudioTrack
import app.deixis.core.AudioChunk
import app.deixis.core.AudioFormat
import app.deixis.core.AudioPacer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import android.media.AudioFormat as AFormat

/**
 * Звук трансляции экрана на ТВ и в «Стать экраном»: AudioTrack в режиме малой задержки. Запас ~40 мс,
 * лишнее сверх 100 мс выбрасывается (как на компьютере, правило общее — [AudioPacer]). Нет звука на устройстве —
 * трансляция идёт без него, картинку это не трогает. Играет, пока вызывающая корутина жива.
 */
suspend fun playScreenSound(chunks: Flow<AudioChunk>) = withContext(Dispatchers.IO) {
    val track = runCatching { openTrack() }.getOrNull() ?: return@withContext
    var written = 0L
    try {
        track.play()
        chunks.collect { c ->
            val played = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            val bufferedMs = ((written - played).coerceAtLeast(0) * 1000 / AudioFormat.RATE).toInt()
            fun put(b: ByteArray) {
                val n = track.write(b, 0, b.size, AudioTrack.WRITE_NON_BLOCKING)
                if (n > 0) written += n / AudioFormat.BYTES_PER_FRAME
            }
            when (AudioPacer.step(bufferedMs)) {
                AudioPacer.Step.DROP -> Unit
                AudioPacer.Step.PAD_THEN_PLAY -> { put(AudioPacer.padding()); put(c.pcm) }
                AudioPacer.Step.PLAY -> put(c.pcm)
            }
        }
    } finally {
        runCatching { track.pause(); track.flush(); track.release() }
    }
}

private fun openTrack(): AudioTrack {
    val min = AudioTrack.getMinBufferSize(AudioFormat.RATE, AFormat.CHANNEL_OUT_STEREO, AFormat.ENCODING_PCM_16BIT)
    return AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setAudioFormat(AFormat.Builder().setSampleRate(AudioFormat.RATE).setChannelMask(AFormat.CHANNEL_OUT_STEREO).setEncoding(AFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(min, AudioFormat.bytes(AudioFormat.MAX_BUFFER_MS + 40)))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()
}
