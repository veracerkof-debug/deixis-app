package app.deixis.desktop

import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.net.URI
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Окно пульта Deixis: пульт внутри самой программы, без браузера в системе. Страница пульта та же, что
 * у телефона в браузере, но идёт во встроенном движке OpenJFX и говорит с приёмником по петле 127.0.0.1 —
 * не зависит ни от установленного Chrome или Edge, ни от их обновлений и вкладок.
 * Это первый шаг к полностью своему окну: разделы будут заменяться нативными по одному.
 *
 * Окно — всегда на мониторе оператора (там, где нет зала), и интерфейс в зал не попадает.
 */
class OperatorWindow(private val onClosed: () -> Unit) {
    private var frame: JFrame? = null
    /** Ссылка на мост для страницы: без неё сборщик мусора убрал бы объект, и кнопки «Скопировать» замолчали бы. */
    private var bridge: Any? = null
    private var web: Any? = null

    val showing: Boolean get() = frame?.isVisible == true

    /** Открывает окно с [url] на мониторе [screen] (или выводит уже открытое вперёд). false — движок недоступен. */
    fun open(url: String, screen: java.awt.Rectangle?): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        frame?.let { f -> f.isVisible = true; f.toFront(); f.requestFocus(); return true }
        if (GraphicsEnvironment.isHeadless() || !available) return false
        return try {
            val panel = javafx.embed.swing.JFXPanel()
            javafx.application.Platform.setImplicitExit(false)
            val f = JFrame(Words.operatorTitle)
            AppIcon.apply(f)
            f.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            f.contentPane.add(panel)
            val area = screen ?: GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            val w = minOf(WIDTH, area.width - MARGIN * 2)
            val h = minOf(HEIGHT, area.height - MARGIN * 2)
            f.setBounds(area.x + (area.width - w) / 2, area.y + (area.height - h) / 2, w, h)
            f.minimumSize = java.awt.Dimension(MIN_WIDTH, MIN_HEIGHT)
            f.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) = close(notify = true)
            })
            frame = f
            javafx.application.Platform.runLater { load(panel, url) }
            f.isVisible = true
            true
        } catch (e: Throwable) {
            System.err.println("Окно пульта: ${e.message}")
            frame?.dispose(); frame = null
            false
        }
    }

    private fun load(panel: javafx.embed.swing.JFXPanel, url: String) {
        val view = javafx.scene.web.WebView()
        view.isContextMenuEnabled = false
        val engine = view.engine
        val host = Host()
        bridge = host
        web = view
        engine.loadWorker.stateProperty().addListener { _, _, st ->
            if (st == javafx.concurrent.Worker.State.SUCCEEDED) {
                runCatching {
                    val window = engine.executeScript("window") as netscape.javascript.JSObject
                    host.page = window
                    window.setMember("deixisHost", host)
                }
            } else if (st == javafx.concurrent.Worker.State.SCHEDULED) host.closeSockets()
        }
        // Ссылки наружу (справка, загрузки) — в обычном браузере, не в окне пульта.
        engine.locationProperty().addListener { _, _, loc ->
            if (loc != null && !loc.startsWith(url.substringBefore("/remote"))) {
                javafx.application.Platform.runLater { engine.load(url) }
                host.open(loc)
            }
        }
        engine.load(url)
        panel.scene = javafx.scene.Scene(view)
    }

    /**
     * Что страница пульта просит у окна: то, чего нет у встроенного движка. Свой WebSocket у движка OpenJFX
     * не работает, поэтому связь пульта с приёмником держит сама программа: страница шлёт строки сюда,
     * ответы приходят ей через window.__deixisWs. Только к самому приёмнику на 127.0.0.1.
     */
    inner class Host {
        @Volatile internal var page: netscape.javascript.JSObject? = null
        private val sockets = java.util.concurrent.ConcurrentHashMap<String, java.net.http.WebSocket>()
        private val seq = java.util.concurrent.atomic.AtomicLong()
        private val client by lazy {
            java.net.http.HttpClient.newBuilder().proxy(java.net.http.HttpClient.Builder.NO_PROXY)
                .connectTimeout(java.time.Duration.ofSeconds(CONNECT_S)).build()
        }

        private fun toPage(id: String, kind: String, data: String = "") = javafx.application.Platform.runLater {
            runCatching { page?.call("__deixisWs", id, kind, data) }
        }

        fun wsOpen(url: String): String {
            val id = "s" + seq.incrementAndGet()
            val uri = runCatching { URI(url) }.getOrNull()
            if (uri == null || uri.scheme != "ws" || uri.host != "127.0.0.1") { toPage(id, "close"); return id }
            client.newWebSocketBuilder().buildAsync(uri, object : java.net.http.WebSocket.Listener {
                private val text = StringBuilder()
                override fun onOpen(ws: java.net.http.WebSocket) {
                    sockets[id] = ws
                    toPage(id, "open")
                    ws.request(1)
                }
                override fun onText(ws: java.net.http.WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*>? {
                    text.append(data)
                    if (last) { val msg = text.toString(); text.setLength(0); toPage(id, "message", msg) }
                    ws.request(1)
                    return null
                }
                override fun onClose(ws: java.net.http.WebSocket, code: Int, reason: String): java.util.concurrent.CompletionStage<*>? {
                    sockets.remove(id); toPage(id, "close"); return null
                }
                override fun onError(ws: java.net.http.WebSocket, error: Throwable) { sockets.remove(id); toPage(id, "close") }
            }).whenComplete { _, e -> if (e != null) toPage(id, "close") }
            return id
        }

        fun wsSend(id: String, text: String) {
            val ws = sockets[id] ?: return
            // Отправки по одной: WebSocket клиента Java не принимает новую, пока не ушла прежняя.
            synchronized(ws) { runCatching { ws.sendText(text, true).join() } }
        }

        fun wsClose(id: String) {
            sockets.remove(id)?.let { runCatching { it.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "") } }
        }

        internal fun closeSockets() = sockets.keys.toList().forEach { wsClose(it) }

        fun copy(text: String) {
            runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
        }

        fun open(link: String) {
            if (!link.startsWith("http://") && !link.startsWith("https://")) return
            Thread { runCatching { Desktop.getDesktop().browse(URI(link)) } }.start()
        }

        fun close() = SwingUtilities.invokeLater { close(notify = true) }
    }

    /** Закрывает окно; [notify] — закрыл оператор (тогда программа решает, завершаться ли). */
    fun close(notify: Boolean) {
        val f = frame ?: return
        frame = null
        f.isVisible = false
        val view = web
        (bridge as? Host)?.closeSockets()
        web = null; bridge = null
        runCatching { javafx.application.Platform.runLater { (view as? javafx.scene.web.WebView)?.engine?.load(null) } }
        f.dispose()
        if (notify) onClosed()
    }

    companion object {
        private const val WIDTH = 1400
        private const val HEIGHT = 900
        private const val MIN_WIDTH = 720
        private const val MIN_HEIGHT = 520
        private const val MARGIN = 40
        private const val CONNECT_S = 4L

        /** Есть ли встроенный движок в этой сборке (без него — запасной путь через браузер). */
        val available: Boolean by lazy {
            System.getProperty("deixis.browserRemote") == null &&
                runCatching { Class.forName("javafx.scene.web.WebView", false, OperatorWindow::class.java.classLoader) }.isSuccess
        }
    }
}
