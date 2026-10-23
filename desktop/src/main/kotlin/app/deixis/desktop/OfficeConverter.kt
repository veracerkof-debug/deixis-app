package app.deixis.desktop

import app.deixis.core.DocConverter
import app.deixis.core.LibreOfficeConverter
import app.deixis.core.MediaKind
import app.deixis.core.PdfResult
import app.deixis.core.StoreException
import app.deixis.core.mediaKindOf
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.poi.sl.usermodel.SlideShowFactory
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs

/**
 * Перевод презентаций и документов в PDF на компьютере. Работает сразу после установки:
 * PPTX и PPT рисует встроенный Apache POI. Если на компьютере есть LibreOffice, он переводит точнее
 * (эффекты, SmartArt, диаграммы) и берёт ещё документы и таблицы.
 *
 * Каждый перевод проверяется: число страниц должно совпасть с числом видимых слайдов, а пропорции
 * страницы — с размером слайда. Неточный перевод не показывается молча: берётся другой способ,
 * а если и он не справился — пульт получает предупреждение.
 */
class OfficeConverter(private val office: LibreOfficeConverter?) : DocConverter {

    /** Слайды презентации по самому файлу: сколько видимых и какой формат. */
    data class Deck(val visibleSlides: Int, val width: Double, val height: Double)

    override fun toPdf(input: File, workDir: File): PdfResult {
        workDir.mkdirs()
        val kind = mediaKindOf(input.name)
        if (kind == MediaKind.DOCUMENT) {
            val lo = office ?: throw StoreException("convert", "Документы и таблицы переводит LibreOffice: установите его на этот компьютер или сохраните файл как PDF")
            return PdfResult(lo.convert(input, workDir))
        }
        // ODP и KEY читает только LibreOffice.
        val deck = runCatching { readDeck(input) }.getOrNull()
        if (deck == null) {
            val lo = office ?: throw StoreException("convert", "«${input.name}» открывает только LibreOffice: установите его или сохраните презентацию как PDF")
            return PdfResult(lo.convert(input, workDir))
        }
        if (deck.visibleSlides == 0) throw StoreException("convert", "В презентации нет видимых слайдов")

        var problem: String? = null
        if (office != null) {
            try {
                val pdf = office.convert(input, workDir)
                problem = check(pdf, deck) ?: return PdfResult(pdf)
            } catch (e: Exception) {
                problem = e.message
            }
        }
        val pdf = renderWithPoi(input, File(workDir, "poi.pdf"))
        check(pdf, deck)?.let { throw StoreException("convert", "Презентация переведена неточно: $it") }
        val warning = when {
            office == null -> null
            else -> "LibreOffice перевёл неточно ($problem), показан встроенный перевод"
        }
        return PdfResult(pdf, warning)
    }

    /** null — PDF соответствует презентации; иначе — что не так. */
    internal fun check(pdf: File, deck: Deck): String? = Loader.loadPDF(pdf).use { doc ->
        if (doc.numberOfPages != deck.visibleSlides) return "страниц ${doc.numberOfPages}, а слайдов ${deck.visibleSlides}"
        val box = doc.getPage(0).mediaBox
        val want = deck.width / deck.height
        val got = box.width.toDouble() / box.height
        if (abs(got - want) / want > 0.02) return "формат страницы ${"%.2f".format(got)} вместо ${"%.2f".format(want)}"
        null
    }

    internal fun readDeck(input: File): Deck = SlideShowFactory.create(input, null, true).use { show ->
        val size = show.pageSize
        Deck(show.slides.count { !it.isHidden }, size.getWidth(), size.getHeight())
    }

    /**
     * Встроенный перевод: каждый видимый слайд рисуется картинкой 2560 точек по ширине
     * и кладётся страницей PDF размера слайда. Скрытые слайды и заметки не попадают.
     */
    internal fun renderWithPoi(input: File, out: File): File {
        SlideShowFactory.create(input, null, true).use { show ->
            val size = show.pageSize
            val scale = RENDER_WIDTH / size.getWidth()
            val w = (size.getWidth() * scale).toInt()
            val h = (size.getHeight() * scale).toInt()
            PDDocument().use { pdf ->
                for (slide in show.slides) {
                    if (slide.isHidden) continue
                    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                    val g = img.createGraphics()
                    try {
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                        g.color = Color.WHITE
                        g.fillRect(0, 0, w, h)
                        g.scale(scale, scale)
                        slide.draw(g)
                    } finally {
                        g.dispose()
                    }
                    val page = PDPage(PDRectangle(size.getWidth().toFloat(), size.getHeight().toFloat()))
                    pdf.addPage(page)
                    val image = JPEGFactory.createFromImage(pdf, img, 0.92f)
                    PDPageContentStream(pdf, page).use { it.drawImage(image, 0f, 0f, page.mediaBox.width, page.mediaBox.height) }
                }
                pdf.save(out)
            }
        }
        return out
    }

    private companion object {
        const val RENDER_WIDTH = 2560.0
    }
}
