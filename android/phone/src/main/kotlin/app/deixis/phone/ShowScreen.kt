package app.deixis.phone

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.deixis.core.Mode
import app.deixis.core.VideoAction

val Kind.icon: String
    get() = when (this) {
        Kind.PRESENTATION -> "pres"
        Kind.VIDEO -> "media"
        Kind.PHOTO -> "photo"
        Kind.FILE -> "file"
        Kind.SONG -> "song"
        Kind.TEXT -> "text"
        Kind.BIBLE -> "bible"
        Kind.COUNTDOWN -> "clock"
    }

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

fun slidesText(n: Int) = "$n " + plural(n, "слайд", "слайда", "слайдов")

/** Подпись элемента: первая строка песни, число слайдов, длительность. */
fun subOf(c: Controller, it: LibItem): String = when (it.kind) {
    Kind.SONG, Kind.TEXT -> it.slides.firstOrNull()?.lineSequence()?.firstOrNull().orEmpty()
    Kind.BIBLE -> it.bible?.modules?.joinToString(" · ") { m -> m.substringBefore('.') }.orEmpty()
    Kind.PRESENTATION, Kind.FILE -> {
        val pages = if (it.mime == "application/pdf") it.pages else c.data.value.conversions[it.id]?.pages ?: 0
        when {
            pages > 0 && it.kind == Kind.FILE -> "$pages стр."
            pages > 0 -> slidesText(pages)
            else -> "Переведёт компьютер при показе"
        }
    }
    Kind.VIDEO -> if (it.durationMs > 0) clock(it.durationMs) else "Видео"
    Kind.COUNTDOWN -> it.countdown?.let { s -> listOfNotNull(s.title, s.label).joinToString(" · ") }.orEmpty()
    Kind.PHOTO -> if (it.title.lowercase().let { t -> t.endsWith(".heic") || t.endsWith(".heif") }) "HEIC → JPEG при отправке" else "Фото"
}

@Composable
fun ShowScreen(c: Controller, h: HolyricsSource, ui: Ui) {
    val p = P.current
    val holyricsRemote by h.remote.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar(c, ui)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val w = maxWidth
            val tall = maxHeight > 500.dp
            val height = maxHeight
            if (w >= 700.dp) Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(minOf(w * 0.42f, 440.dp)).fillMaxHeight()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        // Два окна друг под другом должны поместиться в колонку вместе с панелью кнопок.
                        val col = minOf(w * 0.42f, 440.dp) - T.gutter * 2
                        val each = (height - T.barKey - MONITOR_CHROME * 2) / 2
                        val slotMin = if (tall && each > 0.dp) col / each else 1f
                        if (holyricsRemote) HolyricsLiveCard(h) else CompositionLocalProvider(LocalSlotMin provides maxOf(1f, slotMin)) { Monitors(c, ui, stacked = tall) }
                    }
                    if (holyricsRemote) HolyricsBar(h) else ControlBar(c, ui)
                }
                Box(Modifier.width(T.hair).fillMaxHeight().background(p.sep))
                Box(Modifier.weight(1f)) { ShowBody(c, h, ui) }
            } else Column(Modifier.fillMaxSize()) {
                if (holyricsRemote) HolyricsLiveCard(h) else Monitors(c, ui, stacked = false)
                Box(Modifier.weight(1f)) { ShowBody(c, h, ui) }
                if (holyricsRemote) HolyricsBar(h) else ControlBar(c, ui)
            }
        }
    }
}

/** Под окнами показа: сетлист и панель тем — узкой полосой справа, на всё место или спрятанная в значок. */
@Composable
private fun ShowBody(c: Controller, h: HolyricsSource, ui: Ui) {
    val st by c.settings.collectAsState()
    when (st.themePanel) {
        ThemePanel.FULL -> ThemeGrid(c, h, ui)
        ThemePanel.STRIP -> SetlistPane(c, ui) { ThemeStrip(c, h, ui) }
        ThemePanel.HIDDEN -> SetlistPane(c, ui)
    }
}

