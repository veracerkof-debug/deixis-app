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
 * Окно пульта Deixis на компьютере — тот же пульт, что на телефоне (Compose): свой экран подключается по 127.0.0.1
 * без PIN, показ с пульта идёт в зал, пульт подтверждает подключения; закрыли окно — программа узнала.
 * Нужен экран (-Ddeixis.gui=1).
 */
class PultWindowTest {
    @Test
    fun nativeRemoteDrivesItsOwnScreen() = runBlocking<Unit> {
        assumeTrue(System.getProperty("deixis.gui") == "1" && !GraphicsEnvironment.isHeadless(), "нужен экран")
        val dir = createTempDirectory("pultwin").toFile()
        val core = ReceiverCore(ReceiverInfo("pc", "Компьютер", "desktop-test", "0"), FileStore(File(dir, "files")), Pairing(MemoryTokenStore()))
        val server = ReceiverServer(core, port = 0)
        val port = server.start()
        try {
            val token = (core.pairing.pair(core.pairing.pin, "Этот компьютер") as Pairing.Result.Ok).token
            var closed = false
            var controller: app.deixis.phone.Controller? = null
            var window: app.deixis.phone.PultWindow? = null
            SwingUtilities.invokeAndWait {
                val platform = app.deixis.phone.DesktopPlatform(File(dir, "data"), { _, _ -> error("нет перевода") }, { "192.0.2.10" }, { window?.showing == true })
                val c = app.deixis.phone.Controller(platform)
                val ui = app.deixis.phone.Ui()
                val host = app.deixis.phone.DesktopHost(platform, { app.deixis.phone.HolyricsSource(platform, c) }, testUpdates(dir), "0") { }
                window = app.deixis.phone.PultWindow(c, host, ui, "Deixis — пульт") { closed = true }
                c.setCable(app.deixis.phone.Target("pc", "Компьютер", "127.0.0.1", port, app.deixis.phone.Target.Kind.LOCAL, "desktop-test"), token)
                window!!.open(null)
                controller = c
            }
            val c = controller!!
            waitFor(30_000, "свой экран на связи") { c.outputs.value.any { it.state == app.deixis.phone.OutState.ONLINE } }
            waitFor(10_000, "пульт вошёл как «этот компьютер» и подтверждает подключения") {
                core.peers.state.value.connected.any { it.here } && core.peers.approvalNeeded
            }
            waitFor(10_000, "ссылка зрителям — по адресу в сети, а не 127.0.0.1") { c.viewUrl.value?.startsWith("http://192.0.2.10:$port/view?k=") == true }
            SwingUtilities.invokeAndWait { c.showSlides("Проверка", listOf("Первый слайд", "Второй"), null) }
            waitFor(10_000, "слайд с пульта — в зале") { (core.state.value.content as? app.deixis.core.TextContent)?.text == "Первый слайд" }
            SwingUtilities.invokeAndWait { c.step(1) }
            waitFor(10_000, "«Дальше» листает в зале") { (core.state.value.content as? app.deixis.core.TextContent)?.text == "Второй" }
            System.getProperty("deixis.outputsPng")?.let { out ->
                Thread.sleep(1_500)
                val shot = java.awt.Robot().createScreenCapture(GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds)
                javax.imageio.ImageIO.write(shot, "png", File(out, "pult-window.png").also { it.parentFile.mkdirs() })
            }
            SwingUtilities.invokeAndWait { window!!.close(notify = true) }
            assertTrue(closed, "программа узнала о закрытии окна")
        } finally {
            server.stop()
        }
    }

    private fun testUpdates(dir: File) = app.deixis.desktop.DesktopUpdates("0", dir, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default), object : app.deixis.desktop.UpdateApp {
        override fun showing() = false
        override fun exitForUpdate() = Unit
    })

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
