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
import java.awt.Component
import java.awt.Cursor
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Как окно вывода занимает монитор. */
enum class WindowMode {
    /** Окно на рабочем столе — для проверки (`--windowed`). */
    WINDOWED,
    /** Полноэкранный режим системы: поверх панелей и меню. Только для мониторов зала без пульта. */
    EXCLUSIVE,
    /**
     * Полный экран macOS: окно в своём пространстве, меню и Dock выезжают у края, ⌘Q и ⌘Tab работают.
     * Так зал занимает монитор, на котором работает и оператор, и ничего не запирает.
     */
    NATIVE,
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
    /** Оператор попросил окно ([windowed] = true) или снова весь экран на этом мониторе. */
    fun setWindowed(windowId: String, windowed: Boolean)
    /** Меню по правой кнопке на мониторе оператора. */
    fun menu(windowId: String): JPopupMenu
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
    /**
     * Монитор, за которым сидит оператор (единственный или главный): мышь показывает курсор,
     * правая кнопка открывает меню, Esc уводит зал в окно. На остальных мониторах зала ничего этого нет.
     */
    val shared: Boolean,
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
    private var videoPane: VideoPane? = null
    private var playingSha: String? = null
    private var state: FrameState? = null
    private var drawnKey: Any? = null
    private var videoKey: Any? = null
    private var renderSeq = 0L

    /**
     * Клавиши окна. Перехват до компонентов: фокус может быть где угодно внутри окна, в том числе на видео.
     * Выход — Ctrl+Q и ⌘Q; Esc — показ в окно (показ не прерывается), F11 и Ctrl+⌘F — обратно во весь экран.
     */
    private val keys = KeyEventDispatcher { e ->
        if (e.id != KeyEvent.KEY_PRESSED || !ownsEvent(e)) return@KeyEventDispatcher false
        val action = keyAction(e.keyCode, e.modifiersEx, mode, shared) ?: return@KeyEventDispatcher false
        when (action) {
            KeyAction.QUIT -> host.quit()
            KeyAction.OPERATOR -> host.openOperator()
            KeyAction.WINDOWED -> host.setWindowed(id, true)
            KeyAction.FULL_SCREEN -> host.setWindowed(id, false)
        }
        true
    }

