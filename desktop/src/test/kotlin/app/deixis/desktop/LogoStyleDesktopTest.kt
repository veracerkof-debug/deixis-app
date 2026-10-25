package app.deixis.desktop

import app.deixis.core.FileStore
import app.deixis.core.LogoStyle
import app.deixis.core.Mode
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/** Четыре вида «Логотипа» на разных экранах: знак виден, по центру, не касается краёв. */
class LogoStyleDesktopTest {
    private val pngDir = System.getProperty("deixis.outputsPng")?.let { File(it, "logo") }

    @Test
    fun everyStyleFitsEveryScreen() {
        val r = SlideRenderer(FileStore(File(createTempDirectory("logo").toFile(), "files")))
        for ((w, h) in listOf(1920 to 1080, 1024 to 768, 1080 to 1920)) for (style in LogoStyle.entries) {
            val img = r.render(w, h, null, Mode.LOGO, null, logo = style)
            pngDir?.let { it.mkdirs(); ImageIO.write(img, "png", File(it, "${style.name.lowercase()}-${w}x$h.png")) }
            var minX = w; var maxX = -1; var minY = h; var maxY = -1
            for (y in 0 until h step 2) for (x in 0 until w step 2) if (img.getRGB(x, y) and 0xFF > 128) {
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
            assertTrue(maxX > 0, "$style $w×$h: знак нарисован")
            val edge = minOf(w, h) * 0.04
            assertTrue(minX > edge && maxX < w - edge && minY > edge && maxY < h - edge, "$style $w×$h: не у краёв ($minX..$maxX, $minY..$maxY)")
            assertTrue(kotlin.math.abs((minX + maxX) / 2 - w / 2) < w * 0.02, "$style $w×$h: по центру ($minX..$maxX)")
            assertTrue(maxX - minX > w * 0.4 || maxY - minY > h * 0.4, "$style $w×$h: крупно")
        }
    }
}
