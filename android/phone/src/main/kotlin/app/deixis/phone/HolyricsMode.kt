package app.deixis.phone

import androidx.compose.foundation.background
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
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = T.gutter, end = T.gapS, bottom = T.gap), verticalAlignment = Alignment.CenterVertically) {
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
    val keys by h.keys.collectAsState()
    Column(Modifier.fillMaxWidth().background(p.cell)) {
        Box(Modifier.fillMaxWidth().height(T.hair).background(p.sep))
        Row(Modifier.padding(horizontal = T.gapS, vertical = T.gapXs), horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
            Key("prev", "Назад", false, Modifier.weight(1f)) { h.previous() }
            Key("next", "Дальше", false, Modifier.weight(1f)) { h.next() }
            Key("photo", "F8 — обои", HolyricsKey.F8 in keys, Modifier.weight(1f)) { h.toggleKey(HolyricsKey.F8) }
            Key("clear", "F9 — пустой экран", HolyricsKey.F9 in keys, Modifier.weight(1f)) { h.toggleKey(HolyricsKey.F9) }
            Key("black", "F10 — чёрный экран", HolyricsKey.F10 in keys, Modifier.weight(1f)) { h.toggleKey(HolyricsKey.F10) }
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
    Column(Modifier.fillMaxSize()) {
        Header("База Holyrics") { IconBtn("loop", "Обновить") { h.refresh() } }
        if (status !is HolyricsStatus.On) { NeedHolyrics(ui); return@Column }
        Column(Modifier.padding(horizontal = T.gutter), verticalArrangement = Arrangement.spacedBy(T.gap)) {
            SearchField(q, "Поиск в базе Holyrics") { q = it }
            Seg(listOf("Песни", "Тексты", "Фото", "Видео"), tab, Modifier.fillMaxWidth()) { tab = it }
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
                    else -> {
                        val kind = if (tab == 2) HolyricsMedia.IMAGE else HolyricsMedia.VIDEO
                        val files by (if (tab == 2) h.images else h.videos).collectAsState()
                        files.filter { !it.isDir && (needle.isEmpty() || it.name.contains(needle, true)) }.take(MAX_LIST).forEachIndexed { i, f ->
                            if (i > 0) Sep()
                            Cell(f.name, null, if (tab == 2) "photo" else "media", chevron = null, onClick = { h.showMediaInHolyrics(kind, f) })
                        }
                    }
                }
            }
            Note("Касание — сразу в Holyrics. Плюс у песни — в плейлист Holyrics. База Holyrics здесь только читается.")
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

private const val MAX_FOUND = 60
private const val MAX_LIST = 300