    init {
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.background = Color.BLACK
        cards.background = Color.BLACK
        cards.add(slide, CARD_SLIDE)
        frame.contentPane = cards
        // Полный экран macOS требует обычного окна с заголовком: система сама убирает рамку.
        if (mode == WindowMode.EXCLUSIVE || mode == WindowMode.COVER) frame.isUndecorated = true
        // На выводе курсора нет; на мониторе оператора он появляется, когда двигают мышь.
        if (mode != WindowMode.WINDOWED) frame.cursor = HIDDEN_CURSOR
        if (mode == WindowMode.NATIVE) MacScreen.allowFullScreen(frame)
        // Окна на мониторах зала не забирают фокус у пульта: щелчок по пульту не сворачивает зал.
        frame.focusableWindowState = focusable
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys)
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            // Крестик, Alt+F4, ⌘W: окно, с которым работает оператор, закрывается вместе с программой.
            override fun windowClosing(e: java.awt.event.WindowEvent?) { if (focusable) host.quit() }
        })
        if (shared) listenMouse(slide)
        slide.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) { drawnKey = null; state?.let(::redraw) }
        })
        place()
    }


    private fun ownsEvent(e: KeyEvent): Boolean {
        val c = e.component ?: return false
        return c === frame || SwingUtilities.getWindowAncestor(c) === frame
    }

    /** Курсор виден, пока мышь движется; правая кнопка (и Ctrl+щелчок на Маке) — меню; двойной щелчок — окно или весь экран. */
    private fun listenMouse(c: Component) {
        val l = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) = showCursor()
            override fun mouseDragged(e: MouseEvent) = showCursor()
            override fun mousePressed(e: MouseEvent) { showCursor(); if (e.isPopupTrigger) popup(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) popup(e) }
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) host.setWindowed(id, mode != WindowMode.WINDOWED)
            }
        }
        c.addMouseListener(l)
        c.addMouseMotionListener(l)
    }

    private val cursorTimer = Timer(CURSOR_HIDE_MS) { if (mode != WindowMode.WINDOWED && menuOpen?.isVisible != true) frame.cursor = HIDDEN_CURSOR }
        .apply { isRepeats = false }

    private fun showCursor() {
        if (mode == WindowMode.WINDOWED) return
        if (frame.cursor !== Cursor.getDefaultCursor()) frame.cursor = Cursor.getDefaultCursor()
        cursorTimer.restart()
    }

    private var menuOpen: JPopupMenu? = null

    private fun popup(e: MouseEvent) {
        menuOpen?.isVisible = false
        val m = host.menu(id)
        menuOpen = m
        m.show(e.component, e.x, e.y)
    }

    /** Курсор сейчас виден (для проверки). */
    internal val cursorVisible: Boolean get() = frame.cursor !== HIDDEN_CURSOR

    /** Окно Swing (для проверки). */
    internal val window: JFrame get() = frame

    /** Открытое меню (для проверки). */
    internal val openMenu: JPopupMenu? get() = menuOpen?.takeIf { it.isVisible }

    private fun place() {
        val d = device
        when {
            mode == WindowMode.WINDOWED || d == null -> {
                frame.setSize(WINDOWED_WIDTH, WINDOWED_HEIGHT)
                if (d == null) frame.setLocationRelativeTo(null) else {
                    val b = d.defaultConfiguration.bounds
                    frame.setLocation(b.x + (b.width - WINDOWED_WIDTH) / 2, b.y + (b.height - WINDOWED_HEIGHT) / 2)
                }
                frame.isVisible = true
            }
            mode == WindowMode.NATIVE -> {
                // Сначала обычное окно посреди монитора: весь монитор оно займёт, только перейдя в полный экран.
                val b = d.defaultConfiguration.bounds
                frame.setBounds(b.x + (b.width - WINDOWED_WIDTH) / 2, b.y + (b.height - WINDOWED_HEIGHT) / 2, WINDOWED_WIDTH, WINDOWED_HEIGHT)
                frame.isVisible = true
                // Окно должно успеть появиться на экране, иначе система не переводит его в полный экран.
                // Если полный экран macOS недоступен, окно остаётся по размеру монитора — с меню и Dock, но не запертым.
                Timer(NATIVE_DELAY_MS) {
                    if (frame.isDisplayable && !MacScreen.toggleFullScreen(frame)) System.err.println("Полный экран macOS недоступен: окно по размеру монитора")
                }.apply { isRepeats = false }.start()
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
        if (l != OutputLayout.HALL) releaseVideo()
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
        v.view?.let { it.background = Color.BLACK }
        val pane = VideoPane(v)
        videoPane = pane
        cards.add(pane, CARD_VIDEO)
        cards.revalidate()
        v.surface?.let { s -> s.isFocusable = false; if (shared) listenMouse(s) }
    }

    private fun releaseVideo() {
        val v = video ?: return
        runCatching { v.stop() }
        videoPane?.let { cards.remove(it) }
        videoPane = null
        runCatching { v.release() }
        video = null
        playingSha = null
        showSlide()
    }

    private fun applyVideo(s: FrameState) {
        if (primary) host.reportError(null)
        val content = s.content
        // VLC запускается, только когда понадобился: программа без видео не зависит от него вовсе.
        if (content is VideoContent || content is ScreenContent) ensureVideo()
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
                videoPane?.reset()
                // Монитор подключили посреди фильма: он подхватывает с того же места.
                v.play(file, content.file.name, content.loop, s.video?.positionMs ?: content.startMs, audio = primary)
            }
            // Как кадр ложится на экран и чем заполнены поля — меняется и без перезапуска фильма.
            videoPane?.let { p -> p.fit = content.fit; p.backdrop = content.backdrop; p.backdropColor = content.backdropColor }
            if (s.mode == Mode.NORMAL) { v.unhide(); showVideo() } else { v.hide(); showSlide() }
        } else if (content is ScreenContent && v != null && v.available) {
            if (playingSha != STREAM) {
                if (playingSha != null && primary) host.reportVideo(null)
                playingSha = STREAM
                v.playStream(host.streamUrl())
            }
            // «Чёрный» и «Логотип» закрывают трансляцию, но поток не рвут: картинка вернётся сразу.
            videoPane?.let { p -> p.fit = app.deixis.core.VideoFit.FIT; p.backdrop = app.deixis.core.VideoBackdrop.BLACK }
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
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys)
        cursorTimer.stop()
        menuOpen?.isVisible = false
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

    internal companion object {
        const val CARD_SLIDE = "slide"
        const val CARD_VIDEO = "video"
        /** Метка в playingSha: в проигрывателе идёт трансляция экрана, а не файл. */
        const val STREAM = "screen"
        const val STREAM_RESTART_MS = 300
        const val WINDOWED_WIDTH = 960
        const val WINDOWED_HEIGHT = 540
        const val CURSOR_HIDE_MS = 2_000
        const val NATIVE_DELAY_MS = 300
        val HIDDEN_CURSOR: Cursor = Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "none")
    }
}

enum class KeyAction { QUIT, OPERATOR, WINDOWED, FULL_SCREEN }

/**
 * Что делает клавиша в окне вывода. Выход и пульт — на любом окне с фокусом; Esc, F11 и Ctrl+⌘F — только
 * на мониторе оператора, где зал можно увести в окно, не обрывая показ.
 */
fun keyAction(code: Int, mods: Int, mode: WindowMode, shared: Boolean): KeyAction? {
    val ctrl = mods and KeyEvent.CTRL_DOWN_MASK != 0
    val meta = mods and KeyEvent.META_DOWN_MASK != 0
    val command = ctrl || meta
    return when {
        code == KeyEvent.VK_Q && command -> KeyAction.QUIT
        code == KeyEvent.VK_O && command -> KeyAction.OPERATOR
        !shared -> null
        code == KeyEvent.VK_ESCAPE && mode != WindowMode.WINDOWED -> KeyAction.WINDOWED
        code == KeyEvent.VK_F11 || (code == KeyEvent.VK_F && ctrl && meta) ->
            if (mode == WindowMode.WINDOWED) KeyAction.FULL_SCREEN else KeyAction.WINDOWED
        else -> null
    }
}
