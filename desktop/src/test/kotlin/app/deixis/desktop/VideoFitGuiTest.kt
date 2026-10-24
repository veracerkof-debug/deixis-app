package app.deixis.desktop

import app.deixis.core.VideoBackdrop
import app.deixis.core.VideoFit
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Настоящий VLC в настоящем окне: вертикальное красное видео 360×640 в окне 800×450 в каждом режиме.
 * Снимок экрана показывает, где кадр и чем залиты поля. Нужны экран (-Ddeixis.gui=1), VLC и ffmpeg.
 */
class VideoFitGuiTest {
    private val pngDir = System.getProperty("deixis.outputsPng")?.let { File(it, "video-fit") }

    private fun red(c: Int) = (c shr 16 and 0xFF) > 150 && (c shr 8 and 0xFF) < 90 && (c and 0xFF) < 90

    @Test
    fun everyFitAndBackdropOnRealVlc() {
        assumeTrue(System.getProperty("deixis.gui") == "1" && !GraphicsEnvironment.isHeadless(), "нужен экран")
        val dir = createTempDirectory("vfit").toFile()
        val file = File(dir, "tall.mp4")
        val ff = runCatching {
            ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=red:s=360x640:d=30:r=25", "-pix_fmt", "yuv420p", file.path)
                .redirectErrorStream(true).start().apply { waitFor(60, TimeUnit.SECONDS) }.exitValue()
        }.getOrDefault(-1)
        assumeTrue(ff == 0 && file.length() > 0, "нужен ffmpeg")
        val player = VideoPlayer({}, {})
        assumeTrue(player.available, "нужен VLC")
        lateinit var frame: JFrame
        lateinit var pane: VideoPane
        SwingUtilities.invokeAndWait {
            frame = JFrame("Видео")
            frame.isUndecorated = true
            pane = VideoPane(player)
            frame.contentPane = pane
            // Не в углу экрана: на Маке верх экрана занимает строка меню, и она попадала бы в снимок.
            frame.setBounds(100, 100, 800, 450)
            frame.isVisible = true
        }
        try {
            player.play(file, "tall.mp4", loop = true, startMs = 0)
            val end = System.currentTimeMillis() + 15_000
            while (player.displaySize() == null && System.currentTimeMillis() < end) Thread.sleep(100)
            assertTrue(player.displaySize() != null, "VLC узнал размер кадра")
            val robot = Robot()
            fun redColumns(img: java.awt.image.BufferedImage) = (0 until img.width).filter { x -> red(img.getRGB(x, img.height / 2)) }
            // Снимок, когда кадр уже на экране: на загруженной машине VLC выводит первый кадр не сразу.
            fun shot(name: String, fit: VideoFit, backdrop: VideoBackdrop, color: Long = 0xFF000000, ready: (java.awt.image.BufferedImage) -> Boolean = { redColumns(it).isNotEmpty() }): java.awt.image.BufferedImage {
                SwingUtilities.invokeAndWait { pane.fit = fit; pane.backdrop = backdrop; pane.backdropColor = color }
                Thread.sleep(1_500)
                val until = System.currentTimeMillis() + 10_000
                var img = robot.createScreenCapture(Rectangle(frame.locationOnScreen.x, frame.locationOnScreen.y, 800, 450))
                while (!ready(img) && System.currentTimeMillis() < until) {
                    Thread.sleep(300)
                    img = robot.createScreenCapture(Rectangle(frame.locationOnScreen.x, frame.locationOnScreen.y, 800, 450))
                }
                pngDir?.let { it.mkdirs(); ImageIO.write(img, "png", File(it, "$name.png")) }
                println("$name: столбцов кадра ${redColumns(img).size}, окон вывода VLC ${runCatching { player.state() }.getOrNull()}")
                return img
            }

            val fit = shot("fit-black", VideoFit.FIT, VideoBackdrop.BLACK)
            val cols = redColumns(fit)
            // Вписать: полоса шириной 450·360/640 ≈ 253 по центру, поля чёрные.
            assertTrue(cols.size in 240..266 && cols.first() in 260..285, "вписано по центру: ${cols.size} столбцов с ${cols.firstOrNull()}")
            assertTrue(Color(fit.getRGB(20, 225)).let { it.red < 30 && it.green < 30 && it.blue < 30 }, "поля чёрные")

            val theme = shot("fit-theme", VideoFit.FIT, VideoBackdrop.THEME, 0xFF18233A) { Color(it.getRGB(20, 225)).blue > 40 }
            assertTrue(Color(theme.getRGB(20, 225)).let { it.blue > 40 && it.red < 40 }, "поля цвета темы")

            val blur = shot("fit-blur", VideoFit.FIT, VideoBackdrop.BLUR) { Color(it.getRGB(20, 225)).red > 40 }
            assertTrue(Color(blur.getRGB(20, 225)).let { it.red > 40 && it.green < 40 }, "поля — размытый кадр (тёмно-красные)")
            // Снимки для размытых полей не оставляют на видео ни превью, ни надписей VLC: в кадре только кадр.
            // Полоса кадра — та же, что при чёрных полях: размытые поля бывают ярко-красными и сами.
            // Края полосы не считаются: там сглаживание при масштабе кадра (на Маке кадр рисует само окно).
            val xs = (cols.first() + 4 until cols.last() - 4 step 4)
            // Верх и низ тоже: на Маке кадр масштабирует окно, и крайние строки бывают смешаны с полем.
            println("верхний край кадра: ${(0..8).map { y -> Color(blur.getRGB(400, y)) }}")
            val bad = xs.flatMap { x -> (8 until 442 step 4).filter { y -> !red(blur.getRGB(x, y)) }.map { y -> x to y } }
            assertTrue(bad.isEmpty(), "в кадре посторонние точки (надписи VLC): ${bad.size}, например ${bad.take(5)}")

            val fill = shot("fill", VideoFit.FILL, VideoBackdrop.BLACK) { redColumns(it).size > 780 }
            assertTrue(redColumns(fill).size > 780, "заполнить: кадр на всю ширину")

            val stretch = shot("stretch", VideoFit.STRETCH, VideoBackdrop.BLACK) { red(it.getRGB(400, 5)) }
            assertTrue(redColumns(stretch).size > 780 && red(stretch.getRGB(400, 5)) && red(stretch.getRGB(400, 444)), "растянуть: весь экран")

            val orig = shot("original", VideoFit.ORIGINAL, VideoBackdrop.BLACK) { redColumns(it).size in 350..370 }
            val oc = redColumns(orig)
            assertTrue(oc.size in 350..370, "исходный размер: 360 столбцов, а не ${oc.size}")
        } finally {
            runCatching { player.stop(); player.release() }
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }
}
