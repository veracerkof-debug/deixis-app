package app.deixis.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import app.deixis.core.SceneBackground

/** Открыть правку темы на ходу: оформление правится при показе, изменения идут в зал сразу. */
fun openTune(c: Controller, ui: Ui) {
    c.tuneStart()
    ui.sheet(L.tune) { LiveTuneSheet(c, ui) }
}

/**
 * Полоса на главном, пока тема правится в зале: напоминает, что изменения идут на экраны сразу, и даёт открыть правку,
 * оставить сделанное или вернуть как было. Без полосы правка не может «забыться».
 */
@Composable
fun TuneBar(c: Controller, ui: Ui) {
    val t by c.tune.collectAsState()
    if (t == null) return
    val p = P.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = T.gutter, vertical = T.gapS).clip(RoundedCornerShape(T.rBtn))
            .background(p.live.copy(alpha = T.devFill)).padding(start = T.gap, end = T.gapS, top = T.gapS, bottom = T.gapS),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gapS),
    ) {
        Txt(L.tuneBar, T.fSub, modifier = Modifier.weight(1f), lines = 2)
        Pill(L.tuneRevert, label = L.tuneRevert) { c.tuneRevert() }
        Pill(L.tuneOpen, tint = true, label = L.tuneOpen) { ui.sheet(L.tune) { LiveTuneSheet(c, ui) } }
    }
}

/**
 * Тема на ходу: настройка и тонкая доводка прямо во время показа. Тема, живой фон (яркость, «Тише», студия), текст — всё
 * уходит в зал при каждом изменении. «Вернуть» отменяет всё сделанное, «Оставить» сохраняет на выбранное: слайд, песню, сетлист, всё.
 */
@Composable
fun LiveTuneSheet(c: Controller, ui: Ui) {
    val tune by c.tune.collectAsState()
    val st = tune ?: return
    var to by remember { mutableStateOf(c.draftScope) }
    val live by c.live.collectAsState()
    Note(L.tuneNote, Modifier.padding(bottom = T.gapS))

    GHead(L.themes)
    ThemesRow(c, ui, st) { t -> c.tuneSet(t.style) }

    // Живой фон: яркость и «Тише» ползунками, студия — глубокая доводка; обычная тема — можно добавить живой фон.
    GHead(L.liveBg)
    val sb = st.backgroundScene
    if (sb != null) {
        val quiet = sb.quiet ?: app.deixis.core.effects.SceneText.sceneFor(sb)?.quiet?.toFloat() ?: 0f
        Group {
            Column2 {
                ParamSlider(L.studioEnergy, "${Math.round(sb.energy * 100)}", sb.energy, { c.tuneSet(st.copy(backgroundScene = sb.copy(energy = it, energyTo = null, rampSec = 0f))) }, { c.tuneSet(st.copy(backgroundScene = sb.copy(energy = SceneBackground("x").energy, energyTo = null, rampSec = 0f))) })
                ParamSlider(L.micReact, if (sb.mic <= 0f) L.micOff else "${Math.round(sb.mic * 100)}", sb.mic, { c.tuneSet(st.copy(backgroundScene = sb.copy(mic = it))) }, { c.tuneSet(st.copy(backgroundScene = sb.copy(mic = 0f))) })
                ParamSlider(L.liveQuiet, "${Math.round(quiet * 100)}", quiet, { c.tuneSet(st.copy(backgroundScene = sb.copy(quiet = it))) }, { c.tuneSet(st.copy(backgroundScene = sb.copy(quiet = null))) })
            }
            Sep(T.gutter)
            Cell(L.studio, L.studioSub, kind = "brush", onClick = {
                ui.push(L.studio) { StudioSheet(c.platform, sb, { next -> c.tuneSet(c.tune.value?.copy(backgroundScene = next) ?: st) }) }
            })
            Sep(T.gutter)
            Cell(L.tuneBgOff, kind = "trash", chevron = null, onClick = { c.tuneSet(st.copy(backgroundScene = null)) })
        }
    } else Group {
        Cell(L.tuneBgOn, L.tuneBgOnSub, kind = "media", onClick = { c.tuneSet(st.copy(backgroundScene = SceneBackground("evening"), backgroundImage = null, backgroundVideo = null)) })
    }

    GHead(L.quickEdit)
    Group {
        Cell(L.textColor, chevron = null) {
            Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) { TUNE_INKS.forEach { (v, n) -> Swatch(Color(v), st.color == v, L.colorNamed(n)) { c.tuneSet(st.copy(color = v)) } } }
        }
        Sep(T.gutter)
        Cell(L.textSize, chevron = null) {
            Stepper("${st.sizePercent.toInt()}", { c.tuneSet(st.copy(sizePercent = textSizeStep(st.sizePercent, up = false))) }, { c.tuneSet(st.copy(sizePercent = textSizeStep(st.sizePercent, up = true))) })
        }
        Sep(T.gutter)
        SwitchRow(L.shadow, st.shadow) { c.tuneSet(st.copy(shadow = it)) }
        Sep(T.gutter)
        SwitchRow(L.outline, st.outline) { c.tuneSet(st.copy(outline = it)) }
    }

    GHead(L.applyTo)
    val scopes = Controller.StyleScope.entries
    val doc = if (live?.source?.kind == Kind.SONG) L.wholeSong else L.wholeDoc
    Seg(listOf(L.thisSlide, doc, L.setlist, L.byDefault), scopes.indexOf(to), Modifier.fillMaxWidth().padding(bottom = T.gutter), scroll = true) { to = scopes[it]; c.draftScope = to }
    Row(horizontalArrangement = Arrangement.spacedBy(T.gap)) {
        Btn(L.tuneRevert, Modifier.weight(1f)) { c.tuneRevert(); ui.closeAll() }
        Btn(L.tuneKeep, Modifier.weight(1f), kind = BtnKind.TINT) { c.tuneKeep(to); ui.closeAll(); ui.toast(L.saved) }
    }
}

private val TUNE_INKS: List<Pair<Long, String>> get() = listOf(0xFFFFFFFF to L.cWhite, 0xFFF4EFE6 to L.cCream, 0xFFFFD43B to L.cGold, 0xFF2B2620 to L.cDark)

@Composable
private fun Column2(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = T.gutter, vertical = T.gapS), content = content)