// ——— Верх: знак, экраны, «Подключить», настройки ———

@Composable
private fun TopBar(c: Controller, ui: Ui) {
    val outputs by c.outputs.collectAsState()
    val state by c.receiverState.collectAsState()
    val viewUrl by c.viewUrl.collectAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = T.topMin).padding(start = T.gutter, end = 8.dp, top = T.gap, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Mark()
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(T.gapXs, Alignment.End)) {
            outputs.forEach { o -> Dev(o.target.icon, o.state, "${o.target.name}: ${o.state.text}") { ui.tab = AppTab.DEVICES } }
            if (viewUrl != null) Dev("people", OutState.ONLINE, "Зрители: ${state?.viewers ?: 0}", count = state?.viewers ?: 0) { ui.tab = AppTab.DEVICES }
        }
        Pill(if (outputs.isEmpty()) "Подключить" else "", "wifi", tint = true, label = "Подключить") { ui.sheet("Подключить") { ConnectSheet(c, ui) } }
        IconBtn("gear", "Настройки") { ui.sheet("Настройки") { SettingsSheet(c) } }
    }
}

/** Значок экрана в полосе: на связи, доставляются файлы (кольцо), нет связи (бледный), нужен PIN (точка). */
@Composable
private fun Dev(icon: String, state: OutState, label: String, count: Int = -1, onClick: () -> Unit) {
    val p = P.current
    Box(Modifier.size(T.dev).clip(RoundedCornerShape(T.rPill)).press(label = label, onClick = onClick), contentAlignment = Alignment.Center) {
        if (state == OutState.SYNC || state == OutState.CONNECTING) {
            val spin = rememberInfiniteTransition(label = "sync")
            val a by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "spin")
            Canvas(Modifier.fillMaxSize().padding(3.dp).rotate(a)) {
                drawArc(p.tint, 0f, 270f, false, style = Stroke(2.dp.toPx()))
            }
        }
        Icon(icon, if (state == OutState.OFFLINE) p.l3 else p.ink)
        if (state == OutState.PIN) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(8.dp).clip(RoundedCornerShape(T.rPill)).background(p.red))
        if (count >= 0) Box(Modifier.align(Alignment.BottomEnd).clip(RoundedCornerShape(T.rPill)).background(p.ink).padding(horizontal = 4.dp)) {
            Txt("$count", T.fNum, p.bg, FontWeight.Bold)
        }
    }
}

// ——— «Сейчас» и «Предпросмотр» ———

