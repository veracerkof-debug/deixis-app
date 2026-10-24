package app.deixis.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Что показывает обратный отсчёт после нуля. */
@Serializable
enum class AfterZero {
    /** Стоит на 00:00. */
    HOLD,
    /** Считает дальше вверх красным: «+0:42». */
    OVERTIME,
    /** Пустой экран: отсчёт исчезает. */
    HIDE,
    /** Пульт показывает следующий элемент сетлиста; экран до этого стоит на 00:00. */
    NEXT,
}

/** Как записаны часы, минуты, секунды. */
@Serializable
enum class CountdownFormat {
    /** Часы — только когда больше часа: «4:59», «1:04:59». */
    AUTO,
    /** Всегда с часами: «0:04:59». */
    HMS,
    /** Только минуты и секунды: «64:59». */
    MS,
    /** Только секунды: «299». */
    S,
}

@Serializable
enum class LabelPosition { ABOVE, BELOW }

/**
 * Обратный отсчёт. По сети передаётся, сколько осталось на миг отправки ([remainingMs]); получатель
 * привязывает это к своим часам ([endsAt]). Так экраны идут вместе, даже если часы устройств сбиты:
 * расходятся они только на задержку сети. На паузе [running] = false, и [remainingMs] не убывает.
 */
@Serializable @SerialName("countdown")
data class CountdownContent(
    /** Один и тот же отсчёт на всех экранах и в «Сцене»: пауза и ±1 мин меняют его, а не создают новый. */
    val id: String,
    val remainingMs: Long,
    val running: Boolean = true,
    /** Мгновение нуля по часам того, кто держит отсчёт; 0 — ещё не привязан. По сети не нужно. */
    val endsAt: Long = 0,
    val label: String? = null,
    val labelPosition: LabelPosition = LabelPosition.ABOVE,
    val format: CountdownFormat = CountdownFormat.AUTO,
    /** Доли секунды: 0 — нет, 1 — десятые, 2 — сотые, 3 — тысячные. Считаются от настоящих часов. */
    val decimals: Int = 0,
    val afterZero: AfterZero = AfterZero.HOLD,
    /** «sans» — шрифт слайдов, «serif» — шрифт знака. */
    val font: String = "sans",
    /** Фон, цвет цифр, размер (доля меньшей стороны), положение, тень, обводка — как у слайдов. */
    val style: TextStyle = TextStyle(sizePercent = 34f),
    val overtimeColor: Long = 0xFFE5484D,
) : Content {
    /** Привязать к своим часам при получении. */
    fun anchored(now: Long): CountdownContent = if (running) copy(endsAt = now + remainingMs) else copy(endsAt = 0)

    /** Сколько осталось сейчас; меньше нуля — сколько прошло после нуля. */
    fun remainingAt(now: Long): Long = if (running && endsAt != 0L) endsAt - now else remainingMs

    /** Для отправки дальше (пересылка, зрители): сколько осталось на этот миг. */
    fun forWire(now: Long): CountdownContent = copy(remainingMs = remainingAt(now), endsAt = 0)
}

/** Что видно на экране в данный миг: строка времени, цвет (после нуля — свой) или ничего. */
data class CountdownFace(val text: String, val overtime: Boolean, val hidden: Boolean)

fun countdownFace(c: CountdownContent, now: Long): CountdownFace {
    val left = c.remainingAt(now)
    if (left > 0) return CountdownFace(formatCountdown(left, c.format, c.decimals, roundUp = true), false, false)
    return when (c.afterZero) {
        AfterZero.OVERTIME -> CountdownFace("+" + formatCountdown(-left, c.format, c.decimals, roundUp = false), left < 0, false)
        AfterZero.HIDE -> CountdownFace("", false, true)
        else -> CountdownFace(formatCountdown(0, c.format, c.decimals, roundUp = false), false, false)
    }
}

/**
 * Время строкой. При обратном счёте секунды округляются вверх: «0:01» видно до самого нуля, а «0:00» —
 * ровно в ноль, как на табло. Доли секунды — честные, от часов, без подмены.
 */
