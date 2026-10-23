package app.deixis.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import app.deixis.core.BibleModule
import app.deixis.core.RefMode
import app.deixis.core.RefPosition
import app.deixis.core.ScriptureOptions
import app.deixis.core.parseBibleRef
import app.deixis.core.refLabel

private enum class Step { BOOK, CHAPTER, VERSE }

/** В MyBible Новый Завет начинается с Евангелия от Матфея (номер 470). */
private const val NEW_TESTAMENT = 470

/**
 * Выбор места Писания: книга → глава → стих или диапазон. Переводы — до двух, первый основной.
 * Быстрый ввод «Ин 3:16» сразу открывает нужные стихи.
 */
@Composable
fun BiblePicker(c: Controller, ui: Ui, edit: LibItem? = null) {
    val p = P.current
    val modules by c.bibleModules.collectAsState()
    if (modules.isEmpty()) {
        Note("Переводы загружаются… Если их нет, добавьте модуль MyBible в «Библиотека → + → Переводы Библии».", Modifier)
        return
    }
    val start = edit?.bible
    var selected by remember(modules) {
        mutableStateOf((start?.modules ?: c.bible.selected).filter { f -> modules.any { it.fileName == f } }.ifEmpty { listOf(modules.first().fileName) })
    }
    val primary: BibleModule = modules.firstOrNull { it.fileName == selected.first() } ?: modules.first()
    var step by remember { mutableStateOf(if (start != null) Step.VERSE else Step.BOOK) }
    var testament by remember { mutableStateOf(if ((start?.book ?: 0) >= NEW_TESTAMENT) 1 else 0) }
    var book by remember { mutableStateOf(start?.book) }
    var chapter by remember { mutableStateOf(start?.chapter) }
    var from by remember { mutableStateOf(start?.from) }
    var to by remember { mutableStateOf(start?.to) }
    var options by remember { mutableStateOf(start?.options ?: c.bible.options) }
    var query by remember { mutableStateOf("") }
    var more by remember { mutableStateOf(false) }

    // Переводы: касание добавляет второй или убирает; первый — основной.
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = T.gap), horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
        modules.forEach { m ->
            val i = selected.indexOf(m.fileName)
            Pill(
                if (i >= 0 && selected.size > 1) "${i + 1} · ${m.abbreviation}" else m.abbreviation, tint = i >= 0, label = m.title,
            ) {
                selected = when {
                    i >= 0 && selected.size > 1 -> selected - m.fileName
                    i >= 0 -> selected
                    selected.size >= 2 -> listOf(selected.first(), m.fileName)
                    else -> selected + m.fileName
                }
            }
        }
    }

    SearchField(query, "Быстро: Ин 3:16 или Пс 22", Modifier.padding(bottom = T.gap)) { query = it }
    val parsed = remember(query, primary) { if (query.isBlank()) null else parseBibleRef(query, primary.books) }
    if (parsed != null) {
        val label = runCatching {
            val last = parsed.toVerse ?: parsed.fromVerse ?: primary.verseCount(parsed.book, parsed.chapter)
            refLabel(primary, parsed.book, parsed.chapter, parsed.fromVerse ?: 1, last, full = true, withModule = false)
        }.getOrNull()
        if (label != null) Group {
            Cell("Открыть: $label", kind = "bible", onClick = {
                book = parsed.book; chapter = parsed.chapter
                from = parsed.fromVerse; to = parsed.toVerse ?: parsed.fromVerse
                testament = if (parsed.book >= NEW_TESTAMENT) 1 else 0
                step = Step.VERSE; query = ""
            })
        }
    }

    // Где мы: книга › глава. Касание возвращает на шаг назад.
    val bk = book?.let { primary.book(it) }
    if (step != Step.BOOK) Row(Modifier.padding(bottom = T.gap), horizontalArrangement = Arrangement.spacedBy(T.gapS), verticalAlignment = Alignment.CenterVertically) {
        Pill(bk?.longName ?: "Книга", "prev") { step = Step.BOOK }
        if (step == Step.VERSE && chapter != null) Pill("Глава $chapter") { step = Step.CHAPTER }
    }

    when (step) {
        Step.BOOK -> {
            Seg(listOf("Ветхий Завет", "Новый Завет"), testament, Modifier.fillMaxWidth().padding(bottom = T.gap)) { testament = it }
            val books = primary.books.filter { if (testament == 0) it.number < NEW_TESTAMENT else it.number >= NEW_TESTAMENT }
            Grid(books, 3) { b, m ->
                Column(
                    m.heightIn(min = T.btn).clip(RoundedCornerShape(T.rField)).background(if (b.number == book) p.tint else p.cell)
                        .press(label = b.longName) { book = b.number; chapter = null; from = null; to = null; step = Step.CHAPTER }
                        .padding(horizontal = 8.dp, vertical = T.gapS),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Txt(b.shortName, T.fPill, if (b.number == book) Color.White else p.ink, FontWeight.SemiBold, lines = 1)
                    Txt(b.longName, T.fTag, if (b.number == book) Color.White else p.l2, lines = 1)
                }
            }
        }
        Step.CHAPTER -> {
            val n = book?.let { runCatching { primary.chapterCount(it) }.getOrDefault(0) } ?: 0
            Grid((1..n).toList(), 6) { ch, m ->
                Num(ch, ch == chapter, m) { chapter = ch; from = null; to = null; step = Step.VERSE }
            }
        }
        Step.VERSE -> {
            val b = book
            val ch = chapter
            if (b != null && ch != null) {
                val n = runCatching { primary.verseCount(b, ch) }.getOrDefault(0)
                Txt(if (from == null) "Стих или диапазон: первое касание — начало, второе — конец" else "Выбрано: ${rangeText(from, to)}", T.fSub, p.l2, modifier = Modifier.padding(bottom = T.gapS))
                Grid((1..n).toList(), 6) { v, m ->
                    val f = from
                    val t = to ?: f
                    val inRange = f != null && t != null && v in f..t
                    Num(v, inRange, m) {
                        if (f == null || t != f || v < f) { from = v; to = v } else to = v
                    }
                }
                val first = from ?: 1
                val last = to ?: from ?: n
                val verses = runCatching { primary.verses(b, ch, first, last) }.getOrDefault(emptyList())
                if (verses.isNotEmpty()) Group(Modifier.padding(top = T.gap)) {
                    Column(Modifier.padding(T.gutter), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        verses.take(6).forEach { Txt("${it.number}  ${app.deixis.core.cleanVerseText(it.text)}", T.fSub) }
                        if (verses.size > 6) Txt("…и ещё ${verses.size - 6}", T.fSub, p.l2)
                    }
                }

                // Оформление Писания: свёрнуто, чтобы выбор места оставался главным.
                Cell("Оформление Писания", if (more) null else optionsText(options, selected.size), "brush", chevron = if (more) "down" else "chev", onClick = { more = !more })
                if (more) Options(options, selected.size) { options = it }
                Spacer(Modifier.height(T.gap))

                val pick = BiblePick(b, ch, first, last, selected, options)
                val title = runCatching { refLabel(primary, b, ch, first, last, full = false, withModule = false) }.getOrDefault("Писание")
                val keep = {
                    c.bible.selected = selected
                    c.bible.options = options
                }
                val source = {
                    val slides = c.scripture(pick)
                    if (slides.isEmpty()) { ui.toast("Стихи не найдены в выбранных переводах"); null }
                    else Source(edit?.id, title, Kind.BIBLE, slides.map { Slide.Scripture(it) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(T.gap)) {
                    Btn("В предпросмотр", Modifier.weight(1f)) {
                        keep(); source()?.let { ui.closeAll(); ui.tab = AppTab.SHOW; c.toPreview(Cue(it, 0)) }
                    }
                    Btn("В зал", Modifier.weight(1f), kind = BtnKind.TINT) {
                        keep(); source()?.let { ui.closeAll(); ui.tab = AppTab.SHOW; c.goLive(Cue(it, 0)); ui.toast("В зале") }
                    }
                }
                Spacer(Modifier.height(T.gap))
                Btn(if (edit != null) "Сохранить" else "В сетлист", icon = if (edit != null) null else "plus") {
                    keep()
                    val item = (edit ?: LibItem(Store.newId(), Kind.BIBLE, title)).copy(title = title, bible = pick)
                    c.saveItem(item)
                    if (edit == null) c.addToSet(item.id)
                    ui.closeAll()
                    ui.toast(if (edit != null) "Сохранено" else "Добавлено в сетлист")
                }
            }
        }
    }
}

private fun rangeText(from: Int?, to: Int?) = when {
    from == null -> ""
    to == null || to == from -> "стих $from"
    else -> "стихи $from–$to"
}

private fun optionsText(o: ScriptureOptions, translations: Int): String = listOfNotNull(
    "по ${o.versesPerSlide} ст. на слайде",
    when { o.refMode == RefMode.NONE -> "без адреса"; o.refPosition == RefPosition.TOP -> "адрес сверху"; else -> "адрес снизу" },
    if (translations > 1 && o.sideBySide) "переводы рядом" else null,
).joinToString(" · ")

@Composable
private fun Options(o: ScriptureOptions, translations: Int, onChange: (ScriptureOptions) -> Unit) {
    Column(Modifier.padding(top = T.gapS), verticalArrangement = Arrangement.spacedBy(T.gap)) {
        Txt("Стихов на слайде", T.fSub, P.current.l2)
        Seg(listOf("1", "2", "3"), (o.versesPerSlide - 1).coerceIn(0, 2), Modifier.fillMaxWidth()) { onChange(o.copy(versesPerSlide = it + 1)) }
        Txt("Адрес", T.fSub, P.current.l2)
        val ref = when { o.refMode == RefMode.NONE -> 2; o.refPosition == RefPosition.TOP -> 0; else -> 1 }
        Seg(listOf("Сверху", "Снизу", "Без адреса"), ref, Modifier.fillMaxWidth()) {
            onChange(
                when (it) {
                    0 -> o.copy(refMode = if (o.refMode == RefMode.NONE) RefMode.ONE_LINE else o.refMode, refPosition = RefPosition.TOP)
                    1 -> o.copy(refMode = if (o.refMode == RefMode.NONE) RefMode.ONE_LINE else o.refMode, refPosition = RefPosition.BOTTOM)
                    else -> o.copy(refMode = RefMode.NONE)
                },
            )
        }
        Group {
            SwitchRow("Полное название книги", o.fullBookName, "«От Иоанна» вместо «Ин»") { onChange(o.copy(fullBookName = it)) }
            Sep(T.gutter)
            SwitchRow("Номера стихов", o.verseNumbers) { onChange(o.copy(verseNumbers = it)) }
            if (translations > 1) {
                Sep(T.gutter)
                SwitchRow("Два перевода рядом", o.sideBySide, "В две колонки, а не друг под другом") { onChange(o.copy(sideBySide = it)) }
                Sep(T.gutter)
                SwitchRow("Адрес у каждого перевода", o.refMode == RefMode.PER_TRANSLATION) {
                    onChange(o.copy(refMode = if (it) RefMode.PER_TRANSLATION else RefMode.ONE_LINE))
                }
            }
        }
    }
}

@Composable
private fun Num(n: Int, on: Boolean, m: Modifier, onClick: () -> Unit) {
    val p = P.current
    Box(
        m.height(T.iconBtn).clip(RoundedCornerShape(T.rField)).background(if (on) p.tint else p.cell).press(label = "$n", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Txt("$n", T.fBody, if (on) Color.White else p.ink, FontWeight.Medium, align = TextAlign.Center) }
}

/** Сетка без ленивого списка: шторка уже прокручивается. */
@Composable
private fun <V> Grid(items: List<V>, columns: Int, cell: @Composable (V, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(T.gapS)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
                row.forEach { cell(it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
