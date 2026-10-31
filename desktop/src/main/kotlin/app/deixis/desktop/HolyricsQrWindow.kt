package app.deixis.desktop

import app.deixis.core.HolyricsClient
import app.deixis.core.HolyricsDiscovery
import app.deixis.core.HolyricsLink
import app.deixis.core.qrMatrix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.GridLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField

/**
 * «Holyrics → телефон по QR»: компьютер рядом с Holyrics проверяет его API Server и показывает QR-код
 * с адресом и токеном. Камера телефона открывает его в Deixis — пульт Holyrics подключён без ввода.
 * Токен один раз копируется из Holyrics (его создаёт сам Holyrics) и хранится только на этом компьютере.
 */
object HolyricsQrWindow {
    private var open: JFrame? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    /** [lanHost] — адрес компьютера в сети: его телефон и увидит в QR. [dataDir] — где хранится токен. */
    fun show(lanHost: String?, dataDir: File) {
        open?.let { it.toFront(); return }
        val t = WindowTokens
        val tokenFile = File(dataDir, "holyrics-token")
        val frame = JFrame("Holyrics — телефон по QR").also(AppIcon::apply)
        val root = JPanel(BorderLayout(0, t.GAP))
        root.background = t.background
        root.border = BorderFactory.createEmptyBorder(t.PAD, t.PAD, t.PAD, t.PAD)

        val head = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
        val status = label("Ищем Holyrics на этом компьютере…", t.TEXT, t.l2)
        head.add(label("Пульт Holyrics на телефоне", t.TITLE, t.ink))
        head.add(status)
        root.add(head, BorderLayout.NORTH)

        val saved = runCatching { tokenFile.readText().trim() }.getOrNull().orEmpty()
        val port = JTextField(HolyricsClient.DEFAULT_PORT.toString(), 6)
        val token = JPasswordField(saved, 24)
        val check = JButton("Показать QR-код")
        val qr = QrPanel()
        val form = JPanel(GridLayout(0, 1, 0, t.GAP / 2)).apply { isOpaque = false }
        form.add(label("<html>Токен: в Holyrics «Файл → Настройки → API Server → Управление правами» создайте токен, дайте права на песни, тексты, медиа и показ и скопируйте его сюда.</html>", t.NOTE, t.l2))
        form.add(JPanel(BorderLayout(t.GAP, 0)).apply { isOpaque = false; add(token, BorderLayout.CENTER); add(port, BorderLayout.EAST) })
        form.add(check)
        val center = JPanel(BorderLayout(0, t.GAP)).apply { isOpaque = false }
        center.add(form, BorderLayout.NORTH)
        center.add(qr, BorderLayout.CENTER)
        root.add(center, BorderLayout.CENTER)
        root.add(label("<html>Наведите камеру телефона на код — Deixis откроет Holyrics сам. Код даёт управлять Holyrics: показывайте его только своим.</html>", t.NOTE, t.l2), BorderLayout.SOUTH)

        fun refresh() {
            val p = port.text.trim().toIntOrNull() ?: HolyricsClient.DEFAULT_PORT
            val tok = String(token.password).trim()
            check.isEnabled = false
            scope.launch {
                val found = HolyricsDiscovery.probe("127.0.0.1", p)
                if (!found) {
                    status.text = "Holyrics не отвечает: в Holyrics включите API Server (порт $p) и доступ по локальной сети"
                    qr.link = null
                } else if (tok.isEmpty()) {
                    status.text = "Holyrics найден. Вставьте токен и нажмите «Показать QR-код»"
                    qr.link = null
                } else {
                    // Токен проверяется входом в Holyrics: неверный — кода нет, верный — запоминается.
                    val version = withContext(Dispatchers.IO) { runCatching { HolyricsClient("127.0.0.1", p, tok).use { it.version().version } } }
                    version.onSuccess { v ->
                        runCatching { dataDir.mkdirs(); tokenFile.writeText(tok) }
                        val host = lanHost ?: "127.0.0.1"
                        status.text = "Holyrics $v · $host:$p"
                        qr.link = HolyricsLink(host, p, tok).toUri()
                    }.onFailure { e ->
                        status.text = "Holyrics не принял токен: ${e.message ?: "проверьте права токена"}"
                        qr.link = null
                    }
                }
                check.isEnabled = true
            }
        }
        check.addActionListener { refresh() }

        frame.contentPane = root
        frame.minimumSize = Dimension(t.WIDTH, t.HEIGHT + t.HEIGHT / 3)
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent?) { open = null }
        })
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        open = frame
        refresh()
    }

    /** QR-код ссылки deixis-holyrics:// на белом поле, по центру и по размеру окна. */
    private class QrPanel : JPanel() {
        var link: String? = null
            set(v) { field = v; matrix = v?.let { qrMatrix(it) }; repaint() }
        private var matrix: Array<BooleanArray>? = null

        init { isOpaque = false; preferredSize = Dimension(QR_SIZE, QR_SIZE) }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val m = matrix ?: return
            val side = minOf(width, height)
            val x0 = (width - side) / 2
            val y0 = (height - side) / 2
            g.color = Color.WHITE
            g.fillRect(x0, y0, side, side)
            val quiet = side / QUIET_DIV
            val cell = (side - 2 * quiet).toDouble() / m.size
            g.color = Color.BLACK
            for (r in m.indices) for (c in m[r].indices) if (m[r][c]) {
                g.fillRect(x0 + quiet + (c * cell).toInt(), y0 + quiet + (r * cell).toInt(), cell.toInt() + 1, cell.toInt() + 1)
            }
        }
    }

    private fun label(text: String, size: Float, color: Color) = JLabel(text).apply {
        font = Fonts.ui.deriveFont(Font.PLAIN, size)
        foreground = color
    }

    private const val QR_SIZE = 240
    private const val QUIET_DIV = 12
}
