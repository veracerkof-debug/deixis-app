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
