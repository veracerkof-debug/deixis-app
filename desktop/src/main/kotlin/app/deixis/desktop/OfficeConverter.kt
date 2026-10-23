package app.deixis.desktop

import app.deixis.core.DocConverter
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
import org.apache.poi.ddf.EscherPropertyTypes
import org.apache.poi.ddf.EscherSimpleProperty
import org.apache.poi.hslf.usermodel.HSLFFreeformShape
import org.apache.poi.sl.draw.Drawable
import org.apache.poi.sl.usermodel.GroupShape
import org.apache.poi.sl.usermodel.Shape
import org.apache.poi.sl.usermodel.SlideShow
import org.apache.poi.sl.usermodel.SlideShowFactory
import org.apache.poi.sl.usermodel.TextShape
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFGraphicFrame
import org.apache.poi.xslf.usermodel.XSLFTable
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import kotlin.math.abs

/**
 * Перевод презентаций и документов в PDF на компьютере.
 *
 * Сначала — точные программы этого компьютера ([findPdfEngines]): PowerPoint, LibreOffice, Keynote.
 * Каждый их PDF проверяется: число страниц должно совпасть с числом видимых слайдов, а пропорции
 * страницы — с размером слайда. Если ни одной программы нет или все ошиблись, PPTX и PPT рисует
 * встроенный Apache POI. Он заранее просматривает слайды и честно называет то, что рисует
 * приблизительно (диаграммы, SmartArt, недостающие шрифты), — пульт получает это предупреждением.
 */
class OfficeConverter(private val engines: List<PdfEngine>) : DocConverter {

    /** Слайды презентации по самому файлу: сколько видимых и какой формат. */
    data class Deck(val visibleSlides: Int, val width: Double, val height: Double)

