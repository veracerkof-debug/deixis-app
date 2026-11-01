package app.deixis.desktop

import app.deixis.core.AudioChunk
import app.deixis.core.AudioFormat
import app.deixis.core.AudioPacer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.AudioFormat as JAudioFormat

/**
 * Звук трансляции экрана в колонки компьютера. Запас — около 40 мс против неровного Wi-Fi; накопилось больше
 * 100 мс (часы телефона и компьютера идут по-разному, сеть отдала пачку) — лишнее выбрасывается, и звук
 * не отстаёт от картинки. Нет звукового устройства — трансляция идёт без звука, картинку это не трогает.
 */
class ScreenAudio {
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope, chunks: Flow<AudioChunk>) {
        if (running) return
        job = scope.launch(Dispatchers.IO) {
            val line = runCatching { openLine() }.getOrNull() ?: return@launch
            try {
                line.start()
                val capacity = line.bufferSize
                chunks.collect { c ->
                    val bufferedMs = AudioFormat.ms(capacity - line.available())
                    when (AudioPacer.step(bufferedMs)) {
                        AudioPacer.Step.DROP -> Unit
                        AudioPacer.Step.PAD_THEN_PLAY -> { val pad = AudioPacer.padding(); line.write(pad, 0, pad.size); line.write(c.pcm, 0, c.pcm.size) }
                        AudioPacer.Step.PLAY -> line.write(c.pcm, 0, c.pcm.size)
                    }
                }
            } finally {
                runCatching { line.stop(); line.flush(); line.close() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun openLine(): SourceDataLine {
        val fmt = JAudioFormat(AudioFormat.RATE.toFloat(), 16, AudioFormat.CHANNELS, true, false)
        val line = AudioSystem.getSourceDataLine(fmt)
        // Буфер вывода чуть больше предела запаса: запись не блокируется, а лишнее отсекает AudioPacer.
        line.open(fmt, AudioFormat.bytes(AudioFormat.MAX_BUFFER_MS + 40))
        return line
    }
}
