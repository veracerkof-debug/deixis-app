package app.deixis.phone

import java.time.LocalDate

/**
 * Перенос из Holyrics в библиотеку Deixis: песни, тексты, темы и плейлист становятся своими и остаются
 * без Holyrics. У перенесённого постоянный id от id в Holyrics: повторный перенос обновляет те же песни,
 * а не добавляет копии. Оформление, заданное в Deixis самому элементу (styles), не трогается.
 */
data class HolyricsHaul(
    val items: List<LibItem> = emptyList(),
    val themes: List<Theme> = emptyList(),
    /** Песни плейлиста Holyrics по порядку (id в Deixis); null — плейлист не переносили. */
    val playlist: List<String>? = null,
)

object HolyricsImport {
    fun songId(holyricsId: String) = "hl-song-$holyricsId"
    fun textId(holyricsId: String) = "hl-text-$holyricsId"
    fun themeId(holyricsId: String) = "hl-theme-$holyricsId"
    const val PLAYLIST_SET = "hl-playlist"

    /** Слить перенесённое с библиотекой: то же id — заменить (дата добавления прежняя), новое — сверху. */
    fun merge(d: StoreData, haul: HolyricsHaul, today: LocalDate = LocalDate.now()): StoreData {
        val old = d.items.associateBy { it.id }
        val incoming = haul.items.map { n -> old[n.id]?.let { n.copy(created = it.created) } ?: n }
        val byId = incoming.associateBy { it.id }
        val fresh = incoming.filter { it.id !in old }
        val items = fresh + d.items.map { byId[it.id] ?: it }
        val themeIds = haul.themes.map { it.id }.toSet()
        val themes = d.themes.filter { it.id !in themeIds } + haul.themes
        // Плейлист Holyrics — свой сетлист на сегодня: при новом переносе он обновляется, а не множится.
        val setlists = haul.playlist?.let { ids ->
            val known = items.map { it.id }.toSet()
            val set = Setlist(PLAYLIST_SET, "Плейлист Holyrics", today.toString(), ids.filter { it in known })
            listOf(set) + d.setlists.filter { it.id != PLAYLIST_SET }
        } ?: d.setlists
        return d.copy(items = items, themes = themes, setlists = setlists)
    }
}