    override fun toPdf(input: File, workDir: File): PdfResult {
        workDir.mkdirs()
        val kind = mediaKindOf(input.name)
        // ODP, KEY и документы встроенный перевод не читает.
        val deck = if (kind == MediaKind.PRESENTATION) runCatching { readDeck(input) }.getOrNull() else null
        if (deck != null && deck.visibleSlides == 0) throw StoreException("convert", "В презентации нет видимых слайдов")

        val problems = mutableListOf<String>()
        for ((i, engine) in engines.filter { it.accepts(input) }.withIndex()) {
            try {
                val pdf = engine.convert(input, File(workDir, "engine$i"))
                val problem = if (deck != null) check(pdf, deck) else pagesOrNull(pdf)
                if (problem == null) return PdfResult(pdf)
                problems += "${engine.name}: $problem"
            } catch (e: Exception) {
                problems += "${engine.name}: ${e.message}"
            }
        }
        if (deck == null) {
            val what = if (kind == MediaKind.DOCUMENT) "Документы и таблицы" else "«${input.name}»"
            if (problems.isEmpty()) throw StoreException("convert", "$what переводит Microsoft Office или LibreOffice: установите один из них на этот компьютер или сохраните файл как PDF")
            throw StoreException("convert", "Не удалось перевести «${input.name}»: ${problems.joinToString("; ")}")
        }

        val pdf = renderWithPoi(input, File(workDir, "poi.pdf"))
        check(pdf, deck)?.let { throw StoreException("convert", "Презентация переведена неточно: $it") }
        val notes = buildList {
            if (problems.isNotEmpty()) add(problems.joinToString("; "))
            audit(input, workDir).takeIf { it.isNotEmpty() }?.let { add("приблизительно: ${it.joinToString("; ")}") }
        }
        val warning = notes.takeIf { it.isNotEmpty() }?.joinToString(". ", prefix = "Встроенный перевод. ", postfix = ". Точнее переводит PowerPoint или LibreOffice")
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

    private fun pagesOrNull(pdf: File): String? = Loader.loadPDF(pdf).use { if (it.numberOfPages > 0) null else "пустой PDF" }

    internal fun readDeck(input: File): Deck = SlideShowFactory.create(input, null, true).use { show ->
        val size = show.pageSize
        Deck(show.slides.count { !it.isHidden }, size.getWidth(), size.getHeight())
    }

    /**
     * Что встроенный перевод нарисует не так, как PowerPoint: по номерам видимых слайдов.
     * Пустой список — всё на слайдах ему по силам.
     */
    internal fun audit(input: File, workDir: File): List<String> = openForPoi(input, workDir).use { show ->
        val found = linkedMapOf<String, MutableSet<Int>>()
        val fonts = sortedSetOf<String>()
        var n = 0
        for (slide in show.slides) {
            if (slide.isHidden) continue
            n++
            for (shape in flatten(slide.shapes)) {
                val what = when {
                    shape is XSLFGraphicFrame && shape.hasChart() -> "диаграммы"
                    shape is XSLFGraphicFrame && shape.hasDiagram() -> "SmartArt"
                    shape is XSLFTable && shape.ctTable.tblPr?.tableStyleId?.let { id -> (show as XMLSlideShow).tableStyles.styles.none { it.styleId == id } } == true -> "оформление таблиц"
                    shape is HSLFFreeformShape && degenerate(shape) -> "фигуры"
                    else -> null
                }
                what?.let { found.getOrPut(it) { sortedSetOf() } += n }
                if (shape is TextShape<*, *>) for (p in shape.textParagraphs) for (r in p.textRuns) {
                    val f = r.fontFamily ?: continue
                    if (f.isNotBlank() && !f.startsWith("+") && !fontAvailable(f)) fonts += f
                }
            }
        }
        buildList {
            found.forEach { (what, slides) -> add("$what (слайд ${slides.joinToString(", ")})") }
            if (fonts.isNotEmpty()) add("шрифты ${fonts.joinToString(", ")} заменены похожими")
        }
    }

    /**
     * Встроенный перевод: каждый видимый слайд рисуется картинкой 2560 точек по ширине
     * и кладётся страницей PDF размера слайда. Скрытые слайды и заметки не попадают.
     */
    internal fun renderWithPoi(input: File, out: File): File {
        openForPoi(input, out.parentFile).use { show ->
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
                        g.setRenderingHint(Drawable.FONT_MAP, fontSubstitutes)
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

    /**
     * Презентация для встроенного перевода. В PPTX добавляются встроенные стили таблиц PowerPoint,
     * если файл ссылается на них, но не хранит (так сохраняют многие программы кроме самого PowerPoint).
     */
    private fun openForPoi(input: File, workDir: File? = null): SlideShow<*, *> {
        val src = if (workDir != null && input.extension.lowercase() in setOf("pptx", "pptm", "ppsx", "potx")) withBuiltInTableStyles(input, File(workDir, "styled.pptx")) else input
        return SlideShowFactory.create(src, null, true)
    }

    private fun flatten(shapes: List<*>): List<Shape<*, *>> = shapes.filterIsInstance<Shape<*, *>>().flatMap { s ->
        if (s is GroupShape<*, *>) listOf(s) + flatten(s.shapes) else listOf(s)
    }

    /** Фигура PPT, записанная формулами без размеров поля (так пишет LibreOffice): POI её не нарисует. */
    private fun degenerate(f: HSLFFreeformShape): Boolean {
        val opt = f.escherOptRecord ?: return false
        fun v(t: EscherPropertyTypes) = (opt.lookup<EscherSimpleProperty>(t))?.propertyValue
        val l = v(EscherPropertyTypes.GEOMETRY__LEFT) ?: 0
        val t = v(EscherPropertyTypes.GEOMETRY__TOP) ?: 0
        val r = v(EscherPropertyTypes.GEOMETRY__RIGHT) ?: return false
        val b = v(EscherPropertyTypes.GEOMETRY__BOTTOM) ?: return false
        return r <= l || b <= t
    }

    companion object {
        private const val RENDER_WIDTH = 2560.0

        private val installedFonts: Set<String> by lazy {
            runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.map { it.lowercase() }.toSet() }.getOrDefault(emptySet())
        }

        private fun fontAvailable(f: String) = f.lowercase() in installedFonts || fontSubstitutes.containsKey(f)

        /**
         * Шрифты Office, которых нет на компьютере, заменяются свободными шрифтами с теми же размерами букв
         * (если они установлены): строки переносятся там же, где в PowerPoint.
         */
        private val fontSubstitutes: Map<String, String> by lazy {
            mapOf(
                "Calibri" to "Carlito", "Cambria" to "Caladea", "Arial" to "Liberation Sans", "Helvetica" to "Liberation Sans",
                "Times New Roman" to "Liberation Serif", "Courier New" to "Liberation Mono", "Arial Narrow" to "Liberation Sans Narrow",
            ).filter { (orig, sub) -> orig.lowercase() !in installedFonts && sub.lowercase() in installedFonts }
        }

        /** Копия PPTX, где в ppt/tableStyles.xml добавлены недостающие встроенные стили. Без изменений — сам файл. */
        internal fun withBuiltInTableStyles(input: File, out: File): File {
            ZipFile(input).use { zip ->
                val entry = zip.getEntry("ppt/tableStyles.xml") ?: return input
                val xml = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                val used = zip.entries().asSequence().filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
                    .flatMap { e -> TABLE_STYLE_ID.findAll(zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }).map { it.groupValues[1] } }
                    .toSet()
                val missing = used.filter { it in BuiltInTableStyles.MEDIUM_2 && !xml.contains("styleId=\"$it\"") }
                if (missing.isEmpty()) return input
                val added = missing.joinToString("") { BuiltInTableStyles.medium2(it) }
                val patched = when {
                    xml.contains("</a:tblStyleLst>") -> xml.replace("</a:tblStyleLst>", "$added</a:tblStyleLst>")
                    else -> SELF_CLOSED.replace(xml) { m -> m.value.removeSuffix("/>") + ">" + added + "</a:tblStyleLst>" }
                }
                out.parentFile.mkdirs()
                ZipOutputStream(out.outputStream().buffered()).use { zo ->
                    for (e in zip.entries()) {
                        zo.putNextEntry(ZipEntry(e.name))
                        if (e.name == entry.name) zo.write(patched.toByteArray(Charsets.UTF_8)) else zip.getInputStream(e).use { it.copyTo(zo) }
                        zo.closeEntry()
                    }
                }
            }
            return out
        }

        private val TABLE_STYLE_ID = Regex("<a:tableStyleId>(\\{[0-9A-Fa-f-]+\\})</a:tableStyleId>")
        private val SELF_CLOSED = Regex("<a:tblStyleLst[^>]*/>")
    }
}

/**
 * Стиль таблицы, который PowerPoint ставит по умолчанию («Средний стиль 2») для каждого цвета темы.
 * PowerPoint хранит такие стили у себя, а не в файле; здесь они описаны заново по их виду:
 * заливка строк цветом темы светлее на 80% и 60% через строку, шапка — полным цветом, белые линии.
 */
internal object BuiltInTableStyles {
    val MEDIUM_2 = mapOf(
        "{073A0DAA-6AF3-43AB-8588-CEC1D06C72B9}" to "dk1",
        "{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}" to "accent1",
        "{21E4AEA4-8DFA-4A89-87EB-49C32662AFE8}" to "accent2",
        "{F5AB1C69-6EDB-4FF4-983F-18BD219EF322}" to "accent3",
        "{00A15C55-8517-42AA-B614-E9B94910E393}" to "accent4",
        "{7DF18680-E054-41AD-8BC1-D1AEF772440D}" to "accent5",
        "{93296810-A885-4BE3-A3E7-6D5BEEA58F35}" to "accent6",
    )

