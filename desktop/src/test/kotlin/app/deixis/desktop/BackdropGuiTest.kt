package app.deixis.desktop

import app.deixis.core.FileRef
import app.deixis.core.FileStore
import app.deixis.core.Mode
import app.deixis.core.OutputLayout
import app.deixis.core.TextContent
import app.deixis.core.TextStyle
import app.deixis.core.VideoState
import app.deixis.core.sha256Of
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Видеофон на настоящем VLC: под текстом играет видео; смена слайда с тем же фоном не начинает его заново;
 * «Очистить» оставляет фон. Нужны экран (-Ddeixis.gui=1), VLC и ffmpeg.
 */
class BackdropGuiTest {
    private val pngDir = System.getProperty("deixis.outputsPng")?.let { File(it, "backdrop") }

    @Test
    fun backgroundVideoPlaysOnUnderChangingText() {
        assumeTrue(System.getProperty("deixis.gui") == "1" && !GraphicsEnvironment.isHeadless(), "нужен экран")
        assumeTrue(VideoPlayer.vlcFound, "нужен VLC")
        val dir = createTempDirectory("bg").toFile()
        val mp4 = File(dir, "bg.mp4")
        val ff = runCatching {
            ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=0x2040C0:s=640x360:d=20:r=25", "-pix_fmt", "yuv420p", mp4.path)
                .redirectErrorStream(true).start().apply { waitFor(60, TimeUnit.SECONDS) }.exitValue()
        }.getOrDefault(-1)
        assumeTrue(ff == 0, "нужен ffmpeg")
        val store = FileStore(File(dir, "files"))
        val sha = sha256Of(mp4.inputStream())
        store.append(sha, mp4.length(), 0, mp4.inputStream())
        val ref = FileRef(sha, "bg.mp4", mp4.length(), "video/mp4")
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
        lateinit var w: OutputWindow
        SwingUtilities.invokeAndWait { w = OutputWindow("t", null, OutputLayout.HALL, WindowMode.WINDOWED, false, true, true, store, host) }
        val style = TextStyle(backgroundVideo = ref, background = 0xFF000000)
        try {
            SwingUtilities.invokeAndWait { w.update(FrameState(TextContent("Слайд один", style = style), Mode.NORMAL)) }
            Thread.sleep(3_000)
            val bg = w.backdropForTest ?: error("видеофон не создан")
            val t1 = bg.timeMs()
            assertTrue(t1 > 1_500, "видеофон идёт: $t1 мс")
            val robot = Robot()
            fun shot(name: String) = SwingUtilities.invokeAndWait { }.let {
                Thread.sleep(800)
                val f = w.window
                robot.createScreenCapture(Rectangle(f.contentPane.locationOnScreen.x, f.contentPane.locationOnScreen.y, f.contentPane.width, f.contentPane.height))
                    .also { img -> pngDir?.let { d -> d.mkdirs(); ImageIO.write(img, "png", File(d, "$name.png")) } }
            }
            val one = shot("slide-1")
            // Фон — синий кадр видео, а не чёрный.
            val c = java.awt.Color(one.getRGB(10, 10))
            assertTrue(c.blue > 150 && c.red < 80, "под текстом видео: $c")
            SwingUtilities.invokeAndWait { w.update(FrameState(TextContent("Слайд два", style = style), Mode.NORMAL)) }
            Thread.sleep(500)
            val t2 = bg.timeMs()
            assertTrue(t2 > t1, "смена слайда не перезапускает видеофон: было $t1, стало $t2")
            SwingUtilities.invokeAndWait { w.update(FrameState(TextContent("Слайд два", style = style), Mode.CLEAR)) }
            val clear = shot("clear")
            assertTrue(java.awt.Color(clear.getRGB(clear.width / 2, clear.height / 2)).blue > 150, "«Очистить» оставляет фон")
            assertTrue(bg.timeMs() > t2, "и видео идёт дальше")
        } finally {
            SwingUtilities.invokeAndWait { w.dispose() }
            w.release()
        }
    }
}
