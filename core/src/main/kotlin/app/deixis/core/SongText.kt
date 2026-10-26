package app.deixis.core

/** Слайд песни или текста в редакторе: подпись («Куплет 1», «Припев») и строки. */
data class SongSlide(val label: String? = null, val text: String = "")

/**
 * Песня или текст как в Holyrics: слайды делит сам ведущий. Текстом — пустая строка между слайдами,
 * подпись слайда — строка в квадратных скобках над ним: «[Припев]». Длинные слайды не делятся сами:
 * где слайд кончается, решает ведущий (деление по курсору, по пустым строкам или по N строк).
 */
object SongText {
    /** Подписи на выбор; своя подпись тоже возможна. */
    val LABELS = listOf("Куплет 1", "Куплет 2", "Куплет 3", "Куплет 4", "Припев", "Пред-припев", "Бридж", "Вступление", "Концовка")

    private val labelLine = Regex("""^\[(.+)]$""")

    fun parse(text: String): List<SongSlide> =
        text.replace("\r\n", "\n").split(Regex("\n[ \t]*\n")).mapNotNull { block ->
            val lines = block.lines().map { it.trimEnd() }.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            if (lines.isEmpty()) return@mapNotNull null
            val label = labelLine.matchEntire(lines.first().trim())?.groupValues?.get(1)?.trim()
            val body = (if (label != null) lines.drop(1) else lines).joinToString("\n").trim()
            if (body.isEmpty() && label == null) null else SongSlide(label, body)
        }

    fun format(slides: List<SongSlide>): String = slides.joinToString("\n\n") { s ->
        listOfNotNull(s.label?.takeIf { it.isNotBlank() }?.let { "[$it]" }, s.text.trim().takeIf { it.isNotEmpty() }).joinToString("\n")
    }

    /**
     * Делит слайд [index] на два по курсору [cursor] (смещение в его тексте), как в Holyrics:
     * курсор в конце строки — новый слайд начинается со следующей строки; в начале или в середине
     * строки — с этой строки; в единственной строке — по самому курсору. Делить нечего — без изменений.
     */
    fun splitAt(slides: List<SongSlide>, index: Int, cursor: Int): List<SongSlide> {
        val s = slides.getOrNull(index) ?: return slides
        val t = s.text
        val c = cursor.coerceIn(0, t.length)
        val lineStart = t.lastIndexOf('\n', c - 1) + 1
        val lineEnd = t.indexOf('\n', c).let { if (it < 0) t.length else it }
        val cut = when {
            '\n' !in t -> c
            c == lineEnd && c > lineStart -> lineEnd
            else -> lineStart
        }
        val head = t.substring(0, cut).trimEnd()
        val tail = t.substring(cut).trim()
        if (head.isEmpty() || tail.isEmpty()) return slides
        return slides.toMutableList().apply { this[index] = s.copy(text = head); add(index + 1, SongSlide(null, tail)) }
    }

    /** Объединяет слайд [index] с предыдущим: подпись — предыдущего. */
    fun mergeWithPrevious(slides: List<SongSlide>, index: Int): List<SongSlide> {
        if (index <= 0 || index >= slides.size) return slides
        val prev = slides[index - 1]
        val merged = prev.copy(text = listOf(prev.text, slides[index].text).filter { it.isNotBlank() }.joinToString("\n"))
        return slides.toMutableList().apply { this[index - 1] = merged; removeAt(index) }
    }

    fun move(slides: List<SongSlide>, index: Int, by: Int): List<SongSlide> {
        val to = index + by
        if (index !in slides.indices || to !in slides.indices) return slides
        return slides.toMutableList().apply { add(to, removeAt(index)) }
    }

    /** Разбивка всего текста заново: по [lines] строк в слайде (подписи остаются у первых частей). */
    fun rechunk(slides: List<SongSlide>, lines: Int): List<SongSlide> = slides.flatMap { s ->
        s.text.lines().filter { it.isNotBlank() }.chunked(lines.coerceAtLeast(1)).mapIndexed { i, part ->
            SongSlide(if (i == 0) s.label else null, part.joinToString("\n"))
        }.ifEmpty { listOf(s) }
    }
}
