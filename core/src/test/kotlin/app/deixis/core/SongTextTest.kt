package app.deixis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SongTextTest {
    private val song = """
        [Куплет 1]
        Коль славен наш Господь в Сионе,
        Не может изъяснить язык.

        [Припев]
        Везде, Господь, везде Ты славен,
        Во дни, в нощи сияньем равен.
    """.trimIndent()

    @Test
    fun labelsAndBlankLinesRoundTrip() {
        val s = SongText.parse(song)
        assertEquals(listOf("Куплет 1", "Припев"), s.map { it.label })
        assertEquals("Коль славен наш Господь в Сионе,\nНе может изъяснить язык.", s[0].text)
        assertEquals(s, SongText.parse(SongText.format(s)))
        // Пустые строки с пробелами и лишние пустые строки — не слайды.
        assertEquals(2, SongText.parse("а\n  \n\n\nб\n\n").size)
        // Только подпись — пустой слайд с подписью (его ещё заполнят).
        assertEquals(listOf(SongSlide("Припев", "")), SongText.parse("[Припев]"))
    }

    @Test
    fun splitAtCursorLineLikeHolyrics() {
        val s = listOf(SongSlide("Куплет 1", "раз\nдва\nтри"))
        // Курсор в начале второй строки — новый слайд с неё.
        assertEquals(listOf(SongSlide("Куплет 1", "раз"), SongSlide(null, "два\nтри")), SongText.splitAt(s, 0, 4))
        // Курсор посреди второй строки — тоже с её начала.
        assertEquals(listOf(SongSlide("Куплет 1", "раз"), SongSlide(null, "два\nтри")), SongText.splitAt(s, 0, 6))
        // Курсор в конце второй строки — новый слайд со следующей.
        assertEquals(listOf(SongSlide("Куплет 1", "раз\nдва"), SongSlide(null, "три")), SongText.splitAt(s, 0, 7))
        // Курсор в начале третьей строки.
        assertEquals(listOf(SongSlide("Куплет 1", "раз\nдва"), SongSlide(null, "три")), SongText.splitAt(s, 0, 8))
        // В начале и в конце — делить нечего.
        assertEquals(s, SongText.splitAt(s, 0, 0))
        assertEquals(s, SongText.splitAt(s, 0, 11))
        // Одна строка: делится по самому курсору (длинная строка — на два слайда).
        assertEquals(listOf(SongSlide(null, "Слава"), SongSlide(null, "в вышних")), SongText.splitAt(listOf(SongSlide(null, "Слава в вышних")), 0, 6))
    }

    @Test
    fun mergeMoveAndRechunk() {
        val s = listOf(SongSlide("К1", "а\nб"), SongSlide(null, "в"), SongSlide("П", "г"))
        assertEquals(listOf(SongSlide("К1", "а\nб\nв"), SongSlide("П", "г")), SongText.mergeWithPrevious(s, 1))
        assertEquals(s, SongText.mergeWithPrevious(s, 0))
        assertEquals(listOf(s[1], s[0], s[2]), SongText.move(s, 1, -1))
        assertEquals(s, SongText.move(s, 0, -1))
        val long = listOf(SongSlide("К1", "1\n2\n3\n4\n5"))
        assertEquals(listOf(SongSlide("К1", "1\n2"), SongSlide(null, "3\n4"), SongSlide(null, "5")), SongText.rechunk(long, 2))
    }
}
