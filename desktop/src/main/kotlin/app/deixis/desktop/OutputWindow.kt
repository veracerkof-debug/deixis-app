package app.deixis.desktop

import app.deixis.core.FileStore
import app.deixis.core.Mode
import app.deixis.core.OutputLayout
import app.deixis.core.ScreenContent
import app.deixis.core.VideoCommand
import app.deixis.core.VideoContent
import app.deixis.core.VideoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.CardLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Timer

/** Как окно вывода занимает монитор. */
enum class WindowMode {
    /** Окно на рабочем столе — для проверки (`--windowed`). */
    WINDOWED,
    /** Полноэкранный режим системы: поверх панелей и меню. */
    EXCLUSIVE,
    /** Окно без рамки точно по монитору; по желанию поверх остальных окон. */
    COVER,
}

/** Что окну вывода нужно от приёмника. */
interface OutputHost {
    val scope: CoroutineScope
    val snapshotDir: File?
    fun file(sha256: String): File?
    fun streamUrl(): String
    fun reportError(message: String?)
    fun reportVideo(state: VideoState?)
    fun quit()
    fun openOperator()
}

/**
 * Окно вывода на одном мониторе со своей раскладкой. Видео играет только раскладка «Зал».
 * Пульт и сбои пульта сюда не попадают: окно рисует только [FrameState]; ошибка кадра даёт чёрный экран.
 */
