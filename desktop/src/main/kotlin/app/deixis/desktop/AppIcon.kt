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

    fun apply(w: Window) { if (images.isNotEmpty()) w.iconImages = images }

    /** Dock на Маке, когда программа запущена не из установленного приложения (там значок уже свой). */
    fun applyToDock() {
        val img = source ?: return
        runCatching { if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) Taskbar.getTaskbar().iconImage = img }
    }

    private val SIZES = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)
}
