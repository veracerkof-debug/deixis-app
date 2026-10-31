package app.deixis.phone

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Перенос из Holyrics: свои копии с постоянным id, повторный перенос обновляет, плейлист — сетлист. */
class HolyricsImportTest {
    private val song = LibItem(HolyricsImport.songId("7"), Kind.SONG, "Великий Бог", listOf("Куплет", "Припев"), listOf("Verse 1", "Chorus"))
    private val text = LibItem(HolyricsImport.textId("x"), Kind.TEXT, "Объявления", listOf("Чай после служения"))
    private val theme = Theme(HolyricsImport.themeId("t1"), "Горы", app.deixis.core.TextStyle(background = 0xFF18233A, name = "Горы"))

    @Test
    fun importThenReimportUpdatesInsteadOfDuplicating() {
        val mine = LibItem("own", Kind.TEXT, "Своё", listOf("Своё"))
        val start = StoreData(items = listOf(mine), setlists = listOf(Setlist("s", "Служение", "2026-11-01", listOf("own"))))
        val once = HolyricsImport.merge(start, HolyricsHaul(listOf(song, text), listOf(theme), listOf(song.id)), LocalDate.of(2026, 10, 31))
        assertEquals(listOf(song.id, text.id, "own"), once.items.map { it.id }, "перенесённое сверху, своё на месте")
        assertEquals(listOf("Горы"), once.themes.map { it.name })
        val set = once.setlists.first()
        assertEquals(HolyricsImport.PLAYLIST_SET to listOf(song.id), set.id to set.items)
        assertEquals("Служение", once.setlists[1].title, "свои сетлисты не тронуты")

        val edited = song.copy(slides = listOf("Куплет", "Припев", "Куплет 2"), created = 1)
        val twice = HolyricsImport.merge(once, HolyricsHaul(listOf(edited), listOf(theme), listOf(song.id, "нет такого")), LocalDate.of(2026, 11, 7))
        assertEquals(3, twice.items.size, "повторный перенос не множит копии")
        val updated = twice.items.first { it.id == song.id }
        assertEquals(3, updated.slides.size, "песня обновилась")
        assertEquals(once.items.first { it.id == song.id }.created, updated.created, "дата добавления прежняя")
        assertEquals(1, twice.themes.size)
        assertEquals(listOf(song.id), twice.setlists.first().items, "в сетлисте только перенесённое")
        assertEquals(2, twice.setlists.size, "сетлист плейлиста один")
    }
}
