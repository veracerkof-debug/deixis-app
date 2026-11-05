package app.deixis.phone

import kotlinx.coroutines.launch
import app.deixis.core.LogoStyle
import app.deixis.core.Mode

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.deixis.core.qrMatrix
import java.time.LocalDate

/** Действия, которым нужен системный экран: выбор файлов, разрешение на трансляцию. */
class Acts(
    val pick: (mimes: Array<String>, presentation: Boolean, then: (LibItem) -> Unit) -> Unit,
    val share: (LargeFile?) -> Unit,
    val addBible: () -> Unit,
    val holyrics: () -> Unit,
)

val LocalActs = staticCompositionLocalOf<Acts> { error("Acts") }

@Composable
fun rememberActs(c: Controller, ui: Ui): Acts {
    val context = LocalContext.current
    var then by remember { mutableStateOf<((LibItem) -> Unit)?>(null) }
    var presentation by remember { mutableStateOf(false) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) c.importFiles(uris, presentation) { then?.invoke(it) }
    }
    val bible = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(c::importBible) }

    // Трансляция экрана: разрешение на уведомление (Android 13+), затем системный запрос на захват.
    var openAfter by remember { mutableStateOf<LargeFile?>(null) }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            ScreenShareService.start(context, r.resultCode, data, sound = c.settings.value.shareSound)
            // Большой файл открывается в проигрывателе телефона, а в зал идёт экран.
            openAfter?.let { f ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            }
        }
        openAfter = null
    }
    val requestCapture = { capture.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { requestCapture() }
    val afterSound = {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else requestCapture()
    }
    // Звук приложений телефона: Android даёт его снимать только с разрешением на запись звука (микрофон не включается).
    val soundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) ui.toast(L.noSoundPermission)
        afterSound()
    }

    return remember(c, ui) {
        Acts(
            pick = { mimes, pres, cb ->
                then = cb; presentation = pres
                runCatching { files.launch(mimes) }.onFailure { ui.toast(L.cantOpenPicker) }
            },
            share = { f ->
                if (c.outputs.value.none { it.state == OutState.ONLINE && it.target.kind != Target.Kind.DLNA }) ui.toast(L.needScreenFirst)
                else {
                    openAfter = f
                    val wantSound = c.settings.value.shareSound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    if (wantSound && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        soundPermission.launch(Manifest.permission.RECORD_AUDIO)
                    } else afterSound()
                }
            },
            addBible = { runCatching { bible.launch(arrayOf("*/*")) } },
            holyrics = { ui.holyrics = true },
        )
    }
}

// ——— Подключить ———

@Composable
fun ConnectSheet(c: Controller, ui: Ui) {
    val targets by c.targets.collectAsState()
    val outputs by c.outputs.collectAsState()
    val context = LocalContext.current
    GHead(L.connectedState)
    Group {
        if (outputs.isEmpty()) Cell(L.noScreenYet, chevron = null)
        outputs.forEachIndexed { i, o ->
            if (i > 0) Sep()
            OutputCell(c, o)
        }
    }
    val free = targets.filter { t -> outputs.none { c.sameScreen(t, it) } }
    GHead(L.foundOnNet)
    Group {
        if (free.isEmpty()) Cell(L.searchingScreens, L.sameWifi, chevron = null)
        free.forEachIndexed { i, t ->
            if (i > 0) Sep()
            Cell(t.name, t.via + if (t.host.isNotEmpty() && t.kind == Target.Kind.NETWORK) " · ${t.host}" else "", t.icon, chevron = null) {
                Pill(L.connect, tint = true) { c.connect(t) }
            }
        }
        Sep()
        Cell(L.manualAddress, kind = "plus", onClick = { ui.push(L.receiverAddress) { ManualSheet(c, ui) } })
        Sep()
        Cell(L.miracastCell, L.miracastSub, "cast", onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_CAST_SETTINGS)) }.onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            }
        })
    }
    Note(L.connectNote)
}

