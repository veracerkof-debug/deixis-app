package app.deixis.core

/** Прямоугольник в пикселях экрана. */
data class QuizRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

data class QuizOptionLayout(
    val box: QuizRect,
    val state: QuizOptionState,
    val badge: QuizRect,
    val mark: String,
    val markSize: Float,
    val markX: Float,
    val markBaseline: Float,
    val size: Float,
    val lines: List<PlacedLine>,
)

/** Всё, что нужно нарисовать: строки вопроса, плашки вариантов, полоса времени, номер. Координаты готовые. */
data class QuizLayout(
    val questionSize: Float,
    val question: List<PlacedLine>,
    val options: List<QuizOptionLayout>,
    val bar: QuizRect,
    val barTrack: QuizRect,
    val metaSize: Float,
    val meta: PlacedLine?,
)

/**
 * Раскладка вопроса. Одна для компьютера, Android и браузера: платформа только измеряет текст ([measure],
 * [ascent]) и рисует готовые прямоугольники и строки. Варианты — сетка в одну колонку при длинных текстах и в две
 * при коротких; вопрос занимает верхнюю часть поля, полоса времени — нижнюю кромку.
 */
fun layoutQuiz(
    c: QuizContent,
    width: Float,
    height: Float,
    now: Long,
    measure: (String, Float) -> Float,
    ascent: (Float) -> Float,
    overscan: Boolean = false,
): QuizLayout {
    val area = safeArea(width, height, overscan, c.style.margins)
    val short = minOf(width, height)
    val lh = c.style.lineHeight
    val gap = short * 0.022f
    val metaSize = short * 0.032f
    val barH = short * 0.012f

    // Полоса времени — на самой нижней кромке области, вне блока вопроса.
    val barTrack = QuizRect(area.left, area.bottom - barH, area.right, area.bottom)
    val frac = c.timeFraction(now)
    val bar = QuizRect(area.left, barTrack.top, area.left + (area.right - area.left) * frac, barTrack.bottom)
    val contentBottom = barTrack.top - gap * 1.5f

    val metaText = "${c.index + 1} / ${c.total}"
    val meta = if (c.total > 0) PlacedLine(metaText, area.right - measure(metaText, metaSize), area.top + ascent(metaSize)) else null
    val top = area.top + (if (meta != null) metaSize * lh + gap * 0.5f else 0f)

    val n = c.options.size
    val fullW = area.right - area.left
    // Две колонки, пока варианты короткие; иначе — одна, чтобы текст не дробился на узкие строки.
    val longest = c.options.maxOfOrNull { it.text.length } ?: 0
    val cols = if (n <= 1 || longest > 34) 1 else 2
    val rows = if (n == 0) 0 else (n + cols - 1) / cols
    val colW = (fullW - gap * (cols - 1)) / cols

    // Высота блока вариантов: до 46 % поля, но не меньше, чем нужно для читаемых плашек.
    val avail = contentBottom - top
    val optBlock = if (rows == 0) 0f else minOf(avail * 0.52f, rows * short * 0.2f + (rows - 1) * gap)
    val rowH = if (rows == 0) 0f else (optBlock - (rows - 1) * gap) / rows
    val qBottom = contentBottom - optBlock - (if (rows > 0) gap * 1.5f else 0f)
    val qMax = short * 0.085f
    val fitted = fitParagraphs(listOf(styledText(c.text, c.style)), fullW, (qBottom - top).coerceAtLeast(1f), qMax, short * 0.025f, lh, measure)
    val qBlockH = fitted.lines.size * fitted.size * lh
    val qTop = top + ((qBottom - top) - qBlockH).coerceAtLeast(0f) / 2f
    val question = fitted.lines.mapIndexed { i, line ->
        PlacedLine(line, alignX(area, measure(line, fitted.size), c.style.align), qTop + i * fitted.size * lh + ascent(fitted.size))
    }

    val options = c.options.mapIndexed { i, o ->
        val col = i % cols
        val row = i / cols
        val l = area.left + col * (colW + gap)
        val t = top + (qBottom - top) + (if (rows > 0) gap * 1.5f else 0f) + row * (rowH + gap)
        val box = QuizRect(l, t, l + colW, t + rowH)
        val pad = rowH * 0.16f
        val badgeSize = rowH * 0.56f
        val badge = QuizRect(l + pad, t + (rowH - badgeSize) / 2f, l + pad + badgeSize, t + (rowH + badgeSize) / 2f)
        val markSize = badgeSize * 0.62f
        val markX = badge.left + (badge.width - measure(o.mark, markSize)) / 2f
        val markBase = badge.top + (badge.height - markSize) / 2f + ascent(markSize)
        val textLeft = badge.right + pad
        val textW = box.right - pad - textLeft
        val text = styledText(o.text, c.style)
        val f = fitParagraphs(listOf(text), textW, rowH - pad, rowH * 0.42f, rowH * 0.16f, lh, measure)
        val block = f.lines.size * f.size * lh
        val first = t + (rowH - block) / 2f
        val lines = f.lines.mapIndexed { k, s -> PlacedLine(s, textLeft, first + k * f.size * lh + ascent(f.size)) }
        QuizOptionLayout(box, c.stateOf(o), badge, o.mark, markSize, markX, markBase, f.size, lines)
    }
    return QuizLayout(fitted.size, question, options, bar, barTrack, metaSize, meta)
}

/** Цвета слайда вопроса (ARGB): токены дизайн-кода для тёмного вывода — `--ok`, `--live`, `--tint`. */
object QuizColors {
    const val RIGHT = 0xFF3F7D57
    const val WRONG = 0xFFB96E65
    const val PICKED_OUTLINE = 0xFFBC9361
    const val BAR = 0xFFBC9361
    /** Плашка варианта и трек полосы: цвет текста слайда с этой прозрачностью. */
    const val PLATE_ALPHA = 0.14f
    const val BADGE_ALPHA = 0.22f
    const val TRACK_ALPHA = 0.18f
    /** Невыбранные варианты после результата тускнеют. */
    const val DIM_ALPHA = 0.45f
}
