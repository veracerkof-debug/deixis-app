package app.deixis.desktop

import app.deixis.core.LibreOfficeConverter
import app.deixis.core.StoreException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfficeConverterTest {
    private fun deck(name: String) = File(createTempDirectory("deck").toFile(), name).apply {
        writeBytes(OfficeConverterTest::class.java.getResourceAsStream("/$name")!!.readBytes())
    }

    private val builtIn = OfficeConverter(office = null)

    @Test
    fun hiddenSlidesAreNotCounted() {
        for (name in listOf("lecture16x9.pptx", "lecture16x9.ppt")) {
            val d = builtIn.readDeck(deck(name))
            assertEquals(5, d.visibleSlides, "$name: шесть слайдов, один скрыт")
            assertEquals(16.0 / 9, d.width / d.height, 0.01)
        }
    }

    @Test
    fun builtInConversionIsExactWithoutLibreOffice() {
        for (name in listOf("lecture16x9.pptx", "lecture16x9.ppt")) {
            val src = deck(name)
            val r = builtIn.toPdf(src, File(src.parentFile, "work"))
            assertNull(r.warning, name)
            Loader.loadPDF(r.pdf).use { doc ->
                assertEquals(5, doc.numberOfPages, "$name: скрытый слайд не попал")
                val box = doc.getPage(0).mediaBox
                assertEquals(16.0 / 9, box.width.toDouble() / box.height, 0.01)
                // Слайд не пустой: на странице есть тёмные точки текста.
                val img = PDFRenderer(doc).renderImage(1, 0.5f)
                var dark = 0
                for (x in 0 until img.width step 4) for (y in 0 until img.height step 4) if ((img.getRGB(x, y) and 0xFF) < 100) dark++
                assertTrue(dark > 50, "$name: на слайде 2 виден текст ($dark)")
            }
        }
    }

    @Test
    fun wrongPageCountOrFormatIsCaught() {
        val src = deck("lecture16x9.pptx")
        val good = builtIn.toPdf(src, File(src.parentFile, "w1")).pdf
        val d = builtIn.readDeck(src)
        assertNull(builtIn.check(good, d))
        assertNotNull(builtIn.check(good, d.copy(visibleSlides = 6)), "лишний или потерянный слайд")
        assertNotNull(builtIn.check(good, d.copy(width = 4.0, height = 3.0)), "4:3 вместо 16:9")
    }

    @Test
    fun documentsNeedLibreOfficeAndSaySo() {
        val doc = File(createTempDirectory("doc").toFile(), "отчёт.docx").apply { writeText("x") }
        val e = assertFailsWith<StoreException> { builtIn.toPdf(doc, File(doc.parentFile, "w")) }
        assertTrue(e.message!!.contains("LibreOffice"))
    }

    @Test
    fun libreOfficeResultIsCheckedToo() {
        val lo = LibreOfficeConverter.find() ?: return // на машине без LibreOffice проверяется только встроенный перевод
        val src = deck("lecture16x9.pptx")
        val r = OfficeConverter(lo).toPdf(src, File(src.parentFile, "lo"))
        Loader.loadPDF(r.pdf).use { assertEquals(5, it.numberOfPages) }
        assertNull(r.warning, "LibreOffice перевёл точно")
    }
}
