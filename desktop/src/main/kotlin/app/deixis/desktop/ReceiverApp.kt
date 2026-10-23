package app.deixis.desktop

import app.deixis.core.Content
import app.deixis.core.Discovery
import app.deixis.core.FileStore
import app.deixis.core.FileTokenStore
import app.deixis.core.Mode
import app.deixis.core.Pairing
import app.deixis.core.PageRenderer
import app.deixis.core.loadViewKey
import app.deixis.core.PairingCode
import app.deixis.core.ReceiverCore
import app.deixis.core.ReceiverInfo
import app.deixis.core.ReceiverServer
import app.deixis.core.VideoContent
import app.deixis.core.HolyricsBridge
import app.deixis.core.BibleStore
import app.deixis.core.ScreenContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetAddress
import java.util.UUID
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer

data class ReceiverOptions(
    val name: String?,
    val port: Int,
    val screen: Int?,
    val windowed: Boolean,
    val dataDir: File,
    val snapshotDir: File?,
)

/** Deixis для компьютера в роли приёмника: полноэкранное окно на выбранном мониторе. */
class ReceiverApp(private val opts: ReceiverOptions) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val core: ReceiverCore
    private val renderer: SlideRenderer
    private val server: ReceiverServer
    private val pinFlow: MutableStateFlow<String>
    private var advertisement: AutoCloseable? = null
    private var port = 0
    private val address: InetAddress? = Discovery.localAddress()

    private lateinit var frame: JFrame
    private lateinit var cards: JPanel
    private val slide = SlideView()
    private lateinit var video: VideoPlayer
    private var playingSha: String? = null

    init {
        opts.dataDir.mkdirs()
        val idFile = File(opts.dataDir, "id")
        val id = idFile.takeIf { it.isFile }?.readText()?.trim() ?: UUID.randomUUID().toString().also { idFile.writeText(it) }
        val name = opts.name ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "Компьютер"
        val screen = Toolkit.getDefaultToolkit().screenSize
        // Компьютер может стоять рядом с Holyrics: тогда фото и видео из Holyrics берутся прямо из его папки.
        val info = ReceiverInfo(id, name, "desktop-" + System.getProperty("os.name").lowercase(), APP_VERSION, screen.width, screen.height, holyrics = true)
        val pairing = Pairing(FileTokenStore(File(opts.dataDir, "tokens.json")))
        core = ReceiverCore(info, FileStore(File(opts.dataDir, "files")), pairing, loadViewKey(File(opts.dataDir, "view-key")))
        pinFlow = MutableStateFlow(pairing.pin)
        pairing.onPinChanged = { pinFlow.value = it }
        renderer = SlideRenderer(core.files)
        core.pageRenderer = PageRenderer { file, page, width -> renderPdfPagePng(file, page, width) }
        core.holyrics = HolyricsBridge(core.files)
        // Для веб-пульта: модули Библии хранятся здесь, страницы PDF считаются здесь.
        core.bible = BibleStore(File(opts.dataDir, "bible")) { JdbcSql(it) }
        core.pageCounter = { f -> org.apache.pdfbox.Loader.loadPDF(f).use { it.numberOfPages } }
        server = ReceiverServer(core, opts.port)
    }

    fun run() {
        port = runBlocking { server.start() }
        advertisement = runCatching { Discovery.advertise(core.info, port, address) }
            .onFailure { System.err.println("Поиск в сети недоступен: ${it.message}") }
            .getOrNull()
        println("Deixis: «${core.info.name}» на ${address?.hostAddress ?: "127.0.0.1"}:$port, PIN ${core.pairing.pin}")
        SwingUtilities.invokeAndWait { buildWindow() }
        observe()
        Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
    }

    private fun buildWindow() {
        video = VideoPlayer(
            onError = { msg -> if (playingSha == STREAM) restartStream() else { core.reportError(msg); showSlide() } },
            onFinished = { if (playingSha == STREAM) restartStream() else core.reportVideo(video.state()) },
        )
        frame = JFrame("Deixis").apply {
            defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
            background = Color.BLACK
        }
        cards = JPanel(CardLayout()).apply { background = Color.BLACK }
        cards.add(slide, CARD_SLIDE)
        video.view?.let { cards.add(it, CARD_VIDEO) }
        frame.contentPane = cards

        // Выход только по Ctrl+Q: случайное нажатие Esc не должно оборвать показ в зале.
        cards.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_Q, KeyEvent.CTRL_DOWN_MASK), "quit")
        cards.actionMap.put("quit", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { shutdown(); System.exit(0) }
        })
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent?) { shutdown(); System.exit(0) }
        })

        val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        // По умолчанию — последний монитор: обычно это проектор, подключённый к ноутбуку.
        val device = devices[(opts.screen ?: devices.lastIndex).coerceIn(0, devices.lastIndex)]
        if (opts.windowed) {
            frame.setSize(960, 540)
            frame.setLocationRelativeTo(null)
            frame.isVisible = true
        } else {
            frame.isUndecorated = true
            frame.cursor = Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "none")
            frame.bounds = device.defaultConfiguration.bounds
            frame.isVisible = true
            if (device.isFullScreenSupported) device.fullScreenWindow = frame
        }
        slide.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) { redraw() }
        })
        Timer(500) { if (playingSha != null && playingSha != STREAM) core.reportVideo(video.state()) }.start()
    }

    private data class Key(val content: Content?, val mode: Mode, val pin: String)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observe() {
        scope.launch(Dispatchers.Swing) {
            core.videoCommands.collect { video.apply(it); core.reportVideo(video.state()) }
        }
        scope.launch(Dispatchers.Swing) {
            core.state.map { it.content to it.mode }
                .combine(pinFlow) { (c, m), pin -> Key(c, m, pin) }
                .distinctUntilChanged()
                .collect { apply(it) }
        }
    }

    private var current = Key(null, Mode.NORMAL, "")

    private fun apply(key: Key) {
        current = key
        core.reportError(null)
        val content = key.content
        if (content is VideoContent) {
            if (!video.available) {
                core.reportError("На этом компьютере не найден VLC: видео показать нельзя")
                playingSha = null
                showSlide()
                return
            }
            if (playingSha != content.file.sha256) {
                val file = core.files.file(content.file.sha256)
                if (file == null) { core.reportError("Нет файла «${content.file.name}»"); return }
                playingSha = content.file.sha256
                video.play(file, content.file.name, content.loop, content.startMs)
            }
            if (key.mode == Mode.NORMAL) { video.unhide(); showVideo() } else { video.hide(); showSlide() }
        } else if (content is ScreenContent && video.available) {
            if (playingSha != STREAM) {
                if (playingSha != null) core.reportVideo(null)
                playingSha = STREAM
                video.playStream(server.localStreamUrl(port))
            }
            // «Чёрный» и «Логотип» закрывают трансляцию, но поток не рвут: картинка вернётся сразу.
            if (key.mode == Mode.NORMAL) showVideo() else showSlide()
        } else {
            if (content is ScreenContent) core.reportError("На этом компьютере не найден VLC: трансляцию экрана показать нельзя")
            if (playingSha != null) { video.stop(); playingSha = null; core.reportVideo(null) }
            showSlide()
        }
    }

    /** Поток экрана закончился (смена размера, сбой): открыть заново, пока трансляция на экране. */
    private fun restartStream() {
        Timer(300) {
            if (playingSha == STREAM && current.content is ScreenContent) video.playStream(server.localStreamUrl(port))
        }.apply { isRepeats = false }.start()
    }

    private fun showVideo() {
        (cards.layout as CardLayout).show(cards, CARD_VIDEO)
    }

    private fun showSlide() {
        (cards.layout as CardLayout).show(cards, CARD_SLIDE)
        redraw()
    }

    private fun redraw() {
        val w = slide.width.takeIf { it > 0 } ?: return
        val h = slide.height.takeIf { it > 0 } ?: return
        val key = current
        val idle = IdleInfo(
            name = core.info.name,
            pin = key.pin,
            address = "${address?.hostAddress ?: "127.0.0.1"}:$port",
            pairingUri = address?.let { PairingCode(it.hostAddress, port, key.pin, core.info.id, core.info.name).toUri() },
        )
        // Кадр готовится в фоне: тяжёлая страница PDF не должна подвешивать окно.
        scope.launch {
            val img = try {
                renderer.render(w, h, key.content.takeUnless { (it is VideoContent || it is ScreenContent) && key.mode == Mode.NORMAL }, key.mode, idle)
            } catch (e: Throwable) {
                core.reportError(e.message ?: "Не удалось показать файл")
                BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            }
            opts.snapshotDir?.let { dir ->
                dir.mkdirs()
                withContext(Dispatchers.IO) { ImageIO.write(img, "png", File(dir, "frame-${System.currentTimeMillis()}.png")) }
            }
            withContext(Dispatchers.Swing) { if (current == key) slide.frame = img }
        }
    }

    private fun shutdown() {
        runCatching { advertisement?.close() }
        runCatching { server.stop() }
        if (::video.isInitialized) runCatching { video.release() }
    }

    private class SlideView : JPanel() {
        var frame: BufferedImage? = null
            set(value) { field = value; repaint() }

        init { background = Color.BLACK; isDoubleBuffered = true }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            frame?.let { g.drawImage(it, 0, 0, width, height, null) }
        }
    }

    companion object {
        const val APP_VERSION = "0.1.0"
        private const val CARD_SLIDE = "slide"
        private const val CARD_VIDEO = "video"
        /** Метка в playingSha: в проигрывателе идёт трансляция экрана, а не файл. */
        private const val STREAM = "screen"
    }
}