@Composable
private fun Monitors(c: Controller, ui: Ui, stacked: Boolean) {
    val p = P.current
    val live by c.live.collectAsState()
    val preview by c.preview.collectAsState()
    val draft by c.draft.collectAsState()
    val mode by c.mode.collectAsState()
    val state by c.receiverState.collectAsState()
    val progress by c.busy.collectAsState()
    val data by c.data.collectAsState()
    val settings by c.settings.collectAsState()
    // Оформление считается здесь, от прочитанных состояний: лямбды окон ниже Compose может не вызывать
    // заново, и тема, сменённая из панели или «Вида», иначе не видна в «Сейчас».
    val liveStyle = remember(live, data, settings) { c.styleFor(live) }
    val shownPre = preview ?: if (draft != null) live else null
    val preStyle = remember(shownPre, data, settings, draft) { c.styleFor(shownPre, withDraft = true) }
    val liveName = when (mode) {
        Mode.BLACK -> "Чёрный экран"
        Mode.LOGO -> "Логотип"
        Mode.CLEAR -> "Очищено"
        Mode.NORMAL -> live?.let { l -> if (l.source.slides.size > 1) "${l.source.title} · ${l.index + 1}/${l.source.slides.size}" else l.source.title } ?: "Пусто"
    }
    val video = state?.video?.takeIf { live?.slide is Slide.Movie }
    val liveHint = progress ?: (liveName + (video?.let { " · ${clock(it.positionMs)}" + if (it.playing) "" else " · пауза" } ?: ""))
    val preName = preview?.let { "${it.source.title} · ${it.index + 1}/${it.source.slides.size}" } ?: if (draft != null) "Новое оформление" else "Выберите слайд"

    val now: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(T.gapS)) {
            Row(Modifier.heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(T.rPill)).background(p.live))
                Txt("Сейчас", T.fLabel, p.l2, FontWeight.SemiBold)
            }
            Box(contentAlignment = Alignment.Center) {
                CueView(
                    c, live, liveStyle,
                    // Тема на окно «Сейчас» — тому, что в зале.
                    Modifier.themeTarget(ui.themeDrag, "live") { st -> c.applyTheme(st); ui.toast("Тема «${st.name ?: "своя"}» — в зале") }
                        .semantics { contentDescription = "Сейчас в зале: $liveName" }
                        .swipes(onTap = { ui.full = FullSpec.Live }, onLeft = { c.step(1) }, onRight = { c.step(-1) }),
                    mode = mode, frame = Frame.LIVE,
                )
                // Файл готовится или передаётся: в зале пока прежний слайд, здесь — ход загрузки.
                progress?.let { Loading(it) }
            }
            Txt(liveHint, T.fTag, p.l2, lines = 1)
        }
    }
    val pre: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(T.gapS)) {
            Row(Modifier.heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Предпросмотр", T.fLabel, p.l2, FontWeight.SemiBold, Modifier.weight(1f), lines = 1)
                val can = preview != null || draft != null
                Box(
                    Modifier.height(26.dp).clip(RoundedCornerShape(T.rPill)).background(if (can) p.tint else p.fill)
                        .press(enabled = can, label = "В зал") { c.previewToLive() }.padding(horizontal = T.gap),
                    contentAlignment = Alignment.Center,
                ) { Txt("В зал", T.fLabel, if (can) Color.White else p.l3, FontWeight.SemiBold) }
            }
            val shown = preview ?: if (draft != null) live else null
            CueView(
                c, shown, preStyle,
                Modifier.semantics { contentDescription = "Предпросмотр: $preName" }
                    .swipes(
                        onTap = { preview?.let { pv -> ui.full = if (pv.slide is Slide.Movie) FullSpec.Video(pv) else FullSpec.Preview } },
                        onUp = { c.previewToLive() },
                    ),
                frame = Frame.PREVIEW,
            )
            Txt(preName + if (draft != null && preview != null) " · новое оформление" else "", T.fTag, if (draft != null) p.tintText else p.l2, lines = 1)
        }
    }
    if (stacked) Column(Modifier.padding(start = T.gutter, end = T.gutter, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        now(Modifier.fillMaxWidth()); pre(Modifier.fillMaxWidth())
    } else Row(Modifier.padding(start = T.gutter, end = T.gutter, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(T.gap)) {
        now(Modifier.weight(1f)); pre(Modifier.weight(1f))
    }
}

/** Слой загрузки поверх окна «Сейчас»: кольцо и проценты. */
@Composable
private fun BoxScope.Loading(text: String) {
    val p = P.current
    val spin = rememberInfiniteTransition(label = "load")
    val a by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    val percent = Regex("(\\d+)%").find(text)?.groupValues?.get(1)
    Column(
        Modifier.matchParentSize().clip(RoundedCornerShape(T.rSlide)).background(Color.Black.copy(alpha = 0.6f)).padding(T.gapS),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(28.dp).rotate(a)) { drawArc(p.tint, 0f, 270f, false, style = Stroke(3.dp.toPx())) }
        Txt(percent?.let { "$it%" } ?: "Подготовка…", T.fLabel, Color.White, FontWeight.SemiBold, Modifier.padding(top = 4.dp))
    }
}

// ——— Плитки «Показать» ———