    fun medium2(id: String): String {
        val c = MEDIUM_2.getValue(id)
        fun ln(side: String, w: Int) = "<a:$side><a:ln w=\"$w\" cmpd=\"sng\"><a:solidFill><a:schemeClr val=\"lt1\"/></a:solidFill></a:ln></a:$side>"
        fun fill(tint: Int?) = "<a:fill><a:solidFill><a:schemeClr val=\"$c\">${tint?.let { "<a:tint val=\"$it\"/>" } ?: ""}</a:schemeClr></a:solidFill></a:fill>"
        val text = "<a:fontRef idx=\"minor\"><a:prstClr val=\"black\"/></a:fontRef>"
        val bold = "<a:tcTxStyle b=\"on\">$text<a:schemeClr val=\"lt1\"/></a:tcTxStyle>"
        val inner = listOf("left", "right", "top", "bottom", "insideH", "insideV").joinToString("") { ln(it, 12700) }
        return "<a:tblStyle styleId=\"$id\" styleName=\"Medium Style 2\">" +
            "<a:wholeTbl><a:tcTxStyle>$text<a:schemeClr val=\"dk1\"/></a:tcTxStyle><a:tcStyle><a:tcBdr>$inner</a:tcBdr>${fill(20000)}</a:tcStyle></a:wholeTbl>" +
            "<a:band1H><a:tcStyle><a:tcBdr/>${fill(40000)}</a:tcStyle></a:band1H>" +
            "<a:band2H><a:tcStyle><a:tcBdr/></a:tcStyle></a:band2H>" +
            "<a:band1V><a:tcStyle><a:tcBdr/>${fill(40000)}</a:tcStyle></a:band1V>" +
            "<a:band2V><a:tcStyle><a:tcBdr/></a:tcStyle></a:band2V>" +
            "<a:lastCol>$bold<a:tcStyle><a:tcBdr/>${fill(null)}</a:tcStyle></a:lastCol>" +
            "<a:firstCol>$bold<a:tcStyle><a:tcBdr/>${fill(null)}</a:tcStyle></a:firstCol>" +
            "<a:lastRow>$bold<a:tcStyle><a:tcBdr>${ln("top", 38100)}</a:tcBdr>${fill(null)}</a:tcStyle></a:lastRow>" +
            "<a:firstRow>$bold<a:tcStyle><a:tcBdr>${ln("bottom", 38100)}</a:tcBdr>${fill(null)}</a:tcStyle></a:firstRow>" +
            "</a:tblStyle>"
    }
}