/** Экран в списке подключённых: состояние, повтор, отключение. */
@Composable
fun OutputCell(c: Controller, o: OutputView) {
    val ui = LocalUi.current
    // Экран Deixis в сети: касание открывает его обновления.
    val open = if (o.state == OutState.ONLINE && (o.target.kind == Target.Kind.NETWORK || o.target.kind == Target.Kind.MANUAL)) ({ ui.push(L.screenNamed(o.info?.name ?: o.target.name)) { ScreenUpdateSheet(c, o.target) } }) else null
    // Имя — то, что сообщил сам экран, а не адрес, по которому его подключили.
    Cell(o.info?.name ?: o.target.name, "${o.target.via} · ${o.state.text}" + (o.info?.appVersion?.let { " · $it" } ?: ""), o.target.icon, chevron = null, onClick = open) {
        when (o.state) {
            OutState.OFFLINE -> Pill(L.retry) { c.retry(o.target) }
            OutState.PIN -> Pill("PIN", tint = true) { c.needPin.value = o.target }
            else -> if (o.target.kind != Target.Kind.CABLE && o.target.kind != Target.Kind.MIRACAST) Pill(L.disconnect) { c.disconnect(o.target) }
        }
    }
}

@Composable
fun PinSheet(c: Controller, t: Target) {
    val ui = LocalUi.current
    val need by c.needPin.collectAsState()
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(need) { if (need == null) ui.close() }
    DisposableEffect(Unit) { onDispose { if (c.needPin.value == t) c.needPin.value = null } }
    Note(L.pinFor(t.name), Modifier)
    Field(pin, "0000", keyboard = KeyboardType.NumberPassword, code = true) { pin = it.filter(Char::isDigit).take(4) }
    Spacer(Modifier.height(12.dp))
    Btn(L.connect, kind = BtnKind.TINT, enabled = pin.length == 4) { c.pair(t, pin) }
}

/** «Новое подключение»: устройство ввело верный PIN на экране и ждёт решения. Решили на экране — шторка уходит. */
@Composable
fun ApproveSheet(c: Controller, a: Approval) {
    val ui = LocalUi.current
    val list by c.approvals.collectAsState()
    LaunchedEffect(list) { if (list.none { it.request.id == a.request.id }) ui.close() }
    Group { Cell(a.request.sender.ifBlank { L.browserDevice }, L.wantsScreen(a.target.name, a.request.address), "phone", chevron = null) }
    Note(L.newConnectAsk)
    Spacer(Modifier.height(12.dp))
    Btn(L.allow, kind = BtnKind.TINT) { c.answerConnect(a, true); ui.close() }
    Spacer(Modifier.height(8.dp))
    Btn(L.reject) { c.answerConnect(a, false); ui.close() }
}

@Composable
private fun ManualSheet(c: Controller, ui: Ui) {
    var addr by remember { mutableStateOf("") }
    Field(addr, "192.168.1.20", keyboard = KeyboardType.Uri) { addr = it.trim() }
    Spacer(Modifier.height(12.dp))
    Btn(L.connect, kind = BtnKind.TINT, enabled = addr.isNotBlank()) { c.addManual(addr); ui.close() }
    Note(L.manualNote)
}

// ——— Настройки ———

