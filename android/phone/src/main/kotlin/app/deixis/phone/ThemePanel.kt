package app.deixis.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.deixis.core.TextStyle
import app.deixis.core.toTextStyle

/** Тема в панели: своя или встроенная Deixis, либо из Holyrics. */
private class ThemeChoice(val key: String, val name: String, val style: TextStyle, val apply: () -> Unit, val edit: (() -> Unit)?)

/** Темы для панели: встроенные и свои Deixis, затем темы Holyrics, если он подключён. */
@Composable
private fun themeChoices(c: Controller, h: HolyricsSource, ui: Ui): List<ThemeChoice> {
    val themes by c.themes.collectAsState()
    val holyrics by h.themes.collectAsState()
    return themes.map { t ->
        ThemeChoice("d:${t.id}", t.name, t.style, { c.applyTheme(t.style) }) {
            ui.sheet(if (t.builtIn) "Новая тема" else "Тема «${t.name}»") { ThemeEditor(c, ui, t) }
        }
    } + holyrics.map { t ->
        ThemeChoice("h:${t.id}", t.name.ifBlank { "Holyrics" }, t.toTextStyle(), { h.applyTheme(t) }, null)
    }
}

/** Тема сейчас у того, что в зале (или в предпросмотре): считается от прочитанных состояний — отметка не отстаёт. */
@Composable
private fun currentStyle(c: Controller): TextStyle {
    val live by c.live.collectAsState()
    val preview by c.preview.collectAsState()
    val data by c.data.collectAsState()
    return remember(live, preview, data) { c.currentStyle() }
}

private fun same(a: TextStyle, b: TextStyle) = a.copy(name = null, caps = false) == b.copy(name = null, caps = false)

/** Плитка темы: превью «Аа» в этой теме и название; касание — тема в зал, удержание — изменить. */
@Composable
private fun ThemeTile(t: ThemeChoice, on: Boolean, drag: ThemeDrag, modifier: Modifier = Modifier) {
    val p = P.current
    Column(
        // Касание — тема в зал; зажать и перенести — на песню, слайд или «Сейчас»; зажать и отпустить — изменить.
        modifier.clickable(onClick = t.apply).themeSource(drag, t.name, t.style.copy(name = t.name), t.edit)
            .semantics { contentDescription = "Тема «${t.name}»" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SlideView(Slide.Text("Аа"), null, t.style, frame = if (on) Frame.PREVIEW_TINT else Frame.NONE)
        Txt(t.name, T.fTag, if (on) p.tintText else p.l2, if (on) FontWeight.SemiBold else FontWeight.Normal, Modifier.padding(top = 2.dp), lines = 1)
    }
}

/** Кнопки вида панели: спрятать в значок, узкая полоса, на всё место. */
@Composable
private fun PanelButtons(c: Controller, mode: ThemePanel) {
    if (mode != ThemePanel.HIDDEN) IconBtn("close", "Спрятать темы") { c.setSettings { it.copy(themePanel = ThemePanel.HIDDEN) } }
    if (mode == ThemePanel.FULL) IconBtn("next", "Темы узкой полосой") { c.setSettings { it.copy(themePanel = ThemePanel.STRIP) } }
    if (mode == ThemePanel.STRIP) IconBtn("prev", "Темы на всё место") { c.setSettings { it.copy(themePanel = ThemePanel.FULL) } }
}

/**
 * Панель тем на главном — узкая полоса в одну плитку справа от сетлиста, во всю высоту до окон показа.
 * Тема меняется на ходу: касание — сразу в зал, и остаётся у песни или документа.
 */
@Composable
fun ThemeStrip(c: Controller, h: HolyricsSource, ui: Ui) {
    val p = P.current
    val choices = themeChoices(c, h, ui)
    val cur = currentStyle(c)
    Column(Modifier.width(T.themeStrip).fillMaxHeight().background(p.cell)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { PanelButtons(c, ThemePanel.STRIP) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = T.gapXs), verticalArrangement = Arrangement.spacedBy(T.gapS)) {
            items(choices.size, key = { choices[it].key + if (same(choices[it].style, cur)) ":on" else "" }) { i -> ThemeTile(choices[i], same(choices[i].style, cur), ui.themeDrag, Modifier.fillMaxWidth()) }
            item("end") { Spacer(Modifier.padding(bottom = T.gap)) }
        }
    }
}

/** Темы на всё место под окнами «Сейчас» и «Предпросмотр»: сеткой, крупнее. */
@Composable
fun ThemeGrid(c: Controller, h: HolyricsSource, ui: Ui) {
    val choices = themeChoices(c, h, ui)
    val cur = currentStyle(c)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = T.gutter, end = 8.dp, bottom = T.gapS), verticalAlignment = Alignment.CenterVertically) {
            Txt("Темы", T.fBody, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            PanelButtons(c, ThemePanel.FULL)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = T.gutter), verticalArrangement = Arrangement.spacedBy(T.gap)) {
            val rows = choices.chunked(GRID_COLS)
            items(rows.size) { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(T.gap)) {
                    rows[r].forEach { t -> ThemeTile(t, same(t.style, cur), ui.themeDrag, Modifier.weight(1f)) }
                    repeat(GRID_COLS - rows[r].size) { Box(Modifier.weight(1f)) }
                }
            }
            item("note") { Note("Касание — тема сразу в зал и остаётся у песни или документа. Зажать и перенести на песню или слайд в сетлисте — тема им. Зажать и отпустить — изменить.") }
        }
    }
}

private const val GRID_COLS = 3
