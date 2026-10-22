package app.deixis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextLayoutTest {
    @Test
    fun blankLineStartsNewSlide() {
        assertEquals(listOf("a\nb", "c"), splitIntoSlides("a\nb\n\nc"))
    }

    @Test
    fun longBlockIsSplitByFourLines() {
        val text = (1..9).joinToString("\n") { "строка $it" }
        val slides = splitIntoSlides(text)
        assertEquals(3, slides.size)
        assertEquals(4, slides[0].lines().size)
        assertEquals(1, slides[2].lines().size)
    }

    @Test
    fun windowsLineEndingsAndExtraSpacesAreHandled() {
        assertEquals(listOf("a", "b"), splitIntoSlides("  a  \r\n \r\n\r\n b "))
        assertEquals(emptyList(), splitIntoSlides("   \n\n "))
    }

    @Test
    fun fontShrinksUntilTextFits() {
        val measure = { s: String, size: Float -> s.length * size * 0.5f }
        val big = fitFontSize(listOf("коротко"), 1000f, 500f, 100f, 10f, measure = measure)
        assertEquals(100f, big)
        val small = fitFontSize(listOf("очень длинная строка текста на слайде"), 400f, 500f, 100f, 10f, measure = measure)
        assertTrue(small < 100f && "очень длинная строка текста на слайде".length * small * 0.5f <= 400f)
        val tall = fitFontSize(List(4) { "x" }, 1000f, 120f, 100f, 5f, measure = measure)
        assertTrue(4 * tall * 1.2f <= 120f)
    }
}