private data class TileSpec(val title: String, val icon: String, val action: () -> Unit)

/**
 * «Показать»: всё, что выводится не из сетлиста, — файл, текст, отсчёт, слой, экран телефона. Открывается
 * плюсом рядом с поиском: главный экран остаётся за показом, без ряда плиток.
 */
@Composable
private fun ShowMenu(c: Controller, ui: Ui) {
    val context = LocalContext.current
    val acts = LocalActs.current
    val settings by c.settings.collectAsState()
    val sharing by c.sharing.collectAsState()
    val tiles = buildList {
        add(TileSpec("Презентация", "pres") { ui.sheet("Презентация") { PickSheet(c, ui, PickKind.PRESENTATION) } })
        add(TileSpec("Видео", "media") { ui.sheet("Видео") { PickSheet(c, ui, PickKind.VIDEO) } })
        add(TileSpec("Фото", "photo") { ui.sheet("Фото") { PickSheet(c, ui, PickKind.PHOTO) } })
        add(TileSpec("Текст", "text") { ui.sheet("Текст") { TextSheet(c, ui) } })
        add(TileSpec("Отсчёт", "clock") { ui.sheet("Обратный отсчёт") { CountdownSheet(c, ui) } })
        add(TileSpec("Поверх", "layers") { ui.sheet("Поверх показа") { OverlaySheet(c, ui) } })
        add(TileSpec(if (sharing) "Остановить экран" else "Экран телефона", "phone") {
            if (sharing) { ui.closeAll(); ScreenShareService.stop(context) } else ui.sheet("Экран телефона") { MirrorSheet(c, ui) }
        })
        add(TileSpec("Файл", "file") { ui.sheet("Файл") { PickSheet(c, ui, PickKind.FILE) } })
        // Тяжёлые файлы службы — на экраны заранее, пока в зале идёт другое.
        add(TileSpec("Файлы сетлиста — на экраны заранее", "send") {
            ui.closeAll()
            val n = c.sendSetAhead()
            ui.toast(when { n < 0 -> "Нет подключённых экранов"; n == 0 -> "В сетлисте нет фото, видео и PDF"; else -> "Передаются в фоне: $n, в зале ничего не меняется" })
        })
        // Церковный раздел: Библия и Holyrics. Выключается в настройках.
        if (settings.church) {
            add(TileSpec("Библия", "bible") { ui.closeAll(); ui.tab = AppTab.BIBLE })
            add(TileSpec("Holyrics", "song") { ui.closeAll(); acts.holyrics() })
        }
        add(TileSpec("Стать экраном", "tv") { ui.closeAll(); context.startActivity(Intent(context, ScreenActivity::class.java)) })
    }
    Group {
        tiles.forEachIndexed { i, t ->
            if (i > 0) Sep(T.gutter)
            Cell(t.title, kind = t.icon, onClick = t.action)
        }
    }
}

// ——— Сетлист на главном ———

/** Ячейка группы в ленивом списке: скругление только у первой и последней. */
fun groupShape(first: Boolean, last: Boolean): Shape = RoundedCornerShape(
    topStart = if (first) T.rCard else 0.dp, topEnd = if (first) T.rCard else 0.dp,
    bottomStart = if (last) T.rCard else 0.dp, bottomEnd = if (last) T.rCard else 0.dp,
)

@Composable
fun Modifier.groupCell(first: Boolean, last: Boolean): Modifier =
    this.padding(horizontal = T.gutter).clip(groupShape(first, last)).background(P.current.cell)

