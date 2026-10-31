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
            ScreenShareService.start(context, r.resultCode, data)
            // Большой файл открывается в проигрывателе телефона, а в зал идёт экран.
            openAfter?.let { f ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            }
        }
        openAfter = null
    }
    val requestCapture = { capture.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { requestCapture() }

    return remember(c, ui) {
        Acts(
            pick = { mimes, pres, cb ->
                then = cb; presentation = pres
                runCatching { files.launch(mimes) }.onFailure { ui.toast("Не удалось открыть выбор файлов") }
            },
            share = { f ->
                if (c.outputs.value.none { it.state == OutState.ONLINE && it.target.kind != Target.Kind.DLNA }) ui.toast("Сначала подключите экран")
                else {
                    openAfter = f
                    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else requestCapture()
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
    GHead("Подключено")
    Group {
        if (outputs.isEmpty()) Cell("Пока ни одного экрана", chevron = null)
        outputs.forEachIndexed { i, o ->
            if (i > 0) Sep()
            OutputCell(c, o)
        }
    }
    val free = targets.filter { t -> outputs.none { c.sameScreen(t, it) } }
    GHead("Найдено в сети")
    Group {
        if (free.isEmpty()) Cell("Ищем экраны Deixis…", "Приёмник должен быть в этой же сети Wi-Fi", chevron = null)
        free.forEachIndexed { i, t ->
            if (i > 0) Sep()
            Cell(t.name, t.via + if (t.host.isNotEmpty() && t.kind == Target.Kind.NETWORK) " · ${t.host}" else "", t.icon, chevron = null) {
                Pill("Подключить", tint = true) { c.connect(t) }
            }
        }
        Sep()
        Cell("Ввести адрес вручную", kind = "plus", onClick = { ui.push("Адрес приёмника") { ManualSheet(c, ui) } })
        Sep()
        Cell("Беспроводной дисплей (Миракаст)", "Включается в настройках Android → Трансляция", "cast", onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_CAST_SETTINGS)) }.onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            }
        })
    }
    Note("Не видно экрана? В гостевом Wi-Fi устройства часто изолированы. Введите адрес с экрана приёмника или включите точку доступа на телефоне и подключите к ней приёмник. Кабель HDMI и Миракаст появляются здесь сами.")
}

