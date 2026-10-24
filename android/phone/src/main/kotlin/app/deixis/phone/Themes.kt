package app.deixis.phone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.deixis.core.HAlign
import app.deixis.core.TextStyle
import app.deixis.core.VAlign
import kotlinx.coroutines.launch

/** Встроенные темы: спокойные, читаемые с последнего ряда. Свои создаются на их основе. */
val BUILT_IN_THEMES = listOf(
    Theme("classic", "Классика", TextStyle(name = "Классика"), builtIn = true),
    Theme("night", "Ночь", TextStyle(background = 0xFF18233A, name = "Ночь"), builtIn = true),
    Theme("warm", "Тёплая", TextStyle(background = 0xFF4A3420, color = 0xFFF4EFE6, name = "Тёплая"), builtIn = true),
    Theme("forest", "Лес", TextStyle(background = 0xFF20443A, name = "Лес"), builtIn = true),
    Theme("gold", "Золото", TextStyle(color = 0xFFFFD43B, outline = true, name = "Золото"), builtIn = true),
    Theme("paper", "Бумага", TextStyle(background = 0xFFF4EFE6, color = 0xFF2B2620, shadow = false, name = "Бумага"), builtIn = true),
    Theme("lower", "Нижняя треть", TextStyle(background = 0xFF000000, sizePercent = 12f, valign = VAlign.BOTTOM, box = 0xB4000000, name = "Нижняя треть"), builtIn = true),
)

/** Фон темы с телефона: хэш → адрес файла, для превью. */
val LocalBackgrounds = staticCompositionLocalOf<(String) -> String?> { { null } }

private val backgrounds = listOf(0xFF000000 to "Чёрный", 0xFF18233A to "Синий", 0xFF4A3420 to "Тёплый", 0xFF20443A to "Зелёный", 0xFF3A2A4A to "Сливовый", 0xFFF4EFE6 to "Бумага")
private val inks = listOf(0xFFFFFFFF to "Белый", 0xFFF4EFE6 to "Кремовый", 0xFFFFD43B to "Золотой", 0xFF2B2620 to "Тёмный")
private const val SAMPLE = "Господь — Пастырь мой;\nя ни в чём не буду нуждаться"

