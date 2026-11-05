package app.deixis.desktop

import app.deixis.core.ConnectRequest
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.WindowConstants

/**
 * Окно «Новое подключение» на мониторе оператора компьютера, когда окно пульта закрыто:
 * устройство с верным PIN ждёт «Разрешить» здесь. На монитор зала окно не выводится никогда.
 */
class ApprovalWindow(private val answer: (id: String, allow: Boolean) -> Unit) {
    private var dialog: JDialog? = null
    private var shownId: String? = null

    /** Показывает первый запрос из [requests] на [area] или убирает окно, если ждать некому или негде. */
    fun update(requests: List<ConnectRequest>, area: Rectangle?) {
        val req = requests.firstOrNull()
        if (req == null || area == null) { hide(); return }
        if (shownId == req.id && dialog?.isVisible == true) return
        hide()
        shownId = req.id
        val d = JDialog(null as java.awt.Frame?, Words.newConnect, false)
        AppIcon.apply(d)
        d.isAlwaysOnTop = true
        d.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        val body = JPanel(BorderLayout(0, GAP))
        body.border = BorderFactory.createEmptyBorder(PAD, PAD, PAD, PAD)
        val who = JLabel("<html><b>${esc(req.sender.ifBlank { Words.browser })}</b><br>${esc(req.address)}</html>")
        val more = requests.size - 1
        val ask = JLabel("<html>${esc(Words.newConnectAsk)}${if (more > 0) " " + esc(Words.moreWaiting(more)) else ""}</html>")
        body.add(who, BorderLayout.NORTH)
        body.add(ask, BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, GAP, 0))
        val no = JButton(Words.reject).apply { addActionListener { answer(req.id, false); this@ApprovalWindow.hide() } }
        val yes = JButton(Words.allow).apply { addActionListener { answer(req.id, true); this@ApprovalWindow.hide() } }
        buttons.add(no); buttons.add(yes)
        body.add(buttons, BorderLayout.SOUTH)
        d.contentPane.add(body)
        d.pack()
        d.setSize(maxOf(d.width, WIDTH), d.height)
        d.setLocation(area.x + (area.width - d.width) / 2, area.y + (area.height - d.height) / 2)
        d.rootPane.defaultButton = null
        dialog = d
        d.isVisible = true
        d.toFront()
    }

    fun hide() {
        dialog?.dispose()
        dialog = null
        shownId = null
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val PAD = 20
        const val GAP = 12
        const val WIDTH = 420
    }
}
