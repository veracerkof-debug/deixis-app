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
    /** Открыть окно пульта при запуске; null — по роли компьютера. */
    val operator: Boolean? = null,
    /** Роль из командной строки; null — сохранённая или выбор при первом запуске. */
    val role: Role? = null,
    /** Спрашивать роль при первом запуске. Ключи --screen, --windowed, --operator отвечают сами. */
    val askRole: Boolean = true,
    /** Выход из процесса (в проверках — без выхода). */
    val exit: (Int) -> Unit = { exitProcess(it) },
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
    /** Мониторы, где оператор увёл зал в окно (Esc, двойной щелчок, меню). Только в потоке окон. */
    private val windowedIds = HashSet<String>()
    private val roleStore = RoleStore(File(opts.dataDir, "role"))
    private val updates: DesktopUpdates
    @Volatile var role: Role = Role.SCREEN
        private set

    init {
        opts.dataDir.mkdirs()
        val idFile = File(opts.dataDir, "id")
        val id = idFile.takeIf { it.isFile }?.readText()?.trim() ?: UUID.randomUUID().toString().also { idFile.writeText(it) }
        val name = opts.name ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "Компьютер"
        // Пульт строит превью в пропорциях зала: размер первого монитора, который показывает зал.
        val screen = hallSize()
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
        updates = DesktopUpdates(APP_VERSION, opts.dataDir, scope, object : UpdateApp {
            override fun showing() = core.state.value.content != null
            override fun exitForUpdate() = exitNow()
        })
        core.updates = updates
        server = ReceiverServer(core, opts.port)
    }

    /** Размер монитора зала по сохранённому выбору; без мониторов (проверки) — 1920×1080. */
    private fun hallSize(): java.awt.Dimension = runCatching {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val list = ge.screenDevices.map { d ->
            val b = d.defaultConfiguration.bounds
            val m = runCatching { d.displayMode }.getOrNull()
            ScreenInfo(d.iDstring, b.x, b.y, m?.width?.takeIf { it > 0 } ?: b.width, m?.height?.takeIf { it > 0 } ?: b.height)
        }
        val hall = resolveMonitors(list, ge.defaultScreenDevice.iDstring, monitorStore.load())
            .firstOrNull { it.enabled && it.layout == OutputLayout.HALL } ?: error("нет монитора зала")
        java.awt.Dimension(hall.width, hall.height)
    }.getOrDefault(java.awt.Dimension(1920, 1080))

    fun run() {
        AppIcon.applyToDock()
        port = runBlocking { server.start() }
        advertisement = runCatching { Discovery.advertise(core.info, port, address) }
            .onFailure { System.err.println("Поиск в сети недоступен: ${it.message}") }
            .getOrNull()
        // Экраны Deixis в сети для пересылки показа.
        browsing = scope.launch {
            runCatching { Discovery.browse(address).collect { relay.found(it) } }
        }
        println("Deixis: «${core.info.name}» на ${address?.hostAddress ?: "127.0.0.1"}:$port, PIN ${core.pairing.pin}")
        val chosen = opts.role ?: roleStore.load() ?: if (opts.askRole && !GraphicsEnvironment.isHeadless()) askRole(null) else Role.SCREEN
        if (chosen == null) { quit(); return }
        if (opts.role == null && opts.askRole) roleStore.save(chosen)
        SwingUtilities.invokeAndWait {
            // Меню поверх видео: окно VLC на Windows и Linux — «тяжёлое» и закрыло бы лёгкое меню Swing.
            javax.swing.JPopupMenu.setDefaultLightWeightPopupEnabled(false)
            refreshScreens()
            applyRole(chosen, changed = false)
            tray = Tray.install(trayActions)
            if (MacScreen.isMac) MacScreen.install(::quit, ::openOperator, dockMenu())
            reconcile()
            startTimers()
        }
        observe()
        updates.start()
        if (!GraphicsEnvironment.isHeadless()) {
            val news = updates.updater.takeWhatsNew()
            if (news.isNotEmpty()) SwingUtilities.invokeLater { UpdatesWindow.whatsNew(news) }
        }
        Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
        if (opts.operator ?: (role != Role.SCREEN)) openOperator()
    }

    /** Окно выбора роли при первом запуске; ждёт ответа. null — окно закрыли. */
    private fun askRole(current: Role?): Role? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var picked: Role? = null
        SwingUtilities.invokeLater { RoleChooser.show(current) { picked = it; latch.countDown() } }
        latch.await()
        return picked
    }

    /**
     * Роль меняет то, что делают мониторы этого компьютера:
     * - «Пульт» — мониторы не показывают зал (включить любой можно в «Экранах»), открывается пульт;
     * - «Экран» — зал во весь экран, пульт не открывается;
     * - «Пульт и экран» — один монитор: зал в окне рядом с пультом; несколько — зал на остальных мониторах.
     * Только в потоке окон.
     */
    private fun applyRole(r: Role, changed: Boolean) {
        role = r
        when (r) {
            Role.REMOTE -> settings = screens.fold(settings) { acc, sc ->
                acc.with(MonitorRequest(sc.id, false, acc.monitors[sc.id]?.layout ?: OutputLayout.HALL))
            }
            else -> if (changed) settings = MonitorSettings()
        }
        if (changed || r == Role.REMOTE) runCatching { monitorStore.save(settings) }
        screenOverride = screenOverride.takeIf { r != Role.REMOTE }
        windowedIds.clear()
        if (r == Role.BOTH && screens.size == 1) windowedIds += screens[0].id
    }

    /** Смена роли из меню. */
    fun chooseRole() {
        RoleChooser.show(role) { r ->
            if (r == null || r == role) return@show
            roleStore.save(r)
            applyRole(r, changed = true)
            reconcile()
            if (r != Role.SCREEN) openOperator()
        }
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

    /**
     * Как окно занимает монитор. Монитор оператора ([shared]) никогда не запирается: на Маке — полный экран
     * системы (меню и Dock у края, ⌘Q, ⌘Tab), на Windows и Linux — окно без рамки по монитору, не поверх
     * остальных (Alt+Tab, Win, Alt+F4 работают). Мониторы только для зала — как раньше.
     */
    private fun windowMode(id: String, shared: Boolean): WindowMode = when {
        opts.windowed || id in windowedIds -> WindowMode.WINDOWED
        shared -> if (MacScreen.isMac) WindowMode.NATIVE else WindowMode.COVER
        // На Windows полноэкранное окно на втором мониторе сворачивается, когда фокус уходит к пульту,
        // поэтому там окно без рамки точно по монитору, поверх остальных.
        isWindows -> WindowMode.COVER
        else -> WindowMode.EXCLUSIVE
    }

    /** Приводит окна вывода в соответствие с мониторами и выбором оператора. Только в потоке окон. */
    private fun reconcile() {
        if (opts.windowed) {
            if (windows.isEmpty()) {
                windows[WINDOWED_ID] = OutputWindow(WINDOWED_ID, null, OutputLayout.HALL, WindowMode.WINDOWED, false, true, true, core.files, this)
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
            val shared = list.size <= 1 || m.primary
            val mode = windowMode(m.id, shared)
            val w = windows[m.id]
            if (w == null || w.mode != mode) {
                w?.dispose()
                // Окна только для зала не забирают фокус у пульта. Без значка в области уведомлений
                // (часть окружений Linux) Ctrl+Q и Ctrl+O нужны на любом мониторе.
                val focusable = shared || tray == null
                windows[m.id] = OutputWindow(m.id, device, m.layout, mode, topmost = !shared, focusable = focusable, shared = shared, files = core.files, host = this)
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
        // Обратный отсчёт: с долями секунды — 25 раз в секунду, иначе 4; окна перерисовываются, только когда меняется строка.
        var lastTick = 0L
        Timer(COUNTDOWN_MS) {
            val st = core.state.value
            val list = listOfNotNull(st.content as? app.deixis.core.CountdownContent, st.timer)
            if (list.isEmpty()) return@Timer
            val now = System.currentTimeMillis()
            if (list.none { it.decimals > 0 } && now - lastTick < COUNTDOWN_SLOW_MS) return@Timer
            lastTick = now
            val fs = frameState()
            windows.values.forEach { it.update(fs) }
        }.start()
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
        return FrameState(st.content, st.mode, st.next, st.video, core.showStartedAt, System.currentTimeMillis(), idle, st.timer, st.overlays, st.logo, st.logoImage)
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

    override fun setWindowed(windowId: String, windowed: Boolean) {
        SwingUtilities.invokeLater {
            if (opts.windowed) return@invokeLater
            if (windowed) windowedIds += windowId else windowedIds -= windowId
            reconcile()
        }
    }

    /** Меню по правой кнопке: то же, что в значке Deixis, плюс окно и весь экран. */
    override fun menu(windowId: String): javax.swing.JPopupMenu {
        val m = javax.swing.JPopupMenu()
        fun item(label: String, keys: String? = null, block: () -> Unit) =
            javax.swing.JMenuItem(if (keys == null) label else "$label    $keys").apply { addActionListener { block() } }
        val cmd = if (MacScreen.isMac) "⌘" else "Ctrl+"
        m.add(item("Открыть пульт", "${cmd}O") { openOperator() })
        val screensMenu = javax.swing.JMenu("Экраны")
        for (mon in monitors()) {
            val sub = javax.swing.JMenu(mon.name + " · ${mon.width}×${mon.height}" + if (mon.primary) " · пульт" else "")
            sub.add(javax.swing.JCheckBoxMenuItem("Показывать", mon.enabled).apply { addActionListener { setMonitor(MonitorRequest(mon.id, isSelected, mon.layout)) } })
            sub.addSeparator()
            for (l in OutputLayout.entries) {
                sub.add(javax.swing.JCheckBoxMenuItem(l.title, mon.layout == l).apply { addActionListener { setMonitor(MonitorRequest(mon.id, true, l)) } })
            }
            screensMenu.add(sub)
        }
        m.add(screensMenu)
        if (!opts.windowed) {
            val inWindow = windowId in windowedIds
            m.add(if (inWindow) item("Во весь экран", "F11") { setWindowed(windowId, false) } else item("В окно", "Esc") { setWindowed(windowId, true) })
        }
        m.add(item("Как работает компьютер…") { chooseRole() })
        m.add(item("Holyrics — телефон по QR…") { showHolyricsQr() })
        m.add(item("Обновления…") { showUpdates() })
        m.addSeparator()
        m.add(item("Выйти", "${cmd}Q") { quit() })
        return m
    }

    private fun dockMenu() = java.awt.PopupMenu().apply {
        add(java.awt.MenuItem("Открыть пульт").apply { addActionListener { openOperator() } })
        add(java.awt.MenuItem("Как работает компьютер…").apply { addActionListener { SwingUtilities.invokeLater { chooseRole() } } })
        add(java.awt.MenuItem("Holyrics — телефон по QR…").apply { addActionListener { showHolyricsQr() } })
        add(java.awt.MenuItem("Обновления…").apply { addActionListener { showUpdates() } })
    }

    private val trayActions = object : TrayActions {
        override fun openOperator() = this@ReceiverApp.openOperator()
        override fun monitors() = this@ReceiverApp.monitors()
        override fun setMonitor(req: MonitorRequest) = this@ReceiverApp.setMonitor(req)
        override fun webScreenUrl() = this@ReceiverApp.webScreenUrl()
        override fun quit() = this@ReceiverApp.quit()
        override fun chooseRole() = SwingUtilities.invokeLater { this@ReceiverApp.chooseRole() }
        override fun updates() = showUpdates()
        override fun holyrics() = showHolyricsQr()
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

    @Volatile private var exiting = false

    /**
     * Выход. В режиме «Автоматически» скачанное обновление ставится сейчас: установщик закроет программу
     * сам; если он не запустился за минуту или выдал ошибку, программа всё равно выходит.
     */
    override fun quit() {
        if (!exiting && updates.installOnExit()) {
            exiting = true
            scope.launch {
                val end = System.currentTimeMillis() + INSTALL_WAIT_MS
                while (System.currentTimeMillis() < end && updates.state.value.phase == "installing") kotlinx.coroutines.delay(200)
                exitNow()
            }
            return
        }
        exitNow()
    }

    private fun exitNow() {
        shutdown()
        opts.exit(0)
    }

    /** Пульт Holyrics на телефоне: QR-код с адресом этого компьютера и токеном Holyrics. */
    fun showHolyricsQr() = SwingUtilities.invokeLater { HolyricsQrWindow.show(address?.hostAddress, opts.dataDir) }

    fun showUpdates() = SwingUtilities.invokeLater { UpdatesWindow.show(updates, object : UpdateApp {
        override fun showing() = core.state.value.content != null
        override fun exitForUpdate() = exitNow()
    }) }

    /** Окна вывода сейчас (для проверки). Только в потоке окон. */
    internal fun windowsForTest(): List<OutputWindow> = windows.values.toList()

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
        // В проверках процесс не завершается: окна закрываются сами.
        SwingUtilities.invokeLater { windows.values.forEach { it.dispose() }; windows.clear() }
    }

    companion object {
        /** Версия программы из метки выпуска (вшита при сборке). */
        val APP_VERSION: String = ReceiverApp::class.java.getResourceAsStream("/deixis-version.txt")?.use { it.readBytes().decodeToString().trim() } ?: "0.1.0"
        private const val WINDOWED_ID = "window"
        private const val HOTPLUG_MS = 2_000
        private const val VIDEO_REPORT_MS = 500
        private const val CLOCK_MS = 1_000
        private const val INSTALL_WAIT_MS = 60_000L
        private const val COUNTDOWN_MS = 40
        private const val COUNTDOWN_SLOW_MS = 250L
        private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    }
}
