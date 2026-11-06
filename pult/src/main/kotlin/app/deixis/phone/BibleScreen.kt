package app.deixis.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.deixis.core.Mode

/**
 * Вкладка «Библия»: открытая глава с двумя переводами, касание — в предпросмотр, двойное — в зал.
 * Переводы, поиск по тексту и история — на этом же экране. Место остаётся, пока работа идёт в других вкладках.
 */
@Composable
fun BibleScreen(c: Controller, ui: Ui) {
    val p = P.current
    val d = c.desk
    val modules by c.bibleModules.collectAsState()
    LaunchedEffect(modules) { d.ensure() }
    val selected by d.selected.collectAsState()
    val chapter by d.chapter.collectAsState()
    val mark by d.mark.collectAsState()
    val history by d.history.collectAsState()
    val query by d.query.collectAsState()
    val hits by d.hits.collectAsState()
    val searching by d.searching.collectAsState()
    val live by c.live.collectAsState()
    val mode by c.mode.collectAsState()
    val scrollTo by d.scrollTo.collectAsState()
    val holyVerse by c.holyricsVerse.collectAsState()
    val settings by c.settings.collectAsState()
    // Пульт Holyrics: «в зале» — то, что отправлено в Holyrics.
    val holy = settings.workMode == WorkMode.HOLYRICS && c.holyricsRemote != null
    val liveRange = (if (holy) holyVerse else live?.source?.bible)?.let { b -> chapter?.let { ch -> if (b.book == ch.book && b.chapter == ch.chapter) b.from..b.to else null } }
    var searchOpen by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(scrollTo, chapter) {
        val v = scrollTo ?: return@LaunchedEffect
        val idx = chapter?.primary?.indexOfFirst { it.number == v } ?: -1
        if (idx >= 0) list.animateScrollToItem((idx).coerceAtLeast(0))
        d.scrollTo.value = null
    }

    Column(Modifier.fillMaxSize()) {
        // Место: книга и глава — касание открывает выбор.
        Row(
            Modifier.fillMaxWidth().heightIn(min = T.topMin).padding(start = T.gutter, end = 8.dp, top = T.gap, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(T.rField)).press(label = L.pickBookChapter) { ui.sheet(L.bookChapter) { Navigator(c, ui) } }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Txt(chapter?.let { "${it.title} ${it.chapter}" } ?: L.tabBible, T.fTitle, weight = FontWeight.Bold, lines = 1, modifier = Modifier.weight(1f, fill = false))
                Icon("down", p.l2)
            }
            IconBtn("search", L.searchText) { searchOpen = !searchOpen; if (!searchOpen) d.search("") }
            IconBtn("brush", L.themeLook) { openStyle(c, ui) }
            if (holy) IconBtn("loop", L.holyricsTranslations) { ui.sheet(L.translationsInHolyrics) { HolyricsBibleSheet(c, ui) } }
            else IconBtn("gear", L.scriptureLook) { ui.sheet(L.scriptureLook) { ScriptureOptionsSheet(c) } }
        }

        // Переводы: быстро на этом экране. Первый — основной; «⇄» меняет их местами.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = T.gutter, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(T.gapS), verticalAlignment = Alignment.CenterVertically,
        ) {
            modules.forEach { m ->
                val i = selected.indexOf(m.fileName)
                Pill(if (i >= 0 && selected.size > 1) "${i + 1} · ${m.abbreviation}" else m.abbreviation, tint = i >= 0, label = m.title) { d.toggle(m.fileName) }
            }
            if (selected.size > 1) Pill("⇄", label = L.swapTr) { d.swap() }
            Pill("", "plus", label = L.addTranslation) { ui.sheet(L.modules) { BibleModulesSheet(c) } }
        }

        if (searchOpen) {
            SearchField(query, L.searchHint, Modifier.padding(horizontal = T.gutter, vertical = 4.dp)) { d.search(it) }
        }

        // История: открытые места — одним касанием.
        if (history.isNotEmpty() && !(searchOpen && query.isNotBlank())) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = T.gutter, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(T.gapS), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon("back10", p.l2, T.iconS)
            history.forEach { h ->
                val on = liveRange != null && chapter?.book == h.book && chapter?.chapter == h.chapter && liveRange == h.from..h.to
                HistoryChip(d.passageLabel(h), on, onClick = { d.openPassage(h) }, onLong = { d.forget(h); ui.toast(L.removedHistory) })
            }
            Pill(L.clear, label = L.clearHistory) { d.clearHistory() }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val ref = if (searchOpen && query.isNotBlank()) d.refOf(query) else null
            when {
                searchOpen && query.isNotBlank() -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                    if (ref != null) item("ref") {
                        Column(Modifier.fillMaxWidth().groupCell(true, true)) {
                            Cell(L.openRef(query), kind = "bible", onClick = {
                                d.go(ref.book, ref.chapter, ref.fromVerse, ref.toVerse, toHall = false)
                                searchOpen = false; d.search("")
                            })
                        }
                    }
                    val found = hits.orEmpty()
                    if (ref == null && found.isEmpty()) item("none") {
                        Note(if (searching) L.searchingShort else L.nothingFound, Modifier.padding(horizontal = T.gutter))
                    }
                    items(found.size, key = { "h$it" }) { i ->
                        val h = found[i]
                        Column(Modifier.fillMaxWidth().groupCell(i == 0, i == found.lastIndex)) {
                            if (i > 0) Sep(T.gutter)
                            Column(
                                Modifier.fillMaxWidth().combinedClick(
                                    onClick = { d.go(h.book, h.chapter, h.verse, h.verse, toHall = false); searchOpen = false; d.search("") },
                                    onDouble = { d.go(h.book, h.chapter, h.verse, h.verse, toHall = true); searchOpen = false; d.search("") },
                                ).padding(horizontal = T.gutter, vertical = T.rowPadV),
                            ) {
                                Txt(d.hitLabel(h), T.fLabel, p.tintText, FontWeight.SemiBold)
                                Txt(h.text, T.fSub, lines = 3)
                            }
                        }
                    }
                    if (found.size >= 200) item("more") { Note(L.first200, Modifier.padding(horizontal = T.gutter)) }
                }
                chapter == null -> Note(if (modules.isEmpty()) L.translationsLoading else L.bookChapterAbove, Modifier.padding(T.gutter))
                else -> {
                    val ch = chapter!!
                    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)) {
                        items(ch.primary, key = { "v${ch.book}:${ch.chapter}:${it.number}" }) { v ->
                            val second = ch.second?.firstOrNull { it.number == v.number }
                            val isLive = liveRange?.contains(v.number) == true && (holy || mode.inHall())
                            val isMark = mark?.contains(v.number) == true
                            VerseRow(v.number, v.text, second?.text, isLive, isMark,
                                onTap = { d.select(v.number) },
                                onDouble = { d.mark.value = v.number..v.number; d.live() },
                                onLong = { d.extend(v.number) },
                            )
                        }
                        item("nav") {
                            Row(Modifier.fillMaxWidth().padding(T.gutter), horizontalArrangement = Arrangement.spacedBy(T.gap)) {
                                Btn(L.chapterN(ch.chapter - 1), Modifier.weight(1f), icon = "prev", enabled = ch.chapter > 1) { d.nextChapter(-1) }
                                Btn(if (ch.chapter < ch.chapters) L.chapterN(ch.chapter + 1) else L.nextBook, Modifier.weight(1f), icon = "next") { d.nextChapter(1) }
                            }
                        }
                    }
                }
            }
        }
        BibleBar(c, ui, mark, liveRange, mode, holy)
    }
}

