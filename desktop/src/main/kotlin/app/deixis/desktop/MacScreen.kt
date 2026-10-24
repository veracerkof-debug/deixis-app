package app.deixis.desktop

import java.awt.Desktop
import java.awt.PopupMenu
import java.awt.Taskbar
import java.awt.Window
import javax.swing.JFrame

/**
 * macOS: полный экран системы, ⌘Q, меню Dock. Полный экран берётся из com.apple.eawt — для него нужен ключ
 * `--add-exports java.desktop/com.apple.eawt=ALL-UNNAMED` (он есть в запуске программы); без ключа окно
 * просто встаёт по размеру монитора. На других системах всё здесь ничего не делает.
 */
object MacScreen {
    val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    fun allowFullScreen(frame: JFrame) {
        if (!isMac) return
        frame.rootPane.putClientProperty("apple.awt.fullscreenable", true)
        runCatching {
            Class.forName("com.apple.eawt.FullScreenUtilities")
                .getMethod("setWindowCanFullScreen", Window::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, frame, true)
        }
    }

    /** Вход в полный экран или выход из него. false — недоступно. */
    fun toggleFullScreen(w: Window): Boolean {
        if (!isMac) return false
        return runCatching {
            val cls = Class.forName("com.apple.eawt.Application")
            val app = cls.getMethod("getApplication").invoke(null)
            cls.getMethod("requestToggleFullScreen", Window::class.java).invoke(app, w)
            true
        }.getOrDefault(false)
    }

    /**
     * Выход из программы по ⌘Q и «Завершить» в Dock — через тот же путь, что и «Выйти» в меню, с остановкой
     * сервера. Щелчок по значку в Dock, когда окон нет (роль «пульт»), открывает пульт.
     */
    fun install(quit: () -> Unit, reopen: () -> Unit, dock: PopupMenu?) {
        if (!Desktop.isDesktopSupported()) return
        val d = Desktop.getDesktop()
        if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) runCatching { d.setQuitHandler { _, response -> quit(); response.performQuit() } }
        if (d.isSupported(Desktop.Action.APP_EVENT_REOPENED)) runCatching { d.addAppEventListener(java.awt.desktop.AppReopenedListener { reopen() }) }
        if (dock != null && Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.MENU)) {
            runCatching { Taskbar.getTaskbar().menu = dock }
        }
    }
}
