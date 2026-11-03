package app.deixis.desktop

import java.awt.Image
import java.awt.Taskbar
import java.awt.Window
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Значок Deixis на компьютере — тот же, что у приложения на телефоне: белая дельта на чёрном
 * (рисует tools/app-icons.py). Окна, трей и Dock берут его отсюда; установщики — из desktop/packaging.
 */
object AppIcon {
    private val source: BufferedImage? by lazy { runCatching { AppIcon::class.java.getResourceAsStream("/app-icon.png")?.use(ImageIO::read) }.getOrNull() }

    /** Несколько размеров: система берёт подходящий для заголовка, панели задач и Alt+Tab. */
    val images: List<Image> by lazy { source?.let { s -> SIZES.map { s.getScaledInstance(it, it, Image.SCALE_SMOOTH) } }.orEmpty() }

    fun image(size: Int): BufferedImage? = source?.let { s ->
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { img ->
            val g = img.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.drawImage(s, 0, 0, size, size, null)
            g.dispose()
        }
    }

    /**
     * Значок для области уведомлений, нарисованный под её точный размер: Δ на чёрном, со сглаживанием.
     * На Linux трей не понимает прозрачность (углы становились белыми) — там квадрат сплошной до краёв;
     * на Windows и Маке — со скруглёнными углами, как у значка программы.
     */
    fun trayImage(size: Int, opaque: Boolean = isLinux): BufferedImage =
        BufferedImage(size, size, if (opaque) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB).also { img ->
            val g = img.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE)
            g.color = java.awt.Color.BLACK
            val s = size.toDouble()
            if (opaque) g.fillRect(0, 0, size, size)
            else g.fill(java.awt.geom.RoundRectangle2D.Double(0.0, 0.0, s, s, s * 0.52, s * 0.52))
            // Тот же знак, что ic_stat_deixis на телефоне: в поле 108×108, крупно, чтобы читался в 16–24 px.
            fun p(x: Double, y: Double) = x / 108.0 * s to y / 108.0 * s
            val delta = java.awt.geom.Path2D.Double(java.awt.geom.Path2D.WIND_EVEN_ODD).apply {
                listOf(listOf(54.0 to 18.0, 92.0 to 94.0, 16.0 to 94.0), listOf(54.0 to 38.0, 31.0 to 84.0, 77.0 to 84.0)).forEach { tri ->
                    tri.forEachIndexed { i, (x, y) -> val (px, py) = p(x, y); if (i == 0) moveTo(px, py) else lineTo(px, py) }
                    closePath()
                }
            }
            g.color = java.awt.Color.WHITE
            g.fill(delta)
            g.dispose()
        }

    private val isLinux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    fun apply(w: Window) { if (images.isNotEmpty()) w.iconImages = images }

    /** Dock на Маке, когда программа запущена не из установленного приложения (там значок уже свой). */
    fun applyToDock() {
        val img = source ?: return
        runCatching { if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) Taskbar.getTaskbar().iconImage = img }
    }

    private val SIZES = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)
}
