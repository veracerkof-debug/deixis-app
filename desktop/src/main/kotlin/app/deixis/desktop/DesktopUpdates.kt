package app.deixis.desktop

import app.deixis.core.ChangelogEntry
import app.deixis.core.UpdateChannel
import app.deixis.core.UpdateHost
import app.deixis.core.UpdateMode
import app.deixis.core.UpdateTarget
import app.deixis.core.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.io.File
import java.io.IOException
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/** Что нужно обновлениям от программы. */
interface UpdateApp {
    /** На экранах сейчас что-то показывается. */
    fun showing(): Boolean
    /** Закрыть программу (после запуска установщика). */
    fun exitForUpdate()
}

/**
 * Обновления Deixis для компьютера: установщик из выпуска GitHub. Windows — .msi через msiexec (система
 * спросит разрешение), Mac — .dmg: новая программа копируется на место старой, Linux — .deb открывается
 * установщиком системы. После установки Deixis запускается снова.
 *
 * Когда ставить: по кнопке — сразу (если идёт показ, программа предупредит); «Автоматически» — скачать
 * и поставить при выходе из программы, чтобы показ не прерывался и окно системы не появилось в зале.
 */
class DesktopUpdates(
    private val version: String,
    dataDir: File,
    scope: CoroutineScope,
    private val app: UpdateApp,
    private val os: String = System.getProperty("os.name").orEmpty().lowercase(),
) : UpdateHost {
    val target: UpdateTarget = when {
        os.contains("win") -> UpdateTarget.WINDOWS
        os.contains("mac") -> UpdateTarget.MAC
        else -> UpdateTarget.LINUX
    }
    @Volatile private var relaunch = true
    val updater = Updater(version, target, File(dataDir, "updates"), scope) { file, _ -> runInstaller(file, relaunch) }

    override val state: StateFlow<app.deixis.core.UpdateState> get() = updater.state
    override fun check() = updater.check()
    override fun setSettings(s: app.deixis.core.UpdateSettings) = updater.setSettings(s)

    /** С пульта: только когда ничего не показывается — окно установщика появится на экране. */
    override fun install() {
        if (app.showing()) { updater.installCancelled("Сначала уберите показ с экранов: установка закроет программу"); return }
        relaunch = true
        updater.install()
    }

    fun start() = updater.start()

    /** Кнопка в окне «Обновления»: поставить сейчас и запустить снова (о показе окно уже спросило). */
    fun installNow() {
        relaunch = true
        updater.install()
    }

    /** Выход из программы: в режиме «Автоматически» скачанная версия ставится сейчас, без перезапуска. */
    fun installOnExit(): Boolean {
        val s = updater.state.value
        if (s.settings.mode != UpdateMode.AUTO || s.phase != "ready") return false
        relaunch = false
        updater.install()
        return true
    }

    /** Путь к запущенной программе: Deixis.exe или Deixis.app; null — запуск из сборки разработчика. */
    private fun appPath(): File? {
        val cmd = ProcessHandle.current().info().command().orElse(null)?.let(::File) ?: return null
        return when (target) {
            UpdateTarget.WINDOWS -> cmd.takeIf { it.name.equals("Deixis.exe", true) }
            UpdateTarget.MAC -> cmd.parentFile?.parentFile?.parentFile?.takeIf { it.name.endsWith(".app") }
            else -> null
        }
    }

    private fun runInstaller(file: File, relaunch: Boolean) {
        val app = appPath()
        val dir = file.parentFile
        when (target) {
            UpdateTarget.WINDOWS -> {
                val script = File(dir, "update-install.cmd")
                script.writeText(windowsScript(file.absoluteFile, if (relaunch) app?.absoluteFile else null), Charsets.UTF_8)
                ProcessBuilder("cmd", "/c", "start", "\"Deixis\"", "/min", script.absolutePath).start()
                this.app.exitForUpdate()
            }
            UpdateTarget.MAC -> {
                if (app == null || !app.parentFile.canWrite()) {
                    // Программа не в «Программах» или нет прав: открыть образ, перенести вручную.
                    Desktop.getDesktop().open(file)
                    throw IOException("Откройте образ и перенесите Deixis в «Программы» поверх старой версии")
                }
                val script = File(dir, "update-install.sh")
                script.writeText(macScript(file, app, relaunch))
                ProcessBuilder("/bin/sh", "-c", "nohup /bin/sh '${script.absolutePath}' >/dev/null 2>&1 &").start()
                this.app.exitForUpdate()
            }
            else -> {
                // Пакет .deb ставит установщик системы (Центр приложений или apt) с паролем пользователя.
                ProcessBuilder("xdg-open", file.absolutePath).start()
            }
        }
    }

    companion object {
        /** Ждёт выхода программы, ставит .msi с окном хода установки и запускает Deixis снова. */
        internal fun windowsScript(msi: File, app: File?): String = buildString {
            append("@echo off\r\n")
            append("chcp 65001 >nul\r\n")
            append("timeout /t 3 /nobreak >nul\r\n")
            append("msiexec /i \"${msi.path}\" /passive /norestart\r\n")
            if (app != null) append("start \"\" \"${app.path}\"\r\n")
        }

        /** Ждёт выхода программы, заменяет Deixis.app из образа (при сбое возвращает старую) и запускает. */
        internal fun macScript(dmg: File, app: File, relaunch: Boolean): String {
            fun q(f: File) = "'" + f.absolutePath.replace("'", "'\\''") + "'"
            val old = File(app.path + ".old")
            return """
                sleep 3
                MNT=${'$'}(mktemp -d)
                hdiutil attach -nobrowse -quiet -mountpoint "${'$'}MNT" ${q(dmg)} || exit 1
                rm -rf ${q(old)}
                if mv ${q(app)} ${q(old)} && cp -R "${'$'}MNT/Deixis.app" ${q(app)}; then rm -rf ${q(old)}; else rm -rf ${q(app)}; mv ${q(old)} ${q(app)}; fi
                hdiutil detach -quiet "${'$'}MNT"
                ${if (relaunch) "open ${q(app)}" else ""}
            """.trimIndent() + "\n"
        }
    }
}

