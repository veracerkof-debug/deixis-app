package app.deixis.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.deixis.core.Mode

/** Правая и средняя кнопки мыши: правая — меню слайда, средняя — действие из настроек. */
private fun Modifier.mouseButtons(onRight: () -> Unit, onMiddle: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            if (e.type == PointerEventType.Press) {
                if (e.buttons.isSecondaryPressed) onRight() else if (e.buttons.isTertiaryPressed) onMiddle()
            }
        }
    }
}

/**
 * Показ на компьютере: слева сетлист и поиск, в центре слайды сеткой по частям песни, справа «Сейчас» и «Предпросмотр»
 * с кнопками, внизу полка тем. Мышь: щелчок — в предпросмотр, двойной — в зал (или как в настройках), правая кнопка — меню,
 * средняя — как в настройках; клавиши — [Hotkeys].
 */
@Composable
fun DesktopShow(c: Controller, h: HolyricsSource, ui: Ui) {
    val p = P.current
    Row(Modifier.fillMaxSize()) {
        DeskSidebar(c, ui, Modifier.width(DESK_SIDE).fillMaxHeight())
        Box(Modifier.width(T.hair).fillMaxHeight().background(p.sep))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            DeskSlides(c, ui, Modifier.weight(1f).fillMaxWidth())
            Box(Modifier.fillMaxWidth().height(T.hair).background(p.sep))
            DeskShelf(c, ui)
        }
        Box(Modifier.width(T.hair).fillMaxHeight().background(p.sep))
        Column(Modifier.width(DESK_RIGHT).fillMaxHeight().verticalScroll(rememberScrollState())) {
            Monitors(c, ui, stacked = true)
            ControlBar(c, ui)
            DeskTools(c, ui)
        }
    }
}

private val DESK_SIDE = 280.dp
private val DESK_RIGHT = 400.dp
private val DESK_TILE = 210.dp

// ——— Слева: сетлист и библиотека ———

@Composable
private fun DeskSidebar(c: Controller, ui: Ui, modifier: Modifier) {
    val p = P.current
    val set by c.activeSet.collectAsState()
    val data by c.data.collectAsState()
    val live by c.live.collectAsState()
    val preview by c.preview.collectAsState()
    val mode by c.mode.collectAsState()
    val items = set?.items.orEmpty().mapNotNull { id -> data.items.firstOrNull { it.id == id } }
    val openId = ui.open ?: (preview ?: live)?.source?.itemId
    Column(modifier.background(p.cell.copy(alpha = 0.5f))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = T.gap, vertical = T.gapS).press(label = L.pickSet) { ui.sheet(L.setlist) { SetPickSheet(c, ui) } },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gapS),
        ) {
            Icon("list", p.l2, T.iconS)
            Txt(set?.title ?: L.setlist, T.fBody, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f), lines = 1)
            Icon("down", p.l2, T.iconS)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(items) { _, it ->
                val isLive = live?.source?.itemId == it.id && mode.inHall()
                val isPre = preview?.source?.itemId == it.id
                val selected = openId == it.id
                Row(
                    Modifier.fillMaxWidth().background(if (selected) p.tint.copy(alpha = T.devFill) else androidx.compose.ui.graphics.Color.Transparent)
                        .combinedClick(onClick = { ui.open = it.id }, onDouble = { c.toPreview(Cue(c.sourceOf(it), 0)) })
                        .padding(horizontal = T.gap, vertical = T.gapS),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gapS),
                ) {
                    Box(Modifier.width(T.markW).height(T.rowMin / 2).background(if (isLive) p.live else if (isPre) p.tint else androidx.compose.ui.graphics.Color.Transparent))
                    Icon(it.kind.icon, p.l2, T.iconS)
                    Txt(it.title, T.fBody, if (selected) p.tintText else p.ink, modifier = Modifier.weight(1f), lines = 1)
                    Txt("${c.slidesOf(it).size}", T.fLabel, p.l2)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(T.gapS), horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
            Pill(L.show, "plus", label = L.show) { ui.sheet(L.show) { ShowMenu(c, ui) } }
            Pill(L.find, "search", label = L.find) { ui.sheet(L.find) { FindSheet(c, ui) } }
        }
    }
}

// ——— В центре: слайды сеткой ———

