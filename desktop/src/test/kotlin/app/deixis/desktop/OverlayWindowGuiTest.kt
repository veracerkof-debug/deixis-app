package app.deixis.desktop

import app.deixis.core.FileStore
import app.deixis.core.Mode
import app.deixis.core.OutputLayout
import app.deixis.core.TextContent
import app.deixis.core.VideoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Color
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * «Наложение» в настоящем окне: без рамки, поверх всех, без фокуса. Где система сводит прозрачные окна
 * (Windows, macOS) — под слоем видна программа, а щелчок доходит до неё. Нужен экран (-Ddeixis.gui=1).
 */
class OverlayWindowGuiTest {
    private val pngDir = System.getProperty("deixis.outputsPng")?.let { File(it, "overlay-mode") }
    private val os = System.getProperty("os.name").orEmpty().lowercase()

    @Test
    fun overlayLetsTheProgramUnderneathShowAndClick() {
        assumeTrue(System.getProperty("deixis.gui") == "1" && !GraphicsEnvironment.isHeadless(), "нужен экран")
        val store = FileStore(File(createTempDirectory("overlay-mode").toFile(), "files"))
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
        val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        val b = device.defaultConfiguration.bounds
        // «Другая программа» под наложением: красное окно с откликом на щелчок.
        val clicked = CountDownLatch(1)
        lateinit var under: JFrame
        SwingUtilities.invokeAndWait {
            under = JFrame("Под наложением").apply {
                contentPane = JPanel().apply {
                    background = Color(0xD02020)
                    addMouseListener(object : MouseAdapter() { override fun mousePressed(e: MouseEvent) = clicked.countDown() })
                }
                setBounds(b.x + b.width / 8, b.y + b.height / 8, b.width * 3 / 4, b.height * 3 / 4)
                isVisible = true
            }
        }
        lateinit var w: OutputWindow
        SwingUtilities.invokeAndWait {
            w = OutputWindow("o", device, OutputLayout.OVERLAY, WindowMode.OVERLAY, topmost = true, focusable = false, shared = true, files = store, host = host)
            w.dim = 0
        }
        try {
            SwingUtilities.invokeAndWait { w.update(FrameState(TextContent("Титры поверх"), Mode.NORMAL)) }
            Thread.sleep(1_500)
            SwingUtilities.invokeAndWait {
                assertTrue(w.window.isUndecorated, "без рамки")
                assertTrue(w.window.isAlwaysOnTop, "поверх всех окон")
                assertFalse(w.window.focusableWindowState, "без фокуса")
                assertTrue(w.window.bounds == b, "по монитору: ${w.window.bounds} != $b")
            }
            val composited = (os.contains("win") || os.contains("mac")) &&
                device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
            if (!composited) { println("Прозрачность и щелчок сквозь не проверены: система без сведения окон"); return }
            val robot = Robot()
            val cx = b.x + b.width / 2
            val cy = b.y + b.height / 3
            val shot = robot.createScreenCapture(b)
            pngDir?.let { d -> d.mkdirs(); ImageIO.write(shot, "png", File(d, "overlay.png")) }
            val seen = Color(shot.getRGB(cx - b.x, cy - b.y))
            assertTrue(seen.red > 150 && seen.green < 90 && seen.blue < 90, "под слоем 0 % видно окно программы: $seen")
            robot.mouseMove(cx, cy)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            assertTrue(clicked.await(5, TimeUnit.SECONDS), "щелчок дошёл до программы под наложением")
        } finally {
            // Курсор — в угол: следующие проверки снимают середину экрана.
            runCatching { Robot().mouseMove(b.x + b.width - 2, b.y + b.height - 2) }
            SwingUtilities.invokeAndWait { w.dispose(); under.dispose() }
            w.release()
        }
    }
}