@Composable
fun SettingsSheet(c: Controller) {
    val st by c.settings.collectAsState()
    val context = LocalContext.current
    val themes = listOf("light" to L.uiLight, "dark" to L.uiDark, "paper" to L.paper, "oled" to "OLED")
    val ui = LocalUi.current
    val book by c.profiles.collectAsState()
    GHead(L.profile)
    Group {
        Cell(book.current.name, L.profilesSub(book.list.size), book.current.kind.icon, onClick = { ui.push(L.profiles) { ProfilesSheet(c, ui) } })
    }
    GHead(L.theme)
    Seg(themes.map { it.second }, themes.indexOfFirst { it.first == st.theme }.coerceAtLeast(0), Modifier.fillMaxWidth().padding(bottom = 18.dp)) { i ->
        c.setSettings { it.copy(theme = themes[i].first) }
    }
    // Язык пульта: названия языков — на своём языке; «Как в системе» — по языку телефона.
    GHead(L.language)
    val langs = L.languages
    Seg(langs.map { it.second }, langs.indexOfFirst { it.first == st.lang }.coerceAtLeast(0), Modifier.fillMaxWidth().padding(bottom = T.gutter), scroll = true) { i ->
        c.setSettings { it.copy(lang = langs[i].first) }
    }
    GHead(L.tabShow)
    Group {
        SwitchRow(L.alwaysPre, st.alwaysPreview, L.alwaysPreSub) { v -> c.setSettings { it.copy(alwaysPreview = v) } }
        Sep(T.gutter)
        SwitchRow(L.darkDuringShow, st.darkDuringShow, L.darkDuringShowSub) { v -> c.setSettings { it.copy(darkDuringShow = v) } }
        Sep(T.gutter)
        SwitchRow(L.clicker, st.clicker) { v -> c.setSettings { it.copy(clicker = v) } }
        Sep(T.gutter)
        SwitchRow(L.volumeKeys, st.volumeKeys) { v -> c.setSettings { it.copy(volumeKeys = v) } }
        Sep(T.gutter)
        SwitchRow(L.caps, st.caps, L.capsSub) { v -> c.setSettings { it.copy(caps = v) } }
    }
    GHead(L.style)
    Group {
        Cell(L.defaultStyle, L.defaultStyleSub, kind = "brush", onClick = {
            ui.push(L.defaultStyle) { DefaultStyleEditor(c, ui) }
        })
    }
    GHead(L.logoScreen)
    Seg(listOf("ΔΕΙΞΙΣ", "Δ", "δεῖξις", "Deixis"), LogoStyle.entries.indexOf(st.logo), Modifier.fillMaxWidth()) { i ->
        c.setSettings { it.copy(logo = LogoStyle.entries[i]) }
    }
    // Своя картинка вместо знака: логотип церкви, школы, афиша.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { c.backgroundFrom(uri) }
                .onSuccess { ref -> c.setSettings { it.copy(logoImage = ref) } }
                .onFailure { e -> ui.toast(e.message ?: L.pictureNotOpened) }
        }
    }
    Group(Modifier.padding(top = T.gapS)) {
        Cell(
            L.ownPicture, st.logoImage?.name ?: L.ownPictureSub, kind = "photo",
            onClick = { pick.launch(arrayOf("image/*")) },
        ) {
            if (st.logoImage != null) Pill(L.remove) { c.setSettings { it.copy(logoImage = null) } }
        }
    }
    SlideView(null, null, c.styleFor(null), Modifier.padding(top = T.gapS), mode = Mode.LOGO, logo = st.logo)
    Note(if (st.logoImage != null) L.logoNoteImage
        else L.logoNote)
    GHead(L.sections)
    Group {
        SwitchRow(L.church, st.church, L.churchSub) { v -> c.setSettings { it.copy(church = v) } }
    }
    @Suppress("UNUSED_VARIABLE") val data by c.data.collectAsState()
    GHead(L.demo)
    Group {
        if (c.hasDemo()) {
            Cell(L.removeDemo, L.removeDemoSub, kind = "trash", onClick = {
                ui.push(L.removeDemo) {
                    Note(L.removeDemoNote, Modifier.padding(bottom = T.gap))
                    Btn(L.remove, kind = BtnKind.RED) { c.removeDemo(); ui.closeAll(); ui.toast(L.demoRemoved) }
                }
            })
            Sep(T.gutter)
        }
        Cell(if (c.hasDemo()) L.updateDemo else L.restoreDemo, L.restoreDemoSub, kind = "layers", onClick = {
            c.restoreDemo(); ui.closeAll(); ui.toast(L.demoOnMain)
        })
    }
    BackupsSection(c, LocalUi.current)
    UpdatesSection()
    FeedbackSection()
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = T.gapS)) {
        Mark(T.fPill, P.current.l2)
        Txt(" · $version · GPL-3.0 · " + L.ethics, T.fSub, P.current.l2)
    }
}

// ——— Плитки «Показать» ———

enum class PickKind(val mimes: Array<String>) {
    PRESENTATION(
        arrayOf(
            "application/pdf", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-powerpoint",
            "application/vnd.oasis.opendocument.presentation", "application/x-iwork-keynote-sffkey", "application/octet-stream",
        ),
    ),
    VIDEO(arrayOf("video/*", "audio/*")),
    PHOTO(arrayOf("image/*")),
    FILE(arrayOf("*/*"));

    /** Какие форматы принимаются: подпись в шторке выбора. */
    val formats: String get() = when (this) { PRESENTATION -> L.fmtPres; VIDEO -> L.fmtVideo; PHOTO -> "JPEG, PNG, WebP, GIF, HEIC"; FILE -> L.fmtFile }
}

private fun PickKind.matches(k: Kind) = when (this) {
    PickKind.PRESENTATION -> k == Kind.PRESENTATION
    PickKind.VIDEO -> k == Kind.VIDEO
    PickKind.PHOTO -> k == Kind.PHOTO
    PickKind.FILE -> k == Kind.FILE || k == Kind.PRESENTATION
}

