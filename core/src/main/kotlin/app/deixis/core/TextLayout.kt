package app.deixis.core

/** Не больше этого числа строк на слайд (DESIGN.md, «Вывод на экран»). */
const val MAX_LINES_PER_SLIDE = 4

/** Безопасное поле с каждой стороны: доля меньшей стороны экрана, одинаковое по всем краям. */
const val SAFE_MARGIN = 0.055f

/** Добавка для ТВ: часть телевизоров обрезает края картинки (overscan), доля ширины и высоты. */
const val OVERSCAN_MARGIN = 0.03f

/** Межстрочный интервал слайдов. */
const val SLIDE_LINE_HEIGHT = 1.2f

/**
 * Прямоугольник, внутри которого лежит текст слайда. Поле считается от меньшей стороны, поэтому
 * отступ одинаков сверху и сбоку на любом формате: 16:9, 4:3, вертикальный экран, широкая доска.
 */
data class SafeArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

fun safeArea(width: Float, height: Float, overscan: Boolean = false): SafeArea {
    val base = minOf(width, height) * SAFE_MARGIN
    val ox = if (overscan) width * OVERSCAN_MARGIN else 0f
    val oy = if (overscan) height * OVERSCAN_MARGIN else 0f
    return SafeArea(base + ox, base + oy, width - base - ox, height - base - oy)
}

/** Наибольший размер шрифта для текста: доля меньшей стороны экрана из [TextStyle.sizePercent]. */
fun maxFontSize(width: Float, height: Float, style: TextStyle): Float = minOf(width, height) * style.sizePercent / 100f

/** Текст слайда с учётом регистра из оформления. */
fun styledText(text: String, style: TextStyle): String = if (style.caps) text.uppercase() else text

/** Подобранный размер и строки после переноса. */
data class FittedText(val size: Float, val lines: List<String>)

/**
 * Наибольший размер, при котором абзацы ([paragraphs], каждый — своя строка слайда) с переносом по словам
 * помещаются в [boxWidth]×[boxHeight]. Слово никогда не режется: если самое длинное слово не входит
 * по ширине, размер уменьшается. Строки затем выравниваются по длине, чтобы не оставалось одного слова внизу.
 */
fun fitParagraphs(
    paragraphs: List<String>,
    boxWidth: Float,
    boxHeight: Float,
    maxSize: Float,
    minSize: Float,
    lineHeight: Float = SLIDE_LINE_HEIGHT,
    measure: (String, Float) -> Float,
): FittedText {
    val paras = paragraphs.map { it.trim() }.filter { it.isNotEmpty() }
    if (paras.isEmpty()) return FittedText(maxSize, emptyList())
    // Небольшой запас: измерение и отрисовка у платформ расходятся на доли пикселя.
    val w = boxWidth * 0.985f
    fun wrapAt(size: Float, width: Float) = paras.flatMap { p -> wrapLine(p, width) { measure(it, size) } }
    fun fits(size: Float): Boolean {
        if (paras.any { p -> p.split(' ').any { word -> measure(word, size) > w } }) return false
        return wrapAt(size, w).size * size * lineHeight <= boxHeight
    }
    val size = largestFitting(minSize, maxSize, ::fits)
    return FittedText(size, balance(paras, size, w, measure))
}

/** Двоичный поиск наибольшего значения из [lo, hi], для которого [fits] верно; если ни одного — [lo]. */
internal fun largestFitting(lo: Float, hi: Float, fits: (Float) -> Boolean): Float {
    if (fits(hi)) return hi
    if (!fits(lo)) return lo
    var a = lo
    var b = hi
    repeat(24) {
        val mid = (a + b) / 2
        if (fits(mid)) a = mid else b = mid
    }
    return a
}

/**
 * Переносит каждый абзац так, чтобы строки были близки по длине: ищется самая узкая ширина,
 * при которой число строк абзаца не растёт.
 */
internal fun balance(paras: List<String>, size: Float, width: Float, measure: (String, Float) -> Float): List<String> =
    paras.flatMap { p ->
        val m: (String) -> Float = { measure(it, size) }
        val greedy = wrapLine(p, width, m)
        if (greedy.size < 2) return@flatMap greedy
        val widest = p.split(' ').maxOf(m)
        var lo = widest
        var hi = width
        repeat(16) {
            val mid = (lo + hi) / 2
            if (wrapLine(p, mid, m).size <= greedy.size) hi = mid else lo = mid
        }
        wrapLine(p, hi, m)
    }

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

data class SlideTextLayout(
    val fontSize: Float,
    val lines: List<PlacedLine>,
    val refFontSize: Float,
    val refRight: Float,
    val refBaseline: Float,
    /** Подпись с учётом регистра; null — без подписи. */
    val ref: String? = null,
)

/**
 * Полная раскладка текстового слайда: безопасные поля по формату экрана, наибольший размер, при котором
 * текст помещается в поля, перенос по словам с ровными строками, центрирование.
 * Платформа только измеряет шрифт и рисует, поэтому слайд одинаков на телефоне, ТВ и компьютере.
 * [overscan] — экран может обрезать края (телевизор).
 */
fun layoutSlideText(
    text: String,
    ref: String?,
    width: Float,
    height: Float,
    style: TextStyle,
    measure: (String, Float) -> Float,
    ascent: (Float) -> Float,
    descent: (Float) -> Float,
    overscan: Boolean = false,
    lineHeight: Float = SLIDE_LINE_HEIGHT,
): SlideTextLayout {
    val area = safeArea(width, height, overscan)
    val refSize = minOf(width, height) * 0.045f
    val refH = if (ref != null) refSize * lineHeight * 1.5f else 0f
    val boxH = area.height - refH
    val fitted = fitParagraphs(
        styledText(text, style).lines(), area.width, boxH,
        maxSize = maxFontSize(width, height, style), minSize = minOf(width, height) * 0.015f, lineHeight = lineHeight, measure = measure,
    )
    val size = fitted.size
    val lineH = size * lineHeight
    var top = area.top + (boxH - fitted.lines.size * lineH) / 2f
    val placed = fitted.lines.map { line ->
        val baseline = top + (lineH - (ascent(size) + descent(size))) / 2f + ascent(size)
        top += lineH
        PlacedLine(line, (width - measure(line, size)) / 2f, baseline)
    }
    return SlideTextLayout(size, placed, refSize, area.right, area.bottom - descent(refSize), ref?.let { styledText(it, style) })
}
