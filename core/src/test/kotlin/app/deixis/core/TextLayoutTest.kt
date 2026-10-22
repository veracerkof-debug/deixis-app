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

class WrapAndCodeTest {
    @Test
    fun longLineWrapsByWords() {
        val measure = { s: String -> s.length.toFloat() }
        assertEquals(listOf("один два", "три"), wrapLine("один два три", 9f, measure))
        assertEquals(listOf("коротко"), wrapLine("коротко", 100f, measure))
        assertEquals(listOf("оченьдлинноеслово"), wrapLine("оченьдлинноеслово", 5f, measure))
    }

    @Test
    fun pairingCodeRoundTrip() {
        val code = PairingCode("192.168.1.23", 47820, "7241", "tv-1", "ТВ в холле")
        assertEquals(code, PairingCode.parse(code.toUri()))
        assertEquals(null, PairingCode.parse("https://example.com"))
        assertEquals(null, PairingCode.parse("deixis://1.2.3.4:1?pin=12"))
        val m = qrMatrix(code.toUri())
        assertTrue(m.size >= 21 && m.all { it.size == m.size })
    }
}

class SlideLayoutTest {
    private val measure = { s: String, size: Float -> s.length * size * 0.55f }

    @Test
    fun linesAreCenteredInsideSafeMargins() {
        val l = layoutSlideText("короткая\nстрока подлиннее", false, 1280f, 720f, 8f, measure, { it * 0.8f }, { it * 0.2f })
        assertEquals(2, l.lines.size)
        for (line in l.lines) {
            val w = measure(line.text, l.fontSize)
            assertEquals(640f, line.x + w / 2, 0.5f)
            assertTrue(line.x >= 64f && line.x + w <= 1216f)
            assertTrue(line.baseline in 36f..684f)
        }
        assertTrue(l.lines[0].baseline < l.lines[1].baseline)
    }

    @Test
    fun overlongLineIsWrapped() {
        val long = "слово ".repeat(40).trim()
        val l = layoutSlideText(long, true, 1280f, 720f, 8f, measure, { it * 0.8f }, { it * 0.2f })
        assertTrue(l.lines.size > 1)
        assertTrue(l.lines.all { it.x >= 64f - 0.5f })
    }
}
