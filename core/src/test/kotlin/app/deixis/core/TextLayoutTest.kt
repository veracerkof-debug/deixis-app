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
    fun fontGrowsToTheLargestSizeThatFits() {
        val measure = { s: String, size: Float -> s.length * size * 0.5f }
        val big = fitParagraphs(listOf("коротко"), 1000f, 500f, 100f, 10f, measure = measure)
        assertEquals(100f, big.size)
        val text = "очень длинная строка текста на слайде"
        val fitted = fitParagraphs(listOf(text), 400f, 500f, 400f, 10f, measure = measure)
        assertTrue(fitted.lines.size > 1, "переносится, а не мельчит")
        assertTrue(fitted.lines.all { measure(it, fitted.size) <= 400f })
        assertTrue(fitted.lines.size * fitted.size * 1.2f <= 500f)
        // Чуть крупнее уже не помещается: размер наибольший.
        val bigger = fitted.size * 1.02f
        val wrapped = wrapLine(text, 400f * 0.985f) { measure(it, bigger) }
        assertTrue(wrapped.size * bigger * 1.2f > 500f || wrapped.any { measure(it, bigger) > 400f * 0.985f } ||
            text.split(' ').any { measure(it, bigger) > 400f * 0.985f })
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
    private val asc = { size: Float -> size * 0.8f }
    private val desc = { size: Float -> size * 0.2f }
    private val song = "Слава в вышних Богу\nи на земле мир\nв человеках благоволение"

    /** Экраны разного формата: 16:9, 4:3, вертикальный, широкая доска, маленький проектор, 4K. */
    private val screens = listOf(1920f to 1080f, 1024f to 768f, 1080f to 1920f, 3840f to 1080f, 800f to 480f, 3840f to 2160f)

    private fun assertInside(l: SlideTextLayout, w: Float, h: Float, overscan: Boolean = false) {
        val a = safeArea(w, h, overscan)
        for (line in l.lines) {
            val lw = measure(line.text, l.fontSize)
            assertTrue(line.x >= a.left - 0.5f && line.x + lw <= a.right + 0.5f, "по ширине: ${line.text} на ${w}×$h")
            assertTrue(line.baseline - asc(l.fontSize) >= a.top - 0.5f && line.baseline + desc(l.fontSize) <= a.bottom + 0.5f, "по высоте на ${w}×$h")
            assertEquals(w / 2, line.x + lw / 2, 0.5f)
        }
    }

    @Test
    fun textFitsSafeAreaOnEveryScreen() {
        for ((w, h) in screens) for (overscan in listOf(false, true)) {
            val l = layoutSlideText(song, null, w, h, TextStyle(), measure, asc, desc, overscan)
            assertInside(l, w, h, overscan)
            assertTrue(l.fontSize > minOf(w, h) * 0.05f, "текст крупный на ${w}×$h: ${l.fontSize}")
        }
    }

    @Test
    fun marginsAreEqualOnAllSides() {
        for ((w, h) in screens) {
            val a = safeArea(w, h)
            assertEquals(a.left, a.top, 0.01f)
            assertEquals(w - a.right, h - a.bottom, 0.01f)
        }
        val tv = safeArea(1920f, 1080f, overscan = true)
        assertTrue(tv.left > safeArea(1920f, 1080f).left)
    }

    @Test
    fun textUsesTheAvailableSpace() {
        // Самая длинная строка или вся высота упирается в поля: больше места не осталось.
        for ((w, h) in screens) {
            val l = layoutSlideText(song, null, w, h, TextStyle(sizePercent = 100f), measure, asc, desc)
            val a = safeArea(w, h)
            val widest = l.lines.maxOf { measure(it.text, l.fontSize) }
            val tall = l.lines.size * l.fontSize * SLIDE_LINE_HEIGHT
            assertTrue(widest >= a.width * 0.9f || tall >= a.height * 0.97f, "на ${w}×$h занято ${widest / a.width} ширины, ${tall / a.height} высоты")
        }
    }

    @Test
    fun longLineWrapsEvenlyWithoutSplittingWords() {
        val long = "Ибо так возлюбил Бог мир что отдал Сына Своего Единородного дабы всякий верующий в Него не погиб но имел жизнь вечную"
        val l = layoutSlideText(long, "Ин 3:16", 1920f, 1080f, TextStyle(), measure, asc, desc)
        assertTrue(l.lines.size > 1)
        assertEquals(long, l.lines.joinToString(" ") { it.text })
        val lengths = l.lines.map { measure(it.text, l.fontSize) }
        assertTrue(lengths.min() > lengths.max() * 0.6f, "строки ровные: $lengths")
        assertInside(l, 1920f, 1080f)
        assertEquals("Ин 3:16", l.ref)
    }

    @Test
    fun capsSettingUppercasesTextAndReference() {
        val l = layoutSlideText("Слава Богу", "Пс 150", 1920f, 1080f, TextStyle(caps = true), measure, asc, desc)
        assertEquals("СЛАВА БОГУ", l.lines.joinToString(" ") { it.text })
        assertEquals("ПС 150", l.ref)
        assertEquals("o‘zbekcha", styledText("o‘zbekcha", TextStyle()))
        assertEquals("OʻZBEKCHA", styledText("oʻzbekcha", TextStyle(caps = true)))
    }

    @Test
    fun hugeWordShrinksInsteadOfOverflowing() {
        val l = layoutSlideText("Достопримечательность", null, 800f, 480f, TextStyle(sizePercent = 100f), measure, asc, desc)
        assertInside(l, 800f, 480f)
    }
}
