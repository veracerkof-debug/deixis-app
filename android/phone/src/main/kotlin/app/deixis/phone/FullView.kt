package app.deixis.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import app.deixis.core.Mode

/** Цвета полноэкранного просмотра: он всегда на чёрном, как зал. */
private object F {
    val bg = Color.Black
    val ink = Color.White
    val button = Color.White.copy(alpha = 0.14f)
    val go = Color(0xFFBC9361)
    val live = Color(0xFFB96E65)
    const val dim = 0.7f
}

@Composable
fun FullHost(c: Controller, ui: Ui) {
    val spec = ui.full
    var shown by remember { mutableStateOf<FullSpec?>(null) }
    if (spec != null) shown = spec
    AnimatedVisibility(
        spec != null,
        enter = fadeIn(tween(T.slow, easing = T.eOut)) + slideInVertically(tween(T.slow, easing = T.eOut)) { it / 30 } + scaleIn(tween(T.slow, easing = T.eOut), 0.98f),
        exit = fadeOut(tween(T.mid, easing = T.eIn)),
    ) {
        shown?.let { if (it is FullSpec.Video) VideoPreview(c, ui, it.cue) else FullView(c, ui, it) }
    }
}

@Composable
private fun FullView(c: Controller, ui: Ui, spec: FullSpec) {
    val live by c.live.collectAsState()
    val preview by c.preview.collectAsState()
    val mode by c.mode.collectAsState()
    val set by c.activeSet.collectAsState()
    @Suppress("UNUSED_VARIABLE") val draft by c.draft.collectAsState()
    @Suppress("UNUSED_VARIABLE") val data by c.data.collectAsState()
    var local by remember(spec) { mutableStateOf((spec as? FullSpec.Local)?.cue) }
    val cue = when (spec) {
        FullSpec.Live -> live
        FullSpec.Preview -> preview
        is FullSpec.Local -> local
        is FullSpec.Video -> spec.cue
    }
    // Предпросмотр ушёл в зал — окно закрывается.
    LaunchedEffect(spec, cue) { if (spec == FullSpec.Preview && cue == null) ui.full = null }
    val close = { ui.full = null }
    val move = { d: Int ->
        when (spec) {
            FullSpec.Live -> c.step(d)
            FullSpec.Preview -> c.previewStep(d)
            is FullSpec.Local -> local?.let { l ->
                val n = l.index + d
                if (n in l.source.slides.indices) local = l.copy(index = n)
            }
            is FullSpec.Video -> {}
        }
        Unit
    }
    val go = {
        when (spec) {
            FullSpec.Preview -> c.previewToLive()
            is FullSpec.Local -> local?.let { l ->
                val item = c.item(l.source.itemId)
                val big = item?.let { c.largeFor(it) }
                if (big != null) ui.sheet("Файл больше 1 ГБ") { LargeSheet(c, ui, big) }
                else { c.goLive(l); ui.toast("В зале") }
            }
            FullSpec.Live, is FullSpec.Video -> {}
        }
        ui.full = null
    }
    val style = when (spec) {
        FullSpec.Preview -> c.styleFor(cue, withDraft = true)
        else -> c.styleFor(cue)
    }

    Column(
        Modifier.fillMaxSize().background(F.bg).clickable(remember { MutableInteractionSource() }, null) {}.safeDrawingPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = T.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBtn("down", "Закрыть", F.ink) { close() }
            Txt(cue?.source?.title ?: "Сейчас", T.fBody, F.ink, FontWeight.SemiBold, Modifier.weight(1f), lines = 1)
            if (spec == FullSpec.Live) Box(Modifier.clip(RoundedCornerShape(T.rPill)).background(F.live).padding(horizontal = 8.dp, vertical = T.gapXs)) {
                Txt("В зале", T.fTag, F.ink, FontWeight.SemiBold)
            }
            cue?.let { Txt("${it.index + 1} / ${it.source.slides.size}", T.fSub, F.ink.copy(alpha = F.dim)) }
        }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .swipes(onLeft = { move(1) }, onRight = { move(-1) }, onDown = close, onUp = { if (spec != FullSpec.Live) go() }),
            contentAlignment = Alignment.Center,
        ) {
            val w = minOf(maxWidth, maxHeight * 16f / 9f)
            CueView(c, cue, style, Modifier.width(w), mode = if (spec == FullSpec.Live) mode else Mode.NORMAL, round = 4.dp)
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = T.gap, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (spec == FullSpec.Live) {
                FullBtn("Назад", "prev", Modifier.weight(1f)) { move(-1) }
                FullBtn("Дальше", "next", Modifier.weight(1f)) { move(1) }
            } else {
                FullBtn("В зал", "up", Modifier.weight(1f), F.go) { go() }
                FullBtn("Вид", "brush", Modifier.weight(1f)) {
                    if (spec is FullSpec.Local) local?.let { c.toPreview(it) }
                    ui.full = null
                    ui.tab = AppTab.SHOW
                    openStyle(c, ui)
                }
                val id = cue?.source?.itemId
                if (id != null && set?.items?.contains(id) != true) FullBtn("В сетлист", "plus", Modifier.weight(1f)) {
                    c.addToSet(id); ui.toast("Добавлено в сетлист")
                }
            }
        }
    }
}

@Composable
private fun FullBtn(text: String, icon: String, modifier: Modifier, bg: Color = F.button, onClick: () -> Unit) {
    Row(
        modifier.height(T.btn).clip(RoundedCornerShape(T.rBtn)).background(bg).press(scaleTo = 0.97f, label = text, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, F.ink, T.iconBar)
        Box(Modifier.width(T.gapS))
        Txt(text, T.fBody, F.ink, FontWeight.SemiBold, lines = 1)
    }
}
