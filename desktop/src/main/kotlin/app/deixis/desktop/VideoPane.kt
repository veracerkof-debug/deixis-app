package app.deixis.desktop

import app.deixis.core.VideoBackdrop
import app.deixis.core.VideoFit
import app.deixis.core.videoRect
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Окно видео: кадр ставится по формуле ядра ([videoRect]) — вписан, заполняет, растянут или в исходном
 * размере; лишнее за краями обрезает само окно. Поля вокруг вписанного кадра — чёрные, цвета темы или
 * размытая копия кадра (снимок раз в [BLUR_MS], уменьшенный и растянутый — мягкое размытие без нагрузки).
 */
class VideoPane(private val player: VideoPlayer) : JPanel(null) {
    var fit = VideoFit.FIT
        set(v) { field = v; relayout() }
    var backdrop = VideoBackdrop.BLACK
        set(v) { field = v; blurred = null; updateBlur(); repaint() }
    var backdropColor: Long = 0xFF000000
        set(v) { field = v; repaint() }

    /** Слой поверх показа над кадром (когда кадр рисует само окно — на Маке). */
    var overlay: ((Graphics2D) -> Unit)? = null
        set(v) { field = v; layer.isVisible = v != null; layer.repaint() }

    // Прозрачный компонент над кадром: кадр видео перерисовывается сам по себе, и всё, что нарисовано
    // поверх него в самой панели, он бы затирал. Сосед сверху Swing перерисовывает вместе с кадром.
    private val layer = object : JPanel(null) {
        init { isOpaque = false; isVisible = false }
        override fun paintComponent(g: Graphics) { overlay?.invoke(g as Graphics2D) }
    }

    private var blurred: BufferedImage? = null
    private val blurTimer = Timer(BLUR_MS) { refreshBlur() }

    init {
        background = Color.BLACK
        isOpaque = true
        add(layer)
        player.view?.let { add(it) }
        player.onVideoSize = { relayout() }
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = relayout()
        })
    }

    /** Новый файл: размеры кадра узнаются заново, размытые поля — тоже. */
    fun reset() { blurred = null; relayout(); updateBlur() }

    fun relayout() {
        layer.setBounds(0, 0, width, height)
        val v = player.view ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Исходный размер — в пикселях монитора: при масштабе 150 % окно на треть меньше.
        val scale = graphicsConfiguration?.defaultTransform?.scaleX?.takeIf { it > 0 }?.toFloat() ?: 1f
        val size = player.displaySize()
        val r = if (size == null) videoRect(w, h, w, h, VideoFit.FIT)
        else if (fit == VideoFit.ORIGINAL) videoRect(w, h, size.first / scale, size.second / scale, fit)
        else videoRect(w, h, size.first, size.second, fit)
        v.setBounds(r.left.toInt(), r.top.toInt(), r.width.toInt().coerceAtLeast(1), r.height.toInt().coerceAtLeast(1))
        player.stretchTo(if (fit == VideoFit.STRETCH) r.width.toInt() to r.height.toInt() else null)
        revalidate()
        repaint()
    }

    private fun updateBlur() {
        if (backdrop == VideoBackdrop.BLUR && isShowing) { blurTimer.start(); refreshBlur() } else blurTimer.stop()
    }

    override fun addNotify() { super.addNotify(); updateBlur() }
    override fun removeNotify() { blurTimer.stop(); super.removeNotify() }

    private fun refreshBlur() {
        val shot = player.snapshot(BLUR_SOURCE_W) ?: return
        // Уменьшить до горстки пикселей: при растяжении на экран с плавной интерполяцией это и есть размытие.
        val tw = BLUR_W
        val th = (BLUR_W * shot.height / shot.width.coerceAtLeast(1)).coerceAtLeast(1)
        val small = BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB)
        val g = small.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(shot, 0, 0, tw, th, null)
        g.dispose()
        blurred = small
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.color = when (backdrop) {
            VideoBackdrop.THEME -> Color(backdropColor.toInt(), true)
            else -> Color.BLACK
        }
        g2.fillRect(0, 0, width, height)
        val b = blurred
        if (backdrop == VideoBackdrop.BLUR && b != null) {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            // Заполнить экран размытым кадром и приглушить: поля не спорят с самим видео.
            val s = maxOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val dw = (b.width * s).toInt()
            val dh = (b.height * s).toInt()
            g2.drawImage(b, (width - dw) / 2, (height - dh) / 2, dw, dh, null)
            g2.color = Color(0, 0, 0, BLUR_DIM)
            g2.fillRect(0, 0, width, height)
        }
    }

    /** Слой и кадр перекрываются: Swing должен рисовать их вместе. */
    override fun isOptimizedDrawingEnabled() = false

    private companion object {
        const val BLUR_MS = 1_500
        const val BLUR_SOURCE_W = 160
        const val BLUR_W = 16
        const val BLUR_DIM = 110
    }
}
