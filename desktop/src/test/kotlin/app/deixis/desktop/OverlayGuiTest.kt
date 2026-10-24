package app.deixis.desktop

import app.deixis.core.Corner
import app.deixis.core.FileRef
import app.deixis.core.FileStore
import app.deixis.core.LogoOverlay
import app.deixis.core.LowerThird
import app.deixis.core.Mode
import app.deixis.core.OutputLayout
import app.deixis.core.Overlays
import app.deixis.core.TextContent
import app.deixis.core.Ticker
import app.deixis.core.ClockOverlay
import app.deixis.core.VideoContent
import app.deixis.core.VideoState
import app.deixis.core.sha256Of
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Слой поверх показа в настоящем окне: плашка, бегущая строка и логотип видны над слайдом, «Чёрный» их
 * убирает; над настоящим VLC — тоже (на Маке — в самом окне, на Windows — прозрачным окном над VLC).
 * Нужен экран (-Ddeixis.gui=1); часть с видео — VLC и ffmpeg.
 */
class OverlayGuiTest {
    private val pngDir = System.getProperty("deixis.outputsPng")?.let { File(it, "overlays") }

    private fun put(store: FileStore, f: File, mime: String): FileRef {
        val sha = sha256Of(f.inputStream())
        store.append(sha, f.length(), 0, f.inputStream())
        return FileRef(sha, f.name, f.length(), mime)
    }

    @Test
    fun layerShowsOverSlidesAndVideo() {
        assumeTrue(System.getProperty("deixis.gui") == "1" && !GraphicsEnvironment.isHeadless(), "нужен экран")
        val dir = createTempDirectory("overlay").toFile()
        val store = FileStore(File(dir, "files"))
        val png = File(dir, "logo.png")
        ImageIO.write(BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().apply { color = Color(0x2050E0); fillRect(0, 0, 200, 100); dispose() }
        }, "png", png)
        val logo = put(store, png, "image/png")
        val host = object : OutputHost {
            override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            override val snapshotDir: File? = null
            override fun file(sha256: String) = store.file(sha256)
            override fun streamUrl() = ""
            override fun reportError(message: String?) = Unit
            override fun reportVideo(state: VideoState?) = Unit
            override fun quit() = Unit
            override fun openOperator() = Unit
            override fun setWindowed(windowId: String, windowed: Boolean) = Unit
            override fun menu(windowId: String) = JPopupMenu()
        }
        val layer = Overlays(
            logo = LogoOverlay(logo, corner = Corner.TOP_RIGHT, sizePercent = 15f),
            lowerThird = LowerThird("Анна Иванова", "Тема: весна", background = 0xFF20A020, accent = null),
            ticker = Ticker("Сбор в 18:00 у входа", background = 0xFFC02020),
            clock = ClockOverlay(corner = Corner.TOP_LEFT),
        )
        lateinit var w: OutputWindow
        SwingUtilities.invokeAndWait { w = OutputWindow("t", null, OutputLayout.HALL, WindowMode.WINDOWED, false, true, true, store, host) }
        val robot = Robot()
        fun shot(name: String): BufferedImage {
            SwingUtilities.invokeAndWait { }
            Thread.sleep(800)
            val c = w.window.contentPane
            return robot.createScreenCapture(Rectangle(c.locationOnScreen.x, c.locationOnScreen.y, c.width, c.height))
                .also { img -> pngDir?.let { d -> d.mkdirs(); ImageIO.write(img, "png", File(d, "$name.png")) } }
        }
        fun at(img: BufferedImage, fx: Float, fy: Float) = Color(img.getRGB((img.width * fx).toInt(), (img.height * fy).toInt()))
        fun red(c: Color) = c.red > 150 && c.green < 80 && c.blue < 80
        fun green(c: Color) = c.green > 120 && c.red < 80 && c.blue < 80
        fun blue(c: Color) = c.blue > 150 && c.red < 80
        try {
            SwingUtilities.invokeAndWait { w.update(FrameState(TextContent("Слайд"), Mode.NORMAL, overlays = layer)) }
            val one = shot("slide")
            assertTrue(red(at(one, 0.5f, 0.97f)), "бегущая строка внизу: ${at(one, 0.5f, 0.97f)}")
            assertTrue(green(at(one, 0.07f, 0.85f)), "плашка над строкой: ${at(one, 0.07f, 0.85f)}")
            assertTrue(blue(at(one, 0.9f, 0.1f)), "логотип справа вверху: ${at(one, 0.9f, 0.1f)}")
            // Бегущая строка идёт: кадры через полсекунды различаются в полосе строки.
            val a = shot("ticker-a")
            Thread.sleep(500)
            val b = shot("ticker-b")
            val row = (a.height * 0.962f).toInt()
            assertTrue((0 until a.width).any { a.getRGB(it, row) != b.getRGB(it, row) }, "строка движется")

            SwingUtilities.invokeAndWait { w.update(FrameState(TextContent("Слайд"), Mode.BLACK, overlays = layer)) }
            val black = shot("black")
            assertTrue(at(black, 0.5f, 0.97f).red < 40, "«Чёрный» убирает и слой")

            if (!VideoPlayer.vlcFound) { println("Слой над видео не проверен: нет VLC"); return }
            val mp4 = File(dir, "v.mp4")
            val ff = runCatching {
                ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=0xE0E020:s=640x360:d=20:r=25", "-pix_fmt", "yuv420p", mp4.path)
                    .redirectErrorStream(true).start().apply { waitFor(60, TimeUnit.SECONDS) }.exitValue()
            }.getOrDefault(-1)
            if (ff != 0) { println("Слой над видео не проверен: нет ffmpeg"); return }
            val video = VideoContent(put(store, mp4, "video/mp4"), loop = true)
            val nativeWindow = VideoPlayer(onError = {}, onFinished = {}).let { p -> p.nativeWindow.also { p.release() } }
            if (nativeWindow && !OverlayWindow.supported(w.window)) { println("Слой над видео не проверен: система без прозрачных окон"); return }
            SwingUtilities.invokeAndWait { w.update(FrameState(video, Mode.NORMAL, overlays = layer)) }
            // Ждём кадр видео на экране (VLC на машине CI начинает не сразу).
            var v = shot("video")
            val until = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < until && at(v, 0.5f, 0.4f).let { !(it.red > 150 && it.green > 150 && it.blue < 100) }) v = shot("video")
            val mid = at(v, 0.5f, 0.4f)
            assertTrue(mid.red > 150 && mid.green > 150 && mid.blue < 100, "видео на экране: $mid")
            assertTrue(red(at(v, 0.5f, 0.97f)), "бегущая строка над видео: ${at(v, 0.5f, 0.97f)}")
            assertTrue(blue(at(v, 0.9f, 0.1f)), "логотип над видео: ${at(v, 0.9f, 0.1f)}")
        } finally {
            SwingUtilities.invokeAndWait { w.dispose() }
            w.release()
        }
    }
}