@Composable
private fun HistoryChip(text: String, live: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val p = P.current
    Box(
        Modifier.height(T.pill).clip(RoundedCornerShape(T.rPill)).background(if (live) p.live else p.fill)
            .combinedClick(onClick = onClick, onLong = onLong).padding(horizontal = T.pillPad),
        contentAlignment = Alignment.Center,
    ) { Txt(text, T.fPill, if (live) Color.White else p.ink, FontWeight.SemiBold, lines = 1) }
}

/** Стих: номер, основной перевод, второй — ниже. В зале — полоса слева, выбран — фон. */
@Composable
private fun VerseRow(n: Int, text: String, second: String?, live: Boolean, marked: Boolean, onTap: () -> Unit, onDouble: () -> Unit, onLong: () -> Unit) {
    val p = P.current
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(if (marked) p.fill else Color.Transparent)
            .combinedClick(onClick = onTap, onLong = onLong, onDouble = onDouble)
            .padding(end = T.gutter),
    ) {
        Box(Modifier.width(4.dp).heightIn(min = T.rowMin).fillMaxHeight().background(if (live) p.live else Color.Transparent))
        Txt("$n", T.fLabel, if (live) p.live else p.tintText, FontWeight.Bold, Modifier.width(36.dp).padding(top = 12.dp), align = TextAlign.Center)
        Column(Modifier.weight(1f).padding(vertical = T.gap), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(text, T.fBody)
            second?.let { Txt(it, T.fSub, p.l2) }
        }
        if (live) Box(Modifier.padding(top = T.gap)) { Tag(L.inHall, true) }
    }
}

