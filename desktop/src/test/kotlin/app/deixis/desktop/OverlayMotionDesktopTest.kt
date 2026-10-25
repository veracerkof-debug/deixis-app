package app.deixis.desktop

import app.deixis.core.FileStore
import app.deixis.core.OverlayMotion
import app.deixis.core.OverlaySamples
import app.deixis.core.Overlays
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/** Кадры появления слоя на компьютере: плашка въезжает слева, строка поднимается, логотип проявляется. */
class OverlayMotionDesktopTest {
    private val pngDir = System.getProperty("deixis.outputsPng")?.let { File(it, "overlay-motion") }

    @Test
    fun sampleEntersStepByStep() {
        val store = FileStore(File(createTempDirectory("motion").toFile(), "files"))
        OverlaySamples.images.forEach { n -> val b = OverlaySamples.bytes(n); store.append(OverlaySamples.ref(n).sha256, b.size.toLong(), 0, b.inputStream()) }
        val sample = OverlaySamples.all().first { it.name == "Всё сразу" }.overlays
        val painter = OverlayPainter(store)
        val motion = OverlayMotion()
        val t0 = 1_000_000L
        motion.update(sample, t0)
        val w = 960
        val h = 540
        fun shot(t: Long): BufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { img ->
            val g = img.createGraphics()
            g.color = Color(0x2A3A4A); g.fillRect(0, 0, w, h)
            painter.paint(g, w.toFloat(), h.toFloat(), motion.frame(t), t)
            g.dispose()
        }
        val steps = listOf(0L, 90L, 180L, 270L, 450L)
        val frames = steps.map { shot(t0 + it) }
        // Правый край плашки: самая правая точка темнее фона над полосой строки (плашка проявляется, поэтому сравнение с фоном).
        fun plateRight(img: BufferedImage): Int {
            val rows = (h * 0.68).toInt() until (h * 0.9).toInt() step 2
            return rows.maxOf { y -> (w - 1 downTo 0).firstOrNull { x -> Color(img.getRGB(x, y)).let { it.blue < 0x4A - 10 && it.red < 0x2A } } ?: -1 }
        }
        val rights = frames.map(::plateRight)
        println("правый край плашки по кадрам: $rights")
        assertTrue(rights.first() < 0, "в первый миг плашки ещё нет: $rights")
        assertTrue(rights.zipWithNext().all { (a, b) -> b >= a }, "плашка въезжает слева: $rights")
        assertTrue(rights.last() > w / 5, "и встаёт целиком: ${rights.last()}")
        // Уход: через время движения слоя нет.
        motion.update(Overlays(), t0 + 2_000)
        val gone = shot(t0 + 2_000 + 500)
        assertTrue(Color(gone.getRGB(w / 2, h - 5)) == Color(0x2A3A4A), "после ухода — пусто")
        pngDir?.let { d ->
            d.mkdirs()
            val strip = BufferedImage(w / 2 * frames.size, h / 2, BufferedImage.TYPE_INT_RGB)
            val g = strip.createGraphics()
            frames.forEachIndexed { i, f -> g.drawImage(f, i * w / 2, 0, w / 2, h / 2, null) }
            g.dispose()
            ImageIO.write(strip, "png", File(d, "enter.png"))
        }
    }
}
