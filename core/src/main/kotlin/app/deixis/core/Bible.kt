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
    lineHeight: Float = 1.2f,
): List<ScriptureLine> {
    val marginX = width * SAFE_MARGIN
    val marginY = height * SAFE_MARGIN
    val maxSize = height * c.style.sizePercent / 100f
    val gap = height * 0.04f
    val refRatio = 0.55f
    val columns = if (c.sideBySide) c.blocks.size else 1
    val colGap = if (columns > 1) width * 0.04f else 0f
    val colW = (width - 2 * marginX - colGap * (columns - 1)) / columns
    val globalRefH = if (c.ref != null) maxSize * refRatio * lineHeight + gap / 2 else 0f
    val areaTop = marginY + if (c.ref != null && c.refPosition == RefPosition.TOP) globalRefH else 0f
    val areaH = height - 2 * marginY - globalRefH

    fun wrap(text: String, size: Float) = text.lines().flatMap { wrapLine(it.trim(), colW) { s -> measure(s, size) } }.filter { it.isNotEmpty() }
    fun blockHeight(b: ScriptureBlock, size: Float): Float {
        val refLines = if (b.ref != null) 1 else 0
        return wrap(b.text, size).size * size * lineHeight + refLines * size * refRatio * lineHeight
    }
    fun fits(size: Float): Boolean = if (columns > 1) {
        c.blocks.all { blockHeight(it, size) <= areaH }
    } else {
        c.blocks.sumOf { blockHeight(it, size).toDouble() } + gap * (c.blocks.size - 1) <= areaH
    }

    // Наибольший размер, при котором всё помещается; не меньше трети от заданного.
    var lo = maxSize / 3
    var hi = maxSize
    if (fits(hi)) lo = hi else repeat(18) { val mid = (lo + hi) / 2; if (fits(mid)) lo = mid else hi = mid }
    val size = lo
    val refSize = size * refRatio
    val out = mutableListOf<ScriptureLine>()

    fun place(text: String, s: Float, isRef: Boolean, left: Float, w: Float, top: Float): Float {
        val lh = s * lineHeight
        val baseline = top + (lh - (ascent(s) + descent(s))) / 2f + ascent(s)
        out += ScriptureLine(text, left + (w - measure(text, s)) / 2f, baseline, s, isRef)
        return top + lh
    }
    fun placeBlock(b: ScriptureBlock, left: Float, top: Float): Float {
        var y = top
        if (b.ref != null && c.refPosition == RefPosition.TOP) y = place(b.ref, refSize, true, left, colW, y)
        for (line in wrap(b.text, size)) y = place(line, size, false, left, colW, y)
        if (b.ref != null && c.refPosition == RefPosition.BOTTOM) y = place(b.ref, refSize, true, left, colW, y)
        return y
    }

    if (columns > 1) {
        c.blocks.forEachIndexed { i, b ->
            val left = marginX + i * (colW + colGap)
            placeBlock(b, left, areaTop + (areaH - blockHeight(b, size)) / 2f)
        }
    } else {
        val total = c.blocks.sumOf { blockHeight(it, size).toDouble() }.toFloat() + gap * (c.blocks.size - 1)
        var y = areaTop + (areaH - total) / 2f
        c.blocks.forEach { y = placeBlock(it, marginX, y) + gap }
    }
    c.ref?.let { r ->
        val top = if (c.refPosition == RefPosition.TOP) marginY else height - marginY - refSize * lineHeight
        place(r, refSize, true, marginX, width - 2 * marginX, top)
    }
    return out
}
