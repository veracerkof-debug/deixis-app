package app.deixis.desktop

import app.deixis.core.LibreOfficeConverter
import app.deixis.core.StoreException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
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

    private val builtIn = OfficeConverter(engines = emptyList())

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
    fun builtInTableStyleOfPowerPointIsApplied() {
        // Файл ссылается на «Средний стиль 2 — акцент 1», но не хранит его, как и всё, что сохранено не в PowerPoint.
        val src = deck("lecture16x9.pptx")
        val pdf = builtIn.toPdf(src, File(src.parentFile, "t")).pdf
        Loader.loadPDF(pdf).use { doc ->
            val img = PDFRenderer(doc).renderImage(2, 0.5f)
            val header = Color(img.getRGB(img.width / 6, (img.height * 0.29).toInt()))
            val row = Color(img.getRGB(img.width / 6, (img.height * 0.45).toInt()))
            assertTrue(header.blue > header.red + 60, "шапка таблицы залита цветом темы: $header")
            assertTrue(row.blue > row.red && row.red > 150, "строки светлее шапки: $row")
        }
    }

    @Test
    fun builtInConversionNamesWhatItDrawsApproximately() {
        val pptx = deck("lecture16x9.pptx")
        assertEquals(emptyList(), builtIn.audit(pptx, File(pptx.parentFile, "a")).filter { !it.startsWith("шрифты") }, "стиль таблицы добавлен, остальное по силам")
        // В PPT от LibreOffice фигуры записаны формулами: встроенный перевод о них предупреждает.
        val ppt = deck("lecture16x9.ppt")
        assertTrue(builtIn.audit(ppt, File(ppt.parentFile, "a")).any { it == "фигуры (слайд 4)" })
        assertTrue(builtIn.toPdf(ppt, File(ppt.parentFile, "w")).warning!!.contains("фигуры (слайд 4)"))
    }

    @Test
    fun nextProgramIsTriedWhenOneIsWrong() {
        val src = deck("lecture16x9.pptx")
        val good = builtIn.toPdf(src, File(src.parentFile, "g")).pdf
        val broken = object : PdfEngine {
            override val name = "Сломанная"
            override fun accepts(input: File) = true
            override fun convert(input: File, workDir: File) = File(workDir.apply { mkdirs() }, "one.pdf").also { f ->
                org.apache.pdfbox.pdmodel.PDDocument().use { it.addPage(org.apache.pdfbox.pdmodel.PDPage()); it.save(f) }
            }
        }
        val exact = object : PdfEngine {
            override val name = "Точная"
            override fun accepts(input: File) = true
            override fun convert(input: File, workDir: File) = good
        }
        val r = OfficeConverter(listOf(broken, exact)).toPdf(src, File(src.parentFile, "n"))
        assertEquals(good, r.pdf, "PDF с одной страницей вместо пяти отброшен")
        assertNull(r.warning)
        val alone = OfficeConverter(listOf(broken)).toPdf(src, File(src.parentFile, "m"))
        assertTrue(alone.warning!!.startsWith("Встроенный перевод. Сломанная: страниц 1, а слайдов 5"), alone.warning)
    }

    @Test
    fun libreOfficeResultIsCheckedToo() {
        val lo = LibreOfficeConverter.find() ?: return // на машине без LibreOffice проверяется только встроенный перевод
        val src = deck("lecture16x9.pptx")
        val r = OfficeConverter(listOf(LibreOfficeEngine(lo))).toPdf(src, File(src.parentFile, "lo"))
        Loader.loadPDF(r.pdf).use { assertEquals(5, it.numberOfPages) }
        assertNull(r.warning, "LibreOffice перевёл точно")
    }
}