/** Показать файл: в сетлист и в предпросмотр, слайды раскрываются на главном. */
fun showItem(c: Controller, ui: Ui, item: LibItem) {
    c.addToSet(item.id)
    ui.listSeg = 0
    ui.open = item.id
    ui.tab = AppTab.SHOW
    val big = c.largeFor(item)
    if (big != null) { ui.sheet(L.fileOver1Gb) { LargeSheet(c, ui, big) }; return }
    c.toPreview(Cue(c.sourceOf(item), 0))
}

@Composable
fun PickSheet(c: Controller, ui: Ui, kind: PickKind) {
    val data by c.data.collectAsState()
    val acts = LocalActs.current
    val list = data.items.filter { kind.matches(it.kind) }.take(12)
    if (list.isNotEmpty()) {
        GHead(L.recentPlural)
        Group {
            list.forEachIndexed { i, it ->
                if (i > 0) Sep()
                Cell(it.title, subOf(c, it), it.kind.icon, item = it, onClick = { ui.closeAll(); showItem(c, ui, it) })
            }
        }
    }
    Btn(L.phoneFilesDots, icon = "file") {
        ui.closeAll()
        acts.pick(kind.mimes, kind == PickKind.PRESENTATION) { showItem(c, ui, it) }
    }
    Note(kind.formats)
}

/** «Текст» на главном: разовый показ со своими слайдами, по желанию — в библиотеку. */
@Composable
fun TextSheet(c: Controller, ui: Ui) = SongEditor(c, ui, Kind.TEXT, quick = true)

@Composable
fun MirrorSheet(c: Controller, ui: Ui) {
    val acts = LocalActs.current
    val st by c.settings.collectAsState()
    Note(L.mirrorNote, Modifier)
    Group(Modifier.padding(top = T.gap)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SwitchRow(L.withSound, st.shareSound, L.withSoundSub) { v -> c.setSettings { it.copy(shareSound = v) } }
        } else {
            Cell(L.noSound, L.noSoundSub, "vol", chevron = null)
        }
    }
    Btn(L.startShare, icon = "phone", kind = BtnKind.TINT) { ui.closeAll(); acts.share(null) }
}

@Composable
fun LargeSheet(c: Controller, ui: Ui, f: LargeFile) {
    val acts = LocalActs.current
    Note(L.largeNote(f.name), Modifier)
    Btn(L.showViaPhone, icon = "phone", kind = BtnKind.TINT) { ui.closeAll(); acts.share(f) }
}

// ——— Найти ———

fun LibItem.matches(q: String): Boolean {
    if (q.isBlank()) return true
    // Без перевода в нижний регистр каждой строки: на тысячах песен это десятки тысяч строк на каждую букву.
    val needle = q.trim()
    return title.contains(needle, ignoreCase = true) || slides.any { it.contains(needle, ignoreCase = true) }
}

@Composable
fun FindSheet(c: Controller, ui: Ui) {
    val data by c.data.collectAsState()
    val set by c.activeSet.collectAsState()
    var q by remember { mutableStateOf("") }
    SearchField(q, L.findHint, Modifier.padding(bottom = T.gap)) { q = it }
    val all = remember(data.items, q) { data.items.filter { it.matches(q) } }
    // Лист не прокручивается списком: при пустом запросе и тысячах песен он строил бы их все сразу.
    val found = all.take(FIND_SHOWN)
    Group {
        if (found.isEmpty()) Cell(L.nothingFound, chevron = null)
        found.forEachIndexed { i, it ->
            if (i > 0) Sep()
            Cell(it.title, subOf(c, it), it.kind.icon, chevron = null, item = it, onClick = {
                ui.closeAll(); ui.tab = AppTab.SHOW
                tapToPreview(c, ui, it)
            }) {
                if (set?.items?.contains(it.id) == true) Tag(L.inSet, false)
                else Pill(L.toSet) { c.addToSet(it.id); ui.toast(L.addedToSet) }
            }
        }
    }
    if (all.size > found.size) Note(L.firstNofM(FIND_SHOWN, all.size), Modifier.padding(top = T.gapS))
    Note(L.findNote)
}

fun tapToPreview(c: Controller, ui: Ui, item: LibItem) {
    val big = c.largeFor(item)
    if (big != null) ui.sheet(L.fileOver1Gb) { LargeSheet(c, ui, big) } else c.toPreview(Cue(c.sourceOf(item), 0))
}

