package app.deixis.desktop

import app.deixis.core.BibleStore
import app.deixis.core.Conversions
import app.deixis.core.Discovery
import app.deixis.core.FileRelayStore
import app.deixis.core.FileStore
import app.deixis.core.TlsIdentity
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
import app.deixis.core.airplay.AirPlayHost
import app.deixis.core.loadViewKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    @Volatile private var advertisement: AutoCloseable? = null
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
    /** Приём AirPlay с iPhone, iPad и Mac: включает оператор, по умолчанию выключен. */
    private lateinit var airPlay: AirPlayHost
    @Volatile var role: Role = Role.SCREEN
        private set
    /** Окно пульта внутри программы; закрыли — программа решает, завершаться ли (как с любым приложением). */
    /** Пульт шёл в своём окне: тогда о закрытии говорит окно, а не пропажа связи страницы. */
    @Volatile private var embeddedOperator = false
    /** Нативный пульт (тот же, что на телефоне): создаётся при первом открытии окна. Только в потоке окон. */
    private var pult: app.deixis.phone.PultWindow? = null
    private var pultController: app.deixis.phone.Controller? = null
    private lateinit var pdfConverter: OfficeConverter
    private var pdfEngines: List<PdfEngine> = emptyList()

    /** Проверки этого компьютера для диагностики (и экрана, и пульта). */
    private fun desktopChecks(): List<app.deixis.diag.Check> =
        DesktopChecks.all({ runCatching { monitors() }.getOrDefault(emptyList()) }, pdfEngines, { pult?.showing })
    /** «Новое подключение» на мониторе оператора, пока окно пульта закрыто. */
    private val approval = ApprovalWindow { id, allow -> core.peers.answer(id, allow) }

    init {
        opts.dataDir.mkdirs()
        // Журнал: консоль и ошибки — в память сразу, в файл log.txt — когда ясно, что эта копия программы
        // и есть работающая (второй запуск не трогает журнал первого).
        if (!GraphicsEnvironment.isHeadless()) {
            app.deixis.diag.Log.captureConsole()
            println("Deixis $APP_VERSION · ${app.deixis.diag.Diagnostics.system()}")
        }
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
        core.pageCounter = { f -> PdfPages.count(f) }
        pdfConverter = OfficeConverter(engines)
        pdfEngines = engines
        core.conversions = Conversions(core, pdfConverter, File(opts.dataDir, "convert"))
        settings = monitorStore.load()
        relay = ScreenRelay(core, FileRelayStore(File(opts.dataDir, "screens.json")), scope, name)
        core.outputs = DesktopOutputs()
        updates = DesktopUpdates(APP_VERSION, opts.dataDir, scope, object : UpdateApp {
            override fun showing() = core.state.value.content != null
            override fun exitForUpdate() = exitNow()
        })
        core.updates = updates
        server = ReceiverServer(core, opts.port, tls = runCatching { TlsIdentity.loadOrCreate(File(opts.dataDir, "tls"), core.info.name) }.getOrNull())
        airPlay = AirPlayHost(core, opts.dataDir) { address }
        core.airPlay = airPlay
        core.capture = DesktopCapture()
        core.diagChecks = { app.deixis.diag.Checks.common(opts.dataDir) + desktopChecks() }
    }

    /** Переключатель приёма AirPlay из меню; в сети он появляется или пропадает сразу. */
    private fun setAirPlay(on: Boolean) {
        scope.launch(Dispatchers.IO) { airPlay.setEnabled(on); SwingUtilities.invokeLater { tray?.rebuild() } }
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
        port = try {
            runBlocking { server.start() }
        } catch (e: Exception) {
            // Deixis уже работает на этом компьютере (например, ушёл с экранов, а окно пульта закрыли):
            // второй запуск возвращает его зал на экраны и открывает пульт, а сам завершается.
            if (activateRunning()) { println("Deixis уже запущен: зал возвращён, пульт открыт"); opts.exit(0); return }
            throw e
        }
        if (!GraphicsEnvironment.isHeadless()) app.deixis.diag.Log.toFile(opts.dataDir)
        // Токен «Этот компьютер» — сразу: по нему повторный запуск просит работающую программу открыть пульт,
        // даже если пульт в этот раз ещё не открывали (роль «Экран»).
        runCatching { operatorToken() }
        core.pairingUri = { address?.let { PairingCode(it.hostAddress, port, core.pairing.pin, core.info.id, core.info.name, core.tls?.port ?: 0, core.tls?.fingerprint.orEmpty()).toUri() } }
        core.onActivate = {
            core.returnToScreens()
            openOperator()
        }
        // Окно пульта закрыли: зал уходит вместе с программой, как у любого приложения — даже если
        // подключён ещё телефон (оператор за компьютером закончил). Компьютер-«Экран» так не закрывается:
        // там пульт открывают лишь изредка, а зал должен оставаться.
        // Это — для пульта в браузере (запасной путь): у своего окна закрытие видно сразу.
        core.onOperatorClosed = { _ -> if (!embeddedOperator && role != Role.SCREEN && !exiting) SwingUtilities.invokeLater { quit() } }
        // Объявление в сети — в фоне: JmDNS может ждать сеть десятки секунд (так бывает на Маке),
        // а зал и пульт открываются сразу и без него.
        scope.launch(Dispatchers.IO) {
            val ad = runCatching { Discovery.advertise(core.publicInfo(), port, address) }
                .onFailure { System.err.println("Поиск в сети недоступен: ${it.message}") }
                .getOrNull()
            if (exiting) runCatching { ad?.close() } else advertisement = ad
        }
        scope.launch(Dispatchers.IO) { airPlay.start() }
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
        autoVirtualHall()
        updates.start()
        // «Что нового» — окно интерфейса: на компьютере-экране с одним монитором оно открылось бы поверх зала.
        if (!GraphicsEnvironment.isHeadless() && !(role == Role.SCREEN && screens.size <= 1)) {
            val news = updates.updater.takeWhatsNew()
            if (news.isNotEmpty()) SwingUtilities.invokeLater { UpdatesWindow.whatsNew(news) }
        }
        Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
        // Пульт при запуске — всегда, когда ему есть место не на зале: роль «Экран» с монитором оператора тоже.
        // Только компьютер-экран, где все мониторы показывают зал, открывает пульт лишь из значка.
        val withOperator = opts.operator ?: (role != Role.SCREEN || onEdt { operatorArea() } != null)
        println("Роль: $role, мониторов: ${screens.size}, пульт при запуске: ${if (withOperator) "да" else "нет"}")
        if (withOperator) openOperator()
    }

    /**
     * Один дисплей у «Пульта и экрана» (проектор дублирует рабочий стол): зал закрывал бы рабочий стол оператора.
     * Deixis создаёт свой виртуальный монитор 1920×1080 и выводит зал туда — рабочий стол свободен, зал идёт
     * зрителям по QR и на экраны в сети. На Windows права администратора — один раз, при первом создании.
     * Если отказались в окне прав или убрали монитор в пульте — больше сам не создаёт: кнопка «Создать» остаётся
     * в пульте («Устройства» → «Входы»). Любая другая неудача не запоминается: при следующем запуске — снова.
     * Почему не создан — в журнале (virtual: …) и в «Диагностике».
     */
    private fun autoVirtualHall() {
        val skip = when {
            GraphicsEnvironment.isHeadless() || opts.windowed -> "без экрана или окном"
            role != Role.BOTH -> "роль «${role.title}»: зал на виртуальном мониторе — только у «Пульта и экрана»"
            screens.size != 1 -> "мониторов: ${screens.size}"
            File(opts.dataDir, AUTO_VIRTUAL_OFF).isFile -> "оператор отказался или убрал монитор — создаётся только кнопкой в пульте"
            else -> runCatching { VirtualScreen.unsupported }.getOrElse { it.message }
        }
        if (skip != null) { app.deixis.diag.Log.i("virtual", "сам не создаю: $skip"); return }
        val off = File(opts.dataDir, AUTO_VIRTUAL_OFF)
        scope.launch(Dispatchers.IO) {
            app.deixis.diag.Log.i("virtual", "один дисплей: создаю виртуальный монитор для зала")
            val r = VirtualScreen.create()
            if (r.isFailure) {
                // Отказ — решение оператора: больше не спрашиваем. Остальное (окно прав без ответа, драйвер) — попробуем снова.
                if (r.exceptionOrNull() is WindowsVirtualScreen.Refused && r.exceptionOrNull()?.message == WindowsVirtualScreen.DECLINED) runCatching { off.writeText("1") }
                return@launch
            }
            // Монитор появляется в системе не сразу: ждём его в списке и отдаём ему зал.
            val end = System.currentTimeMillis() + VIRTUAL_WAIT_MS
            while (System.currentTimeMillis() < end) {
                kotlinx.coroutines.delay(VIRTUAL_POLL_MS)
                val moved = kotlinx.coroutines.withContext(Dispatchers.Swing) {
                    refreshScreens()
                    val virtual = screens.firstOrNull { sc -> runCatching { VirtualScreen.isVirtual(sc.id, sc.x) }.getOrDefault(false) }
                        ?: screens.firstOrNull { it.id != primaryId }.takeIf { screens.size == 2 }
                    if (virtual == null) return@withContext false
                    screens.filter { it.id != virtual.id }.forEach { settings = settings.with(MonitorRequest(it.id, false, OutputLayout.HALL)) }
                    settings = settings.with(MonitorRequest(virtual.id, true, OutputLayout.HALL))
                    windowedIds.remove(virtual.id)
                    runCatching { monitorStore.save(settings) }
                    reconcile()
                    true
                }
                if (moved) { app.deixis.diag.Log.i("virtual", "зал — на виртуальном мониторе"); return@launch }
            }
            app.deixis.diag.Log.w("virtual", "виртуальный монитор не появился в списке мониторов")
        }
    }

    /** Просит уже запущенный Deixis этого компьютера вернуться на экраны. true — он ответил. */
    private fun activateRunning(): Boolean = runCatching {
        val token = File(opts.dataDir, "operator-token").takeIf { it.isFile }?.readText()?.trim() ?: return false
        val req = java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:${opts.port}/api/activate"))
            .header("Authorization", "Bearer $token").timeout(java.time.Duration.ofSeconds(3))
            .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build()
        val client = java.net.http.HttpClient.newBuilder().proxy(java.net.http.HttpClient.Builder.NO_PROXY)
            .connectTimeout(java.time.Duration.ofSeconds(2)).build()
        client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() == 204
    }.getOrDefault(false)

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
        val all = ge.screenDevices.map { d ->
            val b = d.defaultConfiguration.bounds
            val m = runCatching { d.displayMode }.getOrNull()
            ScreenInfo(d.iDstring, b.x, b.y, m?.width?.takeIf { it > 0 } ?: b.width, m?.height?.takeIf { it > 0 } ?: b.height)
        }
        primaryId = runCatching { ge.defaultScreenDevice.iDstring }.getOrNull()
        screens = withoutMirrors(all, primaryId)
        if (all.size - screens.size != lastMirrorCount) {
            lastMirrorCount = all.size - screens.size
            if (lastMirrorCount > 0)
                app.deixis.diag.Log.i("monitors", "повторяют рабочий стол: $lastMirrorCount — считаются одним монитором")
        }
        // Проектор пропал на миг (кабель, сон) — зал не переезжает на монитор пульта. Остался один монитор
        // надолго (Win+P «Повторяющийся», работа без проектора) — он снова берёт зал, как при запуске с одним.
        val now = System.currentTimeMillis()
        if (screens.size > 1) { sawSeveralScreens = true; singleSince = 0L }
        else if (sawSeveralScreens) {
            if (singleSince == 0L) singleSince = now
            else if (now - singleSince > SINGLE_TAKES_HALL_AFTER_MS) sawSeveralScreens = false
        }
    }
    @Volatile private var singleSince = 0L
    private var lastMirrorCount = 0

    /** Был ли с запуска второй монитор: тогда пропажа проектора не переносит зал на монитор пульта. */
    @Volatile private var sawSeveralScreens = false

    private fun monitors(): List<MonitorOutput> {
        val list = resolveMonitors(screens, primaryId, settings, singleTakesHall = !sawSeveralScreens || role == Role.SCREEN)
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
            // «Наложение» — своё окно (прозрачное, без мыши): смена раскладки на него и с него пересоздаёт окно.
            val mode = if (m.layout == OutputLayout.OVERLAY) WindowMode.OVERLAY else windowMode(m.id, shared)
            val w = windows[m.id]
            if (w == null || w.mode != mode) {
                w?.dispose()
                // Окна только для зала не забирают фокус у пульта. Без значка в области уведомлений
                // (часть окружений Linux) Ctrl+Q и Ctrl+O нужны на любом мониторе.
                val focusable = shared || tray == null
                windows[m.id] = OutputWindow(m.id, device, m.layout, mode, topmost = !shared, focusable = focusable && mode != WindowMode.OVERLAY, shared = shared, files = core.files, host = this)
                    .also { it.dim = m.dim }
            } else {
                w.dim = m.dim
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
        updateApproval()
    }

    private fun startTimers() {
        // Мониторы подключают и отключают на ходу: окна следуют за ними.
        Timer(HOTPLUG_MS) {
            val before = Triple(screens, primaryId, sawSeveralScreens)
            refreshScreens()
            if (before != Triple(screens, primaryId, sawSeveralScreens)) reconcile()
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
            pairingUri = address?.let { PairingCode(it.hostAddress, port, pin, core.info.id, core.info.name, core.tls?.port ?: 0, core.tls?.fingerprint.orEmpty()).toUri() },
        )
        // «QR и код на экран»: заставка подключения вместо слайда, пока оператор её не убрал.
        if (st.connectCard) return FrameState(null, app.deixis.core.Mode.NORMAL, st.next, null, core.showStartedAt, System.currentTimeMillis(), idle, st.timer, st.overlays, st.logo, st.logoImage)
        return FrameState(st.content, st.mode, st.next, st.video, core.showStartedAt, System.currentTimeMillis(), idle, st.timer, st.overlays, st.logo, st.logoImage)
    }

    private fun observe() {
        scope.launch(Dispatchers.Swing) { core.peers.state.collect { updateApproval() } }
        scope.launch(Dispatchers.Swing) {
            core.videoCommands.collect { cmd ->
                windows.values.forEach { it.videoCommand(cmd) }
                hallWindow()?.videoState()?.let { core.reportVideo(it) }
            }
        }
        scope.launch(Dispatchers.Swing) {
            core.state.map { it.connectCard }.distinctUntilChanged().collect { tray?.rebuild() }
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

    /**
     * Пульт — в своём окне Deixis на мониторе оператора. Если встроенного движка нет (сборка без него,
     * сбой видеокарты) — как раньше, окном браузера.
     */
    /**
     * Пульт — своё окно Deixis на мониторе оператора: тот же пульт, что на телефоне (Compose), со своим экраном
     * компьютера по 127.0.0.1. Без экрана (проверки) — как раньше, окном браузера с веб-пультом.
     */
    override fun openOperator() {
        if (GraphicsEnvironment.isHeadless()) {
            scope.launch(Dispatchers.IO) { OperatorLauncher.open("http://127.0.0.1:$port/remote#t=${operatorToken()}", File(opts.dataDir, "operator-browser")) }
            return
        }
        scope.launch(Dispatchers.IO) {
            val token = operatorToken()
            // Библиотека пульта читается здесь, в фоне: окно пульта открывается без паузы, зал не замирает.
            runCatching { pultController() }.onFailure { app.deixis.diag.Log.w("pult", "пульт не подготовлен заранее", it) }
            kotlinx.coroutines.withContext(Dispatchers.Swing) {
                val ok = runCatching { openPult(token) }.onFailure { System.err.println("Окно пульта не открылось:"); it.printStackTrace() }.isSuccess
                println("Окно пульта: ${if (ok) "открыто" else "запасной путь — браузер"}")
                if (ok) { embeddedOperator = true; updateApproval() }
                else OperatorLauncher.open("http://127.0.0.1:$port/remote#t=$token", File(opts.dataDir, "operator-browser"))
            }
        }
    }

    /** Пульт этого компьютера; первый раз читает библиотеку — поэтому зовётся заранее, не из потока окон. */
    @Synchronized
    private fun pultController(): app.deixis.phone.Controller = pultController ?: run {
        val platform = app.deixis.phone.DesktopPlatform(opts.dataDir, pdfConverter, { address?.hostAddress }, { pult?.showing == true }, APP_VERSION, ::desktopChecks)
        app.deixis.phone.Controller(platform).also { pultController = it }
    }

    /** Открывает (или выводит вперёд) окно пульта и связывает его со своим экраном. Только в потоке окон. */
    private fun openPult(token: String) {
        val c = pultController()
        val w = pult ?: run {
            val ui = app.deixis.phone.Ui()
            val host = app.deixis.phone.DesktopHost(c.platform, { app.deixis.phone.HolyricsSource(c.platform, c) }, updates, APP_VERSION) { ui.toast(it) }
            app.deixis.phone.PultWindow(c, host, ui, Words.operatorTitle) { pultClosed() }.also { pult = it }
        }
        // Свой экран — первым, без PIN: токен «Этот компьютер».
        c.setCable(app.deixis.phone.Target(core.info.id, core.info.name, "127.0.0.1", port, app.deixis.phone.Target.Kind.LOCAL, core.info.platform), token)
        c.startDiscovery()
        w.open(operatorArea())
    }

    /** Окно пульта закрыли: связь пульта со своим экраном уходит (запросы на подключение — в окно на мониторе). */
    private fun pultClosed() {
        pultController?.let { it.setCable(null, null); it.stopDiscovery() }
        if (role != Role.SCREEN && !exiting) quit() else updateApproval()
    }

    /**
     * Где можно показать интерфейс, не задев зал: монитор без зала (сначала основной), иначе монитор, где зал
     * идёт в окне. null — все мониторы показывают зал во весь экран.
     */
    private fun operatorArea(): java.awt.Rectangle? {
        if (GraphicsEnvironment.isHeadless()) return null
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val devices = ge.screenDevices.associateBy { it.iDstring }
        val id = if (opts.windowed) ge.defaultScreenDevice.iDstring else {
            val list = monitors().filter { it.id in devices }
            list.filter { !it.enabled }.sortedByDescending { it.primary }.firstOrNull()?.id
                ?: list.firstOrNull { it.id in windowedIds }?.id ?: return null
        }
        val conf = devices[id]?.defaultConfiguration ?: return null
        val b = conf.bounds
        val i = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(conf)
        return java.awt.Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
    }

    /** Запрос на подключение — в окне пульта, если оно открыто, иначе отдельным окном на мониторе оператора. */
    private fun updateApproval() {
        val area = operatorArea()
        core.peers.screenApprover = area != null
        approval.update(if (pult?.showing == true) emptyList() else core.peers.state.value.requests, area)
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
        m.add(javax.swing.JCheckBoxMenuItem(AIRPLAY_LABEL, airPlay.enabled).apply { addActionListener { setAirPlay(isSelected) } })
        m.add(item("Как работает компьютер…") { chooseRole() })
        m.add(item("Holyrics — телефон по QR…") { showHolyricsQr() })
        m.add(item("Обновления…") { showUpdates() })
        m.add(item(Words.logFolder) { showLog() })
        m.addSeparator()
        m.add(item("Выйти", "${cmd}Q") { quit() })
        return m
    }

    private fun dockMenu() = java.awt.PopupMenu().apply {
        add(java.awt.MenuItem("Открыть пульт").apply { addActionListener { openOperator() } })
        add(java.awt.CheckboxMenuItem(AIRPLAY_LABEL, airPlay.enabled).apply { addItemListener { setAirPlay(state) } })
        add(java.awt.MenuItem("Как работает компьютер…").apply { addActionListener { SwingUtilities.invokeLater { chooseRole() } } })
        add(java.awt.MenuItem("Holyrics — телефон по QR…").apply { addActionListener { showHolyricsQr() } })
        add(java.awt.MenuItem("Обновления…").apply { addActionListener { showUpdates() } })
        add(java.awt.MenuItem(Words.logFolder).apply { addActionListener { showLog() } })
    }

    private val trayActions = object : TrayActions {
        override fun openOperator() = this@ReceiverApp.openOperator()
        override fun monitors() = this@ReceiverApp.monitors()
        override fun setMonitor(req: MonitorRequest) = this@ReceiverApp.setMonitor(req)
        override fun webScreenUrl() = this@ReceiverApp.webScreenUrl()
        override fun quit() = this@ReceiverApp.quit()
        override fun chooseRole() = SwingUtilities.invokeLater { this@ReceiverApp.chooseRole() }
        override fun updates() = showUpdates()
        override fun showLog() = this@ReceiverApp.showLog()
        override fun holyrics() = showHolyricsQr()
        override fun airPlay() = this@ReceiverApp.airPlay.enabled
        override fun setAirPlay(on: Boolean) = this@ReceiverApp.setAirPlay(on)
        override fun connectCard() = core.state.value.connectCard
        // Без зала («Пульт») QR и код — в пульте: «Устройства» → «QR и код для подключения».
        override fun setConnectCard(on: Boolean) = if (role == Role.REMOTE) openOperator() else core.connectCard(on)
    }

    /**
     * Трансляция монитора этого компьютера (HDMI-заглушка, виртуальный экран, второй монитор, куда Holyrics или
     * PowerPoint выводят «проектор») в общий поток экрана. Монитор, где сейчас окно Deixis, при выборе освобождается:
     * иначе Deixis снимал бы сам себя. Показ другого с пульта останавливает съёмку — процессор не занят зря.
     */
    private inner class DesktopCapture : app.deixis.core.CaptureHost {
        private var capture: ScreenCapture? = null
        private var output: app.deixis.core.MirrorOutput? = null
        private var activeId: String? = null
        @Volatile private var error: String? = null
        private var keys: Job? = null
        private var watch: Job? = null

        @Synchronized
        override fun state(): app.deixis.core.CaptureState {
            if (capture?.alive == false) stopNow()
            val mons = monitors()
            val list = ArrayList<app.deixis.core.CaptureSource>()
            screens.forEachIndexed { i, sc ->
                // Виртуальный экран Deixis, если Java его видит, — отдельной строкой ниже, не как монитор.
                if (VirtualScreen.exists && VirtualScreen.isVirtual(sc.id, sc.x)) return@forEachIndexed
                val m = mons.firstOrNull { it.id == sc.id }
                list += app.deixis.core.CaptureSource(sc.id, m?.name ?: "Монитор ${i + 1}", sc.width, sc.height, hall = m?.enabled == true)
            }
            if (VirtualScreen.exists) list += app.deixis.core.CaptureSource(VIRTUAL_ID, "Виртуальный экран Deixis", VirtualScreen.WIDTH, VirtualScreen.HEIGHT, kind = app.deixis.core.CaptureSource.VIRTUAL)
            else if (VirtualScreen.supported) list += app.deixis.core.CaptureSource(app.deixis.core.CaptureSource.ID_NEW_VIRTUAL, "Создать виртуальный экран", VirtualScreen.WIDTH, VirtualScreen.HEIGHT, kind = app.deixis.core.CaptureSource.NEW_VIRTUAL)
            AppWindows.list().forEach { w -> list += app.deixis.core.CaptureSource("win:${w.id}", w.title, w.width, w.height, kind = app.deixis.core.CaptureSource.WINDOW) }
            // Windows: создание и удаление монитора спрашивают права администратора (окно Windows на мониторе пульта).
            val why = if (VirtualScreen.exists) null else runCatching { VirtualScreen.unsupported }.getOrElse { it.message }
            val note = when {
                why != null -> "Виртуальный экран здесь не создаётся: $why."
                System.getProperty("os.name").orEmpty().lowercase().contains("win") ->
                    "Windows спросит права администратора: драйвер виртуального монитора Deixis ставится один раз, монитор остаётся до «Убрать»."
                else -> null
            }
            return app.deixis.core.CaptureState(list, activeId, error, note)
        }

        @Synchronized
        override fun select(id: String?) {
            stopNow()
            error = null
            when {
                id == null -> return
                id == app.deixis.core.CaptureSource.ID_REMOVE_VIRTUAL -> {
                    // Убрали сами — Deixis больше не создаёт его при запуске.
                    runCatching { File(opts.dataDir, AUTO_VIRTUAL_OFF).writeText("1") }
                    VirtualScreen.remove(); return
                }
                id == app.deixis.core.CaptureSource.ID_NEW_VIRTUAL || id == VIRTUAL_ID -> {
                    val area = VirtualScreen.create().getOrElse { error = "Виртуальный экран не создан: ${it.message}"; return }
                    // Создали сами — значит, он нужен: при одном дисплее Deixis снова создаёт его при запуске.
                    runCatching { File(opts.dataDir, AUTO_VIRTUAL_OFF).delete() }
                    // Если Java увидит новый монитор, Deixis не должен ставить на него свой зал.
                    scope.launch(Dispatchers.Swing) {
                        kotlinx.coroutines.delay(1_500)
                        refreshScreens()
                        screens.firstOrNull { VirtualScreen.isVirtual(it.id, it.x) }?.let { sc -> monitors().firstOrNull { it.id == sc.id && it.enabled }?.let { setMonitor(MonitorRequest(it.id, false, it.layout)) } }
                    }
                    start(VIRTUAL_ID, area)
                    return
                }
                id.startsWith("win:") -> {
                    val wid = id.removePrefix("win:").toLongOrNull() ?: return
                    val w = AppWindows.list().firstOrNull { it.id == wid } ?: run { error = "Окно уже закрыто"; return }
                    val linux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")
                    start(id, CaptureArea(0, 0, w.width, w.height, windowId = wid.takeIf { linux }, windowTitle = w.title.takeIf { !linux }))
                    return
                }
            }
            val index = screens.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
            val sc = screens[index]
            monitors().firstOrNull { it.id == id && it.enabled }?.let { setMonitor(MonitorRequest(it.id, false, it.layout)) }
            val area = runCatching {
                val d = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.first { it.iDstring == id }
                val b = d.defaultConfiguration.bounds
                // Windows снимает в настоящих точках экрана, а Java считает с учётом масштаба (125 %, 150 %).
                val k = sc.width.toDouble() / b.width
                CaptureArea((b.x * k).toInt(), (b.y * k).toInt(), sc.width, sc.height, index)
            }.getOrElse { CaptureArea(sc.x, sc.y, sc.width, sc.height, index) }
            start(id!!, area)
        }

        private fun start(id: String, area: CaptureArea) {
            val out = app.deixis.core.MirrorOutput(core)
            val cap = ScreenCapture(area, out, onError = { error = it })
            output = out; capture = cap; activeId = id
            keys = scope.launch { core.stream.keyRequests.collect { cap.requestKey() } }
            watch = scope.launch {
                // Пока картинки ещё нет — ждём; потом, если оператор показал другое, съёмка останавливается.
                var seen = false
                while (true) {
                    kotlinx.coroutines.delay(1_000)
                    val on = out.onScreen()
                    if (on) seen = true
                    if ((seen && !on) || !cap.alive) { synchronized(this@DesktopCapture) { if (capture === cap) stopNow() }; break }
                }
            }
        }

        private fun stopNow() {
            keys?.cancel(); watch?.cancel()
            runCatching { capture?.close() }
            runCatching { output?.close() }
            capture = null; output = null; activeId = null
        }

        fun close() = synchronized(this) { stopNow(); VirtualScreen.releaseOnExit() }
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
    override fun streamFrames() = core.stream.subscribe()
    override fun streamAudio() = core.audio.subscribe()
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

    /** Папка с журналом программы: log.txt (и прошлый запуск — log.old.txt) — переслать автору. */
    fun showLog() {
        Thread {
            runCatching {
                val f = app.deixis.diag.Log.logFile
                if (f != null && isWindows) ProcessBuilder("explorer.exe", "/select,", f.absolutePath).start()
                else java.awt.Desktop.getDesktop().open(f?.parentFile ?: opts.dataDir)
            }.onFailure { System.err.println("Папка журнала: ${it.message}") }
        }.start()
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
        // Свой виртуальный монитор (Linux, Мак) — первым: иначе после выхода остаётся пустой монитор, куда уходят окна.
        runCatching { VirtualScreen.releaseOnExit() }
        browsing?.cancel()
        runCatching { relay.close() }
        runCatching { advertisement?.close() }
        runCatching { airPlay.close() }
        runCatching { (core.capture as? DesktopCapture)?.close() }
        runCatching { server.stop() }
        runCatching { tray?.remove() }
        SwingUtilities.invokeLater { pult?.close(notify = false); approval.hide() }
        windows.values.toList().forEach { it.release() }
        // В проверках процесс не завершается: окна закрываются сами.
        SwingUtilities.invokeLater { windows.values.forEach { it.dispose() }; windows.clear() }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T>? = null
        if (SwingUtilities.isEventDispatchThread()) return block()
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r!!.getOrThrow()
    }

    companion object {
        /** id виртуального экрана Deixis в списке источников трансляции. */
        private const val VIRTUAL_ID = "virtual"
        /** iPhone, iPad и Mac показывают экран здесь без отдельных программ. */
        const val AIRPLAY_LABEL = "Принимать AirPlay (iPhone, iPad, Mac)"
        /** Флаг в папке данных: свой виртуальный монитор для зала при одном дисплее больше не создавать. */
        private const val AUTO_VIRTUAL_OFF = "virtual-auto-off"
        private const val VIRTUAL_WAIT_MS = 20_000L
        private const val VIRTUAL_POLL_MS = 1_000L
        /** Сколько один монитор должен оставаться один, чтобы снова взять зал. */
        private const val SINGLE_TAKES_HALL_AFTER_MS = 15_000L
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