@Composable
private fun SetlistPane(c: Controller, ui: Ui, side: (@Composable () -> Unit)? = null) {
    val p = P.current
    val data by c.data.collectAsState()
    val set by c.activeSet.collectAsState()
    val live by c.live.collectAsState()
    val preview by c.preview.collectAsState()
    val mode by c.mode.collectAsState()
    val st by c.settings.collectAsState()
    // Вертикальный экран в зале — плитки узкие: в ряд по три, чтобы песня не растягивалась на всю ленту.
    val cols = if (LocalScreenRatio.current < 1f) 3 else 2
    val ids = if (ui.listSeg == 0) set?.items.orEmpty() else data.recent
    val items = ids.mapNotNull { id -> data.items.firstOrNull { it.id == id } }
    // Строка «Сетлист / Недавнее» — на всю ширину; панель тем, если есть, — справа под ней, до низа.
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = T.gutter, end = 8.dp, bottom = T.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Seg(listOf("Сетлист", "Недавнее"), ui.listSeg, Modifier.weight(1f)) { ui.listSeg = it }
            IconBtn("plus", "Показать") { ui.sheet("Показать") { ShowMenu(c, ui) } }
            IconBtn("search", "Найти") { ui.sheet("Найти") { FindSheet(c, ui) } }
            // Панель тем спрятана — значок возвращает её.
            if (st.themePanel == ThemePanel.HIDDEN) IconBtn("themes", "Темы") { c.setSettings { it.copy(themePanel = ThemePanel.STRIP) } }
        }
        Row(Modifier.weight(1f)) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                if (ui.listSeg == 0) item("pick") {
                    Row(
                        Modifier.padding(start = T.gutter + 4.dp, end = T.gutter, bottom = T.gapS).heightIn(min = T.rowMin)
                            .press(label = "Выбрать сетлист") { ui.sheet("Сетлист") { SetPickSheet(c, ui) } },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon("list")
                        Txt(set?.let { "${it.title}, ${it.dateText}" } ?: "Сетлист", T.fBody, weight = FontWeight.Bold, lines = 1, modifier = Modifier.weight(1f, fill = false))
                        Icon("down")
                    }
                }
                if (items.isEmpty()) item("empty") {
                    Box(Modifier.fillMaxWidth().groupCell(true, true).padding(horizontal = T.gutter, vertical = T.rowPadV)) {
                        Txt(
                            if (ui.listSeg == 0) "Пусто. Добавьте элементы через «Найти» или плитки «Показать»." else "Здесь появится то, что показывали.",
                            T.fSub, p.l2,
                        )
                    }
                }
                items.forEachIndexed { n, it ->
                    val open = ui.listSeg == 0 && ui.open == it.id
                    val last = n == items.lastIndex
                    setRow(c, ui, it, n == 0, last && !open, open, live, preview, mode, recent = ui.listSeg == 1)
                    if (open) slideGrid(c, ui, it, live, preview, mode, last, cols)
                }
                item("end") { Spacer(Modifier.height(24.dp)) }
            }
            side?.invoke()
        }
    }
}

private fun LazyListScope.setRow(
    c: Controller, ui: Ui, it: LibItem, first: Boolean, last: Boolean, open: Boolean,
    live: Cue?, preview: Cue?, mode: Mode, recent: Boolean,
) = item("row:${it.id}") {
    // Тема, перенесённая из панели, — всей песне или документу.
    Column(Modifier.fillMaxWidth().groupCell(first, last).themeTarget(ui.themeDrag, "item:${it.id}") { st -> dropped(c, ui, it.id, null, st, it.title) }) {
        if (!first) Sep()
        val isLive = live?.source?.itemId == it.id && mode != Mode.BLACK
        Cell(
            it.title, subOf(c, it), it.kind.icon, open = open, item = it, chevron = if (open) "down" else "chev",
            onLong = if (recent) null else ({ ui.sheet(it.title) { ItemMenu(c, ui, it, inSet = true) } }),
            onClick = {
                if (recent) c.toPreview(Cue(c.sourceOf(it), 0))
                else ui.open = if (ui.open == it.id) null else it.id
            },
        ) {
            if (isLive) Tag("В зале", true)
            if (preview?.source?.itemId == it.id) Tag("Предпросмотр", false)
        }
    }
}