@Composable
private fun DeskSlides(c: Controller, ui: Ui, modifier: Modifier) {
    val p = P.current
    val set by c.activeSet.collectAsState()
    val data by c.data.collectAsState()
    val live by c.live.collectAsState()
    val preview by c.preview.collectAsState()
    val mode by c.mode.collectAsState()
    val st by c.settings.collectAsState()
    val openId = ui.open ?: (preview ?: live)?.source?.itemId ?: set?.items?.firstOrNull()
    val item = data.items.firstOrNull { it.id == openId }
    if (item == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Txt(L.setEmptyHint, T.fBody, p.l2) }
        return
    }
    val slides = c.slidesOf(item)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = T.gutter, vertical = T.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gap)) {
            Txt(item.title, T.fSheet, weight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false), lines = 1)
            Txt(slidesText(slides.size), T.fSub, p.l2)
            Spacer(Modifier.weight(1f))
            Pill(L.edit, "edit", label = L.edit) { ui.sheet(item.title) { ItemMenu(c, ui, item, inSet = true) } }
        }
        LazyVerticalGrid(
            GridCells.Adaptive(DESK_TILE), Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = T.gutter, vertical = T.gapS),
            horizontalArrangement = Arrangement.spacedBy(T.gap), verticalArrangement = Arrangement.spacedBy(T.gap),
        ) {
            itemsIndexed(slides, span = { i, _ -> GridItemSpan(1) }) { i, s ->
                val isLive = live?.source?.itemId == item.id && live?.index == i && mode.inHall()
                val isPre = preview?.source?.itemId == item.id && preview?.index == i
                val label = (s as? Slide.Text)?.label
                val cue = Cue(c.sourceOf(item), i)
                Column(Modifier.semantics { contentDescription = L.slideN(i + 1) }) {
                    SlideView(
                        s, item, c.styleFor(cue),
                        Modifier.fillMaxWidth()
                            .combinedClick(
                                onClick = { tapSlide(c, ui, item, i) },
                                onDouble = { if (st.mouseDouble == "preview") c.toPreview(cue) else liveSlide(c, ui, item, i) },
                            )
                            .mouseButtons(
                                onRight = { ui.sheet(item.title + " · " + L.slideN(i + 1)) { SlideMenu(c, ui, item, i) } },
                                onMiddle = { when (st.mouseMiddle) { "black" -> Hotkeys.run(c, ui, KeyAction.BLACK); "next" -> c.step(1) } },
                            ),
                        num = i + 1, frame = if (isLive) Frame.LIVE else if (isPre) Frame.PREVIEW_TINT else Frame.NONE,
                    )
                    if (label != null) Txt(label, T.fLabel, if (isLive) p.live else if (isPre) p.tintText else p.l2, FontWeight.SemiBold, Modifier.padding(top = T.gapXs), lines = 1)
                }
            }
        }
    }
}

/** Правая кнопка по слайду: что с ним можно сделать. */
@Composable
fun SlideMenu(c: Controller, ui: Ui, item: LibItem, index: Int) {
    Group {
        Cell(L.toHall, kind = "show", chevron = null, onClick = { ui.closeAll(); liveSlide(c, ui, item, index) })
        Sep(T.gutter)
        Cell(L.toPreview, kind = "prev", chevron = null, onClick = { ui.closeAll(); c.toPreview(Cue(c.sourceOf(item), index)) })
        Sep(T.gutter)
        Cell(L.look, kind = "brush", chevron = null, onClick = { ui.closeAll(); c.toPreview(Cue(c.sourceOf(item), index)); openStyle(c, ui) })
        Sep(T.gutter)
        Cell(L.assistant, kind = "media", chevron = null, onClick = { ui.closeAll(); ui.sheet(L.assistant) { AssistantChooser(c, ui) } })
    }
}

// ——— Внизу: полка тем ———

@Composable
private fun DeskShelf(c: Controller, ui: Ui) {
    val p = P.current
    val live by c.live.collectAsState()
    val preview by c.preview.collectAsState()
    val data by c.data.collectAsState()
    val current = remember(live, preview, data) { c.currentStyle() }
    Column(Modifier.fillMaxWidth().background(p.cell.copy(alpha = 0.5f)).padding(start = T.gutter, top = T.gapS)) {
        Txt(L.themesShelf, T.fLabel, p.l2, FontWeight.SemiBold, Modifier.padding(bottom = T.gapS))
        ThemesRow(c, ui, current) { t -> c.applyTheme(t.style) }
    }
}

// ——— Справа под окнами: тема на ходу и помощник ———

@Composable
private fun DeskTools(c: Controller, ui: Ui) {
    val tune by c.tune.collectAsState()
    Column(Modifier.fillMaxWidth().padding(T.gutter), verticalArrangement = Arrangement.spacedBy(T.gapS)) {
        Group {
            Cell(L.tune, if (tune != null) L.tuneBar else L.tuneNote, kind = "brush", onClick = { Hotkeys.run(c, ui, KeyAction.TUNE) })
            Sep(T.gutter)
            Cell(L.assistant, L.assistantBarSub, kind = "media", onClick = { Hotkeys.run(c, ui, KeyAction.ASSISTANT) })
        }
        Txt(L.deskKeysHint, T.fLabel, P.current.l2)
    }
}
