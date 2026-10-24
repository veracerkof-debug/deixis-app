package app.deixis.core

/** Не больше этого числа строк на слайд (DESIGN.md, «Вывод на экран»). */
const val MAX_LINES_PER_SLIDE = 4

/** Безопасное поле с каждой стороны: доля меньшей стороны экрана, одинаковое по всем краям. */
const val SAFE_MARGIN = 0.055f

/** Добавка для ТВ: часть телевизоров обрезает края картинки (overscan), доля ширины и высоты. */
const val OVERSCAN_MARGIN = 0.03f

/** Два перевода в колонки — только если экран шире этого (ширина к высоте); иначе друг под другом. */
const val SIDE_BY_SIDE_MIN_ASPECT = 1.2f

/** Кегль подписи: не больше [size] и такой, чтобы строка уместилась в [width] (узкий экран). */
fun fitWidth(text: String?, size: Float, width: Float, measure: (String, Float) -> Float): Float {
    if (text.isNullOrEmpty() || size <= 0f) return size
    val w = measure(text, size)
    return if (w <= width) size else size * width / w * 0.98f
}

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

fun safeArea(width: Float, height: Float, overscan: Boolean = false, margins: Margins? = null): SafeArea {
    val base = minOf(width, height) * SAFE_MARGIN
    val ox = if (overscan) width * OVERSCAN_MARGIN else 0f
    val oy = if (overscan) height * OVERSCAN_MARGIN else 0f
    // Поля оформления (например, темы Holyrics) соблюдаются, но не уже безопасных.
    fun side(safe: Float, percent: Float?, of: Float) = maxOf(safe, (percent ?: 0f) * of / 100f)
    val l = side(base + ox, margins?.left, width)
    val t = side(base + oy, margins?.top, height)
    val r = side(base + ox, margins?.right, width)
    val b = side(base + oy, margins?.bottom, height)
    // Слишком большие поля не съедают экран целиком.
    return if (l + r > width * 0.8f || t + b > height * 0.8f) SafeArea(base + ox, base + oy, width - base - ox, height - base - oy)
    else SafeArea(l, t, width - r, height - b)
}

/** Левый край строки шириной [w] внутри [area] по выравниванию. */
fun alignX(area: SafeArea, w: Float, align: HAlign): Float = when (align) {
    HAlign.LEFT -> area.left
    HAlign.RIGHT -> area.right - w
    HAlign.CENTER -> area.left + (area.width - w) / 2f
}

/** Верх блока высотой [h] внутри полосы [top]..[top]+[space] по выравниванию. */
fun alignY(top: Float, space: Float, h: Float, valign: VAlign): Float = when (valign) {
    VAlign.TOP -> top
    VAlign.BOTTOM -> top + space - h
    VAlign.MIDDLE -> top + (space - h) / 2f
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
    /** Прямоугольник текста — для плашки под ним. */
    val block: SafeArea? = null,
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
    lineHeight: Float = style.lineHeight,
): SlideTextLayout {
    val area = safeArea(width, height, overscan, style.margins)
    val refSize = fitWidth(ref?.let { styledText(it, style) }, minOf(width, height) * 0.045f, area.width, measure)
    val refH = if (ref != null) refSize * lineHeight * 1.5f else 0f
    val boxH = area.height - refH
    val fitted = fitParagraphs(
        styledText(text, style).lines(), area.width, boxH,
        maxSize = maxFontSize(width, height, style), minSize = minOf(width, height) * 0.015f, lineHeight = lineHeight, measure = measure,
    )
    val size = fitted.size
    val lineH = size * lineHeight
    val blockTop = alignY(area.top, boxH, fitted.lines.size * lineH, style.valign)
    var top = blockTop
    val placed = fitted.lines.map { line ->
        val baseline = top + (lineH - (ascent(size) + descent(size))) / 2f + ascent(size)
        top += lineH
        PlacedLine(line, alignX(area, measure(line, size), style.align), baseline)
    }
    val widest = fitted.lines.maxOfOrNull { measure(it, size) } ?: 0f
    val left = alignX(area, widest, style.align)
    // Плашка чуть шире текста, но в пределах экрана.
    val pad = size * 0.35f
    val block = SafeArea((left - pad).coerceAtLeast(0f), (blockTop - pad * 0.6f).coerceAtLeast(0f), (left + widest + pad).coerceAtMost(width), (top + pad * 0.6f).coerceAtMost(height))
    return SlideTextLayout(size, placed, refSize, area.right, area.bottom - descent(refSize), ref?.let { styledText(it, style) }, block)
}

/** Знак ΔΕΙΞΙΣ на «Логотипе»: на всю ширину экрана с полями, не выше доли высоты. */
object LogoMark {
    /** Ширина знака в кеглях шрифта GFS Didot с трекингом 0.18 (без пробела после последней буквы). */
    const val WIDTH_EM = 4.386f
    /** Доля ширины экрана. */
    const val FILL = 0.86f
    const val MAX_HEIGHT = 0.6f

    /** Кегль знака для экрана [width] × [height]. */
    fun size(width: Float, height: Float): Float = minOf(width * FILL / WIDTH_EM, height * MAX_HEIGHT)
}
