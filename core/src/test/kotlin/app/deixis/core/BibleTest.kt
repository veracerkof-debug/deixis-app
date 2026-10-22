package app.deixis.core

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BibleTest {
    private val dir = createTempDirectory("bible").toFile()
    private val rst = BibleModule(JdbcSql(TestBibles.rst(dir)), "RST.SQLite3")
    private val kjv = BibleModule(JdbcSql(TestBibles.kjv(dir)), "KJV.SQLite3")

    @Test
    fun moduleInfoAndBooksAreRead() {
        assertEquals("Синодальный перевод", rst.title)
        assertEquals("RST", rst.abbreviation)
        assertEquals(listOf(10, 230, 500, 530), rst.books.map { it.number })
        assertEquals(18, rst.verseCount(500, 3))
    }

    @Test
    fun markupIsRemoved() {
        val text = rst.verses(500, 3, 16, 16).single().text
        assertTrue(!text.contains("2316") && !text.contains("[1]") && !text.contains("<"), text)
        assertEquals("Ибо так возлюбил Бог мир, что отдал Сына Своего Единородного, дабы всякий верующий в Него, не погиб, но имел жизнь вечную.", text)
        assertEquals("He that believeth on him is not condemned: but he that believeth not is condemned already.", kjv.verses(500, 3, 18, 18).single().text)
        assertEquals("a & b", cleanVerseText("a &amp; <e>b</e>"))
    }

    @Test
    fun referencesAreParsedByModuleBookNames() {
        assertEquals(BibleRef(500, 3, 16, 18), parseBibleRef("Ин 3:16-18", rst.books))
        assertEquals(BibleRef(500, 3, 16, 16), parseBibleRef("От Иоанна 3:16", rst.books))
        assertEquals(BibleRef(500, 3, 16, 16), parseBibleRef("иоанна 3.16", rst.books), "начало слова и точка вместо двоеточия")
        assertEquals(BibleRef(530, 13, 4, 7), parseBibleRef("1 Кор 13:4–7", rst.books))
        assertEquals(BibleRef(230, 22, null, null), parseBibleRef("Пс 22", rst.books))
        assertEquals(BibleRef(500, 3, 16, 16), parseBibleRef("John 3:16", kjv.books))
        assertNull(parseBibleRef("Неизвестная 1:1", rst.books))
        assertNull(parseBibleRef("Ин", rst.books))
    }

    @Test
    fun slidesGroupVersesAndPairTranslations() {
        val ref = parseBibleRef("Ин 3:16-18", rst.books)!!
        val one = buildScriptureSlides(ref, listOf(rst), ScriptureOptions(versesPerSlide = 1))
        assertEquals(3, one.size)
        assertEquals("Ин 3:16", one[0].ref)
        assertEquals(1, one[0].blocks.size)

        val two = buildScriptureSlides(ref, listOf(rst, kjv), ScriptureOptions(versesPerSlide = 2, refMode = RefMode.PER_TRANSLATION, fullBookName = true))
        assertEquals(2, two.size, "3 стиха по 2 — два слайда")
        assertNull(two[0].ref)
        assertEquals(listOf("От Иоанна 3:16–17 · RST", "John 3:16–17 · KJV"), two[0].blocks.map { it.ref })
        assertTrue(two[0].blocks[0].text.startsWith("16 Ибо так"), "номер стиха перед текстом, когда стихов несколько")
        assertTrue(two[0].blocks[1].text.contains("17 For God sent"))
        assertEquals("Ин 3:16–18", buildScriptureSlides(ref, listOf(rst), ScriptureOptions(versesPerSlide = 3)).single().ref)
    }

    @Test
    fun layoutFitsInsideSafeAreaAndRefIsSmaller() {
        val ref = parseBibleRef("Ин 3:16-17", rst.books)!!
        val measure = { s: String, size: Float -> s.length * size * 0.5f }
        val asc = { s: Float -> s * 0.8f }
        val desc = { s: Float -> s * 0.2f }
        for (opts in listOf(
            ScriptureOptions(versesPerSlide = 2),
            ScriptureOptions(versesPerSlide = 2, refMode = RefMode.PER_TRANSLATION, refPosition = RefPosition.TOP),
            ScriptureOptions(versesPerSlide = 2, sideBySide = true),
        )) {
            val slide = buildScriptureSlides(ref, listOf(rst, kjv), opts).single()
            val lines = layoutScripture(slide, 1920f, 1080f, measure, asc, desc)
            assertTrue(lines.isNotEmpty())
            val a = safeArea(1920f, 1080f)
            for (l in lines) {
                assertTrue(l.x >= a.left - 1 && l.x + measure(l.text, l.size) <= a.right + 1, "по ширине: $opts ${l.text}")
                assertTrue(l.baseline + l.size * 0.2f <= a.bottom + 1 && l.baseline - l.size * 0.8f >= a.top - 1, "по высоте: $opts ${l.text}")
            }
            val body = lines.filter { !it.ref }.map { it.size }.distinct()
            assertEquals(1, body.size, "один размер текста для всех переводов")
            assertTrue(lines.filter { it.ref }.all { it.size < body.single() })
            if (opts.refPosition == RefPosition.TOP && opts.refMode == RefMode.PER_TRANSLATION) {
                val firstRef = lines.first { it.ref }
                val firstBody = lines.first { !it.ref }
                assertTrue(firstRef.baseline < firstBody.baseline, "адрес над текстом")
            }
            if (opts.sideBySide) {
                val xs = lines.filter { !it.ref }.map { it.x }
                assertTrue(xs.any { it < 960 } && xs.any { it > 960 }, "два перевода в две колонки")
            }
        }
    }

    @Test
    fun scriptureFitsAnyScreenAndHonoursCaps() {
        val ref = parseBibleRef("Ин 3:16-18", rst.books)!!
        val measure = { s: String, size: Float -> s.length * size * 0.5f }
        val slide = buildScriptureSlides(ref, listOf(rst, kjv), ScriptureOptions(versesPerSlide = 3), TextStyle(caps = true)).single()
        for ((w, h) in listOf(1920f to 1080f, 1080f to 1920f, 1024f to 768f, 3840f to 1080f)) {
            val lines = layoutScripture(slide, w, h, measure, { it * 0.8f }, { it * 0.2f }, overscan = true)
            val a = safeArea(w, h, overscan = true)
            for (l in lines) {
                assertTrue(l.x >= a.left - 1 && l.x + measure(l.text, l.size) <= a.right + 1, "по ширине на ${w}×$h")
                assertTrue(l.baseline - l.size * 0.8f >= a.top - 1 && l.baseline + l.size * 0.2f <= a.bottom + 1, "по высоте на ${w}×$h")
                assertEquals(l.text.uppercase(), l.text)
            }
        }
    }

    @Test
    fun scriptureSurvivesProtocol() {
        val ref = parseBibleRef("Ин 3:16", rst.books)!!
        val slide = buildScriptureSlides(ref, listOf(rst, kjv)).single()
        assertEquals(Show(slide), decodeMsg(Show(slide).encode()))
    }
}
