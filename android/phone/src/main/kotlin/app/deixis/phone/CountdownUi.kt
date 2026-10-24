package app.deixis.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import app.deixis.core.AfterZero
import app.deixis.core.CountdownFormat
import app.deixis.core.LabelPosition
import app.deixis.core.VAlign
import app.deixis.core.formatCountdown
import kotlinx.coroutines.delay

private val PRESETS = listOf(30, 15, 10, 5)
private val INKS = listOf(0xFFFFFFFF to "Белый", 0xFFFFD43B to "Золотой", 0xFFF4EFE6 to "Кремовый", 0xFFE5484D to "Красный")

/**
 * Обратный отсчёт: заготовки 30, 15, 10, 5 минут или своё время, либо «до 18:30»; подпись, формат, доли секунды,
 * что после нуля, шрифт, размер, цвет, положение. Фон — от темы оформления. Сохраняется в библиотеку и сетлист.
 */
@Composable
fun CountdownSheet(c: Controller, ui: Ui, edit: LibItem? = null) {
    var spec by remember { mutableStateOf(edit?.countdown ?: CountdownSpec()) }
    var minutes by remember { mutableStateOf(((spec.durationMs / 60_000).toInt()).coerceAtLeast(1)) }
    var untilText by remember { mutableStateOf(spec.until ?: "18:30") }
    val untilMode = spec.until != null
    val preview = Slide.Countdown(spec)
    SlideView(preview, null, c.styleFor(null), Modifier.padding(bottom = T.gap))
    Column(verticalArrangement = Arrangement.spacedBy(T.gap)) {
        Seg(listOf("Длительность", "До времени"), if (untilMode) 1 else 0, Modifier.fillMaxWidth()) { i ->
            spec = if (i == 1) spec.copy(until = untilText) else spec.copy(until = null)
        }
        if (!untilMode) {
            Seg(PRESETS.map { "$it мин" } + "Своё", PRESETS.indexOf(minutes).let { if (it < 0) PRESETS.size else it }, Modifier.fillMaxWidth()) { i ->
                if (i < PRESETS.size) { minutes = PRESETS[i]; spec = spec.copy(durationMs = minutes * 60_000L) }
            }
            Group {
                Cell("Минут", chevron = null) {
                    Stepper("$minutes", { minutes = (minutes - 1).coerceAtLeast(1); spec = spec.copy(durationMs = minutes * 60_000L) },
                        { minutes = (minutes + 1).coerceAtMost(600); spec = spec.copy(durationMs = minutes * 60_000L) })
                }
            }
        } else {
            Field(untilText, "18:30", keyboard = KeyboardType.Number) { v ->
                untilText = v.filter { it.isDigit() || it == ':' }.take(5)
                if (runCatching { java.time.LocalTime.parse(untilText) }.isSuccess) spec = spec.copy(until = untilText)
            }
            Note("Отсчёт до ближайшего такого времени: сегодня, а если оно прошло, — завтра.")
        }
        Field(spec.label.orEmpty(), "Подпись: «До начала», «Перерыв»") { spec = spec.copy(label = it.ifBlank { null }) }
        Seg(listOf("Подпись сверху", "Снизу"), if (spec.labelPosition == LabelPosition.BELOW) 1 else 0, Modifier.fillMaxWidth()) { i ->
            spec = spec.copy(labelPosition = if (i == 1) LabelPosition.BELOW else LabelPosition.ABOVE)
        }
        GHead("После нуля")
        Seg(listOf("00:00", "Дальше красным", "Убрать", "Следующий"), AfterZero.entries.indexOf(spec.afterZero), Modifier.fillMaxWidth()) { i ->
            spec = spec.copy(afterZero = AfterZero.entries[i])
        }
        if (spec.afterZero == AfterZero.NEXT) Note("В ноль в зал сам уйдёт следующий элемент сетлиста.")
        GHead("Вид")
        Seg(listOf("Авто", "Ч:ММ:СС", "ММ:СС", "Секунды"), CountdownFormat.entries.indexOf(spec.format), Modifier.fillMaxWidth()) { i ->
            spec = spec.copy(format = CountdownFormat.entries[i])
        }
        Seg(listOf("Без долей", "0,1 с", "0,01 с", "0,001 с"), spec.decimals.coerceIn(0, 3), Modifier.fillMaxWidth()) { i -> spec = spec.copy(decimals = i) }
        Seg(listOf("Обычный шрифт", "С засечками"), if (spec.font == "serif") 1 else 0, Modifier.fillMaxWidth()) { i -> spec = spec.copy(font = if (i == 1) "serif" else "sans") }
        Seg(listOf("Сверху", "По центру", "Снизу"), VAlign.entries.indexOf(spec.valign), Modifier.fillMaxWidth()) { i -> spec = spec.copy(valign = VAlign.entries[i]) }
        Group {
            Cell("Размер", chevron = null) {
                Stepper("${spec.sizePercent.toInt()}", { spec = spec.copy(sizePercent = (spec.sizePercent - 4).coerceAtLeast(8f)) }, { spec = spec.copy(sizePercent = (spec.sizePercent + 4).coerceAtMost(60f)) })
            }
            Sep(T.gutter)
            Cell("Цвет цифр", chevron = null) {
                Row(horizontalArrangement = Arrangement.spacedBy(T.gapXs)) {
                    Swatch(Color(c.styleFor(null).color), spec.color == null, "Цвет темы") { spec = spec.copy(color = null) }
                    INKS.forEach { (v, n) -> Swatch(Color(v), spec.color == v, n) { spec = spec.copy(color = v) } }
                }
            }
        }
        Note("Фон, тень и обводка — от темы оформления. Время на всех экранах идёт вместе: каждый экран считает от своих часов.")
        val go = { live: Boolean ->
            val item = (edit ?: LibItem(Store.newId(), Kind.COUNTDOWN, spec.title)).copy(title = listOfNotNull(spec.label, spec.title).joinToString(" · "), countdown = spec)
            c.saveItem(item)
            if (edit == null) c.addToSet(item.id)
            ui.closeAll()
            val cue = Cue(c.sourceOf(item), 0)
            if (live) { c.goLive(cue); ui.toast("Отсчёт в зале") } else c.toPreview(cue)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(T.gap)) {
            Btn("В предпросмотр", Modifier.weight(1f)) { go(false) }
            Btn("В зал", Modifier.weight(1f), kind = BtnKind.TINT) { go(true) }
        }
    }
}

/** Сколько осталось у идущего отсчёта — для пульта, несколько раз в секунду. */
@Composable
fun rememberTimerLeft(c: Controller): Long? {
    val run by c.timer.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(run) { while (run != null) { now = System.currentTimeMillis(); delay(200) } }
    return run?.left(now)
}

/** Строка отсчёта на пульте: «4:59», после нуля — «+0:12» или «0:00». */
fun timerText(run: Controller.CountdownRun?, left: Long?): String {
    if (run == null || left == null) return ""
    return if (left > 0) formatCountdown(left, run.spec.format)
    else if (run.spec.afterZero == AfterZero.OVERTIME) "+" + formatCountdown(-left, run.spec.format, roundUp = false) else formatCountdown(0, run.spec.format)
}
