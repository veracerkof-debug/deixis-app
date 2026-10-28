package app.deixis.core

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocxTest {

    private val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun docx(body: String, styles: String = STYLES, numbering: String? = NUMBERING, media: Map<String, ByteArray> = emptyMap(), rels: String = ""): File {
        val f = File(createTempDirectory("docx").toFile(), "test.docx")
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""")
            put("word/document.xml", """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="$W" xmlns:r="$R"
                xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
                xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><w:body>$body
                <w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="850" w:bottom="1134" w:left="1701"/></w:sectPr></w:body></w:document>""")
            put("word/styles.xml", styles)
            numbering?.let { put("word/numbering.xml", it) }
            put("word/_rels/document.xml.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$rels</Relationships>""")
            media.forEach { (name, bytes) -> z.putNextEntry(ZipEntry("word/media/$name")); z.write(bytes); z.closeEntry() }
        }
        return f
    }

    private val STYLES = """<?xml version="1.0"?><w:styles xmlns:w="$W">
        <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:sz w:val="22"/></w:rPr></w:rPrDefault>
        <w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
        <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
        <w:style w:type="paragraph" w:styleId="Heading1"><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="240" w:after="0"/></w:pPr>
          <w:rPr><w:b/><w:color w:val="2F5496"/><w:sz w:val="32"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="ListParagraph"><w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="720"/><w:contextualSpacing/></w:pPr></w:style>
        <w:style w:type="table" w:styleId="TableGrid"><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4"/><w:left w:val="single" w:sz="4"/>
          <w:bottom w:val="single" w:sz="4"/><w:right w:val="single" w:sz="4"/><w:insideH w:val="single" w:sz="4"/><w:insideV w:val="single" w:sz="4"/></w:tblBorders></w:tblPr></w:style>
        </w:styles>"""

    private val NUMBERING = """<?xml version="1.0"?><w:numbering xmlns:w="$W">
        <w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/>
          <w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl>
          <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%2)"/><w:pPr><w:ind w:left="1440" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>
        <w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="&#xF0B7;"/>
          <w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr><w:rPr><w:rFonts w:ascii="Symbol" w:hAnsi="Symbol"/></w:rPr></w:lvl></w:abstractNum>
        <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
        <w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
        </w:numbering>"""

    private fun p(text: String, pPr: String = "", rPr: String = "") = """<w:p><w:pPr>$pPr</w:pPr><w:r><w:rPr>$rPr</w:rPr><w:t xml:space="preserve">$text</w:t></w:r></w:p>"""
    private fun item(text: String, num: Int, level: Int = 0) = p(text, """<w:pStyle w:val="ListParagraph"/><w:numPr><w:ilvl w:val="$level"/><w:numId w:val="$num"/></w:numPr>""")

    /** Ширина строки — по числу букв: вёрстке нужна только последовательность. */
    private val fake = object : DeckPainter {
        override fun measure(text: String, font: FontSpec) = text.length * font.size * 0.5f
        override fun save() {}
        override fun restore() {}
        override fun transform(degrees: Float, flipH: Boolean, flipV: Boolean, cx: Float, cy: Float) {}
        override fun clip(box: Box) {}
        override fun fill(outline: Outline, paint: DeckPaint, box: Box) {}
        override fun stroke(outline: Outline, line: DeckLine) {}
        override fun image(bytes: ByteArray, key: String, dst: Box, crop: Crop, clip: Outline?) {}
        override fun text(text: String, x: Float, baseline: Float, font: FontSpec, argb: Int, underline: Boolean, strike: Boolean) {}
    }

    private fun paras(doc: WordDoc) = doc.blocks.filterIsInstance<WordBlock.Para>().map { it.para }
    private fun text(p: DeckPara) = p.runs.joinToString("") { it.text }

    @Test
    fun readsStylesRunsAndPage() {
        val doc = Docx.read(docx(p("Глава первая", """<w:pStyle w:val="Heading1"/>""") +
            """<w:p><w:pPr><w:jc w:val="both"/></w:pPr><w:r><w:t xml:space="preserve">Обычный </w:t></w:r><w:r><w:rPr><w:b/><w:i/></w:rPr><w:t>жирный курсив</w:t></w:r></w:p>"""))
        assertEquals(595.3f, doc.page.width, 0.1f)
        assertEquals(85.05f, doc.page.left, 0.01f)
        val (h, body) = paras(doc)
        assertEquals("Глава первая", text(h))
        assertEquals(16f, h.runs.first().font.size)
        assertTrue(h.runs.first().font.bold, "заголовок жирный из стиля")
        assertEquals(0xFF2F5496.toInt(), h.runs.first().argb)
        assertEquals(12f, h.spaceBefore)
        assertEquals(TextAlign.JUSTIFY, body.align)
        assertEquals(listOf("Обычный ", "жирный курсив"), body.runs.map { it.text })
        assertTrue(body.runs[1].font.bold && body.runs[1].font.italic)
        assertEquals(8f, body.spaceAfter)
        assertEquals(259f / 240f, body.lineSpacing, 0.001f)
    }

    @Test
    fun numbersAndBulletsLists() {
        val doc = Docx.read(docx(item("Раз", 1) + item("Два", 1) + item("Подпункт", 1, 1) + item("Три", 1) + item("Маркер", 2)))
        val ps = paras(doc)
        assertEquals(listOf("1.", "2.", "a)", "3.", "•"), ps.map { it.bullet?.text })
        assertEquals(36f, ps[0].marL)
        assertEquals(-18f, ps[0].indent)
        assertEquals(72f, ps[2].marL)
    }

    @Test
    fun longTextFlowsOntoNextPagesAndPageBreakStartsNewPage() {
        val long = (1..60).joinToString(" ") { "строка текста номер $it, достаточно длинная для переноса" }
        val doc = Docx.read(docx(p(long) + p(long) + """<w:p><w:r><w:br w:type="page"/></w:r></w:p>""" + p("После разрыва")))
        val deck = DocxLayout.deck(doc, fake)
        assertTrue(deck.slides.size >= 3, "длинный текст на нескольких страницах: ${deck.slides.size}")
        val last = deck.slides.last().items.filterIsInstance<DeckItem.Shape>()
        assertEquals("После разрыва", last.single().text!!.paras.single().runs.joinToString("") { it.text })
        // Ничего не выходит за нижнее поле.
        val bottom = doc.page.height - doc.page.bottom
        for (s in deck.slides) for (it in s.items) assertTrue(it.box.y + it.box.h <= bottom + 2f, "в пределах страницы ${s.number}: ${it.box}")
        // Продолжение абзаца на новой странице начинается с верхнего поля.
        assertEquals(doc.page.top, deck.slides[1].items.first().box.y, 0.01f)
    }

    @Test
    fun tablesKeepSpansAndBorders() {
        val tbl = """<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/></w:tblPr><w:tblGrid><w:gridCol w:w="3000"/><w:gridCol w:w="3000"/><w:gridCol w:w="3000"/></w:tblGrid>
            <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/><w:shd w:fill="D9E2F3"/></w:tcPr>${p("Шапка")}</w:tc><w:tc>${p("Три")}</w:tc></w:tr>
            <w:tr><w:tc>${p("а")}</w:tc><w:tc>${p("б")}</w:tc><w:tc>${p("в")}</w:tc></w:tr></w:tbl>"""
        val doc = Docx.read(docx(tbl))
        val t = doc.blocks.single() as WordBlock.Table
        assertEquals(listOf(150f, 150f, 150f), t.cols)
        assertEquals(2, t.rows[0][0].span)
        assertEquals(0xFFD9E2F3.toInt(), t.rows[0][0].fill)
        assertTrue(t.borders != null, "рамки из стиля «Сетка таблицы»")
        val laid = DocxLayout.deck(doc, fake).slides.single().items.single() as DeckItem.Table
        // Объединённая ячейка занимает два столбца: за ней — пустое место под второй.
        assertEquals(listOf(false, true, false), laid.cells[0].map { it.merged })
        assertEquals(3, laid.cells[1].size)
    }

    @Test
    fun picturesComeFromTheArchive() {
        val img = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
        val png = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        val drawing = """<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:drawing><wp:inline><wp:extent cx="1270000" cy="635000"/>
            <a:graphic><a:graphicData><a:blip r:embed="rId5"/></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
        val doc = Docx.read(docx(drawing, media = mapOf("image1.png" to png),
            rels = """<Relationship Id="rId5" Type="image" Target="media/image1.png"/>"""))
        val pic = doc.blocks.filterIsInstance<WordBlock.Picture>().single()
        assertEquals("word/media/image1.png", pic.image)
        assertEquals(100f, pic.w, 0.01f)
        assertEquals(TextAlign.CENTER, pic.align)
        assertTrue(doc.images(pic.image)!!.isNotEmpty())
    }

    /**
     * Страницы настоящей кистью — в картинки для глаза (-Ddeixis.docxPng=папка). С -Ddeixis.docxFile=файл —
     * страницы этого документа: так собираются страницы демо-документа для симулятора (tools/demo/make_assets.py).
     */
    @Test
    fun rendersPages() {
        val long = (1..25).joinToString(" ") { "Экспериментальная поэзия ищет новые формы: звук, ритм и графика строки $it." }
        val body = p("Экспериментальная поэзия", """<w:pStyle w:val="Heading1"/><w:jc w:val="center"/>""") +
            p(long, """<w:jc w:val="both"/><w:ind w:firstLine="709"/>""") +
            item("Звукопись", 1) + item("Визуальная поэзия", 1) + item("Заумь", 1, 1) + item("Маркер", 2) +
            """<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/></w:tblPr><w:tblGrid><w:gridCol w:w="2500"/><w:gridCol w:w="6000"/></w:tblGrid>
              <w:tr><w:tc><w:tcPr><w:shd w:fill="D9E2F3"/></w:tcPr>${p("Автор", rPr = "<w:b/>")}</w:tc><w:tc><w:tcPr><w:shd w:fill="D9E2F3"/></w:tcPr>${p("Приём", rPr = "<w:b/>")}</w:tc></w:tr>
              <w:tr><w:tc>${p("Хлебников")}</w:tc><w:tc>${p("Заумь, словотворчество, игра корнями слов")}</w:tc></w:tr></w:tbl>""" +
            p(long)
        val file = System.getProperty("deixis.docxFile")?.let(::File)
        val deck = DocxLayout.deck(if (file != null) Docx.read(file) else Docx.read(docx(body)), AwtPainterHolder.painter)
        assertTrue(deck.slides.size >= 2)
        val dir = System.getProperty("deixis.docxPng")?.let(::File) ?: return
        dir.mkdirs()
        for (s in deck.slides) {
            val k = 1.5f
            val img = BufferedImage((deck.width * k).toInt(), (deck.height * k).toInt(), BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.color = Color.WHITE
            g.fillRect(0, 0, img.width, img.height)
            g.scale(k.toDouble(), k.toDouble())
            DeckRender.paint(deck, s, AwtPainter(g))
            g.dispose()
            ImageIO.write(img, "png", File(dir, "docx-page-${s.number}.png"))
        }
    }

    private object AwtPainterHolder {
        val painter = AwtPainter(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics())
    }
}
