package app.deixis.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Доступ к SQLite: у каждой платформы свой (Android — android.database.sqlite, компьютер — sqlite-jdbc),
 * а чтение модулей MyBible общее.
 */
interface SqlSource : AutoCloseable {
    /** Строки результата; значения — String, Long, Double или null. */
    fun query(sql: String, vararg args: Any): List<List<Any?>>
}

data class BibleBook(val number: Int, val shortName: String, val longName: String)

data class Verse(val number: Int, val text: String)

/**
 * Модуль Библии в формате MyBible (файл .SQLite3): таблицы info, books и verses.
 * Номера книг (book_number) общие для всех модулей, поэтому переводы легко показывать параллельно.
 */
class BibleModule(private val db: SqlSource, val fileName: String) : AutoCloseable {

    val info: Map<String, String> = runCatching {
        db.query("SELECT name, value FROM info").associate { (it[0]?.toString() ?: "") to (it[1]?.toString() ?: "") }
    }.getOrDefault(emptyMap())

    /** Название перевода для списка, например «Синодальный перевод». */
    val title: String = info["description"]?.takeIf { it.isNotBlank() } ?: fileName.substringBefore('.')

    /** Короткая подпись для адреса: имя файла без расширения, например RST или KJV. */
    val abbreviation: String = fileName.substringBefore('.').substringBefore('_').take(12)

    val language: String = info["language"].orEmpty()

    val books: List<BibleBook> = run {
        // В новых модулях есть books_all с отметкой is_present; в старых — только books.
        val rows = runCatching { db.query("SELECT book_number, short_name, long_name FROM books_all WHERE is_present = 1 ORDER BY book_number") }
            .getOrNull()?.takeIf { it.isNotEmpty() }
            ?: db.query("SELECT book_number, short_name, long_name FROM books ORDER BY book_number")
        rows.mapNotNull { r ->
            val n = (r[0] as? Number)?.toInt() ?: r[0]?.toString()?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
            BibleBook(n, r[1]?.toString().orEmpty().trim(), r[2]?.toString().orEmpty().trim())
        }
    }

    fun book(number: Int): BibleBook? = books.firstOrNull { it.number == number }

    fun chapterCount(book: Int): Int =
        (db.query("SELECT MAX(chapter) FROM verses WHERE book_number = ?", book).firstOrNull()?.get(0) as? Number)?.toInt() ?: 0

    fun verseCount(book: Int, chapter: Int): Int =
        (db.query("SELECT MAX(verse) FROM verses WHERE book_number = ? AND chapter = ?", book, chapter).firstOrNull()?.get(0) as? Number)?.toInt() ?: 0

    fun verses(book: Int, chapter: Int, from: Int = 1, to: Int = Int.MAX_VALUE): List<Verse> =
        db.query(
            "SELECT verse, text FROM verses WHERE book_number = ? AND chapter = ? AND verse BETWEEN ? AND ? ORDER BY verse",
            book, chapter, from, to,
        ).mapNotNull { r ->
            val n = (r[0] as? Number)?.toInt() ?: return@mapNotNull null
            Verse(n, cleanVerseText(r[1]?.toString().orEmpty()))
        }

    override fun close() = db.close()
}

private val REMOVE_WITH_CONTENT = Regex("(?is)<(S|m|f|n|h)\\b[^>]*>.*?</\\1>")
private val BREAKS = Regex("(?i)<(pb|br)\\s*/?>")
private val ANY_TAG = Regex("<[^>]+>")
private val SPACES = Regex("\\s+")

/**
 * Убирает разметку MyBible: номера Стронга (S), морфологию (m), сноски (f), примечания (n) и заголовки (h)
 * вместе с содержимым; остальные теги (слова Христа J, курсив i, выделение e, цитаты t) — оставляя текст.
 */
fun cleanVerseText(raw: String): String = raw
    .replace(REMOVE_WITH_CONTENT, "")
    .replace(BREAKS, " ")
    .replace(ANY_TAG, "")
    .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&amp;", "&")
    .replace(SPACES, " ")
    .trim()

/** Место Писания: книга, глава и стихи (если стихи не указаны — вся глава). */
data class BibleRef(val book: Int, val chapter: Int, val fromVerse: Int? = null, val toVerse: Int? = null)

private val REF = Regex("^\\s*(\\d?\\s*[\\p{L}.]+(?:\\s+[\\p{L}.]+)*)\\s*(\\d+)(?:\\s*[:.,]\\s*(\\d+)(?:\\s*[-–—]\\s*(\\d+))?)?\\s*$")

/**
 * Разбирает «Ин 3:16-18», «Иоанна 3:16», «1 Кор 13:4–7», «Быт 1», «Пс 22.1». Книга ищется по названиям
 * из самого модуля: сначала точное совпадение короткого или полного имени, затем начало слова.
 */
