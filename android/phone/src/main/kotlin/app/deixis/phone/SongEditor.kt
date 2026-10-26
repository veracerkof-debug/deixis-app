package app.deixis.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.deixis.core.SongSlide
import app.deixis.core.SongText

/** Слайд в редакторе: подпись и текст с курсором; [id] держит карточку на месте при перестановках. */
private data class EdSlide(val id: Long, val label: String?, val value: TextFieldValue) {
    val slide get() = SongSlide(label, value.text)
}

private var nextId = 0L
private fun ed(s: SongSlide) = EdSlide(nextId++, s.label, TextFieldValue(s.text))

/**
 * Песня или текст, как в Holyrics: слайды делит ведущий. Карточка на слайд — превью в оформлении зала,
 * текст, подпись («Куплет 1», «Припев»); «Разделить» — новый слайд с места курсора; объединить с
 * предыдущим, переставить, удалить. Можно и всё одним текстом: пустая строка — новый слайд, [Припев] —
 * подпись. [quick] — разовый показ («Текст» на главном): в предпросмотр или в зал, по желанию — в библиотеку.
 */
@Composable
fun SongEditor(c: Controller, ui: Ui, kind: Kind, item: LibItem? = null, quick: Boolean = false) {
    var title by remember { mutableStateOf(item?.title.orEmpty()) }
    var slides by remember {
        mutableStateOf(
            item?.slides?.mapIndexed { i, s -> ed(SongSlide(item.labels.getOrNull(i), s)) }?.ifEmpty { null } ?: listOf(ed(SongSlide())),
        )
    }
    var asText by remember { mutableStateOf(false) }
    var whole by remember { mutableStateOf(TextFieldValue("")) }
    var save by remember { mutableStateOf(!quick) }
    val style = c.styleFor(null)
    fun set(list: List<SongSlide>) { slides = list.map(::ed) }
    val current = { if (asText) SongText.parse(whole.text) else slides.map { it.slide } }

    Column(verticalArrangement = Arrangement.spacedBy(T.gap)) {
        if (!quick || save) Field(title, if (kind == Kind.SONG) "Название песни" else "Название") { title = it }
        Seg(listOf("Слайды", "Одним текстом"), if (asText) 1 else 0, Modifier.fillMaxWidth()) { i ->
            if (i == 1 && !asText) { whole = TextFieldValue(SongText.format(slides.map { it.slide })); asText = true }
            if (i == 0 && asText) { set(SongText.parse(whole.text).ifEmpty { listOf(SongSlide()) }); asText = false }
        }
        if (asText) {
            EditorField(whole, "Куплеты и припев.\nПустая строка — новый слайд, [Припев] — подпись слайда", minLines = 10) { whole = it }
            Note("Пустая строка между строками — новый слайд. Строка в квадратных скобках над слайдом — его подпись: [Куплет 1], [Припев].")
        } else {
            slides.forEachIndexed { i, s ->
                SlideCard(
                    c, ui, i, s, style,
                    onChange = { v -> slides = slides.map { if (it.id == s.id) it.copy(value = v) else it } },
                    onLabel = { l -> slides = slides.map { if (it.id == s.id) it.copy(label = l) else it } },
                    onSplit = { set(SongText.splitAt(slides.map { it.slide }, i, s.value.selection.start)) },
                    onMerge = if (i > 0) ({ set(SongText.mergeWithPrevious(slides.map { it.slide }, i)) }) else null,
                    onUp = if (i > 0) ({ slides = slides.toMutableList().apply { add(i - 1, removeAt(i)) } }) else null,
                    onDown = if (i < slides.lastIndex) ({ slides = slides.toMutableList().apply { add(i + 1, removeAt(i)) } }) else null,
                    onDelete = if (slides.size > 1) ({ slides = slides.filter { it.id != s.id } }) else null,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) {
                Pill("Слайд", "plus") { slides = slides + ed(SongSlide()) }
                Pill("По 2 строки") { set(SongText.rechunk(slides.map { it.slide }, 2)) }
                Pill("По 4 строки") { set(SongText.rechunk(slides.map { it.slide }, 4)) }
            }
            Note("Курсор в тексте и «Разделить» — новый слайд с этого места. Подпись — касанием по ней.")
        }
        val result = current().filter { it.text.isNotBlank() }
        Note(if (result.isEmpty()) "Пока пусто" else slidesText(result.size))
        if (quick) Group { SwitchRow("Сохранить в библиотеку", save) { save = it } }
        val keep = {
            val name = title.ifBlank { result.firstOrNull()?.text?.lineSequence()?.first()?.take(40) ?: kind.title }
            val saved = (item ?: LibItem(Store.newId(), kind, name)).copy(title = name, slides = result.map { it.text }, labels = result.map { it.label })
            c.saveItem(saved)
            saved
        }
        if (quick) {
            val go = { live: Boolean ->
                val source = if (save) c.sourceOf(keep())
                else Source(null, title.ifBlank { "Текст" }, kind, result.map { Slide.Text(it.text, it.label) })
                ui.closeAll()
                if (live) { c.goLive(Cue(source, 0)); ui.toast("В зале") } else c.toPreview(Cue(source, 0))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(T.gap)) {
                Btn("В предпросмотр", Modifier.weight(1f), enabled = result.isNotEmpty()) { go(false) }
                Btn("В зал", Modifier.weight(1f), kind = BtnKind.TINT, enabled = result.isNotEmpty()) { go(true) }
            }
        } else {
            Btn("Сохранить", kind = BtnKind.TINT, enabled = result.isNotEmpty()) { keep(); ui.closeAll(); ui.toast("Сохранено") }
        }
    }
}

@Composable
private fun SlideCard(
    c: Controller, ui: Ui, index: Int, s: EdSlide, style: app.deixis.core.TextStyle,
    onChange: (TextFieldValue) -> Unit, onLabel: (String?) -> Unit, onSplit: () -> Unit,
    onMerge: (() -> Unit)?, onUp: (() -> Unit)?, onDown: (() -> Unit)?, onDelete: (() -> Unit)?,
) {
    val p = P.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(T.rCard)).background(p.cell).padding(T.gapS),
        verticalArrangement = Arrangement.spacedBy(T.gapS),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gapXs)) {
            Txt("${index + 1}", T.fLabel, p.l2, FontWeight.SemiBold)
            Pill(s.label ?: "Подпись", tint = s.label != null, label = "Подпись слайда ${index + 1}") {
                ui.push("Подпись слайда ${index + 1}") { LabelPicker(ui, s.label, onLabel) }
            }
            Box(Modifier.weight(1f))
            onUp?.let { IconBtn("up", "Выше", onClick = it) }
            onDown?.let { IconBtn("down", "Ниже", onClick = it) }
            onDelete?.let { IconBtn("trash", "Удалить слайд", p.red, onClick = it) }
        }
        // Превью в оформлении зала: видно, как слайд ляжет на экран, пока его правят.
        SlideView(Slide.Text(s.value.text, s.label), null, style)
        EditorField(s.value, "Строки слайда", minLines = 3, onChange = onChange)
        Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) {
            Pill("Разделить по курсору", "edit", label = "Разделить слайд ${index + 1} по курсору", onClick = onSplit)
            onMerge?.let { Pill("С предыдущим", "up", label = "Объединить слайд ${index + 1} с предыдущим", onClick = it) }
        }
    }
}