/** Экран в списке подключённых: состояние, повтор, отключение. */
@Composable
fun OutputCell(c: Controller, o: OutputView) {
    val ui = LocalUi.current
    // Экран Deixis в сети: касание открывает его обновления.
    val open = if (o.state == OutState.ONLINE && (o.target.kind == Target.Kind.NETWORK || o.target.kind == Target.Kind.MANUAL)) ({ ui.push("Экран «${o.info?.name ?: o.target.name}»") { ScreenUpdateSheet(c, o.target) } }) else null
    // Имя — то, что сообщил сам экран, а не адрес, по которому его подключили.
    Cell(o.info?.name ?: o.target.name, "${o.target.via} · ${o.state.text}" + (o.info?.appVersion?.let { " · $it" } ?: ""), o.target.icon, chevron = null, onClick = open) {
        when (o.state) {
            OutState.OFFLINE -> Pill("Повторить") { c.retry(o.target) }
            OutState.PIN -> Pill("PIN", tint = true) { c.needPin.value = o.target }
            else -> if (o.target.kind != Target.Kind.CABLE && o.target.kind != Target.Kind.MIRACAST) Pill("Отключить") { c.disconnect(o.target) }
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
    Note("Введите PIN, который показан на экране «${t.name}». Это нужно только при первом подключении.", Modifier)
    Field(pin, "0000", keyboard = KeyboardType.NumberPassword, code = true) { pin = it.filter(Char::isDigit).take(4) }
    Spacer(Modifier.height(12.dp))
    Btn("Подключить", kind = BtnKind.TINT, enabled = pin.length == 4) { c.pair(t, pin) }
}

@Composable
private fun ManualSheet(c: Controller, ui: Ui) {
    var addr by remember { mutableStateOf("") }
    Field(addr, "192.168.1.20", keyboard = KeyboardType.Uri) { addr = it.trim() }
    Spacer(Modifier.height(12.dp))
    Btn("Подключить", kind = BtnKind.TINT, enabled = addr.isNotBlank()) { c.addManual(addr); ui.close() }
    Note("Адрес показан внизу экрана приёмника. Можно вставить и ссылку из его QR-кода.")
}

// ——— Настройки ———

@Composable
fun SettingsSheet(c: Controller) {
    val st by c.settings.collectAsState()
    val context = LocalContext.current
    val themes = listOf("light" to "Светлая", "dark" to "Тёмная", "paper" to "Бумага", "oled" to "OLED")
    val ui = LocalUi.current
    val book by c.profiles.collectAsState()
    GHead("Профиль")
    Group {
        Cell(book.current.name, "Своя библиотека и вид · профилей: ${book.list.size}", book.current.kind.icon, onClick = { ui.push("Профили") { ProfilesSheet(c, ui) } })
    }
    GHead("Тема")
    Seg(themes.map { it.second }, themes.indexOfFirst { it.first == st.theme }.coerceAtLeast(0), Modifier.fillMaxWidth().padding(bottom = 18.dp)) { i ->
        c.setSettings { it.copy(theme = themes[i].first) }
    }
    GHead("Показ")
    Group {
        SwitchRow("Всегда через предпросмотр", st.alwaysPreview, "Любой слайд сначала идёт в предпросмотр") { v -> c.setSettings { it.copy(alwaysPreview = v) } }
        Sep(T.gutter)
        SwitchRow("Тёмная тема во время показа", st.darkDuringShow, "Включается сама, пока идёт показ") { v -> c.setSettings { it.copy(darkDuringShow = v) } }
        Sep(T.gutter)
        SwitchRow("Bluetooth-кликер и педаль", st.clicker) { v -> c.setSettings { it.copy(clicker = v) } }
        Sep(T.gutter)
        SwitchRow("Кнопки громкости листают слайды", st.volumeKeys) { v -> c.setSettings { it.copy(volumeKeys = v) } }
        Sep(T.gutter)
        SwitchRow("Текст заглавными буквами", st.caps, "Песни, тексты и Писание в зале") { v -> c.setSettings { it.copy(caps = v) } }
    }
    GHead("Оформление")
    Group {
        Cell("Оформление по умолчанию", "Фон, шрифт, размер — для всех слайдов без своего", kind = "brush", onClick = {
            ui.push("Оформление по умолчанию") { DefaultStyleEditor(c, ui) }
        })
    }
    GHead("Заставка «Логотип»")
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
                .onFailure { e -> ui.toast(e.message ?: "Картинка не открылась") }
        }
    }
    Group(Modifier.padding(top = T.gapS)) {
        Cell(
            "Своя картинка", st.logoImage?.name ?: "Логотип церкви, школы или афиша вместо знака", kind = "photo",
            onClick = { pick.launch(arrayOf("image/*")) },
        ) {
            if (st.logoImage != null) Pill("Убрать") { c.setSettings { it.copy(logoImage = null) } }
        }
    }
    SlideView(null, null, c.styleFor(null), Modifier.padding(top = T.gapS), mode = Mode.LOGO, logo = st.logo)
    Note(if (st.logoImage != null) "Так выглядит экран в зале по кнопке «Логотип»: картинка целиком на чёрном."
        else "Так выглядит экран в зале по кнопке «Логотип».")
    GHead("Разделы")
    Group {
        SwitchRow("Церковный раздел", st.church, "Библия и Holyrics на главном") { v -> c.setSettings { it.copy(church = v) } }
    }
    @Suppress("UNUSED_VARIABLE") val data by c.data.collectAsState()
    if (c.hasDemo()) {
        Group(Modifier.padding(top = T.gap)) {
            Cell("Убрать демо-материалы", "Песни, тексты, файлы и сетлист «Демо» первого запуска", kind = "trash", onClick = {
                ui.push("Убрать демо-материалы") {
                    Note("Уйдут песни, тексты, места из Библии, фото, видео, презентации и сетлист «Демо», добавленные при первом запуске. Своё остаётся.", Modifier.padding(bottom = T.gap))
                    Btn("Убрать", kind = BtnKind.RED) { c.removeDemo(); ui.closeAll(); ui.toast("Демо-материалы убраны") }
                }
            })
        }
    }
    BackupsSection(c, LocalUi.current)
    UpdatesSection()
    FeedbackSection()
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = T.gapS)) {
        Mark(T.fPill, P.current.l2)
        Txt(" · $version · GPL-3.0 · без рекламы, аналитики и трекеров", T.fSub, P.current.l2)
    }
}