/** Нижняя полоса: стих назад и вперёд (в зале — сразу в зал), «В зал» для выбранного, «Чёрный». */
@Composable
private fun BibleBar(c: Controller, ui: Ui, mark: IntRange?, liveRange: IntRange?, mode: Mode, holy: Boolean) {
    val p = P.current
    val d = c.desk
    Column(Modifier.fillMaxWidth().background(p.cell)) {
        Box(Modifier.fillMaxWidth().height(T.hair).background(p.sep))
        Row(
            Modifier.fillMaxWidth().padding(start = T.gutter, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(T.rPill)).background(if (liveRange != null) p.live else p.l3))
            Txt(
                when {
                    holy && liveRange != null -> L.inHolyricsColon(d.label(liveRange))
                    holy && mark == null -> L.tapHolyrics
                    !holy && mode == Mode.BLACK -> L.blackInHall
                    !holy && mode == Mode.AWAY -> L.awayNow
                    liveRange != null -> L.inHallColon(d.label(liveRange))
                    mark != null -> L.selectedColon(d.label(mark))
                    else -> L.verseHint
                },
                T.fLabel, p.l2, lines = 1, modifier = Modifier.weight(1f),
            )
            if (mark != null) Pill(L.toSet, label = L.saveToSet) { d.saveToSet()?.let { ui.toast(L.inSetColon(it)) } }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
            BarBtn2("black", L.black, !holy && mode == Mode.BLACK, Modifier.weight(1f)) {
                if (holy) c.holyricsRemote?.toggleKey(app.deixis.core.HolyricsKey.F10) else c.mode(Mode.BLACK)
            }
            BarBtn2("prev", L.verse, false, Modifier.weight(1f)) { d.step(-1) }
            val can = mark != null && mark != liveRange
            Column(
                Modifier.weight(1.6f).heightIn(min = T.barBtn).clip(RoundedCornerShape(T.rBtn)).background(if (can) p.tint else p.fill)
                    .press(enabled = can, scaleTo = 0.94f, label = if (holy) L.toHolyrics else L.toHall) { d.live(); ui.toast(if (holy) L.toHolyrics else L.inHall) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon("up", if (can) Color.White else p.l3, T.iconBar)
                Txt(if (holy) L.toHolyrics else L.toHall, T.fBar, if (can) Color.White else p.l3, FontWeight.SemiBold)
            }
            BarBtn2("next", L.verse, false, Modifier.weight(1f)) { d.step(1) }
            BarBtn2("clear", L.clear, !holy && mode == Mode.CLEAR, Modifier.weight(1f)) {
                if (holy) c.holyricsRemote?.toggleKey(app.deixis.core.HolyricsKey.F9) else c.mode(Mode.CLEAR)
            }
        }
    }
}

@Composable
private fun BarBtn2(icon: String, text: String, pressed: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = P.current
    Column(
        modifier.heightIn(min = T.barBtn).clip(RoundedCornerShape(T.rBtn)).background(if (pressed) p.ink else p.fill)
            .press(scaleTo = 0.94f, label = text, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, if (pressed) p.bg else p.ink, T.iconBar)
        Txt(text, T.fBar, if (pressed) p.bg else p.ink, FontWeight.SemiBold, lines = 1)
    }
}