private fun LazyListScope.slideGrid(c: Controller, ui: Ui, it: LibItem, live: Cue?, preview: Cue?, mode: Mode, last: Boolean, cols: Int) {
    val slides = c.slidesOf(it)
    // Песня, текст, Писание — строками: номер, подпись и сам текст, без уменьшенного экрана.
    if (slides.isNotEmpty() && slides.all { s -> s is Slide.Text || s is Slide.Scripture }) {
        slides.forEachIndexed { i, s ->
            item("row:${it.id}:$i") {
                val isLive = live?.source?.itemId == it.id && live?.index == i && mode != Mode.BLACK
                val isPre = preview?.source?.itemId == it.id && preview.index == i
                Box(
                    Modifier.fillMaxWidth().groupCell(false, last && i == slides.lastIndex).background(P.current.fill)
                        .padding(start = T.gutter, end = T.gutter, top = if (i == 0) 4.dp else 0.dp, bottom = if (i == slides.lastIndex) 14.dp else T.gapS),
                ) {
                    TextSlideRow(
                        i + 1, s, if (isLive) Frame.LIVE else if (isPre) Frame.PREVIEW_TINT else Frame.NONE,
                        Modifier.themeTarget(ui.themeDrag, "slide:${it.id}:$i") { st -> dropped(c, ui, it.id, i, st, it.title) }
                            .semantics { contentDescription = "Слайд ${i + 1}" }
                            .combinedClick(onClick = { tapSlide(c, ui, it, i) }, onDouble = { liveSlide(c, ui, it, i) }),
                    )
                }
            }
        }
        return
    }
    val rows = slides.indices.chunked(cols)
    rows.forEachIndexed { r, pair ->
        item("grid:${it.id}:$r") {
            Row(
                Modifier.fillMaxWidth().groupCell(false, last && r == rows.lastIndex).background(P.current.fill)
                    .padding(start = T.gutter, end = T.gutter, top = if (r == 0) 4.dp else 0.dp, bottom = if (r == rows.lastIndex) 14.dp else T.gap),
                horizontalArrangement = Arrangement.spacedBy(T.gap),
            ) {
                pair.forEach { i ->
                    val isLive = live?.source?.itemId == it.id && live?.index == i && mode != Mode.BLACK
                    val isPre = preview?.source?.itemId == it.id && preview.index == i
                    val cue = Cue(c.sourceOf(it), i)
                    SlideView(
                        slides[i], it, c.styleFor(cue),
                        Modifier.weight(1f).padding(T.gapXs)
                            .themeTarget(ui.themeDrag, "slide:${it.id}:$i") { st -> dropped(c, ui, it.id, i, st, it.title) }
                            .semantics { contentDescription = "Слайд ${i + 1}" }
                            .combinedClick(onClick = { tapSlide(c, ui, it, i) }, onDouble = { liveSlide(c, ui, it, i) }),
                        num = i + 1, frame = if (isLive) Frame.LIVE else if (isPre) Frame.PREVIEW_TINT else Frame.NONE,
                    )
                }
                repeat(cols - pair.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Тема брошена на песню (index = null) или на слайд: остаётся у них, а если они в зале — сразу там. */
private fun dropped(c: Controller, ui: Ui, itemId: String, index: Int?, st: app.deixis.core.TextStyle, title: String) {
    c.applyThemeTo(itemId, index, st)
    val name = st.name ?: "своя"
    ui.toast(if (index == null) "Тема «$name» — «$title»" else "Тема «$name» — слайду ${index + 1}")
}

/** Текстовый слайд строкой: номер, подпись («Припев») и текст; в зале — рамкой «в зале», в предпросмотре — акцентом. */
@Composable
private fun TextSlideRow(num: Int, slide: Slide, frame: Frame, modifier: Modifier) {
    val p = P.current
    val (label, text) = when (slide) {
        is Slide.Text -> slide.label to slide.text
        is Slide.Scripture -> slide.content.ref to slide.content.blocks.joinToString("\n") { it.text }
        else -> null to ""
    }
    val border = when (frame) { Frame.LIVE -> p.live; Frame.PREVIEW_TINT -> p.tint; else -> null }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(T.rSlide)).background(p.cell)
            .then(if (border != null) Modifier.border(T.outline, border, RoundedCornerShape(T.rSlide)) else Modifier)
            .padding(horizontal = T.gapS, vertical = T.gapS),
        horizontalArrangement = Arrangement.spacedBy(T.gapS),
    ) {
        Txt("$num", T.fLabel, if (border != null) border else p.l2, FontWeight.SemiBold, Modifier.width(T.numCol))
        Column(Modifier.weight(1f)) {
            label?.takeIf { it.isNotBlank() }?.let { Txt(it, T.fTag, p.tintText, FontWeight.SemiBold, lines = 1) }
            Txt(text.ifBlank { "Пустой слайд" }, T.fSub, if (text.isBlank()) p.l3 else p.ink, lines = 4)
        }
    }
}

/**
 * Касание слайда: в предпросмотр (у элемента, который уже в зале, — сразу в зал).
 * Видео открывается отдельным экраном предпросмотра. Большой файл — через трансляцию экрана.
 */
fun tapSlide(c: Controller, ui: Ui, item: LibItem, index: Int) {
    val big = c.largeFor(item)
    if (big != null) { ui.sheet("Файл больше 1 ГБ") { LargeSheet(c, ui, big) }; return }
    val cue = Cue(c.sourceOf(item), index)
    if (cue.slide is Slide.Movie) { c.toPreview(cue); ui.full = FullSpec.Video(cue); return }
    c.tap(cue)
}

/** Двойное касание: сразу в зал. */
fun liveSlide(c: Controller, ui: Ui, item: LibItem, index: Int) {
    val big = c.largeFor(item)
    if (big != null) { ui.sheet("Файл больше 1 ГБ") { LargeSheet(c, ui, big) }; return }
    c.goLive(Cue(c.sourceOf(item), index))
    ui.toast("В зале")
}

// ——— Полоса кнопок показа ———

@Composable
fun ControlBar(c: Controller, ui: Ui) {
    val p = P.current
    val live by c.live.collectAsState()
    val mode by c.mode.collectAsState()
    val state by c.receiverState.collectAsState()
    val context = LocalContext.current
    val slide = live?.slide
    Column(Modifier.fillMaxWidth().background(p.cell)) {
        Box(Modifier.fillMaxWidth().height(T.hair).background(p.sep))
        Column(Modifier.padding(horizontal = T.gapS, vertical = T.gapXs), verticalArrangement = Arrangement.spacedBy(T.gapXs)) {
            val black: @Composable RowScope.() -> Unit = { BarBtn("black", "Чёрный", mode == Mode.BLACK) { c.mode(Mode.BLACK) } }
            when (slide) {
                is Slide.Movie -> {
                    val v = state?.video
                    val dur = v?.durationMs?.takeIf { it > 0 } ?: c.item(live?.source?.itemId)?.durationMs ?: 0L
                    VideoSeek(v?.positionMs ?: 0L, dur) { c.video(VideoAction.SEEK, positionMs = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
                        black()
                        BarBtn("back10", "−10 с") { c.video(VideoAction.SEEK, deltaMs = -10_000) }
                        val playing = v?.playing ?: true
                        BarBtn(if (playing) "pause" else "play", if (playing) "Пауза" else "Пуск") { c.video(if (playing) VideoAction.PAUSE else VideoAction.PLAY) }
                        BarBtn("fwd10", "+10 с") { c.video(VideoAction.SEEK, deltaMs = 10_000) }
                        BarBtn("loop", "Повтор", v?.loop == true) { c.video(VideoAction.LOOP) }
                        val vol = v?.volume ?: 100
                        BarBtn("vol", "$vol%") { c.video(VideoAction.VOLUME, volume = if (vol >= 100) 0 else (vol + 20).coerceAtMost(100)) }
                    }
                }
                is Slide.Screen -> Row(horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
                    black()
                    BarBtn("stop", "Остановить трансляцию", weight = 2f) { ScreenShareService.stop(context) }
                }
                // Отсчёт в зале: пауза, ±1 мин, заново — на всех экранах сразу.
                is Slide.Countdown -> {
                    val run by c.timer.collectAsState()
                    val left = rememberTimerLeft(c)
                    Txt(timerText(run, left), T.fBody, if (left != null && left <= 0) p.live else p.ink, FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
                        black()
                        val running = run?.running ?: true
                        BarBtn(if (running) "pause" else "play", if (running) "Пауза" else "Пуск") { c.timerToggle() }
                        BarBtn("back10", "−1 мин") { c.timerAdd(-60_000) }
                        BarBtn("fwd10", "+1 мин") { c.timerAdd(60_000) }
                        BarBtn("restart", "Заново") { c.timerRestart() }
                        BarBtn("stop", "Убрать") { c.timerStop() }
                    }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
                    black()
                    BarBtn("logo", "Логотип", mode == Mode.LOGO) { c.mode(Mode.LOGO) }
                    // Слои поверх показа: что в эфире — видно акцентом, касание — поправить, добавить, убрать.
                    val layers by c.overlays.collectAsState()
                    BarBtn("layers", "Слои", !layers.isEmpty) { ui.sheet("Поверх показа") { OverlaySheet(c, ui) } }
                    BarBtn("prev", "Назад") { c.step(-1) }
                    BarBtn("next", "Дальше") { c.step(1) }
                    BarBtn("brush", "Вид") { openStyle(c, ui) }
                }
            }
        }
    }
}

fun openStyle(c: Controller, ui: Ui) {
    c.startDraft()
    ui.sheet("Оформление") { StyleSheet(c, ui) }
}

/**
 * Кнопка полосы показа — только значок, без подписи: шесть в ряд легко нажать вслепую. Подпись
 * остаётся для озвучки и подсказок. Нажатое (чёрный экран, логотип, слой в эфире) — акцентом.
 */
@Composable
private fun RowScope.BarBtn(icon: String, text: String, pressed: Boolean = false, weight: Float = 1f, onClick: () -> Unit) {
    val p = P.current
    Box(
        Modifier.weight(weight).height(T.barKey).clip(RoundedCornerShape(T.rPill)).background(if (pressed) p.tint else Color.Transparent)
            .press(scaleTo = 0.9f, label = text, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (pressed) Color.White else p.ink, T.barIcon)
    }
}

/** Перемотка видео: позиция двигается пальцем, на экран уходит при отпускании. */
@Composable
private fun VideoSeek(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    val p = P.current
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gap)) {
        Txt(clock(if (drag != null) (frac * duration).toLong() else position), T.fLabel, p.l2)
        BoxWithConstraints(
            Modifier.weight(1f).height(28.dp)
                .pointerInput(duration) {
                    detectTapGestures { o -> if (duration > 0) onSeek((o.x / size.width * duration).toLong()) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { if (duration > 0) onSeek((it * duration).toLong()) }; drag = null },
                        onDragCancel = { drag = null },
                    ) { ch, _ -> drag = (ch.position.x / size.width).coerceIn(0f, 1f) }
                }
                .semantics { contentDescription = "Перемотка" },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(T.rPill)).background(p.fill2))
            Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(T.rPill)).background(p.tint))
            Box(Modifier.offset(x = (maxWidth - 20.dp) * frac).size(20.dp).clip(RoundedCornerShape(T.rPill)).background(Color.White).background(p.tint.copy(alpha = 0.15f)))
        }
        Txt(clock(duration), T.fLabel, p.l2)
    }
}

/** Подписи, подсказка и отступы вокруг окна «Сейчас» или «Предпросмотр». */
private val MONITOR_CHROME = 72.dp

