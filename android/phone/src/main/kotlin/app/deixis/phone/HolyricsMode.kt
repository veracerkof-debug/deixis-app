package app.deixis.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.deixis.core.HolyricsBackground
import app.deixis.core.HolyricsKey
import app.deixis.core.HolyricsMedia
import app.deixis.core.HolyricsSong

/*
 * Режим «Пульт Holyrics»: телефон работает с базой Holyrics на компьютере. Главный экран — что идёт в
 * Holyrics и его плейлист, «Библиотека» — его песни, тексты и медиа, «Сетлисты» — его сохранённые плейлисты.
 * Без подписки Holyrics Plan база песен только читается; плейлист и показ меняются свободно.
 */

/** Режим работы — в разделе подключения («Устройства», «Подключить»). */
@Composable
fun WorkModeSwitch(c: Controller, ui: Ui, h: HolyricsSource) {
    val st by c.settings.collectAsState()
    val status by h.status.collectAsState()
    GHead("Режим работы")
    Seg(listOf("Deixis", "Пульт Holyrics"), if (st.workMode == WorkMode.HOLYRICS) 1 else 0, Modifier.fillMaxWidth()) { i ->
        val mode = if (i == 1) WorkMode.HOLYRICS else WorkMode.DEIXIS
        c.setSettings { it.copy(workMode = mode) }
        // Пульт Holyrics без связи с Holyrics бесполезен: сразу окно подключения.
        if (mode == WorkMode.HOLYRICS && status !is HolyricsStatus.On) ui.holyrics = true
    }
    Note(
        if (st.workMode == WorkMode.HOLYRICS) "Пульт Holyrics: показ, плейлист и база — в Holyrics на компьютере. Своя библиотека Deixis ждёт в режиме «Deixis»."
        else "Deixis: своя библиотека, сетлисты и экраны. Песни Holyrics можно перенести в библиотеку в окне Holyrics.",
        Modifier.padding(top = T.gapS),
    )
}

/** Holyrics не подключён: в его режиме больше нечего показать. */
@Composable
private fun NeedHolyrics(ui: Ui) {
    Column(Modifier.fillMaxWidth().padding(T.gutter), verticalArrangement = Arrangement.spacedBy(T.gap)) {
        Note("Пульт Holyrics: подключитесь к Holyrics на компьютере — найдите его в сети или отсканируйте QR-код в Deixis для компьютера.")
        Btn("Подключить Holyrics", kind = BtnKind.TINT, icon = "song") { ui.holyrics = true }
    }
}

