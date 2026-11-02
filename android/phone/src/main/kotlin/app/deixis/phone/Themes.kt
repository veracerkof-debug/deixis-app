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
val BUILT_IN_THEMES: List<Theme> get() = listOf(
    Theme("classic", L.thClassic, TextStyle(name = L.thClassic), builtIn = true),
    Theme("night", L.thNight, TextStyle(background = 0xFF18233A, name = L.thNight), builtIn = true),
    Theme("warm", L.thWarm, TextStyle(background = 0xFF4A3420, color = 0xFFF4EFE6, name = L.thWarm), builtIn = true),
    Theme("forest", L.thForest, TextStyle(background = 0xFF20443A, name = L.thForest), builtIn = true),
    Theme("gold", L.thGold, TextStyle(color = 0xFFFFD43B, outline = true, name = L.thGold), builtIn = true),
    Theme("paper", L.paper, TextStyle(background = 0xFFF4EFE6, color = 0xFF2B2620, shadow = false, name = L.paper), builtIn = true),
    Theme("lower", L.thLower, TextStyle(background = 0xFF000000, sizePercent = 12f, valign = VAlign.BOTTOM, box = 0xB4000000, name = L.thLower), builtIn = true),
)

/** Фон темы с телефона: хэш → адрес файла, для превью. */
val LocalBackgrounds = staticCompositionLocalOf<(String) -> String?> { { null } }