class OutputWindow(
    val id: String,
    private var device: GraphicsDevice?,
    layout: OutputLayout,
    val mode: WindowMode,
    private val topmost: Boolean,
    private val focusable: Boolean,
    files: FileStore,
    private val host: OutputHost,
) {
    var layout: OutputLayout = layout
        private set

    /**
     * Главный проигрыватель зала: даёт звук и сообщает пульту ход видео и ошибки.
     * Остальные мониторы зала показывают то же без звука.
     */
    var primary = false

    private val frames = OutputFrames(files)
    private val frame = JFrame("Deixis")
    private val cards = JPanel(CardLayout())
    private val slide = SlideView()
    private var video: VideoPlayer? = null
    private var playingSha: String? = null
    private var state: FrameState? = null
    private var drawnKey: Any? = null
    private var videoKey: Any? = null
    private var renderSeq = 0L

    init {
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.background = Color.BLACK
        cards.background = Color.BLACK
        cards.add(slide, CARD_SLIDE)
        frame.contentPane = cards
        if (mode != WindowMode.WINDOWED) {
            frame.isUndecorated = true
            // Курсора на выводе нет никогда.
            frame.cursor = Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "none")
        }
        // Окна на мониторах зала не забирают фокус у пульта: щелчок по пульту не сворачивает зал.
        frame.focusableWindowState = focusable
        bindKeys()
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent?) { if (mode == WindowMode.WINDOWED) host.quit() }
        })
        slide.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) { drawnKey = null; state?.let(::redraw) }
        })
        if (layout == OutputLayout.HALL) ensureVideo()
        place()
    }

    private fun bindKeys() {
        // Выход только по Ctrl+Q: случайное нажатие Esc не должно оборвать показ в зале.
        val im = cards.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Q, KeyEvent.CTRL_DOWN_MASK), "quit")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_O, KeyEvent.CTRL_DOWN_MASK), "operator")
        cards.actionMap.put("quit", action { host.quit() })
        cards.actionMap.put("operator", action { host.openOperator() })
    }

    private fun action(block: () -> Unit) = object : AbstractAction() {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) = block()
    }

    private fun place() {
        val d = device
        when {
            mode == WindowMode.WINDOWED || d == null -> {
                frame.setSize(WINDOWED_WIDTH, WINDOWED_HEIGHT)
                frame.setLocationRelativeTo(null)
                frame.isVisible = true
            }
            mode == WindowMode.EXCLUSIVE -> {
                frame.bounds = d.defaultConfiguration.bounds
                frame.isVisible = true
                if (d.isFullScreenSupported) d.fullScreenWindow = frame
            }
            else -> {
                frame.bounds = d.defaultConfiguration.bounds
                frame.isAlwaysOnTop = topmost
                frame.isVisible = true
            }
        }
    }

    /** Монитор переподключили или сменили разрешение: окно встаёт на его новое место. */
    fun moveTo(d: GraphicsDevice) {
        val old = device
        if (old === d && frame.bounds == d.defaultConfiguration.bounds) return
        if (old != null && old.fullScreenWindow === frame) old.fullScreenWindow = null
        device = d
        place()
    }

    fun setLayout(l: OutputLayout) {
        if (l == layout) return
        layout = l
        if (l == OutputLayout.HALL) ensureVideo() else releaseVideo()
        drawnKey = null
        videoKey = null
        state?.let(::update)
    }

    /** Новое состояние показа. Зал и титры перерисовываются, только если изменилось то, что они показывают. */
    fun update(s: FrameState) {
        state = s
        if (layout == OutputLayout.HALL) {
            val vk = s.content to s.mode
            if (vk != videoKey) { videoKey = vk; applyVideo(s) }
        }
        val key: Any = when (layout) {
            OutputLayout.HALL -> Triple(s.content, s.mode, s.idle)
            OutputLayout.STAGE -> s.copy(now = s.now / 1000)
            else -> s.content to s.mode
        }
        if (key != drawnKey) { drawnKey = key; redraw(s) }
    }

    fun videoCommand(cmd: VideoCommand) {
        if (playingSha != null && playingSha != STREAM) video?.apply(cmd)
    }

    /** Ход видео для пульта; null — сейчас не идёт файл. */
    fun videoState(): VideoState? = if (playingSha != null && playingSha != STREAM) video?.state() else null

    private fun ensureVideo() {
        if (video != null) return
        val v = VideoPlayer(
            onError = { msg -> if (playingSha == STREAM) restartStream() else { if (primary) host.reportError(msg); showSlide() } },
            onFinished = { if (playingSha == STREAM) restartStream() else if (primary) host.reportVideo(video?.state()) },
        )
        video = v
        v.view?.let { cards.add(it, CARD_VIDEO) }
    }

    private fun releaseVideo() {
        val v = video ?: return
        runCatching { v.stop() }
        v.view?.let { cards.remove(it) }
        runCatching { v.release() }
        video = null
        playingSha = null
        showSlide()
    }

    private fun applyVideo(s: FrameState) {
        if (primary) host.reportError(null)
        val content = s.content
        val v = video
        if (content is VideoContent) {
            if (v == null || !v.available) {
                if (primary) host.reportError("На этом компьютере не найден VLC: видео показать нельзя")
                playingSha = null
                showSlide()
                return
            }
            if (playingSha != content.file.sha256) {
                val file = host.file(content.file.sha256)
                if (file == null) { if (primary) host.reportError("Нет файла «${content.file.name}»"); return }
                playingSha = content.file.sha256
                // Монитор подключили посреди фильма: он подхватывает с того же места.
                v.play(file, content.file.name, content.loop, s.video?.positionMs ?: content.startMs, audio = primary)
            }
            if (s.mode == Mode.NORMAL) { v.unhide(); showVideo() } else { v.hide(); showSlide() }
        } else if (content is ScreenContent && v != null && v.available) {
            if (playingSha != STREAM) {
                if (playingSha != null && primary) host.reportVideo(null)
                playingSha = STREAM
                v.playStream(host.streamUrl())
            }
            // «Чёрный» и «Логотип» закрывают трансляцию, но поток не рвут: картинка вернётся сразу.
            if (s.mode == Mode.NORMAL) showVideo() else showSlide()
        } else {
            if (content is ScreenContent && primary) host.reportError("На этом компьютере не найден VLC: трансляцию экрана показать нельзя")
            if (playingSha != null) { v?.stop(); playingSha = null; if (primary) host.reportVideo(null) }
            showSlide()
        }
    }

    /** Поток экрана закончился (смена размера, сбой): открыть заново, пока трансляция на экране. */
    private fun restartStream() {
        Timer(STREAM_RESTART_MS) {
            if (playingSha == STREAM && state?.content is ScreenContent) video?.playStream(host.streamUrl())
        }.apply { isRepeats = false }.start()
    }

    private fun showVideo() = (cards.layout as CardLayout).show(cards, CARD_VIDEO)

    private fun showSlide() = (cards.layout as CardLayout).show(cards, CARD_SLIDE)

    private fun redraw(s: FrameState) {
        val w = slide.width.takeIf { it > 0 } ?: return
        val h = slide.height.takeIf { it > 0 } ?: return
        // Кадр в настоящих пикселях монитора: при масштабе 150 % текст не размывается.
        val scale = slide.graphicsConfiguration?.defaultTransform?.scaleX?.takeIf { it > 0 } ?: 1.0
        val pw = (w * scale).toInt()
        val ph = (h * scale).toInt()
        val seq = ++renderSeq
        val l = layout
        // Кадр готовится в фоне: тяжёлая страница PDF не должна подвешивать окно.
        host.scope.launch {
            val img = try {
                frames.render(l, pw, ph, s)
            } catch (e: Throwable) {
                if (primary || l != OutputLayout.HALL) host.reportError(e.message ?: "Не удалось показать файл")
                BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB).also { blank ->
                    if (l == OutputLayout.CHROMA) blank.createGraphics().run { color = Color(OutputTokens.CHROMA_ARGB.toInt(), true); fillRect(0, 0, pw, ph); dispose() }
                }
            }
            host.snapshotDir?.let { dir ->
                dir.mkdirs()
                withContext(Dispatchers.IO) { ImageIO.write(img, "png", File(dir, "frame-${l.name.lowercase()}-${System.currentTimeMillis()}.png")) }
            }
            withContext(Dispatchers.Swing) { if (renderSeq == seq) slide.frame = img }
        }
    }

    fun dispose() {
        releaseVideo()
        device?.let { if (it.fullScreenWindow === frame) it.fullScreenWindow = null }
        frame.dispose()
    }

    /** Освобождение при выходе из приложения (не из потока окна). */
    fun release() {
        runCatching { video?.release() }
    }

    private class SlideView : JPanel() {
        var frame: BufferedImage? = null
            set(value) { field = value; repaint() }

        init { background = Color.BLACK; isDoubleBuffered = true }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            (g as? Graphics2D)?.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            frame?.let { g.drawImage(it, 0, 0, width, height, null) }
        }
    }

    private companion object {
        const val CARD_SLIDE = "slide"
        const val CARD_VIDEO = "video"
        /** Метка в playingSha: в проигрывателе идёт трансляция экрана, а не файл. */
        const val STREAM = "screen"
        const val STREAM_RESTART_MS = 300
        const val WINDOWED_WIDTH = 960
        const val WINDOWED_HEIGHT = 540
    }
}