// ——— Сетлисты ———

@Composable
fun SetPickSheet(c: Controller, ui: Ui) {
    val data by c.data.collectAsState()
    Group {
        data.setlists.sortedBy { it.date }.forEachIndexed { i, s ->
            if (i > 0) Sep(T.gutter)
            Cell("${s.title}, ${s.dateText}", L.itemsShort(s.items.size) + if (s.past) L.pastSuffix else "", chevron = null, onClick = {
                c.pickSet(s.id); ui.open = s.items.firstOrNull(); ui.closeAll(); ui.tab = AppTab.SHOW
                ui.toast(L.onMainSet("${s.title}, ${s.dateText}"))
            }) {
                if (s.id == data.active) Tag(L.chosen, false)
                IconBtn("trash", L.deleteSetNamed(s.title), P.current.red) { ui.push(L.deleteSet) { DeleteSetSheet(c, ui, s) } }
            }
        }
    }
    Btn(L.newSet, icon = "plus") { ui.push(L.newSet) { NewSetSheet(c, ui) } }
}

/** Удаление сетлиста — с подтверждением. Песни, файлы и тексты остаются в библиотеке. */
@Composable
fun DeleteSetSheet(c: Controller, ui: Ui, s: Setlist) {
    Note(L.setDeleting("${s.title}, ${s.dateText}"), Modifier.padding(bottom = T.gap))
    Btn(L.delete, kind = BtnKind.RED) { c.deleteSet(s.id); ui.closeAll(); ui.toast(L.setDeleted) }
}

/** Новый сетлист или копия прошлого: название и дата. */
@Composable
fun NewSetSheet(c: Controller, ui: Ui, copyOf: Setlist? = null) {
    var title by remember { mutableStateOf(copyOf?.title ?: "") }
    var date by remember { mutableStateOf(LocalDate.now().let { if (copyOf != null) it.plusDays(7) else it }) }
    Column(verticalArrangement = Arrangement.spacedBy(T.gap)) {
        if (copyOf == null) Field(title, L.setTitleHint) { title = it }
        Group {
            Cell(L.date, Setlist("", "", date.toString()).dateText, chevron = null) {
                Stepper("", { date = date.minusDays(1) }, { date = date.plusDays(1) })
            }
        }
        Btn(if (copyOf == null) L.create else L.duplicate, kind = BtnKind.TINT) {
            if (copyOf != null) { c.duplicateSet(copyOf.id, date); ui.toast(L.copyCreatedFor(Setlist("", "", date.toString()).dateText)) }
            else { c.newSet(title, date); ui.toast(L.setCreated) }
            ui.closeAll()
        }
    }
}

/** Меню элемента: удержание в сетлисте или библиотеке. */
@Composable
fun ItemMenu(c: Controller, ui: Ui, item: LibItem, inSet: Boolean) {
    val set by c.activeSet.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(T.gap)) {
        Btn(L.fullScreen, icon = "show") { ui.closeAll(); ui.full = FullSpec.Local(Cue(c.sourceOf(item), 0)) }
        Btn(L.toPreview, icon = "up") { ui.closeAll(); ui.tab = AppTab.SHOW; tapToPreview(c, ui, item) }
        if (item.kind == Kind.SONG || item.kind == Kind.TEXT) Btn(L.editSlides, icon = "edit") { ui.push(L.edit) { EditSheet(c, ui, item.kind, item) } }
        // Тема на весь документ сразу, как в Holyrics: видно в «Предпросмотре», в зал — по «Применить в зал».
        if (item.kind == Kind.SONG || item.kind == Kind.TEXT || item.kind == Kind.BIBLE) Btn(L.style, icon = "brush") {
            ui.closeAll(); ui.tab = AppTab.SHOW
            c.toPreview(Cue(c.sourceOf(item), 0))
            c.draftScope = Controller.StyleScope.ITEM
            openStyle(c, ui)
        }
        // Большой файл — на экраны заранее, пока в зале идёт другое: потом показ без ожидания.
        if (c.canSendAhead(item)) Btn(L.sendAhead, icon = "tv") {
            ui.closeAll()
            ui.toast(if (c.sendAhead(item) > 0) L.sendingBackground else L.noScreens)
        }
        if (item.kind == Kind.BIBLE) Btn(L.otherPlace, icon = "bible") { ui.push(L.tabBible) { BiblePicker(c, ui, item) } }
        if (inSet) {
            Row(horizontalArrangement = Arrangement.spacedBy(T.gap)) {
                Btn(L.up, Modifier.weight(1f)) { c.moveInSet(item.id, -1) }
                Btn(L.down, Modifier.weight(1f)) { c.moveInSet(item.id, 1) }
            }
            Btn(L.removeFromSet, kind = BtnKind.RED) { c.removeFromSet(item.id); ui.closeAll(); ui.toast(L.removedFromSet) }
        } else {
            if (set?.items?.contains(item.id) != true) Btn(L.toSet, icon = "plus") { c.addToSet(item.id); ui.toast(L.addedToSet) }
            Btn(L.deleteFromLib, icon = "trash", kind = BtnKind.RED) {
                ui.push(L.deleteQ(item.title)) {
                    Note(L.deleteItemNote, Modifier)
                    Row(horizontalArrangement = Arrangement.spacedBy(T.gap)) {
                        Btn(L.cancelShort, Modifier.weight(1f)) { ui.close() }
                        Btn(L.delete, Modifier.weight(1f), kind = BtnKind.DANGER) { c.deleteItem(item.id); ui.closeAll(); ui.toast(L.deleted) }
                    }
                }
            }
        }
    }
}

