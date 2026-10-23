package app.deixis.desktop

import app.deixis.core.BibleStore
import app.deixis.core.Conversions
import app.deixis.core.Discovery
import app.deixis.core.FileRelayStore
import app.deixis.core.FileStore
import app.deixis.core.FileTokenStore
import app.deixis.core.HolyricsBridge
import app.deixis.core.LinkRequest
import app.deixis.core.LinkResult
import app.deixis.core.MonitorOutput
import app.deixis.core.MonitorRequest
import app.deixis.core.OutputLayout
import app.deixis.core.OutputsHost
import app.deixis.core.OutputsState
import app.deixis.core.PageRenderer
import app.deixis.core.Pairing
import app.deixis.core.PairingCode
import app.deixis.core.ReceiverCore
import app.deixis.core.ReceiverInfo
import app.deixis.core.ReceiverServer
import app.deixis.core.ScreenRelay
import app.deixis.core.VideoState
import app.deixis.core.loadViewKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.io.File
import java.net.InetAddress
import java.util.UUID
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.system.exitProcess

data class ReceiverOptions(
    val name: String?,
    val port: Int,
    val screen: Int?,
    val windowed: Boolean,
    val dataDir: File,
    val snapshotDir: File?,
    /** Открыть окно пульта при запуске; null — само, если мониторов больше одного. */
    val operator: Boolean? = null,
)

/**
 * Deixis для компьютера: приёмник с несколькими выводами. Каждый включённый монитор — своё окно со своей
 * раскладкой (зал, сцена, титры, хромакей); показ пересылается и на экраны Deixis в сети.
 * Пульт — веб-пульт этого же приёмника в отдельном окне браузера.
 */
class ReceiverApp(private val opts: ReceiverOptions) : OutputHost {

    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val snapshotDir: File? get() = opts.snapshotDir
    private val core: ReceiverCore
    private val server: ReceiverServer
    private val pinFlow: MutableStateFlow<String>
    private val relay: ScreenRelay
    private val monitorStore = MonitorSettingsStore(File(opts.dataDir, "monitors.json"))
    @Volatile private var settings: MonitorSettings
    @Volatile private var screens: List<ScreenInfo> = emptyList()
    @Volatile private var primaryId: String? = null
    /** `--screen N`: зал только на этом мониторе, пока оператор не выберет иначе. */
    @Volatile private var screenOverride: Int? = opts.screen
    private var advertisement: AutoCloseable? = null
    private var browsing: Job? = null
    private var port = 0
    private val address: InetAddress? = Discovery.localAddress()

    /** Окна вывода по мониторам; меняются только в потоке окон. */
    private val windows = LinkedHashMap<String, OutputWindow>()
    private var tray: Tray? = null

    init {
        opts.dataDir.mkdirs()
        val idFile = File(opts.dataDir, "id")
        val id = idFile.takeIf { it.isFile }?.readText()?.trim() ?: UUID.randomUUID().toString().also { idFile.writeText(it) }
        val name = opts.name ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "Компьютер"
        val screen = Toolkit.getDefaultToolkit().screenSize
        // Компьютер может стоять рядом с Holyrics: тогда фото и видео из Holyrics берутся прямо из его папки.
        // PPTX и PPT переводятся в PDF всегда: PowerPoint, LibreOffice или Keynote этого компьютера,
        // без них — встроенный перевод. Документы и таблицы — только Office или LibreOffice.
        val engines = findPdfEngines()
        val info = ReceiverInfo(
            id, name, "desktop-" + System.getProperty("os.name").lowercase(), APP_VERSION, screen.width, screen.height,
            holyrics = true, convert = true, documents = engines.any { it.accepts(File("document.docx")) },
        )
        val pairing = Pairing(FileTokenStore(File(opts.dataDir, "tokens.json")))
        core = ReceiverCore(info, FileStore(File(opts.dataDir, "files")), pairing, loadViewKey(File(opts.dataDir, "view-key")))
        pinFlow = MutableStateFlow(pairing.pin)
        pairing.onPinChanged = { pinFlow.value = it }
        core.pageRenderer = PageRenderer { file, page, width -> renderPdfPagePng(file, page, width) }
        core.holyrics = HolyricsBridge(core.files)
        // Для веб-пульта: модули Библии хранятся здесь, страницы PDF считаются здесь.
        core.bible = BibleStore(File(opts.dataDir, "bible")) { JdbcSql(it) }
        core.pageCounter = { f -> org.apache.pdfbox.Loader.loadPDF(f).use { it.numberOfPages } }
        core.conversions = Conversions(core, OfficeConverter(engines), File(opts.dataDir, "convert"))
        settings = monitorStore.load()
        relay = ScreenRelay(core, FileRelayStore(File(opts.dataDir, "screens.json")), scope, name)
        core.outputs = DesktopOutputs()
        server = ReceiverServer(core, opts.port)
    }

