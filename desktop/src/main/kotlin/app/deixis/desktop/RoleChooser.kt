package app.deixis.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.AbstractAction

/** Чем работает этот компьютер. Выбирается при первом запуске и меняется из меню. */
enum class Role(val title: String, val note: String) {
    REMOTE("Пульт", "Управлять показом отсюда. Экраны — телевизоры, проекторы и компьютеры Deixis в сети."),
    SCREEN("Экран", "Показывать в зал во весь экран. Управлять — с телефона или другого компьютера."),
    BOTH("Пульт и экран", "Один монитор — зал в окне рядом с пультом. Несколько — зал на втором мониторе."),
}

/** Роль в текстовом файле. Испорченный файл — как первый запуск. */
class RoleStore(private val file: File) {
    fun load(): Role? = runCatching { Role.valueOf(file.readText().trim()) }.getOrNull()

    fun save(role: Role) {
        runCatching { file.parentFile?.mkdirs(); file.writeText(role.name) }
    }
}

/** Цвета и размеры окна выбора: тёмная тема дизайн-кода (DESIGN.md). */
private object ChooserTokens {
    val background: Color get() = OutputTokens.black
    val card: Color get() = OutputTokens.cell
    val cardHover = Color(0x2C2C2E)
    val ink: Color get() = OutputTokens.ink
    val l2: Color get() = OutputTokens.l2
    val tint: Color get() = OutputTokens.tint
    const val WIDTH = 720
    const val HEIGHT = 460
    const val PAD = 28
    const val GAP = 12
    const val CARD_PAD = 18
    const val TITLE = 24f
    const val CARD_TITLE = 18f
    const val NOTE = 13f
}

/**
 * Окно «Как работает этот компьютер?»: обычное окно с рамкой, его можно закрыть, свернуть, перетащить.
 * Три карточки, клавиши 1–3, Esc — закрыть без выбора.
 */
object RoleChooser {

    /** Показывает окно; [onPick] получает выбор или null, если окно закрыли. Только в потоке окон. */
    fun show(current: Role?, onPick: (Role?) -> Unit): JFrame {
        val t = ChooserTokens
        val frame = JFrame("Deixis")
        var done = false
        fun finish(r: Role?) {
            if (done) return
            done = true
            frame.dispose()
            onPick(r)
        }
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) = finish(null)
        })
        val root = JPanel(BorderLayout(0, t.PAD))
        root.background = t.background
        root.border = BorderFactory.createEmptyBorder(t.PAD, t.PAD, t.PAD, t.PAD)
        root.add(label("Как работает этот компьютер?", t.TITLE, t.ink), BorderLayout.NORTH)
        val cards = JPanel(GridLayout(1, Role.entries.size, t.GAP, 0))
        cards.isOpaque = false
        Role.entries.forEachIndexed { i, r -> cards.add(card(i + 1, r, r == current) { finish(r) }) }
        root.add(cards, BorderLayout.CENTER)
        root.add(label("Изменить можно потом: правая кнопка мыши на экране зала или значок Deixis.", t.NOTE, t.l2), BorderLayout.SOUTH)
        val im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        Role.entries.forEachIndexed { i, r ->
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_1 + i, 0), r.name)
            root.actionMap.put(r.name, action { finish(r) })
        }
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close")
        root.actionMap.put("close", action { finish(null) })
        frame.contentPane = root
        frame.size = Dimension(t.WIDTH, t.HEIGHT)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        frame.toFront()
        return frame
    }

    private fun card(n: Int, role: Role, current: Boolean, onClick: () -> Unit): JComponent {
        val t = ChooserTokens
        val p = JPanel()
        p.layout = BoxLayout(p, BoxLayout.Y_AXIS)
        p.background = t.card
        p.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(if (current) t.tint else t.card, 2),
            BorderFactory.createEmptyBorder(t.CARD_PAD, t.CARD_PAD, t.CARD_PAD, t.CARD_PAD),
        )
        p.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        p.name = "role-${role.name}"
        p.add(label("$n", t.NOTE, t.tint))
        p.add(label(role.title, t.CARD_TITLE, t.ink))
        // Перенос строк в карточке: ширина текста — ширина карточки без полей.
        val textWidth = (t.WIDTH - 2 * t.PAD - (Role.entries.size - 1) * t.GAP) / Role.entries.size - 2 * t.CARD_PAD - t.GAP
        p.add(label("<html><body style='width:${textWidth}px'>${role.note}</body></html>", t.NOTE, t.l2))
        p.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick()
            override fun mouseEntered(e: MouseEvent) { p.background = t.cardHover }
            override fun mouseExited(e: MouseEvent) { p.background = t.card }
        })
        return p
    }

    private fun label(text: String, size: Float, color: Color) = JLabel(text).apply {
        font = Fonts.ui.deriveFont(Font.PLAIN, size)
        foreground = color
        border = BorderFactory.createEmptyBorder(0, 0, ChooserTokens.GAP / 2, 0)
    }

    private fun action(block: () -> Unit) = object : AbstractAction() {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) = block()
    }
}
