package app.deixis.phone

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import app.deixis.core.Connection
import app.deixis.core.Content
import app.deixis.core.DenyReason
import app.deixis.core.DefaultBibles
import app.deixis.core.Discovery
import app.deixis.core.FILE_LIMIT_BYTES
import app.deixis.core.FileRef
import app.deixis.core.ImageContent
import app.deixis.core.MediaKind
import app.deixis.core.Mode
import app.deixis.core.PairingCode
import app.deixis.core.PairingException
import app.deixis.core.PdfContent
import app.deixis.core.ReceiverInfo
import app.deixis.core.ReceiverServer
import app.deixis.core.ReceiverState
import app.deixis.core.ScreenContent
import app.deixis.core.ScreenStream
import app.deixis.core.ScriptureContent
import app.deixis.core.ScriptureOptions
import app.deixis.core.SenderClient
import app.deixis.core.TextContent
import app.deixis.core.TextStyle
import app.deixis.core.UploadSource
import app.deixis.core.VideoAction
import app.deixis.core.VideoCommand
import app.deixis.core.VideoContent
import app.deixis.core.buildScriptureSlides
import app.deixis.core.BibleRef
import app.deixis.core.mediaKindOf
import app.deixis.core.mimeOf
import app.deixis.core.splitIntoSlides
import app.deixis.receiver.PptxPdf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.time.LocalDate

/** Экран, на который можно показать. */
data class Target(val id: String, val name: String, val host: String, val port: Int, val kind: Kind, val platform: String = "") {
    enum class Kind { NETWORK, CABLE, MIRACAST, MANUAL, DLNA }

    /** Как экран подключён — подпись в списке устройств. */
    val via: String
        get() = when {
            kind == Kind.CABLE -> "Кабель HDMI"
            kind == Kind.MIRACAST -> "Миракаст"
            kind == Kind.DLNA -> "DLNA"
            platform.startsWith("desktop") -> "Deixis для компьютера"
            platform == "android-screen" -> "Deixis на телефоне или доске"
            platform == "android-tv" -> "Deixis TV"
            else -> "Deixis"
        }

    /** Значок: проектор, телевизор, компьютер, телефон. */
    val icon: String
        get() = when {
            kind == Kind.CABLE || kind == Kind.MIRACAST -> "proj"
            platform.startsWith("desktop") -> "laptop"
            platform == "android-screen" -> "phone"
            else -> "tv"
        }
}

/** Состояние экрана для полосы на главном и раздела «Устройства». */
enum class OutState(val text: String) { ONLINE("на связи"), SYNC("доставляются файлы"), CONNECTING("подключение…"), OFFLINE("нет связи"), PIN("нужен PIN") }

data class OutputView(val target: Target, val state: OutState, val info: ReceiverInfo?)

/** Файл, который не передаётся из-за размера. */
data class LargeFile(val uri: Uri, val name: String, val mime: String)

/** Подключённый экран: своя связь, свои переданные файлы. */
private class Output(val target: Target, val client: SenderClient) {
    val syncing = MutableStateFlow(0)
    var jobs: List<Job> = emptyList()
    /** Хэши файлов, которые уже целиком на этом экране. */
    val delivered = mutableSetOf<String>()
}

