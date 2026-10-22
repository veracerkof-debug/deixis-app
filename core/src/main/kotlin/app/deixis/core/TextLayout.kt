package app.deixis.core

/** Не больше этого числа строк на слайд (DESIGN.md, «Вывод на экран»). */
const val MAX_LINES_PER_SLIDE = 4

/** Безопасное поле с каждой стороны экрана, доля размера. */
const val SAFE_MARGIN = 0.05f

/**
 * Разбивает текст на слайды: пустая строка начинает новый слайд,
 * длинный блок делится по [maxLines] строк.
 */
fun splitIntoSlides(text: String, maxLines: Int = MAX_LINES_PER_SLIDE): List<String> {
    require(maxLines > 0)
    val slides = mutableListOf<String>()
    for (block in text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))) {
        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        lines.chunked(maxLines).forEach { slides += it.joinToString("\n") }
    }
    return slides
}

/**
 * Подбирает размер шрифта, при котором строки помещаются в прямоугольник.
 * [measure] возвращает ширину строки при заданном размере; измерение у каждой платформы своё,
 * а правило подбора общее, поэтому слайд выглядит одинаково на телефоне, ТВ и компьютере.
 */
fun fitFontSize(
    lines: List<String>,
    boxWidth: Float,
    boxHeight: Float,
    maxSize: Float,
    minSize: Float,
    lineHeight: Float = 1.2f,
    measure: (String, Float) -> Float,
): Float {
    require(minSize > 0 && maxSize >= minSize)
    if (lines.isEmpty()) return maxSize
    fun fits(size: Float): Boolean =
        lines.size * size * lineHeight <= boxHeight && lines.all { measure(it, size) <= boxWidth }
    if (fits(maxSize)) return maxSize
    var lo = minSize
    var hi = maxSize
    repeat(20) {
        val mid = (lo + hi) / 2
        if (fits(mid)) lo = mid else hi = mid
    }
    return lo
}

/** Переносит строку по словам, если она не помещается в [maxWidth] при уже подобранном размере. */
fun wrapLine(line: String, maxWidth: Float, measure: (String) -> Float): List<String> {
    if (measure(line) <= maxWidth) return listOf(line)
    val out = mutableListOf<String>()
    var current = ""
    for (word in line.split(' ').filter { it.isNotEmpty() }) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (current.isNotEmpty() && measure(candidate) > maxWidth) {
            out += current
            current = word
        } else {
            current = candidate
        }
    }
    if (current.isNotEmpty()) out += current
    return out
}

/** Строка слайда с готовыми координатами: x — левый край, baseline — базовая линия. */
data class PlacedLine(val text: String, val x: Float, val baseline: Float)

data class SlideTextLayout(val fontSize: Float, val lines: List<PlacedLine>, val refFontSize: Float, val refRight: Float, val refBaseline: Float)

/**
 * Полная раскладка текстового слайда: безопасные поля, подбор размера, перенос, центрирование.
 * Платформа только измеряет шрифт и рисует, поэтому слайд одинаков на телефоне, ТВ и компьютере.
 */
fun layoutSlideText(
    text: String,
    hasRef: Boolean,
    width: Float,
    height: Float,
    sizePercent: Float,
    measure: (String, Float) -> Float,
    ascent: (Float) -> Float,
    descent: (Float) -> Float,
    lineHeight: Float = 1.2f,
): SlideTextLayout {
    val marginX = width * SAFE_MARGIN
    val marginY = height * SAFE_MARGIN
    val refH = if (hasRef) height * 0.06f else 0f
    val boxW = width - 2 * marginX
    val boxH = height - 2 * marginY - refH
    val maxSize = height * sizePercent / 100f
    val minSize = maxSize * 0.45f
    var lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    var size = fitFontSize(lines, boxW, boxH, maxSize, minSize, lineHeight, measure)
    if (lines.any { measure(it, size) > boxW }) {
        lines = lines.flatMap { line -> wrapLine(line, boxW) { measure(it, size) } }
        size = fitFontSize(lines, boxW, boxH, size, minSize * 0.6f, lineHeight, measure)
    }
    val lineH = size * lineHeight
    var top = marginY + (boxH - lines.size * lineH) / 2f
    val placed = lines.map { line ->
        val baseline = top + (lineH - (ascent(size) + descent(size))) / 2f + ascent(size)
        top += lineH
        PlacedLine(line, (width - measure(line, size)) / 2f, baseline)
    }
    return SlideTextLayout(size, placed, height * 0.032f, width - marginX, height - marginY)
}