private val backgrounds: List<Pair<Long, String>> get() = listOf(0xFF000000 to L.black, 0xFF18233A to L.cNavy, 0xFF4A3420 to L.cWarm, 0xFF20443A to L.cGreen, 0xFF3A2A4A to L.cPlum, 0xFFF4EFE6 to L.paper)
private val inks: List<Pair<Long, String>> get() = listOf(0xFFFFFFFF to L.cWhite, 0xFFF4EFE6 to L.cCream, 0xFFFFD43B to L.cGold, 0xFF2B2620 to L.cDark)
private val SAMPLE: String get() = L.sample

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
            Column(Modifier.width(112.dp).combinedClick(onClick = { onPick(t) }, onLong = { ui.push(if (t.builtIn) L.newTheme else L.themeNamed(t.name)) { ThemeEditor(c, ui, t) } })) {
                SlideView(Slide.Text(L.sampleShort), null, t.style, frame = if (on) Frame.PREVIEW_TINT else Frame.NONE)
                Txt(t.name, T.fLabel, if (on) P.current.tintText else P.current.ink, FontWeight.SemiBold, Modifier.padding(top = 6.dp), lines = 1)
            }
        }
        Column(Modifier.width(112.dp).press(label = L.newTheme) { ui.push(L.newTheme) { ThemeEditor(c, ui, null, current) } }) {
            Box(Modifier.fillMaxWidth().height(63.dp).clip(RoundedCornerShape(T.rSlide)).background(P.current.fill), contentAlignment = Alignment.Center) {
                Icon("plus", P.current.tintText)
            }
            Txt(L.newThemeShort, T.fLabel, P.current.l2, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * Редактор темы: живое превью, фон (цвет или своё фото), цвет и размер текста, тень, обводка, плашка,
 * выравнивание, положение, заглавные. Встроенная тема не меняется — сохраняется копия.
 */
@Composable
fun ThemeEditor(c: Controller, ui: Ui, theme: Theme?, base: TextStyle? = null) {
    val own = theme != null && !theme.builtIn
    var name by remember { mutableStateOf(if (own) theme!!.name else (theme?.name?.let { L.copyOf(it) } ?: L.myTheme)) }
    var st by remember { mutableStateOf((theme?.style ?: base ?: TextStyle()).copy(name = null)) }
    SlideView(Slide.Text(SAMPLE), null, st, Modifier.padding(bottom = T.gutter))
    Field(name, L.themeName, Modifier.padding(bottom = T.gap)) { name = it }
    StyleControls(c, ui, st) { st = it }
    Row(horizontalArrangement = Arrangement.spacedBy(T.gap)) {
        if (own) Btn(L.delete, Modifier.weight(1f), kind = BtnKind.RED) { c.deleteTheme(theme!!.id); ui.close(); ui.toast(L.themeDeleted) }
        Btn(L.save, Modifier.weight(1f), kind = BtnKind.TINT, enabled = name.isNotBlank()) {
            val t = Theme(if (own) theme!!.id else Store.newId(), name.trim(), st.copy(name = name.trim()))
            c.saveTheme(t)
            // Открыта правка оформления — сохранённая тема сразу в ней; из панели тем — только сохранение,
            // иначе тема молча ушла бы в зал со следующим «В зал».
            if (c.draft.value != null) c.draft.value = t.style
            ui.close()
            ui.toast(L.themeSaved(t.name))
        }
    }
}

/**
 * Настройки оформления: фон (цвет, фото, видеофон), цвет и размер текста, тень, обводка, плашка, заглавные,
 * выравнивание, положение. Общие для редактора темы и оформления по умолчанию.
 */
@Composable
fun StyleControls(c: Controller, ui: Ui, st: TextStyle, onChange: (TextStyle) -> Unit) {
    val scope = rememberCoroutineScope()
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { runCatching { c.backgroundFrom(it) }.onSuccess { ref -> onChange(st.copy(backgroundImage = ref)) }.onFailure { e -> ui.toast(e.message ?: L.photoNotOpened) } } }
    }
    // Видеофон, как анимированный фон Holyrics: играет по кругу без звука, текст меняется поверх.
    val video = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { runCatching { c.backgroundFrom(it) }.onSuccess { ref -> onChange(st.copy(backgroundVideo = ref)) }.onFailure { e -> ui.toast(e.message ?: L.videoNotOpened) } } }
    }
    Group {
        Cell(L.background, chevron = null) {
            Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) {
                backgrounds.take(4).forEach { (v, n) -> Swatch(Color(v), st.background == v && st.backgroundImage == null, L.bgNamed(n)) { onChange(st.copy(background = v, backgroundImage = null)) } }
            }
        }
        Sep(T.gutter)
        Cell(L.moreColors, chevron = null) {
            Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) {
                backgrounds.drop(4).forEach { (v, n) -> Swatch(Color(v), st.background == v && st.backgroundImage == null, L.bgNamed(n)) { onChange(st.copy(background = v, backgroundImage = null)) } }
            }
        }
        Sep(T.gutter)
        Cell(if (st.backgroundImage != null) L.bgNamed(st.backgroundImage?.name) else L.photoBg, kind = "photo", chevron = null, onClick = { photo.launch(arrayOf("image/*")) }) {
            if (st.backgroundImage != null) Pill(L.remove) { onChange(st.copy(backgroundImage = null)) }
        }
        Sep(T.gutter)
        Cell(if (st.backgroundVideo != null) L.videoBgNamed(st.backgroundVideo?.name) else L.videoBg, L.videoBgSub, kind = "media", chevron = null, onClick = { video.launch(arrayOf("video/*")) }) {
            if (st.backgroundVideo != null) Pill(L.remove) { onChange(st.copy(backgroundVideo = null)) }
        }
        Sep(T.gutter)
        Cell(L.textColor, chevron = null) {
            Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) { inks.forEach { (v, n) -> Swatch(Color(v), st.color == v, L.colorNamed(n)) { onChange(st.copy(color = v)) } } }
        }
        Sep(T.gutter)
        Cell(L.textSize, chevron = null) {
            Stepper("${st.sizePercent.toInt()}", { onChange(st.copy(sizePercent = textSizeStep(st.sizePercent, up = false))) }, { onChange(st.copy(sizePercent = textSizeStep(st.sizePercent, up = true))) })
        }
        Sep(T.gutter)
        SwitchRow(L.shadow, st.shadow) { onChange(st.copy(shadow = it)) }
        Sep(T.gutter)
        SwitchRow(L.outline, st.outline) { onChange(st.copy(outline = it)) }
        Sep(T.gutter)
        SwitchRow(L.box, st.box != null, L.boxSub) { onChange(st.copy(box = if (it) 0xB4000000 else null)) }
        Sep(T.gutter)
        SwitchRow(L.capsTheme, st.caps) { onChange(st.copy(caps = it)) }
    }
    GHead(L.alignment)
    Seg(listOf(L.left, L.center, L.right), HAlign.entries.indexOf(st.align), Modifier.fillMaxWidth().padding(bottom = T.gutter)) { onChange(st.copy(align = HAlign.entries[it])) }
    GHead(L.position)
    Seg(listOf(L.top, L.center, L.bottom), VAlign.entries.indexOf(st.valign), Modifier.fillMaxWidth().padding(bottom = T.gutter)) { onChange(st.copy(valign = VAlign.entries[it])) }
}

/** Оформление по умолчанию: как выглядит любой слайд, пока у элемента или сетлиста нет своего. */
@Composable
fun DefaultStyleEditor(c: Controller, ui: Ui) {
    var st by remember { mutableStateOf(c.defaultStyle()) }
    SlideView(Slide.Text(SAMPLE), null, st, Modifier.padding(bottom = T.gutter))
    GHead(L.themes)
    ThemesRow(c, ui, st) { t -> st = t.style.copy(name = t.name) }
    StyleControls(c, ui, st) { st = it }
    Note(L.defaultStyleNote, Modifier.padding(bottom = T.gap))
    Row(horizontalArrangement = Arrangement.spacedBy(T.gap)) {
        Btn(L.asBefore, Modifier.weight(1f)) { st = TextStyle() }
        Btn(L.save, Modifier.weight(1f), kind = BtnKind.TINT) { c.setDefaultStyle(st); ui.close(); ui.toast(L.defaultStyleSaved) }
    }
}