/** Ряд тем в «Оформлении»: касание — тема в предпросмотр, удержание — изменить. */
@Composable
fun ThemesRow(c: Controller, ui: Ui, current: TextStyle, onPick: (Theme) -> Unit) {
    val themes by c.themes.collectAsState()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = T.gutter),
        horizontalArrangement = Arrangement.spacedBy(T.gap),
    ) {
        themes.forEach { t ->
            val on = current.copy(caps = false) == t.style.copy(caps = false)
            Column(Modifier.width(112.dp).combinedClick(onClick = { onPick(t) }, onLong = { ui.push(if (t.builtIn) "Новая тема" else "Тема «${t.name}»") { ThemeEditor(c, ui, t) } })) {
                SlideView(Slide.Text("Аа Бб"), null, t.style, frame = if (on) Frame.PREVIEW_TINT else Frame.NONE)
                Txt(t.name, T.fLabel, if (on) P.current.tintText else P.current.ink, FontWeight.SemiBold, Modifier.padding(top = 6.dp), lines = 1)
            }
        }
        Column(Modifier.width(112.dp).press(label = "Новая тема") { ui.push("Новая тема") { ThemeEditor(c, ui, null, current) } }) {
            Box(Modifier.fillMaxWidth().height(63.dp).clip(RoundedCornerShape(T.rSlide)).background(P.current.fill), contentAlignment = Alignment.Center) {
                Icon("plus", P.current.tintText)
            }
            Txt("Новая", T.fLabel, P.current.l2, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * Редактор темы: живое превью, фон (цвет или своё фото), цвет и размер текста, тень, обводка, плашка,
 * выравнивание, положение, заглавные. Встроенная тема не меняется — сохраняется копия.
 */
@Composable
fun ThemeEditor(c: Controller, ui: Ui, theme: Theme?, base: TextStyle? = null) {
    val scope = rememberCoroutineScope()
    val own = theme != null && !theme.builtIn
    var name by remember { mutableStateOf(if (own) theme!!.name else (theme?.name?.let { "$it — копия" } ?: "Моя тема")) }
    var st by remember { mutableStateOf((theme?.style ?: base ?: TextStyle()).copy(name = null)) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { runCatching { c.backgroundFrom(it) }.onSuccess { ref -> st = st.copy(backgroundImage = ref) }.onFailure { e -> ui.toast(e.message ?: "Фото не открылось") } } }
    }
    // Видеофон, как анимированный фон Holyrics: играет по кругу без звука, текст меняется поверх.
    val video = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { runCatching { c.backgroundFrom(it) }.onSuccess { ref -> st = st.copy(backgroundVideo = ref) }.onFailure { e -> ui.toast(e.message ?: "Видео не открылось") } } }
    }
    SlideView(Slide.Text(SAMPLE), null, st, Modifier.padding(bottom = T.gutter))
    Field(name, "Название темы", Modifier.padding(bottom = T.gap)) { name = it }
    Group {
        Cell("Фон", chevron = null) {
            Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) {
                backgrounds.take(4).forEach { (v, n) -> Swatch(Color(v), st.background == v && st.backgroundImage == null, "Фон: $n") { st = st.copy(background = v, backgroundImage = null) } }
            }
        }
        Sep(T.gutter)
        Cell("Ещё цвета", chevron = null) {
            Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) {
                backgrounds.drop(4).forEach { (v, n) -> Swatch(Color(v), st.background == v && st.backgroundImage == null, "Фон: $n") { st = st.copy(background = v, backgroundImage = null) } }
            }
        }
        Sep(T.gutter)
        Cell(if (st.backgroundImage != null) "Фон: ${st.backgroundImage?.name}" else "Фото на фон", kind = "photo", chevron = null, onClick = { photo.launch(arrayOf("image/*")) }) {
            if (st.backgroundImage != null) Pill("Убрать") { st = st.copy(backgroundImage = null) }
        }
        Sep(T.gutter)
        Cell(if (st.backgroundVideo != null) "Видеофон: ${st.backgroundVideo?.name}" else "Видеофон", "Играет по кругу без звука, текст меняется поверх", kind = "media", chevron = null, onClick = { video.launch(arrayOf("video/*")) }) {
            if (st.backgroundVideo != null) Pill("Убрать") { st = st.copy(backgroundVideo = null) }
        }
        Sep(T.gutter)
        Cell("Цвет текста", chevron = null) {
            Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) { inks.forEach { (v, n) -> Swatch(Color(v), st.color == v, "Цвет: $n") { st = st.copy(color = v) } } }
        }
        Sep(T.gutter)
        Cell("Размер текста", chevron = null) {
            Stepper("${st.sizePercent.toInt()}", { st = st.copy(sizePercent = (st.sizePercent - 2).coerceAtLeast(8f)) }, { st = st.copy(sizePercent = (st.sizePercent + 2).coerceAtMost(32f)) })
        }
        Sep(T.gutter)
        SwitchRow("Тень", st.shadow) { st = st.copy(shadow = it) }
        Sep(T.gutter)
        SwitchRow("Обводка", st.outline) { st = st.copy(outline = it) }
        Sep(T.gutter)
        SwitchRow("Плашка под текстом", st.box != null, "Тёмная полоса: текст читается на любом фото") { st = st.copy(box = if (it) 0xB4000000 else null) }
        Sep(T.gutter)
        SwitchRow("Заглавные буквы", st.caps) { st = st.copy(caps = it) }
    }
    GHead("Выравнивание")
    Seg(listOf("Слева", "По центру", "Справа"), HAlign.entries.indexOf(st.align), Modifier.fillMaxWidth().padding(bottom = T.gutter)) { st = st.copy(align = HAlign.entries[it]) }
    GHead("Положение")
    Seg(listOf("Сверху", "По центру", "Снизу"), VAlign.entries.indexOf(st.valign), Modifier.fillMaxWidth().padding(bottom = T.gutter)) { st = st.copy(valign = VAlign.entries[it]) }
    Row(horizontalArrangement = Arrangement.spacedBy(T.gap)) {
        if (own) Btn("Удалить", Modifier.weight(1f), kind = BtnKind.RED) { c.deleteTheme(theme!!.id); ui.close(); ui.toast("Тема удалена") }
        Btn("Сохранить", Modifier.weight(1f), kind = BtnKind.TINT, enabled = name.isNotBlank()) {
            val t = Theme(if (own) theme!!.id else Store.newId(), name.trim(), st.copy(name = name.trim()))
            c.saveTheme(t)
            // Сохранённая тема сразу в предпросмотре: её можно применить в зал.
            c.draft.value = t.style
            ui.close()
            ui.toast("Тема «${t.name}» сохранена")
        }
    }
}
