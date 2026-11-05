package app.deixis.desktop

import app.deixis.core.FileStore
import app.deixis.core.MemoryTokenStore
import app.deixis.core.Pairing
import app.deixis.core.ReceiverCore
import app.deixis.core.ReceiverInfo
import app.deixis.core.ReceiverServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.SwingUtilities
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Окно пульта Deixis: пульт идёт во встроенном движке, связь с приёмником — через саму программу
 * (у движка OpenJFX свой WebSocket не работает). Окно открылось → пульт вошёл как «этот компьютер»;
 * закрыли → связь ушла и программа узнала о закрытии. Нужен экран (-Ddeixis.gui=1).
 */
class OperatorWindowTest {
    @Test
    fun remoteInsideTheAppConnectsAndLeaves() = runBlocking<Unit> {
        assumeTrue(System.getProperty("deixis.gui") == "1" && !GraphicsEnvironment.isHeadless(), "нужен экран")
        assumeTrue(OperatorWindow.available, "нужен встроенный движок")
        val dir = createTempDirectory("opwin").toFile()
        val core = ReceiverCore(ReceiverInfo("pc", "Компьютер", "desktop-test", "0"), FileStore(File(dir, "files")), Pairing(MemoryTokenStore()))
        val server = ReceiverServer(core, port = 0)
        val port = server.start()
        try {
            val token = (core.pairing.pair(core.pairing.pin, "Этот компьютер") as Pairing.Result.Ok).token
            var closed = false
            val window = OperatorWindow { closed = true }
            var opened = false
            SwingUtilities.invokeAndWait { opened = window.open("http://127.0.0.1:$port/remote#t=$token", null) }
            assertTrue(opened, "окно открылось")
            waitFor(60_000, "пульт в окне вошёл через мост") { core.peers.state.value.connected.any { it.here } }
            // Подтверждающим пульт становится сразу после приветствия — чуть позже, чем появляется «на связи».
            waitFor(10_000, "пульт в окне подтверждает подключения") { core.peers.approvalNeeded }
            SwingUtilities.invokeAndWait { window.close(notify = true) }
            assertTrue(closed, "программа узнала о закрытии окна")
            waitFor(15_000, "связь пульта ушла вместе с окном") { core.peers.state.value.connected.none { it.here } }
        } finally {
            server.stop()
        }
    }

    /** «Новое подключение» на мониторе оператора: окно показывает, кто просится, и передаёт ответ. */
    @Test
    fun approvalWindowAnswers() {
        assumeTrue(System.getProperty("deixis.gui") == "1" && !GraphicsEnvironment.isHeadless(), "нужен экран")
        val answers = mutableListOf<Pair<String, Boolean>>()
        val w = ApprovalWindow { id, allow -> answers += id to allow }
        val req = app.deixis.core.ConnectRequest("r1", "Алексей · Redmi 9T", "10.0.0.5", 0)
        val area = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        var dialog: javax.swing.JDialog? = null
        SwingUtilities.invokeAndWait {
            w.update(listOf(req), area)
            dialog = java.awt.Window.getWindows().filterIsInstance<javax.swing.JDialog>().firstOrNull { it.isVisible }
        }
        val d = dialog ?: error("окна подтверждения нет")
        assertTrue(area.contains(d.bounds), "окно на мониторе оператора")
        System.getProperty("deixis.outputsPng")?.let { dir ->
            Thread.sleep(500)
            val img = java.awt.Robot().createScreenCapture(d.bounds)
            javax.imageio.ImageIO.write(img, "png", File(dir, "approval.png").also { it.parentFile.mkdirs() })
        }
        SwingUtilities.invokeAndWait {
            fun buttons(c: java.awt.Container): List<javax.swing.JButton> =
                c.components.flatMap { if (it is javax.swing.JButton) listOf(it) else if (it is java.awt.Container) buttons(it) else emptyList() }
            buttons(d.contentPane).first { it.text == Words.allow }.doClick()
        }
        assertTrue(answers == listOf("r1" to true), "ответ «Разрешить» ушёл: $answers")
        SwingUtilities.invokeAndWait { assertTrue(!d.isVisible, "окно закрылось") }
        // Решили на пульте — окно уходит само.
        SwingUtilities.invokeAndWait { w.update(listOf(req.copy(id = "r2")), area); w.update(emptyList(), area) }
        SwingUtilities.invokeAndWait { assertTrue(java.awt.Window.getWindows().filterIsInstance<javax.swing.JDialog>().none { it.isVisible }) }
    }

    private fun waitFor(ms: Long, what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            assertTrue(System.currentTimeMillis() < end, what)
            Thread.sleep(50)
        }
    }
}