/** Подпись слайда: готовые («Куплет 1», «Припев»…), своя или без подписи. */
@Composable
private fun LabelPicker(ui: Ui, current: String?, onPick: (String?) -> Unit) {
    var own by remember { mutableStateOf(current?.takeIf { it !in SongText.LABELS }.orEmpty()) }
    Group {
        Cell("Без подписи", chevron = null, onClick = { onPick(null); ui.close() })
        SongText.LABELS.forEach { l ->
            Sep(T.gutter)
            Cell(l, chevron = null, open = l == current, onClick = { onPick(l); ui.close() })
        }
    }
    Field(own, "Своя подпись", Modifier.padding(top = T.gap)) { own = it }
    Btn("Поставить свою", Modifier.padding(top = T.gapS), enabled = own.isNotBlank()) { onPick(own.trim()); ui.close() }
}

/** Многострочное поле с курсором: по нему слайд делится на два. */
@Composable
private fun EditorField(value: TextFieldValue, placeholder: String, minLines: Int, onChange: (TextFieldValue) -> Unit) {
    val p = P.current
    val style = TextStyle(fontSize = T.fBody, color = p.ink)
    Box(Modifier.fillMaxWidth().heightIn(min = T.btn).clip(RoundedCornerShape(T.rCard)).background(p.bg).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.text.isEmpty()) Txt(placeholder, T.fBody, p.l3)
        BasicTextField(
            value, onChange, textStyle = style, cursorBrush = SolidColor(p.tint), minLines = minLines,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
