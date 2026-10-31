package app.deixis.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Перевод Библии в Holyrics (`GetBibleVersionsV2`): ключ, название, язык. */
@Serializable
data class HolyricsBibleVersion(
    val key: String = "",
    val version: String = "",
    val title: String = "",
    val language: Language? = null,
) {
    @Serializable
    data class Language(val id: String = "", val iso: String = "", val name: String = "", @SerialName("alt_name") val altName: String? = null)

    /** Ключ, который понимает `ShowVerse` и вкладки Библии Holyrics. */
    val id: String get() = version.ifBlank { key }
}

/**
 * Библия Deixis (модули MyBible) рядом с Библией Holyrics. Текст Библии через API Holyrics не отдаётся, поэтому
 * стихи ищутся и листаются в Deixis, а показываются в Holyrics — его переводом, тем же, что выбран в Deixis.
 */
object HolyricsBible {
    /** Номера книг MyBible в порядке 66 книг (Бытие — 10 … Откровение — 730). Holyrics нумерует их 01–66. */
    private val CANON = listOf(
        10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160, 190, 220, 230, 240, 250, 260,
        290, 300, 310, 330, 340, 350, 360, 370, 380, 390, 400, 410, 420, 430, 440, 450, 460,
        470, 480, 490, 500, 510, 520, 530, 540, 550, 560, 570, 580, 590, 600, 610, 620, 630, 640, 650,
        660, 670, 680, 690, 700, 710, 720, 730,
    )

    /** Номер книги Holyrics (1–66) для номера MyBible; null — неканоническая книга, в Holyrics её нет. */
    fun bookOf(myBibleBook: Int): Int? = CANON.indexOf(myBibleBook).takeIf { it >= 0 }?.plus(1)

    /** Стих для `ShowVerse`: BBCCCVVV, например Ин 3:16 → 43003016. */
    fun verseId(myBibleBook: Int, chapter: Int, verse: Int): String? =
        bookOf(myBibleBook)?.let { "%02d%03d%03d".format(it, chapter, verse) }

    fun verseIds(myBibleBook: Int, chapter: Int, from: Int, to: Int): List<String> =
        (from..maxOf(from, to)).mapNotNull { verseId(myBibleBook, chapter, it) }.take(MAX_VERSES)

    /**
     * Тот же перевод в Holyrics для модуля Deixis: сокращение модуля в ключе Holyrics («RST» → `ru_rst`),
     * затем совпадение названия, затем любой перевод того же языка. Ничего не подошло — null (Holyrics покажет своим основным).
     */
    fun match(title: String, abbreviation: String, language: String, versions: List<HolyricsBibleVersion>): HolyricsBibleVersion? {
        if (versions.isEmpty()) return null
        val abbr = abbreviation.lowercase().filter { it.isLetterOrDigit() }
        val name = norm(title)
        val lang = language.lowercase().take(2)
        fun sameLang(v: HolyricsBibleVersion) = lang.isNotEmpty() &&
            (v.language?.iso?.lowercase() == lang || v.language?.id?.lowercase() == lang || v.id.lowercase().startsWith(lang + "_"))
        fun keyTail(v: HolyricsBibleVersion) = v.id.lowercase().substringAfter('_').filter { it.isLetterOrDigit() }
        return versions.firstOrNull { abbr.length >= 2 && keyTail(it) == abbr }
            ?: versions.firstOrNull { name.isNotEmpty() && norm(it.title) == name }
            ?: versions.firstOrNull { name.length >= 4 && (norm(it.title).contains(name) || name.contains(norm(it.title)).and(norm(it.title).length >= 4)) && (lang.isEmpty() || sameLang(it)) }
            ?: versions.firstOrNull { abbr.length >= 2 && keyTail(it).startsWith(abbr) && (lang.isEmpty() || sameLang(it)) }
            ?: versions.firstOrNull { sameLang(it) }
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    /** Holyrics показывает за один раз не больше 100 стихов. */
    const val MAX_VERSES = 100
}