/** Главный экран пульта Holyrics под окном «Сейчас»: плейлист Holyrics, слайды песни строками. */
@Composable
fun HolyricsPlaylistPane(h: HolyricsSource, ui: Ui) {
    val p = P.current
    val status by h.status.collectAsState()
    if (status !is HolyricsStatus.On) { NeedHolyrics(ui); return }
    val list by h.playlist.collectAsState()
    val live by h.live.collectAsState()
    var open by remember { mutableStateOf<Int?>(null) }
    val cur = live?.p
    LaunchedEffect(Unit) { if (h.backgrounds.value.isEmpty()) h.loadExtras() }
    Column(Modifier.fillMaxSize()) {
        HolyricsThemeStrip(h)
        Row(Modifier.padding(start = T.gutter, end = T.gapS, top = T.gapS, bottom = T.gap), verticalAlignment = Alignment.CenterVertically) {
            Txt("Плейлист Holyrics", T.fBody, weight = FontWeight.Bold, modifier = Modifier.weight(1f), lines = 1)
            IconBtn("plus", "Песню в плейлист") { ui.sheet("В плейлист Holyrics") { HolyricsAddSheet(h) } }
            IconBtn("loop", "Обновить") { h.refresh() }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (list.isEmpty()) item("empty") {
                Box(Modifier.fillMaxWidth().groupCell(true, true).padding(horizontal = T.gutter, vertical = T.rowPadV)) {
                    Txt("Плейлист Holyrics пуст. Плюс — добавить песню из базы Holyrics.", T.fSub, p.l2)
                }
            }
            itemsIndexed(list, key = { i, s -> "$i:${s.id}" }) { i, s ->
                val isLive = cur != null && cur.type == "song" && (cur.songId ?: cur.id) == s.id
                Column(Modifier.animateItem().fillMaxWidth().groupCell(i == 0, i == list.lastIndex && open != i)) {
                    if (i > 0) Sep()
                    Cell(
                        s.title, if (isLive) "В Holyrics · слайд ${cur!!.slideNumber} из ${cur.totalSlides}" else s.artist.ifBlank { s.key },
                        "song", open = open == i, chevron = if (open == i) "down" else "chev", mark = if (isLive) p.live else null,
                        onLong = { ui.sheet(s.title) { PlaylistItemMenu(h, ui, s, i) } },
                        onClick = { open = if (open == i) null else i },
                    )
                    if (open == i) SongRows(h, s, if (isLive) cur!!.slideNumber - 1 else null)
                }
            }
            item("end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Слайды песни строками: касание — этот слайд сразу в Holyrics. */
@Composable
private fun SongRows(h: HolyricsSource, s: HolyricsSong, liveIndex: Int?) {
    val p = P.current
    val song by produceState<HolyricsSong?>(null, s.id) { value = h.song(s.id) }
    val slides = song?.orderedSlides()
    Column(Modifier.fillMaxWidth().background(p.fill).padding(horizontal = T.gutter, vertical = T.gapS), verticalArrangement = Arrangement.spacedBy(T.gapS)) {
        if (slides == null) Txt("Слайды загружаются…", T.fSub, p.l2)
        slides?.forEachIndexed { i, sl ->
            val on = i == liveIndex
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(T.rSlide)).background(p.cell)
                    .then(if (on) Modifier.border(T.outline, p.live, RoundedCornerShape(T.rSlide)) else Modifier)
                    .clickable { h.showSongAt(s.id, i) }.padding(T.gapS),
                horizontalArrangement = Arrangement.spacedBy(T.gapS),
            ) {
                Txt("${i + 1}", T.fLabel, if (on) p.live else p.l2, FontWeight.SemiBold, Modifier.width(T.numCol))
                Column(Modifier.weight(1f)) {
                    sl.description?.takeIf { it.isNotBlank() }?.let { Txt(it, T.fTag, p.tintText, FontWeight.SemiBold, lines = 1) }
                    Txt(sl.text.ifBlank { "Пустой слайд" }, T.fSub, if (sl.text.isBlank()) p.l3 else p.ink, lines = 4)
                }
            }
        }
    }
}

@Composable
private fun PlaylistItemMenu(h: HolyricsSource, ui: Ui, s: HolyricsSong, index: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(T.gap)) {
        Btn("Показать в Holyrics", icon = "up") { ui.closeAll(); h.showSongAt(s.id, 0) }
        Btn("Убрать из плейлиста", kind = BtnKind.RED) { ui.closeAll(); h.removeFromPlaylist(index) }
        Note("Песня останется в базе Holyrics: убирается только из плейлиста.")
    }
}

/** Песня из базы Holyrics — в его плейлист (без подписки Holyrics Plan база только читается). */
@Composable
private fun HolyricsAddSheet(h: HolyricsSource) {
    val songs by h.songs.collectAsState()
    var q by remember { mutableStateOf("") }
    SearchField(q, "Песня в базе Holyrics") { q = it }
    val found = songs.filter { q.isBlank() || it.title.contains(q.trim(), true) || it.artist.contains(q.trim(), true) }.take(MAX_FOUND)
    Group(Modifier.padding(top = T.gap)) {
        found.forEachIndexed { i, s ->
            if (i > 0) Sep()
            Cell(s.title, s.artist.ifBlank { null }, "song", chevron = null) { Pill("В плейлист", tint = true) { h.addToPlaylist(s) } }
        }
        if (found.isEmpty()) Cell("Ничего не найдено", chevron = null)
    }
    Note("Новые песни в саму базу Holyrics с телефона не записать: Holyrics разрешает это только с подпиской Holyrics Plan.")
}

/** Кнопки пульта Holyrics: назад, дальше, F8 обои, F9 пустой, F10 чёрный. */
@Composable
fun HolyricsKeys(h: HolyricsSource) {
    val p = P.current
    val ui = LocalUi.current
    val keys by h.keys.collectAsState()
    Column(Modifier.fillMaxWidth().background(p.cell)) {
        Box(Modifier.fillMaxWidth().height(T.hair).background(p.sep))
        Row(Modifier.padding(horizontal = T.gapS, vertical = T.gapXs), horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
            Key("prev", "Назад", false, Modifier.weight(1f)) { h.previous() }
            Key("next", "Дальше", false, Modifier.weight(1f)) { h.next() }
            Key("photo", "F8 — обои", HolyricsKey.F8 in keys, Modifier.weight(1f)) { h.toggleKey(HolyricsKey.F8) }
            Key("clear", "F9 — пустой экран", HolyricsKey.F9 in keys, Modifier.weight(1f)) { h.toggleKey(HolyricsKey.F9) }
            Key("black", "F10 — чёрный экран", HolyricsKey.F10 in keys, Modifier.weight(1f)) { h.toggleKey(HolyricsKey.F10) }
            Key("more", "Ещё: текст, отсчёт, объявления", false, Modifier.weight(1f)) { ui.sheet("Ещё в Holyrics") { HolyricsExtrasSheet(h, ui) } }
        }
    }
}

@Composable
private fun Key(icon: String, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = P.current
    val ui = LocalUi.current
    Box(
        modifier.height(T.barKey).clip(RoundedCornerShape(T.rPill)).background(if (on) p.tint else androidx.compose.ui.graphics.Color.Transparent)
            .press(label = label, onLong = { ui.toast(label) }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, if (on) androidx.compose.ui.graphics.Color.White else p.ink, T.barIcon) }
}

/** «Библиотека» в режиме Holyrics: база Holyrics с поиском; касание — сразу в Holyrics. */
@Composable
fun HolyricsLibraryScreen(h: HolyricsSource, ui: Ui) {
    val status by h.status.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(status) { if (status is HolyricsStatus.On && h.announcements.value.isEmpty()) h.loadExtras() }
    Column(Modifier.fillMaxSize()) {
        Header("База Holyrics") { IconBtn("loop", "Обновить") { h.refresh() } }
        if (status !is HolyricsStatus.On) { NeedHolyrics(ui); return@Column }
        Column(Modifier.padding(horizontal = T.gutter), verticalArrangement = Arrangement.spacedBy(T.gap)) {
            SearchField(q, "Поиск в базе Holyrics") { q = it }
            Seg(LIB_TABS, tab, Modifier.fillMaxWidth(), scroll = true) { tab = it }
        }
        val needle = q.trim()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(T.gutter)) {
            Group {
                when (tab) {
                    0 -> {
                        val songs by h.songs.collectAsState()
                        songs.filter { needle.isEmpty() || it.title.contains(needle, true) || it.artist.contains(needle, true) }.take(MAX_LIST).forEachIndexed { i, s ->
                            if (i > 0) Sep()
                            Cell(s.title, s.artist.ifBlank { null }, "song", chevron = null, onClick = { h.showSongAt(s.id, 0); ui.toast("«${s.title}» — в Holyrics") }) {
                                IconBtn("plus", "В плейлист Holyrics") { h.addToPlaylist(s) }
                            }
                        }
                    }
                    1 -> {
                        val texts by h.texts.collectAsState()
                        texts.filter { needle.isEmpty() || it.title.contains(needle, true) }.take(MAX_LIST).forEachIndexed { i, t ->
                            if (i > 0) Sep()
                            Cell(t.title, t.folder.ifBlank { null }, "text", chevron = null, onClick = { h.showInHolyrics(text = t); ui.toast("«${t.title}» — в Holyrics") })
                        }
                    }
                    2 -> {
                        val files by h.documents.collectAsState()
                        Files(files, needle, "pres") { h.openFile(it); ui.toast("«${it.name}» — в Holyrics") }
                    }
                    3, 4 -> {
                        val kind = if (tab == 3) HolyricsMedia.IMAGE else HolyricsMedia.VIDEO
                        val files by (if (tab == 3) h.images else h.videos).collectAsState()
                        Files(files, needle, if (tab == 3) "photo" else "media") { h.showMediaInHolyrics(kind, it); ui.toast("«${it.name}» — в Holyrics") }
                    }
                    5 -> {
                        val files by h.audios.collectAsState()
                        Files(files, needle, "vol") { h.playAudio(it); ui.toast("«${it.name}» играет в Holyrics") }
                    }
                    6 -> {
                        val list by h.announcements.collectAsState()
                        Cell("Все объявления по кругу", "каждые $ANN_SECONDS с", "list", chevron = null, onClick = { h.showAnnouncement(null, ANN_SECONDS) })
                        list.filter { needle.isEmpty() || it.name.contains(needle, true) || it.text.contains(needle, true) }.take(MAX_LIST).forEach { a ->
                            Sep()
                            Cell(a.name.ifBlank { a.text }, a.text.takeIf { a.name.isNotBlank() }?.lineSequence()?.firstOrNull(), "text", chevron = null, onClick = { h.showAnnouncement(a.id, ANN_SECONDS) })
                        }
                    }
                    else -> {
                        val list by h.automatic.collectAsState()
                        val shown = list.filter { needle.isEmpty() || it.contains(needle, true) }.take(MAX_LIST)
                        if (shown.isEmpty()) Cell("Автопрезентаций нет", chevron = null)
                        shown.forEachIndexed { i, f ->
                            if (i > 0) Sep()
                            Cell(f.removeSuffix(".ap"), "с музыкой и сменой слайдов по времени", "play", chevron = null, onClick = { h.playAutomatic(f); ui.toast("«${f.removeSuffix(".ap")}» — в Holyrics") })
                        }
                    }
                }
            }
            Note("Касание — сразу в Holyrics, с темой из ленты на главном, если она выбрана. Плюс у песни — в плейлист Holyrics. База Holyrics здесь только читается.")
        }
    }
}