    fun run() {
        port = runBlocking { server.start() }
        advertisement = runCatching { Discovery.advertise(core.info, port, address) }
            .onFailure { System.err.println("Поиск в сети недоступен: ${it.message}") }
            .getOrNull()
        // Экраны Deixis в сети для пересылки показа.
        browsing = scope.launch {
            runCatching { Discovery.browse(address).collect { relay.found(it) } }
        }
        println("Deixis: «${core.info.name}» на ${address?.hostAddress ?: "127.0.0.1"}:$port, PIN ${core.pairing.pin}")
        SwingUtilities.invokeAndWait {
            refreshScreens()
            tray = Tray.install(trayActions)
            reconcile()
            startTimers()
        }
        observe()
        Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
        if (opts.operator ?: (screens.size > 1)) openOperator()
    }

    // Мониторы

    private fun refreshScreens() {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        screens = ge.screenDevices.map { d ->
            val b = d.defaultConfiguration.bounds
            val m = runCatching { d.displayMode }.getOrNull()
            ScreenInfo(d.iDstring, b.x, b.y, m?.width?.takeIf { it > 0 } ?: b.width, m?.height?.takeIf { it > 0 } ?: b.height)
        }
        primaryId = runCatching { ge.defaultScreenDevice.iDstring }.getOrNull()
    }

    private fun monitors(): List<MonitorOutput> {
        val list = resolveMonitors(screens, primaryId, settings)
        val only = screenOverride ?: return list
        val index = only.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        return list.mapIndexed { i, m -> m.copy(enabled = i == index, layout = if (i == index) OutputLayout.HALL else m.layout) }
    }

    private fun windowMode(primary: Boolean, count: Int): WindowMode = when {
        opts.windowed -> WindowMode.WINDOWED
        // Один монитор — как раньше: полноэкранный режим системы.
        count <= 1 -> WindowMode.EXCLUSIVE
        // На Windows полноэкранное окно на втором мониторе сворачивается, когда фокус уходит к пульту,
        // поэтому там окно без рамки точно по монитору, поверх остальных.
        isWindows || primary -> WindowMode.COVER
        else -> WindowMode.EXCLUSIVE
    }

    /** Приводит окна вывода в соответствие с мониторами и выбором оператора. Только в потоке окон. */
    private fun reconcile() {
        if (opts.windowed) {
            if (windows.isEmpty()) {
                windows[WINDOWED_ID] = OutputWindow(WINDOWED_ID, null, OutputLayout.HALL, WindowMode.WINDOWED, false, true, core.files, this)
                    .also { it.primary = true; it.update(frameState()) }
            }
            return
        }
        val list = monitors()
        val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.associateBy { it.iDstring }
        val wanted = list.filter { it.enabled && it.id in devices }
        for (id in windows.keys.toList()) if (wanted.none { it.id == id }) windows.remove(id)?.dispose()
        for (m in wanted) {
            val device = devices.getValue(m.id)
            val mode = windowMode(m.primary, list.size)
            val w = windows[m.id]
            if (w == null || w.mode != mode) {
                w?.dispose()
                // Окна зала не забирают фокус у пульта; единственный монитор — как раньше, с Ctrl+Q.
                // Без значка в области уведомлений (часть окружений Linux) Ctrl+Q и Ctrl+O нужны на любом мониторе.
                val focusable = list.size <= 1 || tray == null
                windows[m.id] = OutputWindow(m.id, device, m.layout, mode, topmost = !m.primary && list.size > 1, focusable = focusable, files = core.files, host = this)
            } else {
                w.setLayout(m.layout)
                w.moveTo(device)
            }
        }
        // Звук и ход видео — от первого монитора зала.
        val hall = windows.values.firstOrNull { it.layout == OutputLayout.HALL }
        windows.values.forEach { it.primary = it === hall }
        val fs = frameState()
        windows.values.forEach { it.update(fs) }
        tray?.rebuild()
    }