/** Окно «Обновления» компьютера: версия, по кнопке или автоматически, канал, проверка и установка. */
object UpdatesWindow {
    private var open: JFrame? = null

    fun show(u: DesktopUpdates, app: UpdateApp) {
        open?.let { it.toFront(); return }
        val t = WindowTokens
        val frame = JFrame("Обновления Deixis").also(AppIcon::apply)
        val root = JPanel(BorderLayout(0, t.GAP))
        root.background = t.background
        root.border = BorderFactory.createEmptyBorder(t.PAD, t.PAD, t.PAD, t.PAD)
        val status = label("", t.TEXT, t.ink)
        val head = JPanel()
        head.layout = BoxLayout(head, BoxLayout.Y_AXIS)
        head.isOpaque = false
        head.add(label("Deixis ${u.state.value.current}", t.TITLE, t.ink))
        head.add(status)
        root.add(head, BorderLayout.NORTH)

        val mode = JComboBox(arrayOf("По кнопке", "Автоматически"))
        val channel = JComboBox(arrayOf("Стабильные", "Пробные"))
        val notes = JTextArea()
        notes.isEditable = false
        notes.lineWrap = true
        notes.wrapStyleWord = true
        notes.font = Fonts.ui.deriveFont(Font.PLAIN, t.TEXT)
        notes.background = t.card
        notes.foreground = t.ink
        val center = JPanel(BorderLayout(0, t.GAP))
        center.isOpaque = false
        val choices = JPanel(GridLayout(1, 2, t.GAP, 0))
        choices.isOpaque = false
        choices.add(mode)
        choices.add(channel)
        center.add(choices, BorderLayout.NORTH)
        center.add(JScrollPane(notes), BorderLayout.CENTER)
        root.add(center, BorderLayout.CENTER)

        val action = JButton()
        val foot = JPanel(BorderLayout(0, t.GAP / 2))
        foot.isOpaque = false
        foot.add(action, BorderLayout.NORTH)
        val hint = label("", t.NOTE, t.l2)
        foot.add(hint, BorderLayout.SOUTH)
        root.add(foot, BorderLayout.SOUTH)

        var syncing = false
        fun render() {
            val s = u.state.value
            syncing = true
            mode.selectedIndex = if (s.settings.mode == UpdateMode.AUTO) 1 else 0
            channel.selectedIndex = if (s.settings.channel == UpdateChannel.TEST) 1 else 0
            syncing = false
            status.text = when (s.phase) {
                "checking" -> "Проверка…"
                "uptodate" -> "Установлена последняя версия"
                "available" -> "Есть версия ${s.available}"
                "downloading" -> "Загрузка ${s.available}: ${(s.progress * 100).toInt()} %"
                "ready" -> "Версия ${s.available} скачана"
                "installing" -> "Установка ${s.available}…"
                "error" -> s.error ?: "Не удалось проверить"
                else -> " "
            }
            notes.text = s.notes.joinToString("\n") { "• $it" }
            val busy = s.phase in setOf("checking", "downloading", "installing")
            action.isEnabled = !busy
            action.text = when (s.phase) {
                "available" -> "Скачать и установить ${s.available}"
                "ready" -> "Установить ${s.available}"
                else -> "Проверить обновления"
            }
            hint.text = "<html>" + (if (s.settings.mode == UpdateMode.AUTO) "Автоматически: проверка при запуске и раз в сутки; скачанная версия ставится при выходе из программы."
            else "По кнопке: Deixis выходит в сеть, только когда нажата «Проверить обновления».") + "</html>"
        }
        mode.addActionListener { if (!syncing) u.setSettings(u.state.value.settings.copy(mode = if (mode.selectedIndex == 1) UpdateMode.AUTO else UpdateMode.MANUAL)) }
        channel.addActionListener { if (!syncing) u.setSettings(u.state.value.settings.copy(channel = if (channel.selectedIndex == 1) UpdateChannel.TEST else UpdateChannel.STABLE)) }
        action.addActionListener {
            val s = u.state.value
            if (s.phase == "available" || s.phase == "ready") {
                if (app.showing() && javax.swing.JOptionPane.showConfirmDialog(
                        frame, "Сейчас идёт показ. Установка закроет программу на минуту. Продолжить?", "Обновление",
                        javax.swing.JOptionPane.OK_CANCEL_OPTION,
                    ) != javax.swing.JOptionPane.OK_OPTION
                ) return@addActionListener
                u.installNow()
            } else u.check()
        }
        val timer = javax.swing.Timer(REFRESH_MS) { render() }
        timer.start()
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent?) { timer.stop(); open = null }
        })
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        frame.contentPane = root
        frame.size = Dimension(t.WIDTH, t.HEIGHT)
        frame.setLocationRelativeTo(null)
        render()
        frame.isVisible = true
        open = frame
    }

    /** «Что нового» один раз после обновления. */
    fun whatsNew(entries: List<ChangelogEntry>) {
        if (entries.isEmpty()) return
        val t = WindowTokens
        val frame = JFrame("Что нового в Deixis").also(AppIcon::apply)
        val root = JPanel(BorderLayout(0, t.GAP))
        root.background = t.background
        root.border = BorderFactory.createEmptyBorder(t.PAD, t.PAD, t.PAD, t.PAD)
        root.add(label("Что нового", t.TITLE, t.ink), BorderLayout.NORTH)
        val text = JTextArea(entries.joinToString("\n\n") { e -> "Версия ${e.version}\n" + e.lines.joinToString("\n") { "• $it" } })
        text.isEditable = false
        text.lineWrap = true
        text.wrapStyleWord = true
        text.font = Fonts.ui.deriveFont(Font.PLAIN, t.TEXT)
        text.background = t.card
        text.foreground = t.ink
        root.add(JScrollPane(text), BorderLayout.CENTER)
        val ok = JButton("Понятно")
        ok.addActionListener { frame.dispose() }
        root.add(ok, BorderLayout.SOUTH)
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        frame.contentPane = root
        frame.size = Dimension(t.WIDTH, t.HEIGHT)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        frame.toFront()
    }

    private fun label(text: String, size: Float, color: java.awt.Color) = JLabel(text).apply {
        font = Fonts.ui.deriveFont(Font.PLAIN, size)
        foreground = color
    }

    private const val REFRESH_MS = 500
}

/** Цвета и размеры обычных окон программы (выбор роли, обновления): тёмная тема дизайн-кода. */
object WindowTokens {
    val background: java.awt.Color get() = OutputTokens.black
    val card: java.awt.Color get() = OutputTokens.cell
    val ink: java.awt.Color get() = OutputTokens.ink
    val l2: java.awt.Color get() = OutputTokens.l2
    const val WIDTH = 560
    const val HEIGHT = 480
    const val PAD = 24
    const val GAP = 12
    const val TITLE = 22f
    const val TEXT = 14f
    const val NOTE = 12f
}