/** «Сетлисты» в режиме Holyrics: сохранённые плейлисты Holyrics — открыть или дописать в текущий. */
@Composable
fun HolyricsPlaylistsScreen(h: HolyricsSource, ui: Ui) {
    val status by h.status.collectAsState()
    val saved by h.saved.collectAsState()
    LaunchedEffect(status) { if (status is HolyricsStatus.On) h.loadSaved() }
    Column(Modifier.fillMaxSize()) {
        Header("Плейлисты Holyrics") { IconBtn("loop", "Обновить") { h.loadSaved() } }
        if (status !is HolyricsStatus.On) { NeedHolyrics(ui); return@Column }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = T.gutter)) {
            Group {
                if (saved.isEmpty()) Cell("Сохранённых плейлистов нет", chevron = null)
                saved.forEachIndexed { i, s ->
                    if (i > 0) Sep()
                    val songs = s.items.count { it.type == "song" }
                    Cell(s.name.ifBlank { "Без названия" }, "${s.items.size} элем." + if (songs > 0) " · песен $songs" else "", "list", onClick = {
                        ui.sheet(s.name.ifBlank { "Плейлист" }) {
                            Column(verticalArrangement = Arrangement.spacedBy(T.gap)) {
                                Btn("Открыть в Holyrics", kind = BtnKind.TINT) { ui.closeAll(); h.loadPlaylist(s.name, merge = false) }
                                Btn("Дописать в текущий плейлист") { ui.closeAll(); h.loadPlaylist(s.name, merge = true) }
                                Note("«Открыть» заменит плейлист, который сейчас открыт в Holyrics.")
                            }
                        }
                    })
                }
            }
            Note("Плейлисты сохраняются в самом Holyrics на компьютере.")
        }
    }
}

