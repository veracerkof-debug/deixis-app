package app.deixis.desktop

import app.deixis.core.MonitorOutput
import app.deixis.core.MonitorRequest
import app.deixis.core.OutputLayout
import java.awt.CheckboxMenuItem
import java.awt.Desktop
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI

/**
 * Окно пульта: веб-пульт этого приёмника в отдельном окне браузера без вкладок и адресной строки.
 * Это отдельный процесс: закрытое окно пульта не останавливает ни приёмник, ни мониторы зала.
 */
object OperatorLauncher {

    /** Пути к браузерам на Chromium по порядку предпочтения: Windows — Edge, затем Chrome. */
    internal fun candidates(
        os: String,
        env: Map<String, String>,
        which: (String) -> String?,
    ): List<String> {
        val o = os.lowercase()
        return when {
            o.contains("win") -> {
                val roots = listOfNotNull(env["ProgramFiles(x86)"], env["ProgramFiles"], env["LOCALAPPDATA"])
                roots.map { "$it\\Microsoft\\Edge\\Application\\msedge.exe" } +
                    roots.map { "$it\\Google\\Chrome\\Application\\chrome.exe" } +
                    roots.map { "$it\\Chromium\\Application\\chrome.exe" }
            }
            o.contains("mac") -> listOf(
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Chromium.app/Contents/MacOS/Chromium",
                "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
            ).flatMap { listOf(it, (env["HOME"] ?: "") + it) }
            else -> listOf(
                "google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge", "microsoft-edge-stable",
            ).mapNotNull(which)
        }
    }

    /** Ключи окна-приложения: свой профиль, чтобы окно открывалось отдельно от обычного браузера с нужным размером. */
    internal fun arguments(exe: String, url: String, profile: File): List<String> = listOf(
        exe, "--app=$url", "--window-size=$WIDTH,$HEIGHT", "--user-data-dir=${profile.absolutePath}",
        "--no-first-run", "--no-default-browser-check",
    )

    /** Открывает пульт. Возвращает false, если не удалось ни одним способом. */
    fun open(url: String, profile: File): Boolean {
        val exes = candidates(System.getProperty("os.name").orEmpty(), System.getenv(), ::which).filter { File(it).canExecute() }
        for (exe in exes) {
            try {
                profile.mkdirs()
                ProcessBuilder(arguments(exe, url, profile))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                return true
            } catch (_: Exception) {
            }
        }
        return try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun which(name: String): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }.firstOrNull { it.canExecute() }?.absolutePath

    private const val WIDTH = 1400
    private const val HEIGHT = 900
}

/** Что меню в области уведомлений делает с приёмником. */
interface TrayActions {
    fun openOperator()
    fun monitors(): List<MonitorOutput>
    fun setMonitor(req: MonitorRequest)
    fun webScreenUrl(): String?
    fun quit()
    fun chooseRole()
    fun updates()
    /** Окно «Holyrics → телефон по QR». */
    fun holyrics()
}

/**
 * Значок в области уведомлений: «Открыть пульт», «Экраны» (мониторы и их раскладки), «Выйти».
 * Где области уведомлений нет (часть окружений Linux), остаются Ctrl+O и Ctrl+Q на окне вывода.
 */
class Tray private constructor(private val actions: TrayActions, private val icon: TrayIcon) {
    private val screens = Menu("Экраны")

    init {
        val popup = PopupMenu()
        popup.add(item("Открыть пульт") { actions.openOperator() })
        popup.add(screens)
        popup.add(item("Как работает компьютер…") { actions.chooseRole() })
        popup.add(item("Holyrics — телефон по QR…") { actions.holyrics() })
        popup.add(item("Обновления…") { actions.updates() })
        popup.addSeparator()
        popup.add(item("Выйти") { actions.quit() })
        icon.popupMenu = popup
        icon.addActionListener { actions.openOperator() }
        rebuild()
    }

    /** Мониторы сменились или оператор поменял раскладку в пульте. */
    fun rebuild() {
        screens.removeAll()
        for (m in actions.monitors()) {
            val menu = Menu(m.name + " · ${m.width}×${m.height}" + if (m.primary) " · пульт" else "")
            val on = CheckboxMenuItem("Показывать", m.enabled)
            on.addItemListener { actions.setMonitor(MonitorRequest(m.id, on.state, m.layout)) }
            menu.add(on)
            menu.addSeparator()
            for (l in OutputLayout.entries) {
                val choice = CheckboxMenuItem(l.title, m.layout == l)
                choice.addItemListener { actions.setMonitor(MonitorRequest(m.id, true, l)) }
                menu.add(choice)
            }
            screens.add(menu)
        }
        actions.webScreenUrl()?.let { url ->
            screens.addSeparator()
            screens.add(item("Скопировать ссылку «Экран в браузере»") {
                runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null) }
            })
        }
    }

    fun remove() = runCatching { SystemTray.getSystemTray().remove(icon) }

    private fun item(label: String, block: () -> Unit) = MenuItem(label).apply { addActionListener { block() } }

    companion object {
        fun install(actions: TrayActions): Tray? {
            if (!SystemTray.isSupported()) return null
            return try {
                val tray = SystemTray.getSystemTray()
                val icon = TrayIcon(iconImage(tray.trayIconSize.height.coerceAtLeast(16)), "Deixis")
                icon.isImageAutoSize = true
                Tray(actions, icon).also { tray.add(icon) }
            } catch (_: Exception) {
                null
            }
        }

        /** Значок — тот же, что у приложения на телефоне и у установщика (AppIcon). */
        private fun iconImage(size: Int): BufferedImage = AppIcon.image(size) ?: BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    }
}
