package app.deixis.phone

import app.deixis.core.Overlays
import app.deixis.core.OverlaySample
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.deixis.core.ClockOverlay
import app.deixis.core.Corner
import app.deixis.core.LogoOverlay
import app.deixis.core.LowerThird
import app.deixis.core.Ticker
import kotlinx.coroutines.launch

private val CORNERS = listOf(Corner.TOP_LEFT, Corner.TOP_RIGHT, Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT)
private val CORNER_NAMES: List<String> get() = listOf(L.cornerTL, L.cornerTR, L.cornerBL, L.cornerBR)
private val SPEEDS = listOf(6f, 10f, 16f)

/**
 * Поверх показа: плашка с именем, бегущая строка, часы, логотип. Каждый элемент включается отдельно и сразу
 * идёт на все экраны; смена слайдов его не трогает. Последние имена и строки остаются заготовками.
 */
@Composable
fun OverlaySheet(c: Controller, ui: Ui) {
    val o by c.overlays.collectAsState()
    val drafts by c.overlayDrafts.collectAsState()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(o.lowerThird?.title ?: drafts.lowerThirds.firstOrNull()?.title.orEmpty()) }
    var subtitle by remember { mutableStateOf(o.lowerThird?.subtitle ?: drafts.lowerThirds.firstOrNull()?.subtitle.orEmpty()) }
    var ticker by remember { mutableStateOf(o.ticker?.text ?: drafts.tickers.firstOrNull().orEmpty()) }
    val logoPick = rememberFilePicker { uris ->
        uris.firstOrNull()?.let {
            scope.launch {
                runCatching { c.backgroundFrom(it) }
                    .onSuccess { ref -> c.setOverlays(c.overlays.value.copy(logo = (c.overlays.value.logo ?: drafts.logo ?: LogoOverlay(ref)).copy(file = ref))) }
                    .onFailure { e -> ui.toast(e.message ?: L.pictureNotOpened) }
            }
        }
    }
    val samples by produceState(emptyList<OverlaySample>()) { value = runCatching { c.overlaySamples() }.getOrDefault(emptyList()) }
    Column(verticalArrangement = Arrangement.spacedBy(T.gap)) {
        GHead(L.examples)
        Note(L.examplesNote)
        Group {
            samples.forEachIndexed { i, smp ->
                if (i > 0) Sep(T.gutter)
                val live = o == smp.overlays
                Cell(smp.name, sampleParts(smp.overlays), chevron = null, open = live, onClick = {
                    smp.overlays.lowerThird?.let { title = it.title; subtitle = it.subtitle.orEmpty() }
                    smp.overlays.ticker?.let { ticker = it.text }
                    c.setOverlays(smp.overlays)
                })
            }
        }

        GHead(L.lowerThird)
        Field(title, L.nameHint) { title = it }
        Field(subtitle, L.subtitleHint) { subtitle = it }
        Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) {
            if (o.lowerThird == null) Pill(L.show, tint = true) {
                if (title.isBlank()) ui.toast(L.needName) else c.setOverlays(o.copy(lowerThird = LowerThird(title.trim(), subtitle.trim().ifBlank { null })))
            } else {
                Pill(L.update, tint = true) { if (title.isNotBlank()) c.setOverlays(o.copy(lowerThird = o.lowerThird!!.copy(title = title.trim(), subtitle = subtitle.trim().ifBlank { null }))) }
                Pill(L.remove) { c.setOverlays(o.copy(lowerThird = null)) }
            }
        }
        if (drafts.lowerThirds.isNotEmpty()) Group {
            drafts.lowerThirds.forEachIndexed { i, lt ->
                if (i > 0) Sep(T.gutter)
                Cell(lt.title, lt.subtitle, chevron = null, onLong = { c.forgetLowerThird(lt) }, onClick = {
                    title = lt.title; subtitle = lt.subtitle.orEmpty()
                    c.setOverlays(o.copy(lowerThird = lt))
                })
            }
        }
        if (drafts.lowerThirds.isNotEmpty()) Note(L.draftsNote)

        GHead(L.ticker)
        Field(ticker, L.tickerHint, single = false) { ticker = it }
        Seg(listOf(L.slow, L.normal, L.fast), SPEEDS.indexOf(o.ticker?.speedPercent ?: 10f).coerceAtLeast(0), Modifier.fillMaxWidth()) { i ->
            o.ticker?.let { c.setOverlays(o.copy(ticker = it.copy(speedPercent = SPEEDS[i]))) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) {
            if (o.ticker == null) Pill(L.launch, tint = true) {
                if (ticker.isBlank()) ui.toast(L.needText) else c.setOverlays(o.copy(ticker = Ticker(ticker.trim())))
            } else {
                Pill(L.update, tint = true) { if (ticker.isNotBlank()) c.setOverlays(o.copy(ticker = o.ticker!!.copy(text = ticker.trim()))) }
                Pill(L.remove) { c.setOverlays(o.copy(ticker = null)) }
            }
        }
        if (drafts.tickers.isNotEmpty()) Group {
            drafts.tickers.forEachIndexed { i, t ->
                if (i > 0) Sep(T.gutter)
                Cell(t, chevron = null, onLong = { c.forgetTicker(t) }, onClick = {
                    ticker = t
                    c.setOverlays(o.copy(ticker = (o.ticker ?: Ticker(t)).copy(text = t)))
                })
            }
        }

        GHead(L.clock)
        Group {
            SwitchRow(L.clockCorner, o.clock != null) { on -> c.setOverlays(o.copy(clock = if (on) ClockOverlay() else null)) }
            o.clock?.let { clock ->
                Sep(T.gutter)
                SwitchRow(L.withSeconds, clock.seconds) { c.setOverlays(o.copy(clock = clock.copy(seconds = it))) }
            }
        }
        o.clock?.let { clock ->
            Seg(CORNER_NAMES, CORNERS.indexOf(clock.corner), Modifier.fillMaxWidth(), scroll = true) { i ->
                c.setOverlays(o.copy(clock = clock.copy(corner = CORNERS[i])))
            }
        }

        GHead(L.logo)
        val logo = o.logo
        Group {
            SwitchRow(L.logoCorner, logo != null, sub = if (logo == null && drafts.logo == null) L.logoCornerSub else (logo ?: drafts.logo)?.file?.name) { on ->
                when {
                    !on -> c.setOverlays(o.copy(logo = null))
                    drafts.logo != null -> c.setOverlays(o.copy(logo = drafts.logo))
                    else -> logoPick.launch(arrayOf("image/*"))
                }
            }
            Sep(T.gutter)
            Cell(L.pickPicture, onClick = { logoPick.launch(arrayOf("image/*")) })
        }
        logo?.let { l ->
            Seg(CORNER_NAMES, CORNERS.indexOf(l.corner), Modifier.fillMaxWidth(), scroll = true) { i ->
                c.setOverlays(o.copy(logo = l.copy(corner = CORNERS[i])))
            }
            Group {
                Cell(L.size, chevron = null) {
                    Stepper("${l.sizePercent.toInt()}", { c.setOverlays(o.copy(logo = l.copy(sizePercent = (l.sizePercent - 2).coerceAtLeast(4f)))) },
                        { c.setOverlays(o.copy(logo = l.copy(sizePercent = (l.sizePercent + 2).coerceAtMost(30f)))) })
                }
            }
        }
        if (!o.isEmpty) Pill(L.removeAll) { c.setOverlays(Overlays()) }
        Note(L.overlayNote)
    }
}

/** Из чего состоит пример: «плашка: Анна Иванова · часы». */
private fun sampleParts(o: Overlays): String = listOfNotNull(
    o.lowerThird?.let { L.lowerThirdColon(it.title) },
    o.ticker?.let { L.tickerLower },
    o.clock?.let { L.clockLower },
    o.logo?.let { L.logoLower },
).joinToString(" · ")