// ——— Плитки «Показать» ———

enum class PickKind(val mimes: Array<String>, val formats: String) {
    PRESENTATION(
        arrayOf(
            "application/pdf", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-powerpoint",
            "application/vnd.oasis.opendocument.presentation", "application/x-iwork-keynote-sffkey", "application/octet-stream",
        ),
        "PDF, PPTX и PPT — прямо на телефоне. ODP и KEY переводит компьютер с Deixis",
    ),
    VIDEO(arrayOf("video/*", "audio/*"), "Любой формат: MP4, MKV, MOV, AVI, WMV, WebM…"),
    PHOTO(arrayOf("image/*"), "JPEG, PNG, WebP, GIF, HEIC"),
    FILE(arrayOf("*/*"), "PDF, DOCX, TXT и любые файлы: формат определяется сам"),
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
    if (big != null) { ui.sheet("Файл больше 1 ГБ") { LargeSheet(c, ui, big) }; return }
    c.toPreview(Cue(c.sourceOf(item), 0))
}

@Composable
fun PickSheet(c: Controller, ui: Ui, kind: PickKind) {
    val data by c.data.collectAsState()
    val acts = LocalActs.current
    val list = data.items.filter { kind.matches(it.kind) }.take(12)
    if (list.isNotEmpty()) {
        GHead("Недавние")
        Group {
            list.forEachIndexed { i, it ->
                if (i > 0) Sep()
                Cell(it.title, subOf(c, it), it.kind.icon, item = it, onClick = { ui.closeAll(); showItem(c, ui, it) })
            }
        }
    }
    Btn("Файлы телефона…", icon = "file") {
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
    Note("Всё, что на экране телефона, уйдёт в зал. Подходит для презентаций с анимацией, Google Slides и любого приложения. Android спросит разрешение.", Modifier)
    Btn("Начать трансляцию", icon = "phone", kind = BtnKind.TINT) { ui.closeAll(); acts.share(null) }
}

@Composable
fun LargeSheet(c: Controller, ui: Ui, f: LargeFile) {
    val acts = LocalActs.current
    Note("«${f.name}» больше 1 ГБ: такой файл долго передавать. Его можно показать через трансляцию экрана: файл откроется в проигрывателе телефона, а в зал пойдёт экран.", Modifier)
    Btn("Показать через экран телефона", icon = "phone", kind = BtnKind.TINT) { ui.closeAll(); acts.share(f) }
}

// ——— Найти ———

fun LibItem.matches(q: String): Boolean {
    if (q.isBlank()) return true
    val needle = q.trim().lowercase()
    return title.lowercase().contains(needle) || slides.any { it.lowercase().contains(needle) }
}

@Composable
fun FindSheet(c: Controller, ui: Ui) {
    val data by c.data.collectAsState()
    val set by c.activeSet.collectAsState()
    var q by remember { mutableStateOf("") }
    SearchField(q, "Песня, стих, файл", Modifier.padding(bottom = T.gap)) { q = it }
    val found = data.items.filter { it.matches(q) }
    Group {
        if (found.isEmpty()) Cell("Ничего не найдено", chevron = null)
        found.forEachIndexed { i, it ->
            if (i > 0) Sep()
            Cell(it.title, subOf(c, it), it.kind.icon, chevron = null, item = it, onClick = {
                ui.closeAll(); ui.tab = AppTab.SHOW
                tapToPreview(c, ui, it)
            }) {
                if (set?.items?.contains(it.id) == true) Tag("В сетлисте", false)
                else Pill("В сетлист") { c.addToSet(it.id); ui.toast("Добавлено в сетлист") }
            }
        }
    }
    Note("Найденное открывается в предпросмотре — его можно показать, даже если в сетлисте этого нет.")
}

fun tapToPreview(c: Controller, ui: Ui, item: LibItem) {
    val big = c.largeFor(item)
    if (big != null) ui.sheet("Файл больше 1 ГБ") { LargeSheet(c, ui, big) } else c.toPreview(Cue(c.sourceOf(item), 0))
}

// ——— Сетлисты ———

@Composable
fun SetPickSheet(c: Controller, ui: Ui) {
    val data by c.data.collectAsState()
    Group {
        data.setlists.sortedBy { it.date }.forEachIndexed { i, s ->
            if (i > 0) Sep(T.gutter)
            Cell("${s.title}, ${s.dateText}", "${s.items.size} элем." + if (s.past) " · прошёл" else "", chevron = null, onClick = {
                c.pickSet(s.id); ui.open = s.items.firstOrNull(); ui.closeAll(); ui.tab = AppTab.SHOW
                ui.toast("На главном: ${s.title}, ${s.dateText}")
            }) {
                if (s.id == data.active) Tag("Выбран", false)
                IconBtn("trash", "Удалить сетлист «${s.title}»", P.current.red) { ui.push("Удалить сетлист") { DeleteSetSheet(c, ui, s) } }
            }
        }
    }
    Btn("Новый сетлист", icon = "plus") { ui.push("Новый сетлист") { NewSetSheet(c, ui) } }
}

/** Удаление сетлиста — с подтверждением. Песни, файлы и тексты остаются в библиотеке. */
@Composable
fun DeleteSetSheet(c: Controller, ui: Ui, s: Setlist) {
    Note("«${s.title}, ${s.dateText}» удаляется. Песни, тексты и файлы из него остаются в библиотеке.", Modifier.padding(bottom = T.gap))
    Btn("Удалить", kind = BtnKind.RED) { c.deleteSet(s.id); ui.closeAll(); ui.toast("Сетлист удалён") }
}

/** Новый сетлист или копия прошлого: название и дата. */
@Composable
fun NewSetSheet(c: Controller, ui: Ui, copyOf: Setlist? = null) {
    var title by remember { mutableStateOf(copyOf?.title ?: "") }
    var date by remember { mutableStateOf(LocalDate.now().let { if (copyOf != null) it.plusDays(7) else it }) }
    Column(verticalArrangement = Arrangement.spacedBy(T.gap)) {
        if (copyOf == null) Field(title, "Название: пара, лекция, служение") { title = it }
        Group {
            Cell("Дата", Setlist("", "", date.toString()).dateText, chevron = null) {
                Stepper("", { date = date.minusDays(1) }, { date = date.plusDays(1) })
            }
        }
        Btn(if (copyOf == null) "Создать" else "Дублировать", kind = BtnKind.TINT) {
            if (copyOf != null) { c.duplicateSet(copyOf.id, date); ui.toast("Копия создана на ${Setlist("", "", date.toString()).dateText}") }
            else { c.newSet(title, date); ui.toast("Сетлист создан") }
            ui.closeAll()
        }
    }
}

/** Меню элемента: удержание в сетлисте или библиотеке. */
@Composable
fun ItemMenu(c: Controller, ui: Ui, item: LibItem, inSet: Boolean) {
    val set by c.activeSet.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(T.gap)) {
        Btn("Во весь экран", icon = "show") { ui.closeAll(); ui.full = FullSpec.Local(Cue(c.sourceOf(item), 0)) }
        Btn("В предпросмотр", icon = "up") { ui.closeAll(); ui.tab = AppTab.SHOW; tapToPreview(c, ui, item) }
        if (item.kind == Kind.SONG || item.kind == Kind.TEXT) Btn("Править слайды", icon = "edit") { ui.push("Править") { EditSheet(c, ui, item.kind, item) } }
        // Тема на весь документ сразу, как в Holyrics: видно в «Предпросмотре», в зал — по «Применить в зал».
        if (item.kind == Kind.SONG || item.kind == Kind.TEXT || item.kind == Kind.BIBLE) Btn("Оформление", icon = "brush") {
            ui.closeAll(); ui.tab = AppTab.SHOW
            c.toPreview(Cue(c.sourceOf(item), 0))
            c.draftScope = Controller.StyleScope.ITEM
            openStyle(c, ui)
        }
        // Большой файл — на экраны заранее, пока в зале идёт другое: потом показ без ожидания.
        if (c.canSendAhead(item)) Btn("Отправить на экраны заранее", icon = "tv") {
            ui.closeAll()
            ui.toast(if (c.sendAhead(item) > 0) "Передаётся в фоне: в зале ничего не меняется" else "Нет подключённых экранов")
        }
        if (item.kind == Kind.BIBLE) Btn("Другое место", icon = "bible") { ui.push("Библия") { BiblePicker(c, ui, item) } }
        if (inSet) {
            Row(horizontalArrangement = Arrangement.spacedBy(T.gap)) {
                Btn("Выше", Modifier.weight(1f)) { c.moveInSet(item.id, -1) }
                Btn("Ниже", Modifier.weight(1f)) { c.moveInSet(item.id, 1) }
            }
            Btn("Убрать из сетлиста", kind = BtnKind.RED) { c.removeFromSet(item.id); ui.closeAll(); ui.toast("Убрано из сетлиста") }
        } else {
            if (set?.items?.contains(item.id) != true) Btn("В сетлист", icon = "plus") { c.addToSet(item.id); ui.toast("Добавлено в сетлист") }
            Btn("Удалить из библиотеки", icon = "trash", kind = BtnKind.RED) {
                ui.push("Удалить «${item.title}»?") {
                    Note("Элемент уберётся из библиотеки и всех сетлистов. Файл на телефоне останется.", Modifier)
                    Row(horizontalArrangement = Arrangement.spacedBy(T.gap)) {
                        Btn("Отмена", Modifier.weight(1f)) { ui.close() }
                        Btn("Удалить", Modifier.weight(1f), kind = BtnKind.DANGER) { c.deleteItem(item.id); ui.closeAll(); ui.toast("Удалено") }
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

private val backgrounds = listOf(0xFF000000 to "Чёрный", 0xFF18233A to "Синий", 0xFF4A3420 to "Тёплый", 0xFF20443A to "Зелёный")
private val inks = listOf(0xFFFFFFFF to "Белый", 0xFFF4EFE6 to "Бумага", 0xFFFFD43B to "Золотой")

@Composable
fun StyleSheet(c: Controller, ui: Ui) {
    val draft by c.draft.collectAsState()
    val d = draft ?: return
    var to by remember { mutableStateOf(c.draftScope) }
    val set = { st: app.deixis.core.TextStyle -> c.draft.value = st }
    GHead("Темы")
    ThemesRow(c, ui, d) { t -> set(t.style) }
    Note("Удержание темы — изменить её или сделать свою на её основе.", Modifier.padding(bottom = T.gapS))
    GHead("Быстрая правка")
    Group {
        Cell("Фон", chevron = null) {
            Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) { backgrounds.forEach { (v, n) -> Swatch(Color(v), d.background == v, "Фон: $n") { set(d.copy(background = v)) } } }
        }
        Sep(T.gutter)
        Cell("Цвет текста", chevron = null) {
            Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) { inks.forEach { (v, n) -> Swatch(Color(v), d.color == v, "Цвет: $n") { set(d.copy(color = v)) } } }
        }
        Sep(T.gutter)
        Cell("Размер текста", chevron = null) {
            Stepper("${d.sizePercent.toInt()}", { set(d.copy(sizePercent = textSizeStep(d.sizePercent, up = false))) }, { set(d.copy(sizePercent = textSizeStep(d.sizePercent, up = true))) })
        }
        Sep(T.gutter)
        SwitchRow("Тень", d.shadow) { set(d.copy(shadow = it)) }
        Sep(T.gutter)
        SwitchRow("Обводка", d.outline) { set(d.copy(outline = it)) }
        Sep(T.gutter)
        Cell("Все настройки", "Фото и видеофон, плашка, выравнивание, положение", onClick = {
            ui.push("Все настройки оформления") { DraftControls(c, ui) }
        })
    }
    GHead("Применить к")
    val scopes = Controller.StyleScope.entries
    val cue = c.preview.value ?: c.live.value
    val doc = if (cue?.source?.kind == Kind.SONG) "Вся песня" else "Весь документ"
    Seg(listOf("Этот слайд", doc, "Сетлист", "По умолчанию"), scopes.indexOf(to), Modifier.fillMaxWidth().padding(bottom = T.gutter), scroll = true) { to = scopes[it]; c.draftScope = scopes[it] }
    Note(
        when (to) {
            Controller.StyleScope.SLIDE -> "Только этот слайд; остальные слайды — как были."
            Controller.StyleScope.ITEM -> "Все слайды этого элемента; прежнее оформление отдельных слайдов снимается."
            Controller.StyleScope.SET -> "Всё в этом сетлисте, у чего нет своего оформления."
            Controller.StyleScope.ALL -> "Везде, где нет своего оформления у элемента или сетлиста."
        } + " Правка видна в «Предпросмотре»; в зал — по «Применить в зал».",
        Modifier,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(T.gap)) {
        Btn("Отменить", Modifier.weight(1f)) { c.cancelDraft(); ui.closeAll() }
        Btn("Применить в зал", Modifier.weight(1f), kind = BtnKind.TINT) { c.applyDraft(to); ui.closeAll(); ui.toast("Оформление в зале") }
    }
}

/** Вся правка оформления во время показа: те же настройки, что у темы; видна в «Предпросмотре». */
@Composable
private fun DraftControls(c: Controller, ui: Ui) {
    val draft by c.draft.collectAsState()
    val d = draft ?: return
    SlideView(Slide.Text("Господь — Пастырь мой;\nя ни в чём не буду нуждаться"), null, d, Modifier.padding(bottom = T.gutter))
    StyleControls(c, ui, d) { c.draft.value = it }
    Btn("Готово", kind = BtnKind.TINT) { ui.close() }
}

// ——— Библиотека: добавить ———

@Composable
fun AddSheet(c: Controller, ui: Ui) {
    val acts = LocalActs.current
    val st by c.settings.collectAsState()
    Group {
        Cell("Новая песня", kind = "song", onClick = { ui.push("Новая песня") { EditSheet(c, ui, Kind.SONG) } })
        Sep()
        Cell("Новый текст", kind = "text", onClick = { ui.push("Новый текст") { EditSheet(c, ui, Kind.TEXT) } })
        if (st.church) {
            Sep()
            Cell("Место из Библии", "Вкладка «Библия» → «В сетлист»", kind = "bible", onClick = { ui.closeAll(); ui.tab = AppTab.BIBLE })
        }
        Sep()
        Cell("Файлы телефона", "PDF, PPTX, видео, фото, TXT, DOCX", "file", onClick = {
            ui.closeAll()
            acts.pick(arrayOf("*/*"), false) { ui.toast("Добавлено: ${it.title}") }
        })
        if (st.church) {
            Sep()
            Cell("Переводы Библии", "Модули MyBible: .SQLite3", "bible", onClick = { ui.push("Переводы Библии") { BibleModulesSheet(c) } })
        }
    }
}

@Composable
fun BibleModulesSheet(c: Controller) {
    val modules by c.bibleModules.collectAsState()
    val acts = LocalActs.current
    Group {
        if (modules.isEmpty()) Cell("Переводы загружаются…", chevron = null)
        modules.forEachIndexed { i, m ->
            if (i > 0) Sep()
            Cell(m.title, m.abbreviation + if (m.language.isNotBlank()) " · ${m.language}" else "", "bible", chevron = null) {
                IconBtn("trash", "Удалить ${m.title}", P.current.red) { c.removeBible(m.fileName) }
            }
        }
    }
    Btn("Добавить модуль…", icon = "plus") { acts.addBible() }
    Note("Синодальный и King James встроены. Другие переводы — файлы MyBible (.SQLite3), например с сайта mybible.zone.")
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
        Group { Cell("Зрители смотрят показ в браузере", "Подключите экран Deixis по Wi-Fi: здесь появится QR-код", "people", chevron = null) }
        return
    }
    Group {
        Row(Modifier.padding(T.gutter), horizontalArrangement = Arrangement.spacedBy(T.gutter), verticalAlignment = Alignment.CenterVertically) {
            Qr(u)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt("Смотрят сейчас", T.fSub, p.l2)
                Txt("${state?.viewers ?: 0}", T.fTitle, weight = FontWeight.Bold)
                Txt("Камерой телефона по QR-коду. Приложение не нужно, телефон зрителя — в этой же сети Wi-Fi.", T.fSub, p.l2)
            }
        }
        Sep(T.gutter)
        Cell(u, "Адрес в сети", chevron = null)
        local?.let { Sep(T.gutter); Cell(it, "По имени: iPhone, компьютер", chevron = null) }
    }
}

// ——— Пульт Holyrics ———

@Composable
fun HolyricsBar(h: HolyricsSource) {
    val p = P.current
    Column(Modifier.fillMaxWidth().background(p.cell).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(T.gapS)) {
        Row(horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
            Btn("Назад", Modifier.weight(1f), icon = "prev") { h.previous() }
            Btn("Дальше", Modifier.weight(1f), icon = "next") { h.next() }
        }
        Btn("Режим Deixis", icon = "close") { h.useAsRemote(false) }
    }
}