/** Логика пульта без экрана: экраны, библиотека, сетлисты, «Сейчас» и «Предпросмотр». */
@OptIn(ExperimentalCoroutinesApi::class)
class Controller(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("tokens", Context.MODE_PRIVATE)
    private val deviceName = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val progress = MutableStateFlow<String?>(null)

    // ——— Библиотека, сетлисты, настройки ———
    private val store = Store(app)
    val data = MutableStateFlow(store.data)
    val backups = Backups(app)

    /** Копия сейчас (кнопка в настройках или перед обновлением). */
    fun backupNow(reason: String = "manual"): String = backups.save(store.data, reason)

    /** Восстановить библиотеку из копии; текущая сначала сохраняется копией. */
    fun restore(d: StoreData) {
        runCatching { backups.save(store.data, "before-restore") }
        data.value = store.replace(d)
    }
    private fun edit(change: (StoreData) -> StoreData) { data.value = store.update(change) }

    val settings: StateFlow<AppSettings> = data.map { it.settings }.stateIn(scope, SharingStarted.Eagerly, store.data.settings)
    fun setSettings(change: (AppSettings) -> AppSettings) {
        edit { it.copy(settings = change(it.settings)) }
        // Регистр меняется сразу и в зале.
        live.value?.let { c -> if (c.slide is Slide.Text || c.slide is Slide.Scripture) deliver(c) }
    }

    fun item(id: String?): LibItem? = id?.let { i -> data.value.items.firstOrNull { it.id == i } }

    /** Модули Библии: встроенные копируются при первом запуске, поэтому открываются не на главном потоке. */
    val bible by lazy { BibleLibrary(app) }
    val bibleModules = MutableStateFlow<List<app.deixis.core.BibleModule>>(emptyList())
    /** Вкладка «Библия»: открытое место и история живут вместе с пультом. */
    val desk by lazy { BibleDesk(this, scope) }

    // ——— Экраны ———
    private val found = MutableStateFlow<List<Target>>(emptyList())
    private val cable = MutableStateFlow<Target?>(null)
    private val manual = MutableStateFlow<List<Target>>(emptyList())
    val dlna = DlnaOutput(app)

    /** Все экраны, которые видит телефон: кабель или Миракаст, найденные в сети, введённые вручную, DLNA. */
    val targets: StateFlow<List<Target>> = combine(cable, found, manual, dlna.found) { c, f, m, d ->
        listOfNotNull(c) + f + m.filter { x -> f.none { it.id == x.id || (it.host == x.host && it.port == x.port) } } +
            d.map { Target(it.id, it.name, it.host, 0, Target.Kind.DLNA) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val outs = MutableStateFlow<List<Output>>(emptyList())
    private val dlnaTarget = MutableStateFlow<Target?>(null)
    val needPin = MutableStateFlow<Target?>(null)

    /** Подключённые экраны с состоянием: показ идёт на все сразу. */
    val outputs: StateFlow<List<OutputView>> = outs.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList())
        else combine(list.map { o -> combine(o.client.connection, o.syncing) { conn, sync -> view(o.target, conn, sync) } }) { it.toList() }
    }.combine(dlnaTarget) { list, d -> list + listOfNotNull(d?.let { OutputView(it, OutState.ONLINE, null) }) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun view(t: Target, c: Connection, sync: Int) = OutputView(
        t,
        when {
            c is Connection.Connected && sync > 0 -> OutState.SYNC
            c is Connection.Connected -> OutState.ONLINE
            c is Connection.Denied -> OutState.PIN
            c is Connection.Lost -> OutState.OFFLINE
            else -> OutState.CONNECTING
        },
        (c as? Connection.Connected)?.receiver,
    )

    /** Главный экран: по нему видно состояние показа, через него идут зрители, трансляция и Holyrics. */
    private val primary: StateFlow<Output?> = outs.map { it.firstOrNull() }.stateIn(scope, SharingStarted.Eagerly, null)
    val receiverState: StateFlow<ReceiverState?> = primary.flatMapLatest { it?.client?.state ?: dlna.state }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val receiverInfo: StateFlow<ReceiverInfo?> = primary.flatMapLatest { o -> o?.client?.connection?.map { (it as? Connection.Connected)?.receiver } ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val viewUrl: StateFlow<String?> = primary.flatMapLatest { o ->
        o?.client?.viewKey?.map { k -> if (k != null && o.target.kind != Target.Kind.CABLE && o.target.kind != Target.Kind.MIRACAST) o.client.viewUrl() else null } ?: flowOf(null)
    }.stateIn(scope, SharingStarted.Eagerly, null)
    val viewLocalUrl: StateFlow<String?> = primary.flatMapLatest { o ->
        o?.client?.viewKey?.map { k -> if (k != null && o.target.kind == Target.Kind.NETWORK) o.client.viewLocalUrl() else null } ?: flowOf(null)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    // ——— «Сейчас» и «Предпросмотр» ———
    val live = MutableStateFlow<Cue?>(null)
    val preview = MutableStateFlow<Cue?>(null)
    /** Правка оформления: видна только в предпросмотре до «Применить в зал». */
    val draft = MutableStateFlow<TextStyle?>(null)
    /** Режим, если экранов нет (для полосы кнопок); с экраном — его состояние. */
    private val localMode = MutableStateFlow(Mode.NORMAL)
    val mode: StateFlow<Mode> = combine(receiverState, localMode) { s, m -> s?.mode ?: m }.stateIn(scope, SharingStarted.Eagerly, Mode.NORMAL)

    val sharing = MutableStateFlow(false)
    private var screenStream: ScreenStream? = null
    val tooLarge = MutableStateFlow<LargeFile?>(null)

    private var browseJob: Job? = null
    private var dlnaSearch: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    init {
        scope.launch(Dispatchers.IO) { bibleModules.value = runCatching { bible.modules() }.getOrDefault(emptyList()) }
        if (store.recovered) scope.launch { messages.emit("Файл библиотеки был испорчен: она восстановлена из последней резервной копии") }
        // Ежедневная резервная копия при запуске.
        scope.launch(Dispatchers.IO) { runCatching { backups.daily(store.data) } }
        seedDemo()
        // Прежние настройки (до библиотеки) переносятся один раз.
        val old = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (old.contains("caps") || old.contains("church")) {
            edit { it.copy(settings = it.settings.copy(caps = old.getBoolean("caps", false), church = old.getBoolean("church", true))) }
            old.edit().clear().apply()
        }
        // Экраны, к которым подключались, подключаются снова, как только найдутся.
        scope.launch {
            targets.collect { list ->
                val want = prefs.getStringSet("outputs", emptySet()).orEmpty()
                list.filter { it.kind != Target.Kind.DLNA && it.id in want && outs.value.none { o -> o.target.id == it.id } }
                    .forEach { t -> prefs.getString(key(t), null)?.let { open(t, it) } }
            }
        }
    }

    fun startDiscovery() {
        if (browseJob != null) return
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("deixis")?.apply { setReferenceCounted(false); acquire() }
        browseJob = scope.launch(Dispatchers.IO) {
            runCatching {
                Discovery.browse().collect { list -> found.value = list.map { Target(it.id, it.name, it.host, it.port, Target.Kind.NETWORK, it.platform) } }
            }
        }
        dlnaSearch = dlna.search(scope)
    }

    fun stopDiscovery() {
        browseJob?.cancel(); browseJob = null
        dlnaSearch?.cancel(); dlnaSearch = null
        runCatching { multicastLock?.release() }
    }

    fun isConnected(t: Target) = outs.value.any { it.target.id == t.id || (it.client.connection.value as? Connection.Connected)?.receiver?.id == t.id } ||
        dlnaTarget.value?.id == t.id
    fun isPaired(t: Target) = t.kind == Target.Kind.DLNA || prefs.getString(key(t), null) != null

    /** Подключает экран к показу; первый раз спрашивает PIN. */
    fun connect(t: Target) {
        if (isConnected(t)) return
        if (t.kind == Target.Kind.DLNA) {
            val r = dlna.found.value.firstOrNull { it.id == t.id } ?: return
            scope.launch {
                runCatching { dlna.open(r, scope) }.onSuccess { dlnaTarget.value = t; live.value?.let(::deliver) }
                    .onFailure { messages.emit("«${r.name}»: ${it.message}") }
            }
            return
        }
        val token = prefs.getString(key(t), null)
        if (token == null) needPin.value = t else open(t, token)
    }

    /** Обновление подключённого экрана (ТВ, проектор, компьютер). null — этот экран так не обновляется. */
    suspend fun screenUpdate(t: Target, req: app.deixis.core.UpdateRequest? = null): app.deixis.core.UpdateState? =
        outs.value.firstOrNull { it.target.id == t.id }?.client?.let { runCatching { it.update(req) }.getOrNull() }

    fun disconnect(t: Target) {
        if (dlnaTarget.value?.id == t.id) { dlna.close(); dlnaTarget.value = null; return }
        val o = outs.value.firstOrNull { it.target.id == t.id } ?: return
        if (o === primary.value && (sharing.value || screenStream != null)) ScreenShareService.stop(app)
        close(o)
        remember(t, false)
    }

    /** Повторить связь с экраном, который пропал. */
    fun retry(t: Target) {
        val o = outs.value.firstOrNull { it.target.id == t.id } ?: return connect(t)
        val token = prefs.getString(key(t), null) ?: return
        close(o)
        open(t, token)
    }

    fun pair(t: Target, pin: String) = scope.launch {
        val c = SenderClient(t.host, t.port, deviceName, scope)
        try {
            val token = c.pair(pin.trim())
            prefs.edit().putString(key(t), token).apply()
            needPin.value = null
            open(t, token)
            messages.emit("${t.name}: подключено")
        } catch (e: PairingException) {
            messages.emit(
                when (e.reason) {
                    DenyReason.WRONG_PIN -> "Неверный PIN"
                    DenyReason.TOO_MANY_ATTEMPTS -> "Слишком много попыток. На экране новый PIN"
                    DenyReason.INCOMPATIBLE_VERSION -> "Версии Deixis не совпадают. Обновите приложения"
                    DenyReason.UNKNOWN_TOKEN -> "Приёмник не узнал телефон"
                },
            )
        } catch (e: Exception) {
            messages.emit("Нет связи с «${t.name}»")
        } finally {
            c.close()
        }
    }

    /** Адрес вида 192.168.1.20[:порт] или ссылка из QR-кода приёмника. */
    fun addManual(input: String) {
        val code = PairingCode.parse(input.trim())
        if (code != null) {
            val t = Target(code.id.ifEmpty { "${code.host}:${code.port}" }, code.name.ifEmpty { code.host }, code.host, code.port, Target.Kind.MANUAL)
            manual.value = manual.value.filter { it.id != t.id } + t
            pair(t, code.pin)
            return
        }
        val host = input.trim().substringBefore(':')
        if (host.isEmpty()) return
        val port = input.substringAfter(':', "").toIntOrNull() ?: ReceiverServer.DEFAULT_PORT
        // Сначала спросить экран, кто он: тот же экран, найденный в сети, останется одним устройством,
        // с настоящим именем и тем же сопряжением. Экран старой версии или без ответа — по адресу.
        scope.launch {
            val probe = SenderClient(host, port, deviceName, scope)
            val info = try { probe.info() } catch (e: Exception) { null } finally { probe.close() }
            val t = if (info != null) Target(info.id, info.name, host, port, Target.Kind.MANUAL, info.platform)
            else Target("$host:$port", host, host, port, Target.Kind.MANUAL)
            if (isConnected(t)) { messages.emit("«${t.name}» уже подключён"); return@launch }
            manual.value = manual.value.filter { it.id != t.id } + t
            connect(t)
        }
    }

    /** Тот же экран в нескольких видах (по адресу и найденный в сети): сравнение по id приёмника. */
    fun sameScreen(a: Target, o: OutputView): Boolean = a.id == o.target.id || a.id == o.info?.id

    /** Кабель HDMI или Миракаст: приёмник в этом же телефоне, сопряжение не нужно. */
    fun setCable(t: Target?, token: String?) {
        val old = cable.value
        if (t != null && token != null) {
            prefs.edit().putString(key(t), token).apply()
            cable.value = t
            if (!isConnected(t)) open(t, token)
        } else {
            old?.let { c -> outs.value.firstOrNull { it.target.id == c.id }?.let(::close) }
            cable.value = null
        }
    }

    fun cableToken(): String? = prefs.getString("cable", null)

    private fun open(t: Target, token: String) {
        outs.value.firstOrNull { it.target.id == t.id }?.let(::close)
        val c = SenderClient(t.host, t.port, deviceName, scope)
        c.connect(token)
        val o = Output(t, c)
        // Кабель — первым: он рядом и самый надёжный.
        outs.value = if (t.kind == Target.Kind.CABLE || t.kind == Target.Kind.MIRACAST) listOf(o) + outs.value else outs.value + o
        if (t.kind != Target.Kind.CABLE && t.kind != Target.Kind.MIRACAST) remember(t, true)
        o.jobs = listOf(
            scope.launch { c.errors.collect { messages.emit(it.message) } },
            scope.launch {
                var was = false
                c.connection.collect {
                    if (it is Connection.Denied && it.reason == DenyReason.UNKNOWN_TOKEN) {
                        // Приёмник забыл телефон (сброс, переустановка): спрашиваем PIN заново.
                        prefs.edit().remove(key(t)).apply()
                        needPin.value = t
                    }
                    // Подключился или вернулся — сразу получает то, что сейчас в зале.
                    val now = it is Connection.Connected
                    if (now && !was) live.value?.let { cue -> deliverTo(o, cue) }
                    was = now
                }
            },
            scope.launch {
                var last: String? = null
                c.state.collect { s -> s?.error?.takeIf { it != last }?.let { messages.emit(it) }; last = s?.error }
            },
        )
    }

    private fun close(o: Output) {
        o.jobs.forEach { it.cancel() }
        o.client.close()
        outs.value = outs.value - o
    }

    private fun remember(t: Target, on: Boolean) {
        val set = prefs.getStringSet("outputs", emptySet()).orEmpty().toMutableSet()
        if (on) set += t.id else set -= t.id
        prefs.edit().putStringSet("outputs", set).apply()
    }

    // ——— Слайды элементов ———
    private val scriptureCache = mutableMapOf<BiblePick, List<ScriptureContent>>()

    fun slidesOf(item: LibItem): List<Slide> = when (item.kind) {
        Kind.SONG, Kind.TEXT -> item.slides.mapIndexed { i, s -> Slide.Text(s, item.labels.getOrNull(i)) }
        Kind.BIBLE -> item.bible?.let { scripture(it) }.orEmpty().map { Slide.Scripture(it) }
        Kind.PHOTO -> listOf(Slide.Picture)
        Kind.VIDEO -> listOf(Slide.Movie)
        Kind.PRESENTATION, Kind.FILE -> {
            val pages = if (item.mime == "application/pdf") item.pages else data.value.conversions[item.id]?.pages ?: 0
            if (pages > 0) (0 until pages).map { Slide.Page(it) } else listOf(Slide.Pending)
        }
    }

    fun sourceOf(item: LibItem) = Source(item.id, item.title, item.kind, slidesOf(item), item.style)

    fun scripture(pick: BiblePick): List<ScriptureContent> = scriptureCache.getOrPut(pick) {
        val modules = pick.modules.mapNotNull { f -> bibleModules.value.firstOrNull { it.fileName == f } }
        if (modules.isEmpty()) emptyList() else buildScriptureSlides(BibleRef(pick.book, pick.chapter, pick.from, pick.to), modules, pick.options)
    }

    // ——— Показ ———
    /** Касание слайда: если этот элемент уже в зале — сразу в зал, иначе в предпросмотр. */
    fun tap(cue: Cue) {
        val l = live.value
        if (l != null && l.source.itemId != null && l.source.itemId == cue.source.itemId && !settings.value.alwaysPreview) goLive(cue)
        else preview.value = cue
    }

    fun toPreview(cue: Cue) { preview.value = cue }

    /** «В зал»: предпросмотр (и правка оформления) уходят на экраны. */
    fun previewToLive() {
        draft.value?.let { applyDraft(draftScope) }
        preview.value?.let { goLive(it); messages.tryEmit("В зале") }
    }

    /** Видео в зал с позиции предпросмотра. */
    fun goLiveVideo(cue: Cue, startMs: Long) = goLive(cue.copy(startMs = startMs.coerceAtLeast(0)))

    fun goLive(cue: Cue) {
        live.value = cue
        localMode.value = Mode.NORMAL
        if (preview.value?.same(cue) == true) preview.value = null
        cue.source.itemId?.let { id -> edit { d -> d.copy(recent = (listOf(id) + d.recent.filter { it != id }).take(12)) } }
        deliver(cue)
    }

    fun step(delta: Int) {
        val c = live.value ?: return
        val i = c.index + delta
        if (i < 0 || i >= c.source.slides.size) return
        goLive(c.copy(index = i))
    }

    fun previewStep(delta: Int) {
        val c = preview.value ?: return
        val i = (c.index + delta).coerceIn(0, c.source.slides.lastIndex)
        preview.value = c.copy(index = i)
    }

    fun mode(m: Mode) {
        val next = if (mode.value == m) Mode.NORMAL else m
        localMode.value = next
        outs.value.forEach { it.client.mode(next) }
        if (dlnaTarget.value != null) scope.launch { runCatching { dlna.toggle(m) } }
    }

    fun video(action: VideoAction, deltaMs: Long = 0, volume: Int? = null, positionMs: Long? = null) {
        val v = receiverState.value?.video
        val cmd = when (action) {
            VideoAction.SEEK -> VideoCommand(action, positionMs = (positionMs ?: ((v?.positionMs ?: 0) + deltaMs)).coerceAtLeast(0))
            VideoAction.LOOP -> VideoCommand(action, loop = !(v?.loop ?: false))
            VideoAction.VOLUME -> VideoCommand(action, volume = volume ?: 100)
            else -> VideoCommand(action)
        }
        outs.value.forEach { it.client.video(cmd) }
        if (dlnaTarget.value != null) scope.launch { runCatching { dlna.video(action, deltaMs) } }
    }

    /** Оформление слайда: слайд → элемент → сетлист → всегда; правка видна только предпросмотру. */
    fun styleFor(cue: Cue?, withDraft: Boolean = false): TextStyle {
        if (withDraft) draft.value?.let { return it }
        val d = data.value
        val id = cue?.source?.itemId
        val base = (id?.let { d.styles["slide:$it:${cue?.index}"] ?: d.styles["item:$it"] })
            ?: d.active?.takeIf { a -> id == null || d.setlists.firstOrNull { it.id == a }?.items?.contains(id) == true }?.let { d.styles["set:$it"] }
            ?: cue?.source?.style
            ?: d.styles["all"]
            ?: TextStyle()
        return if (settings.value.caps) base.copy(caps = true) else base
    }

    enum class StyleScope { SLIDE, ITEM, SET, ALL }

    /** Куда сохраняется правка оформления при «В зал»: по умолчанию — для сетлиста. */
    var draftScope = StyleScope.SET

    fun startDraft() { draft.value = draft.value ?: styleFor(preview.value ?: live.value) }
    fun cancelDraft() { draft.value = null }

    /** Сохраняет правку оформления и показывает её в зале. */
    fun applyDraft(to: StyleScope) {
        val st = draft.value ?: return
        val cue = preview.value ?: live.value
        val id = cue?.source?.itemId
        val key = when (to) {
            StyleScope.SLIDE -> id?.let { "slide:$it:${cue?.index}" }
            StyleScope.ITEM -> id?.let { "item:$it" }
            StyleScope.SET -> data.value.active?.let { "set:$it" }
            StyleScope.ALL -> "all"
        } ?: "all"
        edit { it.copy(styles = it.styles + (key to st.copy(caps = false))) }
        draft.value = null
        live.value?.let(::deliver)
    }

    private fun deliver(cue: Cue) {
        outs.value.forEach { deliverTo(it, cue) }
        if (dlnaTarget.value != null) scope.launch {
            runCatching { contentForDlna(cue)?.let { dlna.show(it) } }.onFailure { messages.emit(it.message ?: "Телевизор не ответил") }
        }
    }

    private fun deliverTo(o: Output, cue: Cue) = scope.launch {
        if (o.client.connection.value !is Connection.Connected) return@launch
        try {
            val content = contentFor(o, cue) ?: return@launch
            // Пока файл шёл, в зал могли отправить другое: устаревший слайд не показывается.
            if (live.value == cue || live.value?.same(cue) == true) o.client.show(content, nextFor(o, cue))
        } catch (e: Exception) {
            messages.emit("«${o.target.name}»: ${e.message ?: "не удалось показать"}")
        }
    }

    private suspend fun contentFor(o: Output, cue: Cue): Content? {
        val item = item(cue.source.itemId)
        return when (val s = cue.slide) {
            is Slide.Text -> TextContent(s.text, style = withBackground(o, styleFor(cue)))
            is Slide.Scripture -> s.content.copy(style = withBackground(o, styleFor(cue)))
            is Slide.Screen -> if (o === primary.value) ScreenContent(s.width, s.height) else null
            is Slide.Picture -> item?.let { ImageContent(fileOn(o, it)) }
            is Slide.Movie -> item?.let { VideoContent(fileOn(o, it), startMs = cue.startMs) }
            is Slide.Page -> item?.let { pageOn(o, it, s.index) }
            is Slide.Pending -> item?.let { convertOn(o, it, cue) }
            null -> null
        }
    }

    /** Следующий слайд для вида «Сцена» на компьютере: только то, что не требует передачи файла. */
    private fun nextFor(o: Output, cue: Cue): Content? {
        val next = cue.copy(index = cue.index + 1, startMs = 0)
        return when (val s = next.slide) {
            is Slide.Text -> TextContent(s.text, style = styleFor(next).copy(backgroundImage = null))
            is Slide.Scripture -> s.content.copy(style = styleFor(next).copy(backgroundImage = null))
            is Slide.Page -> {
                val item = item(cue.source.itemId)
                val ref = item?.let { data.value.hashes[it.id] }?.takeIf { it.sha256 in o.delivered && item.mime == "application/pdf" }
                    ?: item?.let { data.value.conversions[it.id] }?.takeIf { it.output == o.target.id }?.pdf
                ref?.let { PdfContent(it, s.index) }
            }
            else -> null
        }
    }

    /** Фон темы — картинка телефона: передаётся на экран один раз; если не вышло, слайд показывается без неё. */
    private suspend fun withBackground(o: Output, st: TextStyle): TextStyle {
        val ref = st.backgroundImage ?: return st
        if (ref.sha256 in o.delivered) return st
        val uri = data.value.localFiles[ref.sha256] ?: return st.copy(backgroundImage = null)
        return try {
            val src = object : UploadSource {
                override val name = ref.name
                override val size = ref.size
                override val mime = ref.mime
                override fun open(): InputStream = app.contentResolver.openInputStream(Uri.parse(uri)) ?: error("Фон темы недоступен")
            }
            o.client.upload(src, sha256 = ref.sha256)
            o.delivered += ref.sha256
            st
        } catch (e: Exception) {
            messages.emit("Фон темы не передан: ${e.message}")
            st.copy(backgroundImage = null)
        }
    }

    // ——— Темы ———

    val themes: StateFlow<List<Theme>> = data.map { BUILT_IN_THEMES + it.themes }.stateIn(scope, SharingStarted.Eagerly, BUILT_IN_THEMES + store.data.themes)

    fun saveTheme(t: Theme) = edit { d ->
        val own = t.copy(builtIn = false)
        d.copy(themes = if (d.themes.any { it.id == t.id }) d.themes.map { if (it.id == t.id) own else it } else d.themes + own)
    }

    fun deleteTheme(id: String) = edit { d -> d.copy(themes = d.themes.filter { it.id != id }) }

    /** Фото телефона как фон темы: хэш считается сразу, файл передаётся при показе. */
    suspend fun backgroundFrom(uri: Uri): FileRef = withContext(Dispatchers.IO) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        var name = uri.lastPathSegment ?: "background.jpg"
        var size = 0L
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) size = c.getLong(1) }
        }
        val sha = app.contentResolver.openInputStream(uri)!!.use { hashWithProgress(it, size) {} }
        val ref = FileRef(sha, name, size, app.contentResolver.getType(uri) ?: mimeOf(name))
        withContext(Dispatchers.Main) { edit { d -> d.copy(localFiles = d.localFiles + (sha to uri.toString())) } }
        ref
    }

    fun localFile(sha: String): String? = data.value.localFiles[sha]

    private suspend fun pageOn(o: Output, item: LibItem, page: Int): Content? {
        if (item.mime == "application/pdf") return PdfContent(fileOn(o, item), page)
        val conv = data.value.conversions[item.id] ?: return null
        // Переведённый PDF лежит на том компьютере, который переводил.
        return if (conv.output == o.target.id) PdfContent(conv.pdf, page) else null.also {
            messages.emit("«${item.title}» показывается на «${outs.value.firstOrNull { it.target.id == conv.output }?.target?.name ?: "компьютере"}»: другие экраны получают её как PDF")
        }
    }

    /** Презентация или документ: компьютер переводит их в PDF; телевизор так не умеет. */
    private suspend fun convertOn(o: Output, item: LibItem, cue: Cue): Content? {
        val info = (o.client.connection.value as? Connection.Connected)?.receiver ?: return null
        val doc = mediaKindOf(item.title) == MediaKind.DOCUMENT
        if (!info.convert || (doc && !info.documents)) {
            if (outs.value.none { (it.client.connection.value as? Connection.Connected)?.receiver?.convert == true }) {
                messages.emit(if (doc) "Документы переводит компьютер с Microsoft Office или LibreOffice: сохраните файл как PDF"
                else "Этот экран не переводит презентации: сохраните файл как PDF или подключите компьютер")
            }
            return null
        }
        val ref = fileOn(o, item)
        progress.value = "«${item.title}»: перевод в PDF…"
        val conv = try { o.client.convert(ref) } finally { progress.value = null }
        conv.warning?.let { messages.emit(it) }
        edit { d -> d.copy(conversions = d.conversions + (item.id to Conversion(o.target.id, conv.file, conv.pages))) }
        // Теперь у элемента есть страницы: в зале и в списке — первая страница.
        val updated = item(item.id) ?: return null
        val next = Cue(sourceOf(updated), cue.index.coerceAtMost(conv.pages - 1))
        if (live.value?.source?.itemId == item.id) live.value = next
        return PdfContent(conv.file, next.index)
    }

    /** Файл элемента на экране: хэш считается один раз, передача — только если файла там ещё нет. */
    private suspend fun fileOn(o: Output, item: LibItem): FileRef {
        val uri = Uri.parse(item.uri ?: error("Файл «${item.title}» не найден"))
        val known = data.value.hashes[item.id]
        if (known != null && known.sha256 in o.delivered) return known
        val source = sourceFor(uri, item)
        o.syncing.value++
        try {
            // Хэш большого файла считается долго: показываем ход и запоминаем результат.
            val sha = known?.sha256 ?: withContext(Dispatchers.IO) {
                source.open().use { input -> hashWithProgress(input, source.size) { p -> progress.value = "«${item.title}»: подготовка $p%" } }
            }
            val ref = o.client.upload(source, sha256 = sha) { sent, total ->
                val p = if (total == 0L) 100 else (sent * 100 / total).toInt()
                progress.value = "«${item.title}» → ${o.target.name}: $p%"
            }
            o.delivered += ref.sha256
            if (known == null) edit { d -> d.copy(hashes = d.hashes + (item.id to ref)) }
            return ref
        } finally {
            o.syncing.value--
            progress.value = null
        }
    }

    private fun hashWithProgress(input: InputStream, size: Long, onProgress: (Int) -> Unit): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        var done = 0L
        var last = -1
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            done += n
            val p = if (size > 0) (done * 100 / size).toInt() else 0
            if (p != last) { last = p; onProgress(p) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun contentForDlna(cue: Cue): Content? {
        val item = item(cue.source.itemId)
        return when (val s = cue.slide) {
            is Slide.Text -> TextContent(s.text, style = styleFor(cue))
            is Slide.Scripture -> s.content.copy(style = styleFor(cue))
            is Slide.Picture -> item?.let { ImageContent(dlna.register(sourceFor(Uri.parse(it.uri ?: error("Файл «${it.title}» не найден")), it))) }
            is Slide.Movie -> item?.let { VideoContent(dlna.register(sourceFor(Uri.parse(it.uri ?: error("Файл «${it.title}» не найден")), it))) }
            is Slide.Page -> item?.takeIf { it.mime == "application/pdf" }?.let { PdfContent(dlna.register(sourceFor(Uri.parse(it.uri ?: error("Файл «${it.title}» не найден")), it)), s.index) }
            else -> null
        }
    }

    private suspend fun sourceFor(uri: Uri, item: LibItem): UploadSource = withContext(Dispatchers.IO) {
        // Переведённая на телефоне презентация уходит как PDF.
        val name = if (item.mime == "application/pdf" && !item.title.lowercase().endsWith(".pdf")) item.title.substringBeforeLast('.') + ".pdf" else item.title
        if (item.kind == Kind.PHOTO && (name.lowercase().endsWith(".heic") || name.lowercase().endsWith(".heif"))) heicToJpeg(uri, name)
        else if (item.size > 0) object : UploadSource {
            override val name = name
            override val size = item.size
            override val mime = item.mime ?: mimeOf(name)
            override fun open(): InputStream = app.contentResolver.openInputStream(uri) ?: error("Файл «$name» недоступен")
        } else {
            val tmp = File(app.cacheDir, "upload-" + name.replace('/', '_'))
            app.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            fileSource(tmp, name)
        }
    }

    // ——— Библиотека ———
    /** Файлы телефона в библиотеку: доступ к ним сохраняется, сам файл не копируется. */
    fun importFiles(uris: List<Uri>, preferPresentation: Boolean = false, then: (LibItem) -> Unit = {}) = scope.launch {
        for (uri in uris) {
            try {
                val item = withContext(Dispatchers.IO) { describe(uri, preferPresentation) }
                if (item == null) { messages.emit("Этот формат не поддерживается"); continue }
                edit { d -> d.copy(items = listOf(item) + d.items) }
                then(item)
            } catch (e: Exception) {
                messages.emit(e.message ?: "Не удалось открыть файл")
            }
        }
    }

    /**
     * Демо-материалы при первом запуске: презентация, PDF, фото, видео, песни, текст, Писание и сетлист «Демо»,
     * чтобы приложение можно было попробовать сразу. Удаляются как обычные элементы библиотеки.
     */
    private fun seedDemo() = scope.launch {
        if (data.value.demoSeeded) return@launch
        edit { it.copy(demoSeeded = true) }
        val files = withContext(Dispatchers.IO) {
            val dir = File(app.filesDir, "demo").apply { mkdirs() }
            app.assets.list("demo").orEmpty().sorted().mapNotNull { name ->
                runCatching {
                    val f = File(dir, name)
                    if (!f.isFile) app.assets.open("demo/$name").use { input -> f.outputStream().use { input.copyTo(it) } }
                    describe(Uri.fromFile(f), preferPresentation = false)?.let { it.copy(title = name) }
                }.getOrNull()
            }
        }
        val songs = listOf(
            LibItem(Store.newId(), Kind.SONG, "Коль славен наш Господь в Сионе", slides = listOf(
                "Коль славен наш Господь в Сионе,\nНе может изъяснить язык.\nВелик Он в небесах на троне,\nВ былинках на земле велик.",
                "Везде, Господь, везде Ты славен,\nВо дни, в нощи сияньем равен.",
                "Тебя Твой агнец златорунный\nВ себе изображает нам;\nПсалтирью мы десятиструнной\nТебе приносим фимиам.",
            ), labels = listOf("Куплет 1", "Припев", "Куплет 2")),
            LibItem(Store.newId(), Kind.SONG, "Amazing Grace", slides = listOf(
                "Amazing grace! How sweet the sound\nThat saved a wretch like me!\nI once was lost, but now am found;\nWas blind, but now I see.",
                "'Twas grace that taught my heart to fear,\nAnd grace my fears relieved;\nHow precious did that grace appear\nThe hour I first believed.",
            ), labels = listOf("Verse 1", "Verse 2")),
            LibItem(Store.newId(), Kind.TEXT, "Объявления", slides = listOf(
                "Добро пожаловать!\nНачало в 10:00",
                "Чай после встречи\nв малом зале",
                "Молодёжная встреча\nв пятницу в 19:00",
            )),
            LibItem(Store.newId(), Kind.BIBLE, "Ин 3:16–17", bible = BiblePick(500, 3, 16, 17, DefaultBibles.FILES, ScriptureOptions(sideBySide = true))),
            LibItem(Store.newId(), Kind.BIBLE, "Пс 22:1–4", bible = BiblePick(230, 22, 1, 4, DefaultBibles.FILES, ScriptureOptions(sideBySide = true))),
        )
        // Тема с фото гор на фоне: пример своей темы.
        val mountains = files.firstOrNull { it.kind == Kind.PHOTO && it.title.startsWith("Горы") }
        val theme = mountains?.uri?.let { u ->
            runCatching { backgroundFrom(Uri.parse(u)) }.getOrNull()?.let { ref ->
                Theme(Store.newId(), "Горы", TextStyle(backgroundImage = ref, box = 0x66000000, name = "Горы"))
            }
        }
        val all = songs + files
        val demo = Setlist(Store.newId(), "Демо", LocalDate.now().toString(), all.map { it.id })
        edit { d ->
            d.copy(
                items = all + d.items,
                setlists = d.setlists.filter { it.items.isNotEmpty() || it.id != d.active } + demo,
                active = demo.id,
                themes = d.themes + listOfNotNull(theme),
            )
        }
    }

    private fun describe(uri: Uri, preferPresentation: Boolean): LibItem? {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) size = c.getLong(1) }
        }
        val mime = app.contentResolver.getType(uri) ?: mimeOf(name)
        val kind = when (mediaKindOf(name) ?: kindFromMime(mime)) {
            MediaKind.IMAGE -> Kind.PHOTO
            MediaKind.VIDEO -> Kind.VIDEO
            MediaKind.PDF -> if (preferPresentation) Kind.PRESENTATION else Kind.FILE
            MediaKind.PRESENTATION -> Kind.PRESENTATION
            MediaKind.DOCUMENT -> Kind.FILE
            MediaKind.TEXT -> Kind.TEXT
            null -> return null
        }
        val id = Store.newId()
        val ext = name.substringAfterLast('.', "").lowercase()
        // PPTX переводится в PDF прямо на телефоне: превью слайдов и показ на любом экране без компьютера.
        if (kind == Kind.PRESENTATION && ext in PptxPdf.FORMATS) {
            progress.value = "«$name»: перевод в PDF…"
            try {
                val src = File(app.cacheDir, "pptx-$id.$ext")
                app.contentResolver.openInputStream(uri)!!.use { input -> src.outputStream().use { input.copyTo(it) } }
                val out = File(File(app.filesDir, "converted").apply { mkdirs() }, "$id.pdf")
                val warnings = try { PptxPdf.convert(app, src, out) } finally { src.delete() }
                val pages = app.contentResolver.openFileDescriptor(Uri.fromFile(out), "r")!!.use { fd -> PdfRenderer(fd).use { it.pageCount } }
                PptxPdf.warning(warnings)?.let { messages.tryEmit(it) }
                return LibItem(id, Kind.PRESENTATION, name, uri = Uri.fromFile(out).toString(), mime = "application/pdf", size = out.length(), pages = pages)
            } finally {
                progress.value = null
            }
        }
        return when (kind) {
            Kind.TEXT -> {
                val text = app.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                LibItem(id, Kind.TEXT, name.substringBeforeLast('.'), slides = splitIntoSlides(text))
            }
            Kind.VIDEO -> {
                val dur = runCatching {
                    MediaMetadataRetriever().run {
                        setDataSource(app, uri)
                        (extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L).also { release() }
                    }
                }.getOrDefault(0L)
                LibItem(id, kind, name, uri = uri.toString(), mime = mime, size = size, durationMs = dur)
            }
            else -> {
                val pages = if (mime == "application/pdf" || name.lowercase().endsWith(".pdf"))
                    runCatching { app.contentResolver.openFileDescriptor(uri, "r")!!.use { fd -> PdfRenderer(fd).use { it.pageCount } } }.getOrDefault(0)
                else 0
                LibItem(id, kind, name, uri = uri.toString(), mime = if (pages > 0) "application/pdf" else mime, size = size, pages = pages)
            }
        }
    }

    fun saveItem(item: LibItem) = edit { d ->
        val exists = d.items.any { it.id == item.id }
        d.copy(items = if (exists) d.items.map { if (it.id == item.id) item else it } else listOf(item) + d.items)
    }

    fun deleteItem(id: String) = edit { d ->
        File(File(app.filesDir, "converted"), "$id.pdf").delete()
        d.copy(
            items = d.items.filter { it.id != id },
            setlists = d.setlists.map { s -> s.copy(items = s.items.filter { it != id }) },
            recent = d.recent - id,
        )
    }

    // ——— Сетлисты ———
    val activeSet: StateFlow<Setlist?> = data.map { d -> d.setlists.firstOrNull { it.id == d.active } ?: d.setlists.firstOrNull() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun pickSet(id: String) = edit { it.copy(active = id) }

    fun newSet(title: String, date: LocalDate): Setlist {
        val s = Setlist(Store.newId(), title.ifBlank { "Новый сетлист" }, date.toString())
        edit { d -> d.copy(setlists = listOf(s) + d.setlists, active = s.id) }
        return s
    }

    fun saveSet(s: Setlist) = edit { d -> d.copy(setlists = d.setlists.map { if (it.id == s.id) s else it }) }

    fun duplicateSet(id: String, date: LocalDate) = edit { d ->
        val s = d.setlists.firstOrNull { it.id == id } ?: return@edit d
        val copy = s.copy(id = Store.newId(), date = date.toString())
        d.copy(setlists = listOf(copy) + d.setlists)
    }

    fun deleteSet(id: String): Boolean {
        if (data.value.active == id) return false
        edit { d -> d.copy(setlists = d.setlists.filter { it.id != id }) }
        return true
    }

    fun addToSet(itemId: String) = edit { d ->
        val a = d.active ?: return@edit d
        d.copy(setlists = d.setlists.map { if (it.id == a && itemId !in it.items) it.copy(items = it.items + itemId) else it })
    }

    fun moveInSet(itemId: String, delta: Int) = edit { d ->
        val a = d.active ?: return@edit d
        d.copy(setlists = d.setlists.map { s ->
            if (s.id != a) return@map s
            val i = s.items.indexOf(itemId)
            val j = (i + delta).coerceIn(0, s.items.lastIndex)
            if (i < 0 || i == j) s else s.copy(items = s.items.toMutableList().apply { add(j, removeAt(i)) })
        })
    }

    fun removeFromSet(itemId: String) = edit { d ->
        val a = d.active ?: return@edit d
        d.copy(setlists = d.setlists.map { if (it.id == a) it.copy(items = it.items - itemId) else it })
    }

    // ——— Разовые показы: текст, Писание, Holyrics ———
    fun textSource(text: String, title: String = "Текст"): Source? {
        val slides = splitIntoSlides(text)
        return if (slides.isEmpty()) null else Source(null, title, Kind.TEXT, slides.map { Slide.Text(it) })
    }

    /** Слайды из другой программы (Holyrics): с её оформлением и подписями. */
    fun showSlides(title: String, slides: List<String>, style: TextStyle?, labels: List<String?> = emptyList(), index: Int = 0) {
        if (slides.isEmpty()) return
        val src = Source(null, title, Kind.SONG, slides.mapIndexed { i, s -> Slide.Text(s, labels.getOrNull(i)) }, style)
        goLive(Cue(src, index.coerceIn(0, slides.lastIndex)))
    }

    fun showImage(ref: FileRef) = showRemote(ref, false)
    fun showVideo(ref: FileRef) = showRemote(ref, true)

    /** Файл уже на главном экране (например из Holyrics): показывается без передачи. */
    private fun showRemote(ref: FileRef, video: Boolean) {
        val src = Source(null, ref.name, if (video) Kind.VIDEO else Kind.PHOTO, listOf(if (video) Slide.Movie else Slide.Picture))
        live.value = Cue(src, 0)
        primary.value?.client?.show(if (video) VideoContent(ref) else ImageContent(ref))
    }

    suspend fun importFromHolyrics(req: app.deixis.core.HolyricsMediaRequest): FileRef? {
        val o = outs.value.firstOrNull { (it.client.connection.value as? Connection.Connected)?.receiver?.holyrics == true } ?: return null
        progress.value = "«${req.name}»: из Holyrics"
        return try { o.client.importFromHolyrics(req) } finally { progress.value = null }
    }

    fun importBible(uri: Uri) = scope.launch {
        try {
            val m = withContext(Dispatchers.IO) { bible.import(uri) }
            bibleModules.value = withContext(Dispatchers.IO) { bible.modules() }
            scriptureCache.clear()
            messages.emit("Добавлен перевод «${m.title}»")
        } catch (e: Exception) {
            messages.emit(e.message ?: "Не удалось добавить модуль")
        }
    }

    fun removeBible(fileName: String) {
        bible.remove(fileName)
        bible.selected = bible.selected - fileName
        bibleModules.value = bible.modules()
        scriptureCache.clear()
    }

    // ——— Демонстрация экрана ———
    fun openScreenStream(): ScreenStream? {
        val o = primary.value ?: return null
        if (o.client.connection.value !is Connection.Connected) return null
        screenStream?.close()
        return o.client.openStream().also { screenStream = it }
    }

    fun showScreen(width: Int, height: Int) = scope.launch {
        sharing.value = true
        val cue = Cue(Source(null, "Экран телефона", null, listOf(Slide.Screen(width, height))), 0)
        live.value = cue
        primary.value?.client?.show(ScreenContent(width, height))
    }

    fun screenStopped(error: String?) = scope.launch {
        screenStream?.close()
        screenStream = null
        val wasSharing = sharing.value
        sharing.value = false
        if (live.value?.slide is Slide.Screen) {
            live.value = null
            // Последний кадр мог захватить шторку уведомлений: в зале остаётся чёрный экран, а не он.
            if (wasSharing) primary.value?.client?.mode(Mode.BLACK)
        }
        error?.let { messages.emit(it) }
    }

    /** Проверка перед началом: какие экраны требуют внимания. */
    fun check(): String {
        val list = outputs.value
        if (list.isEmpty()) return "Ни один экран не подключён"
        val bad = list.filter { it.state != OutState.ONLINE }
        return if (bad.isEmpty()) "Все экраны готовы" else "Требует внимания: " + bad.joinToString { "${it.target.name} (${it.state.text})" }
    }

    /** Большой файл: не передаётся, показывается демонстрацией экрана. */
    fun largeFor(item: LibItem): LargeFile? =
        if (item.size > FILE_LIMIT_BYTES && item.uri != null) LargeFile(Uri.parse(item.uri), item.title, item.mime ?: mimeOf(item.title)) else null

    private fun heicToJpeg(uri: Uri, name: String): UploadSource {
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri))
        } else {
            app.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) } ?: error("Не удалось прочитать «$name»")
        }
        val jpegName = name.substringBeforeLast('.') + ".jpg"
        val tmp = File(app.cacheDir, "upload-$jpegName")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        return fileSource(tmp, jpegName)
    }

    private fun fileSource(file: File, name: String) = object : UploadSource {
        override val name = name
        override val size = file.length()
        override val mime = mimeOf(name)
        override fun open(): InputStream = file.inputStream()
    }

    private fun kindFromMime(mime: String?): MediaKind? = when {
        mime == null -> null
        mime.startsWith("image/") -> MediaKind.IMAGE
        mime.startsWith("video/") || mime.startsWith("audio/") -> MediaKind.VIDEO
        mime == "application/pdf" -> MediaKind.PDF
        mime.startsWith("text/") -> MediaKind.TEXT
        else -> null
    }

    private fun key(t: Target) = if (t.kind == Target.Kind.CABLE || t.kind == Target.Kind.MIRACAST) "cable" else t.id
}