fun formatCountdown(ms: Long, format: CountdownFormat, decimals: Int = 0, roundUp: Boolean = true): String {
    val d = decimals.coerceIn(0, 3)
    val unit = when (d) { 0 -> 1000L; 1 -> 100L; 2 -> 10L; else -> 1L }
    val v = abs(ms)
    val ticks = if (roundUp) (v + unit - 1) / unit else v / unit
    val totalMs = ticks * unit
    val totalS = totalMs / 1000
    val frac = if (d == 0) "" else "." + (totalMs % 1000 / unit).toString().padStart(d, '0')
    val h = totalS / 3600
    val m = totalS / 60 % 60
    val s = totalS % 60
    val body = when (format) {
        CountdownFormat.S -> "$totalS"
        CountdownFormat.MS -> "${totalS / 60}:${s.pad()}"
        CountdownFormat.HMS -> "$h:${m.pad()}:${s.pad()}"
        CountdownFormat.AUTO -> if (h > 0) "$h:${m.pad()}:${s.pad()}" else "$m:${s.pad()}"
    }
    return body + frac
}

private fun Long.pad() = toString().padStart(2, '0')

/** Как часто перерисовывать отсчёт: с долями секунды — каждый кадр, без них — 4 раза в секунду. */
fun countdownFrameMs(c: CountdownContent): Long = if (c.decimals > 0 && c.running) 16L else 250L

/**
 * Раскладка цифр: каждая цифра — в ячейке одной ширины, поэтому строка не дрожит, когда меняются цифры.
 * Возвращает левые края символов и общий размер. Подпись — сверху или снизу, меньшим кеглем.
 */
data class CountdownLayout(val size: Float, val cells: List<Pair<String, Float>>, val baseline: Float, val labelSize: Float, val labelBaseline: Float, val labelX: Float)

fun layoutCountdown(
    c: CountdownContent,
    text: String,
    width: Float,
    height: Float,
    measure: (String, Float) -> Float,
    ascent: (Float) -> Float,
    descent: (Float) -> Float,
    overscan: Boolean = false,
): CountdownLayout {
    val area = safeArea(width, height, overscan, c.style.margins)
    val short = minOf(width, height)
    val label = c.label?.takeIf { it.isNotBlank() }?.let { styledText(it, c.style) }
    // Ширина ячейки — по самой широкой цифре; двоеточие, точка и «+» — своей шириной.
    fun cellW(ch: Char, size: Float) = if (ch.isDigit()) (0..9).maxOf { measure(it.toString(), size) } else measure(ch.toString(), size)
    fun textW(size: Float) = text.sumOf { cellW(it, size).toDouble() }.toFloat()
    val labelRatio = 0.28f
    fun totalH(size: Float) = (ascent(size) + descent(size)) + if (label != null) (ascent(size * labelRatio) + descent(size * labelRatio)) * 1.4f else 0f
    var size = short * c.style.sizePercent / 100f
    // Уменьшить, пока цифры и подпись не уместятся в поля (узкий экран, длинный формат).
    val one = textW(1f).coerceAtLeast(0.001f)
    size = minOf(size, area.width * 0.98f / one)
    while (size > 1f && totalH(size) > area.height) size *= 0.95f
    var labelSize = size * labelRatio
    if (label != null) labelSize = minOf(labelSize, area.width * 0.98f / measure(label, 1f).coerceAtLeast(0.001f))
    val blockH = totalH(size)
    val top = alignY(area.top, area.height, blockH, c.style.valign)
    val labelH = if (label != null) (ascent(labelSize) + descent(labelSize)) * 1.4f else 0f
    val digitsTop = if (label != null && c.labelPosition == LabelPosition.ABOVE) top + labelH else top
    val baseline = digitsTop + ascent(size)
    val w = textW(size)
    var x = alignX(area, w, c.style.align)
    val cells = text.map { ch -> val cw = cellW(ch, size); val left = x + (cw - measure(ch.toString(), size)) / 2f; x += cw; ch.toString() to left }
    val labelBaseline = if (c.labelPosition == LabelPosition.ABOVE) top + ascent(labelSize) else digitsTop + ascent(size) + descent(size) + labelH * 0.2f + ascent(labelSize)
    val labelX = if (label != null) alignX(area, measure(label, labelSize), c.style.align) else 0f
    return CountdownLayout(size, cells, baseline, labelSize, labelBaseline, labelX)
}