/** Новая песня или текст и правка своих: редактор слайдов, как в Holyrics. */
@Composable
fun EditSheet(c: Controller, ui: Ui, kind: Kind, item: LibItem? = null) = SongEditor(c, ui, kind, item)

// ——— Оформление ———

private val backgrounds: List<Pair<Long, String>> get() = listOf(0xFF000000 to L.black, 0xFF18233A to L.cNavy, 0xFF4A3420 to L.cWarm, 0xFF20443A to L.cGreen)
private val inks: List<Pair<Long, String>> get() = listOf(0xFFFFFFFF to L.cWhite, 0xFFF4EFE6 to L.paper, 0xFFFFD43B to L.cGold)

@Composable
fun StyleSheet(c: Controller, ui: Ui) {
    val draft by c.draft.collectAsState()
    val d = draft ?: return
    var to by remember { mutableStateOf(c.draftScope) }
    val set = { st: app.deixis.core.TextStyle -> c.draft.value = st }
    GHead(L.themes)
    ThemesRow(c, ui, d) { t -> set(t.style) }
    Note(L.themesHint, Modifier.padding(bottom = T.gapS))
    GHead(L.quickEdit)
    Group {
        Cell(L.background, chevron = null) {
            Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) { backgrounds.forEach { (v, n) -> Swatch(Color(v), d.background == v, L.bgNamed(n)) { set(d.copy(background = v)) } } }
        }
        Sep(T.gutter)
        Cell(L.textColor, chevron = null) {
            Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) { inks.forEach { (v, n) -> Swatch(Color(v), d.color == v, L.colorNamed(n)) { set(d.copy(color = v)) } } }
        }
        Sep(T.gutter)
        Cell(L.textSize, chevron = null) {
            Stepper("${d.sizePercent.toInt()}", { set(d.copy(sizePercent = textSizeStep(d.sizePercent, up = false))) }, { set(d.copy(sizePercent = textSizeStep(d.sizePercent, up = true))) })
        }
        Sep(T.gutter)
        SwitchRow(L.shadow, d.shadow) { set(d.copy(shadow = it)) }
        Sep(T.gutter)
        SwitchRow(L.outline, d.outline) { set(d.copy(outline = it)) }
        Sep(T.gutter)
        Cell(L.allSettings, L.allSettingsSub, onClick = {
            ui.push(L.allStyleSettings) { DraftControls(c, ui) }
        })
    }
    GHead(L.applyTo)
    val scopes = Controller.StyleScope.entries
    val cue = c.preview.value ?: c.live.value
    val doc = if (cue?.source?.kind == Kind.SONG) L.wholeSong else L.wholeDoc
    Seg(listOf(L.thisSlide, doc, L.setlist, L.byDefault), scopes.indexOf(to), Modifier.fillMaxWidth().padding(bottom = T.gutter), scroll = true) { to = scopes[it]; c.draftScope = scopes[it] }
    Note(
        when (to) {
            Controller.StyleScope.SLIDE -> L.scopeSlide
            Controller.StyleScope.ITEM -> L.scopeItem
            Controller.StyleScope.SET -> L.scopeSet
            Controller.StyleScope.ALL -> L.scopeAll
        } + L.styleNoteSuffix,
        Modifier,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(T.gap)) {
        Btn(L.cancel, Modifier.weight(1f)) { c.cancelDraft(); ui.closeAll() }
        Btn(L.applyHall, Modifier.weight(1f), kind = BtnKind.TINT) { c.applyDraft(to); ui.closeAll(); ui.toast(L.styleLive) }
    }
}

