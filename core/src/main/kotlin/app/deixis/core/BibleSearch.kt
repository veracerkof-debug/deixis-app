package app.deixis.core

/** Найденный стих: место и текст. */
data class VerseHit(val book: Int, val chapter: Int, val verse: Int, val text: String)

/**
 * Поиск по тексту Писания. Модуль читается в память один раз (около 5 МБ на перевод), дальше поиск
 * мгновенный и не зависит от регистра и буквы «ё». Все слова запроса должны встретиться в стихе.
 */
class BibleSearch(private val module: BibleModule) {
    private class Row(val book: Int, val chapter: Int, val verse: Int, val text: String, val norm: String)

    private val rows: List<Row> by lazy {
        module.allVerses().map { (b, c, v, t) -> Row(b, c, v, t, normalize(t)) }
    }

    fun search(query: String, limit: Int = 200): List<VerseHit> {
        val words = normalize(query).split(' ').filter { it.length >= 2 || it.any(Char::isDigit) }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<VerseHit>()
        for (r in rows) {
            if (words.all { r.norm.contains(it) }) {
                out += VerseHit(r.book, r.chapter, r.verse, r.text)
                if (out.size >= limit) break
            }
        }
        return out
    }

    companion object {
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        fun normalize(s: String): String = s.lowercase().replace('ё', 'е').replace(NON_WORD, " ").trim()
    }
}

/** Все стихи модуля по порядку: книга, глава, стих, очищенный текст. */
fun BibleModule.allVerses(): List<VerseRow> = query(
    "SELECT book_number, chapter, verse, text FROM verses ORDER BY book_number, chapter, verse",
).mapNotNull { r ->
    val b = (r[0] as? Number)?.toInt() ?: r[0]?.toString()?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
    val c = (r[1] as? Number)?.toInt() ?: return@mapNotNull null
    val v = (r[2] as? Number)?.toInt() ?: return@mapNotNull null
    VerseRow(b, c, v, cleanVerseText(r[3]?.toString().orEmpty()))
}

data class VerseRow(val book: Int, val chapter: Int, val verse: Int, val text: String)