fun parseBibleRef(input: String, books: List<BibleBook>): BibleRef? {
    val m = REF.matchEntire(input) ?: return null
    val name = normalizeBookName(m.groupValues[1])
    if (name.isEmpty()) return null
    val book = books.firstOrNull { normalizeBookName(it.shortName) == name || normalizeBookName(it.longName) == name }
        ?: books.firstOrNull { normalizeBookName(it.longName).startsWith(name) || normalizeBookName(it.shortName).startsWith(name) }
        // «Иоанна» находит «От Иоанна», «Коринфянам» — «1-е Коринфянам»: начало любого слова названия.
        ?: books.firstOrNull { b -> b.longName.split(' ', '-').any { w -> w.length > 1 && normalizeBookName(w).startsWith(name) } }
        ?: return null
    val chapter = m.groupValues[2].toIntOrNull() ?: return null
    val from = m.groupValues[3].toIntOrNull()
    val to = m.groupValues[4].toIntOrNull()?.takeIf { from != null && it >= from }
    return BibleRef(book.number, chapter, from, to ?: from)
}

private fun normalizeBookName(s: String) = s.lowercase().replace(".", "").replace(Regex("\\s+"), "").replace('ё', 'е')

// Раскладка и настройки показа

@Serializable
enum class RefPosition { TOP, BOTTOM }

/** Как показывать адрес: одной строкой на слайд, у каждого перевода отдельно или не показывать. */
@Serializable
enum class RefMode { ONE_LINE, PER_TRANSLATION, NONE }

@Serializable
data class ScriptureOptions(
    /** Сколько стихов на слайде: 1, 2 или 3. */
    val versesPerSlide: Int = 1,
    val refMode: RefMode = RefMode.ONE_LINE,
    val refPosition: RefPosition = RefPosition.BOTTOM,
    /** Полное название книги («От Иоанна») или сокращённое («Ин»). */
    val fullBookName: Boolean = false,
    /** Номер стиха перед текстом, когда на слайде больше одного стиха. */
    val verseNumbers: Boolean = true,
    /** Два перевода рядом в две колонки, а не друг под другом. */
    val sideBySide: Boolean = false,
)

/** Перевод на слайде: текст стихов и его собственный адрес (для режима «у каждого перевода»). */
@Serializable
data class ScriptureBlock(val text: String, val ref: String? = null)

@Serializable @SerialName("scripture")
data class ScriptureContent(
    val blocks: List<ScriptureBlock>,
    /** Адрес одной строкой на весь слайд. */
    val ref: String? = null,
    val refPosition: RefPosition = RefPosition.BOTTOM,
    val sideBySide: Boolean = false,
    val style: TextStyle = TextStyle(),
) : Content

/**
 * Слайды для места Писания на одном или двух переводах. Стихи идут группами по [ScriptureOptions.versesPerSlide];
 * второй перевод берёт те же номера стихов той же книги.
 */
fun buildScriptureSlides(ref: BibleRef, modules: List<BibleModule>, options: ScriptureOptions = ScriptureOptions(), style: TextStyle = TextStyle()): List<ScriptureContent> {
    require(modules.isNotEmpty())
    val main = modules.first()
    val verses = main.verses(ref.book, ref.chapter, ref.fromVerse ?: 1, ref.toVerse ?: Int.MAX_VALUE)
    if (verses.isEmpty()) return emptyList()
    val perSlide = options.versesPerSlide.coerceIn(1, 3)
    return verses.chunked(perSlide).map { group ->
        val from = group.first().number
        val to = group.last().number
        val blocks = modules.map { m ->
            val texts = if (m === main) group else m.verses(ref.book, ref.chapter, from, to)
            val text = texts.joinToString(" ") { v ->
                if (options.verseNumbers && texts.size > 1) "${v.number} ${v.text}" else v.text
            }
            ScriptureBlock(
                text = text.ifBlank { "—" },
                ref = if (options.refMode == RefMode.PER_TRANSLATION) refLabel(m, ref.book, ref.chapter, from, to, options.fullBookName, withModule = modules.size > 1) else null,
            )
        }
        ScriptureContent(
            blocks = blocks,
            ref = if (options.refMode == RefMode.ONE_LINE) refLabel(main, ref.book, ref.chapter, from, to, options.fullBookName, withModule = false) else null,
            refPosition = options.refPosition,
            sideBySide = options.sideBySide && modules.size > 1,
            style = style,
        )
    }
}

/** «Ин 3:16», «От Иоанна 3:16–18», с подписью перевода: «Ин 3:16 · KJV». */
fun refLabel(module: BibleModule, book: Int, chapter: Int, from: Int, to: Int, full: Boolean, withModule: Boolean): String {
    val b = module.book(book)
    val name = (if (full) b?.longName else b?.shortName)?.takeIf { it.isNotBlank() } ?: b?.longName ?: book.toString()
    val verses = if (from == to) "$from" else "$from–$to"
    return "$name $chapter:$verses" + if (withModule) " · ${module.abbreviation}" else ""
}