/** Файлы одной вкладки Holyrics без папок, с поиском. */
@Composable
private fun Files(files: List<app.deixis.core.HolyricsFile>, needle: String, icon: String, onClick: (app.deixis.core.HolyricsFile) -> Unit) {
    val shown = files.filter { !it.isDir && (needle.isEmpty() || it.name.contains(needle, true)) }.take(MAX_LIST)
    if (shown.isEmpty()) Cell("Здесь пусто", chevron = null)
    shown.forEachIndexed { i, f ->
        if (i > 0) Sep()
        Cell(f.name, null, icon, chevron = null, onClick = { onClick(f) })
    }
}

/**
 * Лента тем и фонов Holyrics. Касание — тема ложится на то, что идёт, и держится для всех следующих показов
 * с пульта (песня, текст, быстрый текст, отсчёт). Касание выбранной — отпустить: у каждого снова своя тема.
 */
@Composable
fun HolyricsThemeStrip(h: HolyricsSource) {
    val p = P.current
    val list by h.backgrounds.collectAsState()
    val thumbs by h.thumbs.collectAsState()
    val mix by h.mix.collectAsState()
    if (list.isEmpty()) return
    val ordered = remember(list) { list.sortedBy { if (it.isTheme) 0 else 1 } }
    val picked = list.firstOrNull { it.id == mix }
    Row(Modifier.padding(start = T.gutter, end = T.gutter, top = T.gapS, bottom = T.gapS), verticalAlignment = Alignment.CenterVertically) {
        Txt("Тема", T.fSub, p.l2, FontWeight.SemiBold)
        Spacer(Modifier.width(T.gapS))
        Txt(
            picked?.let { "«${it.name}» — на всё, что показываете" } ?: "у каждой песни и текста своя",
            T.fSub, if (picked != null) p.tintText else p.l3, modifier = Modifier.weight(1f), lines = 1,
        )
    }
    LazyRow(contentPadding = PaddingValues(horizontal = T.gutter), horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
        items(ordered, key = { it.id }) { bg -> ThemeTile(bg, thumbs[bg.id], bg.id == mix) { h.pickMix(bg) } }
    }
}

