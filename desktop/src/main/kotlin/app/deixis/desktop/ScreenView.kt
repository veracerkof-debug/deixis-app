package app.deixis.desktop

import app.deixis.core.StreamSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Трансляция экрана телефона в окне зала с малой задержкой: кадр из приёмника этой же программы — сразу в
 * [LowLatencyDecoder] и сразу на экран, без HTTP, контейнера MP4 и буфера плеера (принцип scrcpy).
 * Кадр вписывается в окно, поля чёрные; поверх — слой (плашки, бегущая строка), как над слайдом.
 */
class ScreenView : JComponent() {
    @Volatile private var image: BufferedImage? = null
    @Volatile private var pending = false
    private var job: Job? = null
    /** Слой поверх трансляции (плашки, строка, логотип). */
    var overlay: ((Graphics2D, Int, Int) -> Unit)? = null

    init {
        isOpaque = true
        background = Color.BLACK
    }

    val running: Boolean get() = job?.isActive == true

    /**
     * Начать показ потока [frames]. Сбой декодера не трогает окно: остаётся последний кадр, поток открывается заново
     * ([onFailed]) — так же, как раньше при сбое плеера.
     */
    fun start(scope: CoroutineScope, frames: Flow<StreamSample>, onFailed: () -> Unit) {
        if (running) return
        job = scope.launch(Dispatchers.IO) {
            try {
                LowLatencyDecoder().use { d ->
                    frames.collect { s ->
                        val bytes = if (s.key && s.config != null) s.config!! + s.data else s.data
                        d.decode(bytes) { img -> show(img) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                SwingUtilities.invokeLater(onFailed)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        image = null
        repaint()
    }

    /** Новый кадр: рисуется при ближайшей перерисовке; лишние кадры между перерисовками просто заменяются. */
    private fun show(img: BufferedImage) {
        image = img
        if (pending) return
        pending = true
        SwingUtilities.invokeLater { pending = false; paintImmediately(0, 0, width, height) }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.color = Color.BLACK
        g2.fillRect(0, 0, width, height)
        image?.let { img ->
            val k = minOf(width.toDouble() / img.width, height.toDouble() / img.height)
            val w = (img.width * k).toInt()
            val h = (img.height * k).toInt()
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.drawImage(img, (width - w) / 2, (height - h) / 2, w, h, null)
        }
        overlay?.invoke(g2, width, height)
    }
}
