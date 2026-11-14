package app.deixis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SongAutoSplitTest {
    private val theme = TextStyle()
    // Мера с запасом: каждый знак 0,6 кегля. Так проверка не зависит от шрифта.
    private val measure: (String, Float) -> Float = { s, size -> s.length * size * 0.6f }

    private fun text(words: Int) = (1..words).joinToString(" ") { "слово$it" }

    @Test
    fun shortSlideStaysAsIs() {
        val s = SongSlide("Куплет 1", "Коль славен наш Господь в Сионе,\nНе может изъяснить язык.")
        assertEquals(listOf(s), SongText.autoSplit(listOf(s), theme, measure = measure))
    }

    @Test
    fun oneSolidParagraphIsCutIntoManySlidesByWords() {
        val long = text(300)
        val out = SongText.autoSplit(listOf(SongSlide(null, long)), theme, measure = measure)
        assertTrue(out.size > 2, "сплошной текст порезан: ${out.size}")
        // Ничего не потеряно и не переставлено.
        assertEquals(long, out.joinToString(" ") { it.text.replace("\n", " ") })
    }

    @Test
    fun everyPartFitsAtTheReadableSize() {
        val out = SongText.autoSplit(listOf(SongSlide(null, text(500))), theme, measure = measure)
        val area = safeArea(1920f, 1080f)
        val size = maxFontSize(1920f, 1080f, theme) * SongText.MIN_SIZE_OF_CAP
        for (p in out) {
            val lines = wrapLine(p.text.replace("\n", " "), area.width * 0.985f) { measure(it, size) }.size
            assertTrue(lines * size * theme.lineHeight <= area.height + 0.5f, "часть не помещается: $lines строк")
        }
    }

    @Test
    fun labelStaysOnlyOnTheFirstPart() {
        val out = SongText.autoSplit(listOf(SongSlide("Припев", text(400))), theme, measure = measure)
        assertEquals("Припев", out.first().label)
        assertTrue(out.drop(1).all { it.label == null })
    }

    @Test
    fun existingLineBreaksArePreferredOverWordBreaks() {
        val lines = (1..40).joinToString("\n") { "Строка номер $it песни о главном" }
        val out = SongText.autoSplit(listOf(SongSlide(null, lines)), theme, measure = measure)
        assertTrue(out.size > 1)
        for (p in out) for (l in p.text.lines()) assertTrue(l.startsWith("Строка номер") && l.endsWith("о главном"), "строка не разорвана: $l")
    }

    @Test
    fun smallerThemeCapMeansSmallerThresholdAndMoreOnASlide() {
        val big = SongText.autoSplit(listOf(SongSlide(null, text(200))), TextStyle(sizePercent = 20f), measure = measure).size
        val small = SongText.autoSplit(listOf(SongSlide(null, text(200))), TextStyle(sizePercent = 8f), measure = measure).size
        assertTrue(small < big, "тема с мелким кеглем вмещает больше: $small против $big")
    }

    @Test
    fun capsAndHugeWordsDoNotBreakIt() {
        val out = SongText.autoSplit(listOf(SongSlide(null, text(120) + " " + "я".repeat(400))), TextStyle(caps = true), measure = measure)
        assertTrue(out.isNotEmpty() && out.all { it.text.isNotBlank() })
        assertEquals(emptyList(), SongText.autoSplit(emptyList(), theme))
        assertEquals(listOf(SongSlide()), SongText.autoSplit(listOf(SongSlide()), theme))
    }
}
