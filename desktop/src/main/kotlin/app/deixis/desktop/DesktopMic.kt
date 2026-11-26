package app.deixis.desktop

import app.deixis.core.effects.MicMeter
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Микрофон компьютера для реакции живого фона на звук в зале. Берётся только громкость ([MicMeter]): запись не ведётся,
 * звук нигде не хранится и не передаётся. Включается, только когда у слайда включена «Реакция на звук»; нет микрофона или
 * доступа — уровень 0, фон работает как обычно, сбой не трогает вывод.
 */
class DesktopMic {
    private val meter = MicMeter()
    @Volatile private var thread: Thread? = null
    @Volatile var level = 0.0
        private set
    @Volatile var available = true
        private set

    @Synchronized
    fun start() {
        if (thread != null || !available) return
        val t = Thread({ run() }, "scene-mic").apply { isDaemon = true }
        thread = t
        t.start()
    }

    @Synchronized
    fun stop() {
        thread?.interrupt(); thread = null
        meter.reset(); level = 0.0
    }

    private fun run() {
        var line: TargetDataLine? = null
        try {
            val fmt = AudioFormat(16000f, 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, fmt)
            if (!AudioSystem.isLineSupported(info)) { available = false; return }
            line = (AudioSystem.getLine(info) as TargetDataLine).also { it.open(fmt, 16000); it.start() }
            val bytes = ByteArray(1600) // 50 мс
            val shorts = ShortArray(800)
            while (!Thread.currentThread().isInterrupted && thread === Thread.currentThread()) {
                val n = line.read(bytes, 0, bytes.size)
                if (n <= 0) continue
                val count = n / 2
                for (i in 0 until count) shorts[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                level = meter.feed(shorts, count, count / 16000.0)
            }
        } catch (e: Throwable) {
            available = false
            app.deixis.diag.Log.w("scene", "микрофон недоступен: ${e.message}")
        } finally {
            runCatching { line?.stop(); line?.close() }
            level = 0.0
        }
    }
}
