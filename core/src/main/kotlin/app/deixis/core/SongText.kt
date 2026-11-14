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

    /** Грубая ширина строки (Noto Sans SemiBold: в среднем 0,58 кегля на знак) — когда настоящей меры текста под рукой нет. */
    val approxMeasure: (String, Float) -> Float = { text, size -> text.length * size * 0.58f }

    /** Наименьший кегль при автоделении — доля предельного кегля темы: ниже текст в зале уже плохо читается. */
    const val MIN_SIZE_OF_CAP = 0.45f

    /**
     * Делит слайды по теме оформления [style]: слайд, который не помещается при читаемом кегле, режется на части
     * (по строкам; одна длинная строка — по словам). Размер зала берётся 16:9 ([width]×[height]) с полями темы: наименьший
     * читаемый кегль — [MIN_SIZE_OF_CAP] от предельного кегля темы, межстрочный — из темы. Слайды, которые и так
     * помещаются, не трогаются; подпись остаётся у первой части. [measure] — ширина строки при данном кегле
     * (на экране — настоящая мера платформы, по умолчанию — [approxMeasure]).
     */
    fun autoSplit(
        slides: List<SongSlide>,
        style: TextStyle,
        width: Float = 1920f,
        height: Float = 1080f,
        measure: (String, Float) -> Float = approxMeasure,
    ): List<SongSlide> {
        val area = safeArea(width, height, false, style.margins)
        val boxW = area.width * 0.985f
        val boxH = area.height
        val size = maxFontSize(width, height, style) * MIN_SIZE_OF_CAP
        val lh = style.lineHeight
        fun fits(lines: List<String>): Boolean {
            if (lines.isEmpty()) return true
            val text = lines.map { styledText(it, style) }
            if (text.any { l -> l.split(' ').any { w -> measure(w, size) > boxW } }) return false
            return text.sumOf { wrapLine(it, boxW) { s -> measure(s, size) }.size } * size * lh <= boxH
        }
        // Одна строка, которая не помещается сама, — по словам, пока части не станут помещаться.
        fun pieces(line: String): List<String> {
            if (fits(listOf(line))) return listOf(line)
            val out = mutableListOf<String>()
            var cur = ""
            for (w in line.split(' ').filter { it.isNotEmpty() }) {
                val next = if (cur.isEmpty()) w else "$cur $w"
                if (cur.isNotEmpty() && !fits(listOf(next))) { out += cur; cur = w } else cur = next
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }
        return slides.flatMap { sl ->
            val lines = sl.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty() || fits(lines)) return@flatMap listOf(sl)
            val parts = mutableListOf<MutableList<String>>(mutableListOf())
            for (u in lines.flatMap(::pieces)) {
                if (parts.last().isNotEmpty() && !fits(parts.last() + u)) parts += mutableListOf<String>()
                parts.last() += u
            }
            parts.mapIndexed { i, p -> SongSlide(if (i == 0) sl.label else null, p.joinToString("\n")) }
        }
    }
}