    private fun startTimers() {
        // Мониторы подключают и отключают на ходу: окна следуют за ними.
        Timer(HOTPLUG_MS) {
            val before = screens to primaryId
            refreshScreens()
            if (before != screens to primaryId) reconcile()
        }.start()
        Timer(VIDEO_REPORT_MS) { hallWindow()?.videoState()?.let { core.reportVideo(it) } }.start()
        // Часы и время показа на «Сцене».
        Timer(CLOCK_MS) {
            val stage = windows.values.filter { it.layout == OutputLayout.STAGE }
            if (stage.isNotEmpty()) { val fs = frameState(); stage.forEach { it.update(fs) } }
        }.start()
    }

    private fun hallWindow() = windows.values.firstOrNull { it.primary }

    private fun frameState(): FrameState {
        val st = core.state.value
        val pin = pinFlow.value
        val idle = IdleInfo(
            name = core.info.name,
            pin = pin,
            address = "${address?.hostAddress ?: "127.0.0.1"}:$port",
            pairingUri = address?.let { PairingCode(it.hostAddress, port, pin, core.info.id, core.info.name).toUri() },
        )
        return FrameState(st.content, st.mode, st.next, st.video, core.showStartedAt, System.currentTimeMillis(), idle)
    }

    private fun observe() {
        scope.launch(Dispatchers.Swing) {
            core.videoCommands.collect { cmd ->
                windows.values.forEach { it.videoCommand(cmd) }
                hallWindow()?.videoState()?.let { core.reportVideo(it) }
            }
        }
        scope.launch(Dispatchers.Swing) {
            core.state.combine(pinFlow) { _, _ -> Unit }.collect {
                val fs = frameState()
                windows.values.forEach { it.update(fs) }
            }
        }
    }

    private fun setMonitor(req: MonitorRequest) {
        screenOverride = null
        settings = settings.with(req)
        runCatching { monitorStore.save(settings) }
        SwingUtilities.invokeLater { reconcile() }
    }

    private fun webScreenUrl(): String? = address?.let { "http://${it.hostAddress}:$port/view?k=${core.viewKey}&screen=1" }

    // Пульт

    /** Токен «Этот компьютер»: выдаётся один раз и хранится, как у любого сопряжённого пульта. */
    private fun operatorToken(): String {
        val file = File(opts.dataDir, "operator-token")
        file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { core.pairing.isValid(it) }?.let { return it }
        val token = (core.pairing.pair(core.pairing.pin, "Этот компьютер") as Pairing.Result.Ok).token
        runCatching { file.writeText(token) }
        return token
    }

    override fun openOperator() {
        scope.launch(Dispatchers.IO) {
            val url = "http://127.0.0.1:$port/remote#t=${operatorToken()}"
            if (!OperatorLauncher.open(url, File(opts.dataDir, "operator-browser"))) {
                System.err.println("Не удалось открыть пульт. Адрес: http://127.0.0.1:$port/remote")
            }
        }
    }

    private val trayActions = object : TrayActions {
        override fun openOperator() = this@ReceiverApp.openOperator()
        override fun monitors() = this@ReceiverApp.monitors()
        override fun setMonitor(req: MonitorRequest) = this@ReceiverApp.setMonitor(req)
        override fun webScreenUrl() = this@ReceiverApp.webScreenUrl()
        override fun quit() = this@ReceiverApp.quit()
    }

    private inner class DesktopOutputs : OutputsHost {
        override fun state() = OutputsState(monitors(), relay.outputs.value, webScreenUrl())
        override fun setMonitor(req: MonitorRequest) = this@ReceiverApp.setMonitor(req)
        override suspend fun link(req: LinkRequest): LinkResult = relay.link(req)
        override fun unlink(id: String) = relay.unlink(id)
    }

    // OutputHost

    override fun file(sha256: String): File? = core.files.file(sha256)
    override fun streamUrl(): String = server.localStreamUrl(port)
    override fun reportError(message: String?) = core.reportError(message)
    override fun reportVideo(state: VideoState?) = core.reportVideo(state)

    override fun quit() {
        shutdown()
        exitProcess(0)
    }

    @Volatile private var stopped = false

    private fun shutdown() {
        if (stopped) return
        stopped = true
        browsing?.cancel()
        runCatching { relay.close() }
        runCatching { advertisement?.close() }
        runCatching { server.stop() }
        runCatching { tray?.remove() }
        windows.values.toList().forEach { it.release() }
    }

    companion object {
        const val APP_VERSION = "0.1.0"
        private const val WINDOWED_ID = "window"
        private const val HOTPLUG_MS = 2_000
        private const val VIDEO_REPORT_MS = 500
        private const val CLOCK_MS = 1_000
        private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    }
}
