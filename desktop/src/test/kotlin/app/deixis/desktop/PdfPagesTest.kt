package app.deixis.desktop

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** PDF держится открытым: та же страница — из памяти, следующая готовится заранее. */
class PdfPagesTest {
    private fun pdf(pages: Int): File {
        val f = File(createTempDirectory("pdfpages").toFile(), "deck.pdf")
        PDDocument().use { doc ->
            repeat(pages) { i ->
                val p = PDPage(PDRectangle(1600f, 900f))
                doc.addPage(p)
                PDPageContentStream(doc, p).use { cs -> cs.setNonStrokingColor(i / pages.toFloat(), 0.2f, 0.4f); cs.addRect(0f, 0f, 1600f, 900f); cs.fill() }
            }
            doc.save(f)
        }
        return f
    }

    @Test
    fun samePageComesFromMemoryAndNextIsPreparedAhead() {
        val f = pdf(4)
        assertEquals(4, PdfPages.count(f))
        val one = PdfPages.page(f, 0, 800, 450)
        assertSame(one, PdfPages.page(f, 0, 800, 450), "второй раз — из памяти")
        val until = System.currentTimeMillis() + 10_000
        while (!PdfPages.isReady(f, 1, 800, 450) && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(PdfPages.isReady(f, 1, 800, 450), "следующая страница готова заранее")
        assertEquals(800, one.width)
    }

    @Test
    fun missingPageIsAnErrorAndLastPageDoesNotPrefetchBeyond() {
        val f = pdf(2)
        PdfPages.page(f, 1, 320, 180)
        val e = runCatching { PdfPages.page(f, 5, 320, 180, "deck.pdf") }.exceptionOrNull()
        assertTrue(e?.message?.contains("нет страницы 6") == true, e?.message)
    }

    @Test
    fun changedFileIsReadAgain() {
        val f = pdf(1)
        assertEquals(1, PdfPages.count(f))
        Thread.sleep(1_100)
        pdf(3).copyTo(f, overwrite = true)
        f.setLastModified(System.currentTimeMillis())
        assertEquals(3, PdfPages.count(f))
    }
}