/** Вся правка оформления во время показа: те же настройки, что у темы; видна в «Предпросмотре». */
@Composable
private fun DraftControls(c: Controller, ui: Ui) {
    val draft by c.draft.collectAsState()
    val d = draft ?: return
    SlideView(Slide.Text(L.sample), null, d, Modifier.padding(bottom = T.gutter))
    StyleControls(c, ui, d) { c.draft.value = it }
    Btn(L.done, kind = BtnKind.TINT) { ui.close() }
}

// ——— Библиотека: добавить ———

@Composable
fun AddSheet(c: Controller, ui: Ui) {
    val acts = LocalActs.current
    val st by c.settings.collectAsState()
    Group {
        Cell(L.newSong, kind = "song", onClick = { ui.push(L.newSong) { EditSheet(c, ui, Kind.SONG) } })
        Sep()
        Cell(L.newText, kind = "text", onClick = { ui.push(L.newText) { EditSheet(c, ui, Kind.TEXT) } })
        if (st.church) {
            Sep()
            Cell(L.biblePlace, L.biblePlaceSub, kind = "bible", onClick = { ui.closeAll(); ui.tab = AppTab.BIBLE })
        }
        Sep()
        Cell(L.phoneFiles, L.phoneFilesSub, "file", onClick = {
            ui.closeAll()
            acts.pick(arrayOf("*/*"), false) { ui.toast(L.addedItem(it.title)) }
        })
        if (st.church) {
            Sep()
            Cell(L.modules, L.modulesSub, "bible", onClick = { ui.push(L.modules) { BibleModulesSheet(c) } })
        }
    }
}

@Composable
fun BibleModulesSheet(c: Controller) {
    val modules by c.bibleModules.collectAsState()
    val acts = LocalActs.current
    Group {
        if (modules.isEmpty()) Cell(L.translationsLoading, chevron = null)
        modules.forEachIndexed { i, m ->
            if (i > 0) Sep()
            Cell(m.title, m.abbreviation + if (m.language.isNotBlank()) " · ${m.language}" else "", "bible", chevron = null) {
                IconBtn("trash", L.deleteNamed(m.title), P.current.red) { c.removeBible(m.fileName) }
            }
        }
    }
    Btn(L.addModule, icon = "plus") { acts.addBible() }
    Note(L.modulesNote)
}

// ——— Зрители ———

@Composable
fun Qr(text: String, size: androidx.compose.ui.unit.Dp = T.qr) {
    val matrix = remember(text) { qrMatrix(text) }
    Canvas(Modifier.size(size).clip(RoundedCornerShape(T.rField)).background(Color.White)) {
        val pad = this.size.width / 12f
        val cell = (this.size.width - 2 * pad) / matrix.size
        for (r in matrix.indices) for (col in matrix[r].indices) if (matrix[r][col]) {
            drawRect(Color.Black, Offset(pad + col * cell, pad + r * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@Composable
fun ViewersBlock(c: Controller) {
    val p = P.current
    val url by c.viewUrl.collectAsState()
    val local by c.viewLocalUrl.collectAsState()
    val state by c.receiverState.collectAsState()
    val u = url
    if (u == null) {
        Group { Cell(L.viewersInBrowser, L.viewersInBrowserSub, "people", chevron = null) }
        return
    }
    Group {
        Row(Modifier.padding(T.gutter), horizontalArrangement = Arrangement.spacedBy(T.gutter), verticalAlignment = Alignment.CenterVertically) {
            Qr(u)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(L.watchingNow, T.fSub, p.l2)
                Txt("${state?.viewers ?: 0}", T.fTitle, weight = FontWeight.Bold)
                Txt(L.viewersHint, T.fSub, p.l2)
            }
        }
        Sep(T.gutter)
        Cell(u, L.netAddress, chevron = null)
        local?.let { Sep(T.gutter); Cell(it, L.byName, chevron = null) }
    }
}

/** Сколько найденного показывает лист «Найти» сразу. */
private const val FIND_SHOWN = 60