/** Выбор книги и главы: фильтр по названию, заветы, сетка книг, затем сетка глав. */
@Composable
private fun Navigator(c: Controller, ui: Ui) {
    val p = P.current
    val d = c.desk
    var filter by remember { mutableStateOf("") }
    var testament by remember { mutableStateOf(if ((d.chapter.value?.book ?: 0) >= 470) 1 else 0) }
    var book by remember { mutableStateOf<Int?>(null) }
    val books = d.books()
    val b = book
    if (b == null) {
        SearchField(filter, L.bookName, Modifier.padding(bottom = T.gap)) { filter = it }
        if (filter.isBlank()) Seg(listOf(L.oldT, L.newT), testament, Modifier.fillMaxWidth().padding(bottom = T.gap)) { testament = it }
        val f = filter.trim().lowercase().replace('ё', 'е')
        val shown = books.filter { bk ->
            if (f.isNotEmpty()) bk.longName.lowercase().replace('ё', 'е').contains(f) || bk.shortName.lowercase().replace('ё', 'е').startsWith(f)
            else if (testament == 0) bk.number < 470 else bk.number >= 470
        }
        GridOf(shown, 3) { bk, m ->
            val cur = d.chapter.value?.book == bk.number
            Column(
                m.heightIn(min = T.btn).clip(RoundedCornerShape(T.rField)).background(if (cur) p.tint else p.cell)
                    .press(label = bk.longName) { book = bk.number }.padding(horizontal = 8.dp, vertical = T.gapS),
                verticalArrangement = Arrangement.Center,
            ) {
                Txt(bk.shortName, T.fPill, if (cur) Color.White else p.ink, FontWeight.SemiBold, lines = 1)
                Txt(bk.longName, T.fTag, if (cur) Color.White else p.l2, lines = 1)
            }
        }
    } else {
        Row(Modifier.padding(bottom = T.gap), verticalAlignment = Alignment.CenterVertically) {
            Pill(books.firstOrNull { it.number == b }?.longName ?: "", "prev") { book = null }
        }
        val n = d.chapterCount(b)
        GridOf((1..n).toList(), 6) { ch, m ->
            val cur = d.chapter.value?.let { it.book == b && it.chapter == ch } == true
            Box(
                m.height(T.iconBtn).clip(RoundedCornerShape(T.rField)).background(if (cur) p.tint else p.cell)
                    .press(label = L.chapterN(ch)) { d.open(b, ch); ui.closeAll() },
                contentAlignment = Alignment.Center,
            ) { Txt("$ch", T.fBody, if (cur) Color.White else p.ink, FontWeight.Medium) }
        }
    }
}

/** Оформление Писания: стихов на слайде, адрес, номера, переводы рядом. Действует и на то, что в зале. */
@Composable
private fun ScriptureOptionsSheet(c: Controller) {
    var o by remember { mutableStateOf(c.bible.options) }
    val set = { n: app.deixis.core.ScriptureOptions -> o = n; c.bible.options = n; c.desk.refreshLive() }
    Txt(L.versesPerSlide, T.fSub, P.current.l2, modifier = Modifier.padding(bottom = T.gapS))
    Seg(listOf("1", "2", "3"), (o.versesPerSlide - 1).coerceIn(0, 2), Modifier.fillMaxWidth().padding(bottom = T.gap)) { set(o.copy(versesPerSlide = it + 1)) }
    Txt(L.refLbl, T.fSub, P.current.l2, modifier = Modifier.padding(bottom = T.gapS))
    val ref = when { o.refMode == app.deixis.core.RefMode.NONE -> 2; o.refPosition == app.deixis.core.RefPosition.TOP -> 0; else -> 1 }
    Seg(listOf(L.top, L.bottom, L.refNone), ref, Modifier.fillMaxWidth().padding(bottom = T.gap)) {
        set(
            when (it) {
                0 -> o.copy(refMode = if (o.refMode == app.deixis.core.RefMode.NONE) app.deixis.core.RefMode.ONE_LINE else o.refMode, refPosition = app.deixis.core.RefPosition.TOP)
                1 -> o.copy(refMode = if (o.refMode == app.deixis.core.RefMode.NONE) app.deixis.core.RefMode.ONE_LINE else o.refMode, refPosition = app.deixis.core.RefPosition.BOTTOM)
                else -> o.copy(refMode = app.deixis.core.RefMode.NONE)
            },
        )
    }
    Group {
        SwitchRow(L.sideBySide, o.sideBySide, L.sideBySideSub) { set(o.copy(sideBySide = it)) }
        Sep(T.gutter)
        SwitchRow(L.fullBook, o.fullBookName, L.fullBookSub) { set(o.copy(fullBookName = it)) }
        Sep(T.gutter)
        SwitchRow(L.verseNumbers, o.verseNumbers) { set(o.copy(verseNumbers = it)) }
    }
    Note(L.scriptureNote, Modifier)
}

@Composable
private fun <V> GridOf(items: List<V>, columns: Int, cell: @Composable (V, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(T.gapS)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
                row.forEach { cell(it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
