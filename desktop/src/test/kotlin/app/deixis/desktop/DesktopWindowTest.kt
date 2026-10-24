package app.deixis.desktop

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Component
import java.awt.GraphicsEnvironment
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.SwingUtilities
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class KeyActionTest {
    private val ctrl = KeyEvent.CTRL_DOWN_MASK
    private val meta = KeyEvent.META_DOWN_MASK

    @Test
    fun quitWorksEverywhereWithCtrlOrCommand() {
        for (mode in WindowMode.entries) for (shared in listOf(true, false)) {
            assertEquals(KeyAction.QUIT, keyAction(KeyEvent.VK_Q, ctrl, mode, shared))
            assertEquals(KeyAction.QUIT, keyAction(KeyEvent.VK_Q, meta, mode, shared))
        }
        assertNull(keyAction(KeyEvent.VK_Q, 0, WindowMode.COVER, true))
    }

    @Test
    fun escapeLeavesFullScreenOnlyOnTheOperatorMonitor() {
        assertEquals(KeyAction.WINDOWED, keyAction(KeyEvent.VK_ESCAPE, 0, WindowMode.NATIVE, true))
        assertEquals(KeyAction.WINDOWED, keyAction(KeyEvent.VK_ESCAPE, 0, WindowMode.COVER, true))
        assertNull(keyAction(KeyEvent.VK_ESCAPE, 0, WindowMode.COVER, false))
        assertNull(keyAction(KeyEvent.VK_ESCAPE, 0, WindowMode.WINDOWED, true))
    }

    @Test
    fun f11AndCtrlCommandFToggle() {
        assertEquals(KeyAction.FULL_SCREEN, keyAction(KeyEvent.VK_F11, 0, WindowMode.WINDOWED, true))
        assertEquals(KeyAction.WINDOWED, keyAction(KeyEvent.VK_F11, 0, WindowMode.COVER, true))
        assertEquals(KeyAction.FULL_SCREEN, keyAction(KeyEvent.VK_F, ctrl or meta, WindowMode.WINDOWED, true))
        assertNull(keyAction(KeyEvent.VK_F, ctrl, WindowMode.WINDOWED, true))
    }

    @Test
    fun roleSurvivesRestartAndBrokenFileMeansFirstRun() {
        val dir = createTempDirectory("role").toFile()
        val store = RoleStore(java.io.File(dir, "role"))
        assertNull(store.load())
        store.save(Role.BOTH)
        assertEquals(Role.BOTH, RoleStore(java.io.File(dir, "role")).load())
        java.io.File(dir, "role").writeText("???")
        assertNull(store.load())
    }
}

/**
 * Настоящие окна на настоящей системе: запускается в CI на macOS, Windows и Linux (xvfb) с `-Ddeixis.gui=1`.
 * Проверяет то, из-за чего на Маке программу пришлось выключать перезагрузкой: окно на мониторе оператора
 * не запирает экран, отвечает мыши и клавишам и закрывается.
 */
class DesktopWindowTest {

    private fun assumeGui() {
        assumeTrue(System.getProperty("deixis.gui") == "1" && !GraphicsEnvironment.isHeadless(), "нужен экран: -Ddeixis.gui=1")
    }