/** Строка слайда Писания: [ref] — подпись меньшим кеглем. */
data class ScriptureLine(val text: String, val x: Float, val baseline: Float, val size: Float, val ref: Boolean)

/**
 * Раскладка слайда Писания: безопасные поля, один размер текста для всех переводов, адреса меньшим кеглем.
 * Платформы только измеряют шрифт и рисуют, поэтому слайд одинаков на телефоне, ТВ, компьютере.
 */
fun layoutScripture(
    c: ScriptureContent,
    width: Float,
    height: Float,
    measure: (String, Float) -> Float,
    ascent: (Float) -> Float,
    descent: (Float) -> Float,
    overscan: Boolean = false,
    lineHeight: Float = c.style.lineHeight,
): List<ScriptureLine> {
    val area = safeArea(width, height, overscan, c.style.margins)
    val short = minOf(width, height)
    val maxSize = maxFontSize(width, height, c.style)
    val gap = short * 0.04f
    val refRatio = 0.55f
    val blocks = c.blocks.map { b -> ScriptureBlock(styledText(b.text, c.style), b.ref?.let { styledText(it, c.style) }) }
    val globalRef = c.ref?.let { styledText(it, c.style) }
    val columns = if (c.sideBySide) blocks.size else 1
    val colGap = if (columns > 1) short * 0.05f else 0f
    val colW = (area.width - colGap * (columns - 1)) / columns
    // Адрес одной строкой — своим кеглем от экрана, текст стихов подбирается под оставшееся место.
    val globalRefSize = short * 0.045f
    val globalRefH = if (globalRef != null) globalRefSize * lineHeight + gap / 2 else 0f
    val areaTop = area.top + if (globalRef != null && c.refPosition == RefPosition.TOP) globalRefH else 0f
    val areaH = area.height - globalRefH
    val w = colW * 0.985f

    fun paras(b: ScriptureBlock) = b.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    fun blockLines(b: ScriptureBlock, size: Float) = balance(paras(b), size, w, measure)
    fun blockHeight(b: ScriptureBlock, size: Float, lines: Int = blockLines(b, size).size): Float =
        lines * size * lineHeight + (if (b.ref != null) size * refRatio * lineHeight else 0f)
    fun fits(size: Float): Boolean {
        if (blocks.any { b -> paras(b).any { p -> p.split(' ').any { measure(it, size) > w } } }) return false
        if (blocks.any { b -> b.ref != null && measure(b.ref, size * refRatio) > w }) return false
        val heights = blocks.map { b -> blockHeight(b, size, paras(b).sumOf { p -> wrapLine(p, w) { measure(it, size) }.size }) }
        return if (columns > 1) heights.all { it <= areaH } else heights.sum() + gap * (blocks.size - 1) <= areaH
    }

    val size = largestFitting(short * 0.015f, maxSize, ::fits)
    val refSize = size * refRatio
    val out = mutableListOf<ScriptureLine>()

    fun place(text: String, s: Float, isRef: Boolean, left: Float, width: Float, top: Float): Float {
        val lh = s * lineHeight
        val baseline = top + (lh - (ascent(s) + descent(s))) / 2f + ascent(s)
        val w = measure(text, s)
        val x = when (c.style.align) {
            HAlign.LEFT -> left
            HAlign.RIGHT -> left + width - w
            HAlign.CENTER -> left + (width - w) / 2f
        }
        out += ScriptureLine(text, x, baseline, s, isRef)
        return top + lh
    }
    fun placeBlock(b: ScriptureBlock, lines: List<String>, left: Float, top: Float): Float {
        var y = top
        if (b.ref != null && c.refPosition == RefPosition.TOP) y = place(b.ref, refSize, true, left, colW, y)
        for (line in lines) y = place(line, size, false, left, colW, y)
        if (b.ref != null && c.refPosition == RefPosition.BOTTOM) y = place(b.ref, refSize, true, left, colW, y)
        return y
    }

    val laid = blocks.map { it to blockLines(it, size) }
    if (columns > 1) {
        laid.forEachIndexed { i, (b, lines) ->
            val left = area.left + i * (colW + colGap)
            placeBlock(b, lines, left, alignY(areaTop, areaH, blockHeight(b, size, lines.size), c.style.valign))
        }
    } else {
        val total = laid.sumOf { (b, lines) -> blockHeight(b, size, lines.size).toDouble() }.toFloat() + gap * (blocks.size - 1)
        var y = alignY(areaTop, areaH, total, c.style.valign)
        laid.forEach { (b, lines) -> y = placeBlock(b, lines, area.left, y) + gap }
    }
    globalRef?.let { r ->
        val top = if (c.refPosition == RefPosition.TOP) area.top else area.bottom - globalRefSize * lineHeight
        place(r, globalRefSize, true, area.left, area.width, top)
    }
    return out
}