@Composable
private fun ThemeTile(bg: HolyricsBackground, thumb: android.graphics.Bitmap?, on: Boolean, onClick: () -> Unit) {
    val p = P.current
    val shape = RoundedCornerShape(T.rSlide)
    Column(Modifier.width(T.themeTile).press(label = bg.name, onClick = onClick), verticalArrangement = Arrangement.spacedBy(T.gapXs)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(T.wide).clip(shape).background(Color.Black)
                .then(if (on) Modifier.border(T.outline, p.tint, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(if (bg.isVideo) "media" else if (bg.isTheme) "themes" else "photo", Color.White)
        }
        Txt(bg.name.ifBlank { "Без названия" }, T.fTag, if (on) p.tintText else p.l2, if (on) FontWeight.SemiBold else FontWeight.Normal, lines = 1)
    }
}

/** «Ещё в Holyrics»: быстрый текст, обратный отсчёт, объявления, тревога, таймер для сцены, избранное. */
@Composable
fun HolyricsExtrasSheet(h: HolyricsSource, ui: Ui) {
    val mix by h.mix.collectAsState()
    val bgs by h.backgrounds.collectAsState()
    val ann by h.announcements.collectAsState()
    val favs by h.favorites.collectAsState()
    val themeName = bgs.firstOrNull { it.id == mix }?.name
    var quick by remember { mutableStateOf("") }
    var exact by remember { mutableStateOf(0) }
    var time by remember { mutableStateOf(COUNT_IN) }
    var before by remember { mutableStateOf("") }
    var alert by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf(STAGE_MIN) }
    LaunchedEffect(Unit) { if (bgs.isEmpty()) h.loadExtras() }

    Note(themeName?.let { "Текст и отсчёт пойдут с темой «$it» — её выбирают в ленте тем на главном." } ?: "Тема — как задано в Holyrics. Другую выбирают в ленте тем на главном.")
    GHead("Быстрый текст")
    Field(quick, "Что показать", single = false, minHeight = T.btn) { quick = it }
    Btn("На экран", Modifier.padding(top = T.gapS, bottom = T.gap), icon = "up", kind = BtnKind.TINT, enabled = quick.isNotBlank()) { h.quickText(quick.trim()); ui.closeAll() }

    GHead("Обратный отсчёт")
    Seg(listOf("На сколько", "До времени"), exact, Modifier.fillMaxWidth()) { exact = it; time = if (it == 1) COUNT_AT else COUNT_IN }
    Row(Modifier.padding(top = T.gapS), horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
        Field(time, if (exact == 1) "ЧЧ:ММ" else "ММ:СС", Modifier.weight(1f)) { time = it }
        Field(before, "Текст над отсчётом", Modifier.weight(2f)) { before = it }
    }
    val okTime = TIME.matches(time.trim())
    Btn("Запустить отсчёт", Modifier.padding(top = T.gapS, bottom = T.gap), icon = "clock", kind = BtnKind.TINT, enabled = okTime) {
        h.countdown(time.trim().padStart(TIME_LEN, '0'), exact == 1, before.trim(), ""); ui.closeAll()
    }

    GHead("Объявления")
    Group {
        Cell("Все по кругу", "каждые $ANN_SECONDS с", "list", chevron = null, onClick = { h.showAnnouncement(null, ANN_SECONDS); ui.closeAll() })
        ann.forEach { a ->
            Sep()
            Cell(a.name.ifBlank { a.text }, null, "text", chevron = null, onClick = { h.showAnnouncement(a.id, ANN_SECONDS); ui.closeAll() })
        }
    }

    GHead("Тревога")
    Field(alert, "Строка поверх всего: «Машина у входа»") { alert = it }
    Row(Modifier.padding(top = T.gapS, bottom = T.gap), horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
        Btn("Показать", Modifier.weight(1f), kind = BtnKind.TINT, enabled = alert.isNotBlank()) { h.alert(alert.trim(), true) }
        Btn("Убрать", Modifier.weight(1f)) { h.alert("", false) }
    }

    GHead("Таймер для сцены")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
        Stepper("$stage мин", { stage = (stage - 1).coerceAtLeast(1) }, { stage = (stage + 1).coerceAtMost(STAGE_MAX) })
        Btn("Старт", Modifier.weight(1f), kind = BtnKind.TINT) { h.stageCountdown(stage, "") }
        Btn("Стоп", Modifier.weight(1f)) { h.stopStageCountdown() }
    }
    Note("Таймер виден на панели для сцены Holyrics, в зале его нет.", Modifier.padding(top = T.gapS, bottom = T.gap))

    if (favs.isNotEmpty()) {
        GHead("Избранное Holyrics")
        Group {
            favs.forEachIndexed { i, f ->
                if (i > 0) Sep()
                Cell(f.name.ifBlank { "Без названия" }, null, "layers", chevron = null, onClick = { h.runFavorite(f); ui.closeAll() })
            }
        }
    }
}

private val LIB_TABS = listOf("Песни", "Тексты", "Презентации", "Фото", "Видео", "Аудио", "Объявления", "Авто")
private val TIME = Regex("\\d{1,2}:\\d{2}")
private const val TIME_LEN = 5
private const val COUNT_IN = "05:00"
private const val COUNT_AT = "10:00"
private const val ANN_SECONDS = 10
private const val STAGE_MIN = 5
private const val STAGE_MAX = 120
private const val MAX_FOUND = 60
private const val MAX_LIST = 300