    private fun <T> onEdt(block: () -> T): T {
        var out: Result<T>? = null
        SwingUtilities.invokeAndWait { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    private fun waitFor(what: String, timeoutMs: Long = 8_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (onEdt(cond)) return
            Thread.sleep(50)
        }
        fail("Не дождались: $what")
    }

    private fun deepest(w: OutputWindow): Component {
        val f = w.window
        val c = f.contentPane
        return SwingUtilities.getDeepestComponentAt(c, c.width / 2, c.height / 2) ?: c
    }

    @Test
    fun singleMonitorDoesNotTrapTheScreen() {
        assumeGui()
        // Как в жизни: VLC уже загружен в этот процесс (видео или видеофон показывали) — полный экран Mac,
        // Esc и F11 должны работать и после этого.
        if (VideoPlayer.vlcFound) SwingUtilities.invokeAndWait { VideoPlayer({}, {}).release() }
        val exits = AtomicInteger(-1)
        val app = ReceiverApp(
            ReceiverOptions(
                name = "Проверка", port = 0, screen = null, windowed = false,
                dataDir = createTempDirectory("deixis-gui").toFile(), snapshotDir = null,
                operator = false, role = Role.SCREEN, askRole = false, exit = { exits.set(it) },
            ),
        )
        app.run()
        try {
            waitFor("окно зала на экране") { app.windowsForTest().any { it.window.isShowing } }
            val w = onEdt { app.windowsForTest().first { it.shared } }
            val expected = if (MacScreen.isMac) WindowMode.NATIVE else WindowMode.COVER
            assertEquals(expected, w.mode, "монитор оператора — не запирающий режим")
            val screen = onEdt { w.window.graphicsConfiguration.bounds }
            println("Экран $screen, окно ${onEdt { w.window.bounds }}, режим ${w.mode}")
            // Окно занимает монитор целиком (на Маке — полный экран системы, иначе меню закрывало бы верх).
            waitFor("окно во весь монитор") { w.window.bounds.size == screen.size }
            assertFalse(onEdt { w.window.isAlwaysOnTop }, "окно оператора не поверх всех: Alt+Tab и ⌘Tab работают")

            // Мышь: курсор появляется, правая кнопка открывает меню с выходом.
            // Курсор прячется сам (настоящая мышь над окном могла его показать при появлении окна).
            waitFor("курсор спрятан", OutputWindow.CURSOR_HIDE_MS + 3_000L) { !w.cursorVisible }
            onEdt { val c = deepest(w); c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 10, 10, 0, false)) }
            assertTrue(onEdt { w.cursorVisible }, "курсор после движения мыши")
            onEdt {
                val c = deepest(w)
                c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), MouseEvent.BUTTON3_DOWN_MASK, 20, 20, 1, true, MouseEvent.BUTTON3))
                c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, 20, 20, 1, true, MouseEvent.BUTTON3))
            }
            waitFor("меню по правой кнопке") { w.openMenu != null }
            val labels = onEdt { w.openMenu!!.components.filterIsInstance<JMenuItem>().map { it.text } }
            println("Меню: $labels")
            assertTrue(labels.any { it.startsWith("Выйти") }, "в меню есть «Выйти»: $labels")
            assertTrue(labels.any { it.startsWith("В окно") }, "в меню есть «В окно»: $labels")
            onEdt { w.openMenu?.isVisible = false }

            // Esc — зал в окно с рамкой; F11 — обратно.
            onEdt { w.window.dispatchEvent(KeyEvent(w.window, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED)) }
            waitFor("окно после Esc") { app.windowsForTest().singleOrNull()?.mode == WindowMode.WINDOWED }
            val windowed = onEdt { app.windowsForTest().single() }
            waitFor("окно с рамкой на экране") { windowed.window.isShowing && !windowed.window.isUndecorated }
            assertTrue(onEdt { windowed.window.bounds.width < screen.width }, "окно меньше экрана")
            onEdt { windowed.window.dispatchEvent(KeyEvent(windowed.window, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_F11, KeyEvent.CHAR_UNDEFINED)) }
            waitFor("снова весь экран после F11") { app.windowsForTest().singleOrNull()?.mode == expected }
            val full = onEdt { app.windowsForTest().single() }
            try {
                waitFor("весь экран после F11") { full.window.isShowing && full.window.bounds.size == screen.size }
            } catch (e: AssertionError) {
                throw AssertionError("${e.message}: окно ${onEdt { full.window.bounds }}, видно ${onEdt { full.window.isShowing }}, экран $screen", e)
            }

            // Выход: Ctrl+Q и ⌘Q.
            val mod = if (MacScreen.isMac) KeyEvent.META_DOWN_MASK else KeyEvent.CTRL_DOWN_MASK
            onEdt { full.window.dispatchEvent(KeyEvent(full.window, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), mod, KeyEvent.VK_Q, KeyEvent.CHAR_UNDEFINED)) }
            assertEquals(0, exits.get(), "выход по ${if (MacScreen.isMac) "⌘Q" else "Ctrl+Q"}")
            waitFor("окна закрыты после выхода") { app.windowsForTest().isEmpty() }
        } finally {
            if (exits.get() < 0) app.quit()
        }
    }

    @Test
    fun roleChooserIsAnOrdinaryWindowAndAnswers() {
        assumeGui()
        var picked: Role? = null
        var answered = false
        val frame = onEdt { RoleChooser.show(null) { picked = it; answered = true } }
        waitFor("окно выбора на экране") { frame.isShowing }
        assertFalse(onEdt { frame.isUndecorated }, "окно выбора с рамкой: его можно закрыть")
        onEdt {
            val card = find(frame.contentPane as JComponent, "role-BOTH") ?: error("нет карточки")
            card.dispatchEvent(MouseEvent(card, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false, MouseEvent.BUTTON1))
        }
        waitFor("выбор принят") { answered }
        assertEquals(Role.BOTH, picked)
        assertFalse(onEdt { frame.isDisplayable })

        // Закрытое окно — ответ null (программа выходит, а не висит).
        answered = false
        val again = onEdt { RoleChooser.show(Role.SCREEN) { picked = it; answered = true } }
        waitFor("окно выбора снова") { again.isShowing }
        onEdt { again.dispatchEvent(java.awt.event.WindowEvent(again, java.awt.event.WindowEvent.WINDOW_CLOSING)) }
        waitFor("закрытие окна выбора") { answered }
        assertNull(picked)
    }

    private fun find(c: java.awt.Container, name: String): Component? {
        for (child in c.components) {
            if (child.name == name) return child
            if (child is java.awt.Container) find(child, name)?.let { return it }
        }
        return null
    }
}
