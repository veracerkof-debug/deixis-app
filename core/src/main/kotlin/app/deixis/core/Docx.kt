package app.deixis.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/*
 * Встроенный перевод документа Word (DOCX): своё чтение WordprocessingML и своя вёрстка по страницам,
 * без чужого кода. Абзацы, стили и заголовки, списки, таблицы, картинки, разрывы страниц. Страницы
 * рисуются той же «кистью», что слайды PowerPoint ([DeckRender]), и сохраняются в PDF: телефону и
 * телевизору не нужен компьютер. Колонтитулы, сноски, надписи и обтекание не переносятся.
 */

/** Страница документа в пунктах: размер и поля. */
data class WordPage(val width: Float, val height: Float, val top: Float, val right: Float, val bottom: Float, val left: Float)

/** Кусок документа в порядке чтения. */
sealed interface WordBlock {
    /** Абзац. [style] и [contextual] — чтобы не ставить отступы между абзацами одного стиля (списки). */
    data class Para(
        val para: DeckPara, val indentRight: Float = 0f, val pageBreakBefore: Boolean = false,
        val style: String? = null, val contextual: Boolean = false,
    ) : WordBlock

    /** Картинка в строке: размер в пунктах, выравнивание — как у её абзаца. */
    data class Picture(val image: String, val w: Float, val h: Float, val align: TextAlign) : WordBlock

    /** Таблица: ширины столбцов в пунктах и строки ячеек. */
    data class Table(val cols: List<Float>, val rows: List<List<WordCell>>, val borders: DeckLine?) : WordBlock

    data object PageBreak : WordBlock
}

/** Ячейка таблицы: абзацы, заливка, на сколько столбцов. [continued] — продолжение объединения по вертикали. */
data class WordCell(val paras: List<DeckPara>, val fill: Int? = null, val span: Int = 1, val continued: Boolean = false)

/** Прочитанный документ. Картинки читаются по требованию из [images]. */
class WordDoc(val page: WordPage, val blocks: List<WordBlock>, val warnings: List<String>, val images: (String) -> ByteArray?)

object Docx {

    val FORMATS = setOf("docx", "docm", "dotx", "dotm")

    fun read(file: File): WordDoc {
        val zip = try { ZipFile(file) } catch (e: Exception) { throw PptxException("«${file.name}» не открывается как документ Word") }
        zip.use { z ->
            val docEntry = z.getEntry("word/document.xml") ?: throw PptxException("«${file.name}» не похож на документ Word")
            fun xml(path: String): Element? = z.getEntry(path)?.let { e -> z.getInputStream(e).use { factory.newDocumentBuilder().parse(it) }.documentElement }
            val doc = z.getInputStream(docEntry).use { factory.newDocumentBuilder().parse(it) }.documentElement
            val rels = xml("word/_rels/document.xml.rels")?.children("Relationship")
                ?.associate { it.attr("Id").orEmpty() to resolve("word/document.xml", it.attr("Target").orEmpty()) }.orEmpty()
            val reader = Reader(xml("word/styles.xml"), xml("word/numbering.xml"), xml("word/theme/theme1.xml"), rels)
            val body = doc.child("body") ?: throw PptxException("В «${file.name}» нет текста")
            val blocks = reader.body(body)
            val page = reader.page(body.child("sectPr"))
            return WordDoc(page, blocks, reader.warnings.toList(), Deck.zipImages(file))
        }
    }

    /** Свойства знаков: null — не задано здесь, берётся из стиля ниже. */
    private data class RProps(
        val font: String? = null, val size: Float? = null, val bold: Boolean? = null, val italic: Boolean? = null,
        val underline: Boolean? = null, val strike: Boolean? = null, val color: Int? = null, val caps: Boolean? = null,
        val hidden: Boolean? = null,
    ) {
        fun over(o: RProps?) = if (o == null) this else RProps(
            o.font ?: font, o.size ?: size, o.bold ?: bold, o.italic ?: italic, o.underline ?: underline,
            o.strike ?: strike, o.color ?: color, o.caps ?: caps, o.hidden ?: hidden,
        )
    }

    /** Свойства абзаца в пунктах. */
    private data class PProps(
        val align: TextAlign? = null, val left: Float? = null, val right: Float? = null, val firstLine: Float? = null,
        val before: Float? = null, val after: Float? = null, val line: Float? = null, val linePt: Float? = null,
        val numId: String? = null, val level: Int? = null, val pageBreakBefore: Boolean? = null, val contextual: Boolean? = null,
    ) {
        fun over(o: PProps?) = if (o == null) this else PProps(
            o.align ?: align, o.left ?: left, o.right ?: right, o.firstLine ?: firstLine, o.before ?: before, o.after ?: after,
            if (o.line != null || o.linePt != null) o.line else line, if (o.line != null || o.linePt != null) o.linePt else linePt,
            o.numId ?: numId, o.level ?: level, o.pageBreakBefore ?: pageBreakBefore, o.contextual ?: contextual,
        )
    }

    private class Style(val basedOn: String?, val p: PProps?, val r: RProps?, val table: Element?)

    private class Level(val format: String, val text: String, val start: Int, val p: PProps?, val font: String?)

    private class Reader(stylesXml: Element?, numberingXml: Element?, themeXml: Element?, private val rels: Map<String, String>) {
        val warnings = linkedSetOf<String>()
        private val majorFont: String?
        private val minorFont: String?
        private val styles = mutableMapOf<String, Style>()
        private var defaultR = RProps(font = "Calibri", size = 11f, bold = false, italic = false, underline = false, strike = false, color = 0xFF000000.toInt(), caps = false, hidden = false)
        private var defaultP = PProps(align = TextAlign.LEFT, left = 0f, right = 0f, firstLine = 0f, before = 0f, after = 0f, line = 1f)
        private var normalStyle: String? = null
        private val nums = mutableMapOf<String, Pair<String, Map<Int, Int>>>()
        private val abstracts = mutableMapOf<String, Map<Int, Level>>()
        private val counters = mutableMapOf<String, IntArray>()

        init {
            val fonts = themeXml?.let { all(it, "fontScheme").firstOrNull() }
            majorFont = fonts?.child("majorFont")?.child("latin")?.attr("typeface")?.takeIf { it.isNotBlank() }
            minorFont = fonts?.child("minorFont")?.child("latin")?.attr("typeface")?.takeIf { it.isNotBlank() }
            if (minorFont != null) defaultR = defaultR.copy(font = minorFont)
            stylesXml?.child("docDefaults")?.let { d ->
                d.child("rPrDefault")?.child("rPr")?.let { defaultR = defaultR.over(rProps(it)) }
                d.child("pPrDefault")?.child("pPr")?.let { defaultP = defaultP.over(pProps(it)) }
            }
            stylesXml?.children("style")?.forEach { s ->
                val id = s.attr("w:styleId") ?: s.attr("styleId") ?: return@forEach
                styles[id] = Style(s.child("basedOn")?.wval(), s.child("pPr")?.let(::pProps), s.child("rPr")?.let(::rProps), s.child("tblPr"))
                if ((s.attr("w:type") ?: s.attr("type")) == "paragraph" && (s.attr("w:default") ?: s.attr("default")).let { it == "1" || it == "true" }) normalStyle = id
            }
            numberingXml?.children("abstractNum")?.forEach { a ->
                val id = a.attr("w:abstractNumId") ?: a.attr("abstractNumId") ?: return@forEach
                abstracts[id] = a.children("lvl").associate { l ->
                    val ilvl = (l.attr("w:ilvl") ?: l.attr("ilvl"))?.toIntOrNull() ?: 0
                    ilvl to Level(
                        l.child("numFmt")?.wval() ?: "decimal", l.child("lvlText")?.wval() ?: "", l.child("start")?.wval()?.toIntOrNull() ?: 1,
                        l.child("pPr")?.let(::pProps), l.child("rPr")?.child("rFonts")?.let { it.attr("w:ascii") ?: it.attr("w:hAnsi") },
                    )
                }
            }
            numberingXml?.children("num")?.forEach { n ->
                val id = n.attr("w:numId") ?: n.attr("numId") ?: return@forEach
                val abs = n.child("abstractNumId")?.wval() ?: return@forEach
                val starts = n.children("lvlOverride").mapNotNull { o ->
                    val lvl = (o.attr("w:ilvl") ?: o.attr("ilvl"))?.toIntOrNull() ?: return@mapNotNull null
                    o.child("startOverride")?.wval()?.toIntOrNull()?.let { lvl to it }
                }.toMap()
                nums[id] = abs to starts
            }
        }

        // ——— Страница ———

        fun page(sect: Element?): WordPage {
            val sz = sect?.child("pgSz")
            val mar = sect?.child("pgMar")
            if (sect?.children("headerReference")?.isNotEmpty() == true || sect?.children("footerReference")?.isNotEmpty() == true) {
                warnings += "колонтитулы не переносятся"
            }
            // По умолчанию — A4 с полями 2 см.
            return WordPage(
                twips(sz?.attrW("w")) ?: 595.3f, twips(sz?.attrW("h")) ?: 841.9f,
                twips(mar?.attrW("top"))?.let { kotlin.math.abs(it) } ?: 56.7f, twips(mar?.attrW("right")) ?: 56.7f,
                twips(mar?.attrW("bottom"))?.let { kotlin.math.abs(it) } ?: 56.7f, twips(mar?.attrW("left")) ?: 56.7f,
            )
        }

        // ——— Тело: абзацы и таблицы по порядку ———

        fun body(el: Element): List<WordBlock> {
            val out = mutableListOf<WordBlock>()
            for (c in el.elements()) when (c.localName) {
                "p" -> paragraph(c, out)
                "tbl" -> table(c)?.let { out += it }
                "sdt" -> c.child("sdtContent")?.let { out += body(it) }
                "customXml" -> out += body(c)
            }
            return out
        }

        private fun styleChainP(id: String?): PProps {
            var p = defaultP
            chain(id ?: normalStyle).forEach { s -> p = p.over(s.p) }
            return p
        }

        private fun styleChainR(id: String?): RProps {
            var r = defaultR
            chain(id).forEach { s -> r = r.over(s.r) }
            return r
        }

        /** Стили от базового к заданному. */
        private fun chain(id: String?): List<Style> {
            val out = mutableListOf<Style>()
            var cur = id
            var guard = 0
            while (cur != null && guard++ < 20) {
                val s = styles[cur] ?: break
                out += s
                cur = s.basedOn
            }
            return out.reversed()
        }

        private fun paragraph(p: Element, out: MutableList<WordBlock>, inCell: Boolean = false) {
            val pPr = p.child("pPr")
            val styleId = pPr?.child("pStyle")?.wval() ?: normalStyle
            val direct = pPr?.let(::pProps)
            val styled = styleChainP(styleId)
            // Как в Word: стиль, поверх него — отступы уровня списка, поверх — оформление самого абзаца.
            val num = direct?.numId ?: styled.numId
            val lvlP = num?.let { level(it, direct?.level ?: styled.level ?: 0)?.p }
            val pp = styled.over(lvlP?.copy(numId = null, level = null)).over(direct)
            val baseR = styleChainR(styleId)
            val markR = baseR.over(pPr?.child("rPr")?.let(::rProps))

            val runs = mutableListOf<DeckRun>()
            val pending = mutableListOf<WordBlock>()
            var breakBefore = pp.pageBreakBefore == true
            fun flushText(last: Boolean) {
                if (runs.isEmpty() && !last) return
                out += WordBlock.Para(deckPara(pp, runs.toList(), markR, styleId), pp.right ?: 0f, breakBefore, styleId, pp.contextual == true)
                breakBefore = false
                runs.clear()
            }

            fun run(r: Element) {
                val rp = baseR.over(r.child("rPr")?.child("rStyle")?.wval()?.let { id -> chain(id).fold(RProps()) { a, s -> a.over(s.r) } })
                    .over(r.child("rPr")?.let(::rProps))
                if (rp.hidden == true) return
                val font = FontSpec(rp.font ?: "Calibri", rp.size ?: 11f, rp.bold == true, rp.italic == true)
                fun add(t: String) {
                    if (t.isEmpty()) return
                    val text = if (rp.caps == true) t.uppercase() else t
                    val last = runs.lastOrNull()
                    if (last != null && last.font == font && last.argb == (rp.color ?: BLACK) && last.underline == (rp.underline == true) && last.strike == (rp.strike == true)) {
                        runs[runs.lastIndex] = last.copy(text = last.text + text)
                    } else runs += DeckRun(text, font, rp.color ?: BLACK, rp.underline == true, rp.strike == true)
                }
                for (c in r.elements()) when (c.localName) {
                    "t" -> add(c.textContent)
                    "tab", "ptab" -> add("\t")
                    "br" -> if ((c.attr("w:type") ?: c.attr("type")) == "page") {
                        flushText(last = false); out += WordBlock.PageBreak
                    } else add("\n")
                    "cr" -> add("\n")
                    "noBreakHyphen" -> add("-")
                    "drawing" -> drawing(c, pp)?.let { pic -> if (runs.isEmpty()) out += pic else pending += pic }
                    "AlternateContent" -> {
                        val d = c.child("Choice")?.let { ch -> all(ch, "drawing").firstOrNull() }
                        if (d != null && all(d, "txbx").isEmpty()) drawing(d, pp)?.let { pic -> if (runs.isEmpty()) out += pic else pending += pic }
                        else warnings += "надписи не переносятся"
                    }
                    "pict", "object" -> warnings += "старые рисунки и объекты не переносятся"
                }
            }

            fun walk(e: Element) {
                for (c in e.elements()) when (c.localName) {
                    "r" -> run(c)
                    "hyperlink", "ins", "smartTag", "fldSimple", "customXml", "moveTo", "bdo", "dir" -> walk(c)
                    "sdt" -> c.child("sdtContent")?.let(::walk)
                    "del", "moveFrom", "pPr" -> {}
                }
            }
            walk(p)
            flushText(last = true)
            out += pending
            // Разрыв раздела в абзаце — новая страница (кроме «на текущей странице»).
            pPr?.child("sectPr")?.let { s -> if (s.child("type")?.wval() != "continuous" && !inCell) out += WordBlock.PageBreak }
        }

        private fun deckPara(pp: PProps, runs: List<DeckRun>, mark: RProps, styleId: String?): DeckPara {
            val size = mark.size ?: 11f
            var para = DeckPara(
                runs = runs,
                align = pp.align ?: TextAlign.LEFT,
                marL = pp.left ?: 0f,
                indent = pp.firstLine ?: 0f,
                spaceBefore = pp.before ?: 0f,
                spaceAfter = pp.after ?: 0f,
                lineSpacing = pp.line ?: 1f,
                lineSpacingPt = pp.linePt,
                emptySize = size,
            )
            val numId = pp.numId
            if (numId != null && numId != "0") {
                val label = label(numId, pp.level ?: 0)
                if (label != null) {
                    val text = label.first
                    val left = pp.left ?: 0f
                    val first = pp.firstLine ?: 0f
                    val font = FontSpec(mark.font ?: "Calibri", size, mark.bold == true, mark.italic == true)
                    para = if (first < 0f) para.copy(marL = left, indent = first, bullet = DeckRun(text, font, mark.color ?: BLACK))
                    else para.copy(marL = left, indent = first, runs = listOf(DeckRun("$text ", font, mark.color ?: BLACK)) + runs)
                }
            }
            return para
        }

        private fun level(numId: String, level: Int): Level? = nums[numId]?.let { (abs, _) -> abstracts[abs]?.get(level) }

        /** Номер или маркер списка; счёт ведётся по ходу документа. */
        private fun label(numId: String, level: Int): Pair<String, Level>? {
            val (abs, starts) = nums[numId] ?: return null
            val levels = abstracts[abs] ?: return null
            val lvl = levels[level] ?: return null
            val c = counters.getOrPut(numId) { IntArray(9) { i -> (starts[i] ?: levels[i]?.start ?: 1) - 1 } }
            if (level in 0..8) {
                c[level]++
                for (i in level + 1..8) c[i] = (starts[i] ?: levels[i]?.start ?: 1) - 1
            }
            if (lvl.format == "bullet") return bullet(lvl, level) to lvl
            if (lvl.format == "none") return "" to lvl
            var text = lvl.text
            for (i in 0..8) {
                if ("%${i + 1}" in text) text = text.replace("%${i + 1}", format(levels[i]?.format ?: "decimal", c[i].coerceAtLeast(1)))
            }
            return text to lvl
        }

        private fun bullet(lvl: Level, level: Int): String {
            val t = lvl.text
            val symbolic = lvl.font?.lowercase()?.let { it.contains("symbol") || it.contains("wingdings") } == true
            return when {
                t.isEmpty() -> "•"
                t == "o" -> "◦"
                symbolic || t.first().code in 0xF000..0xF0FF -> when (t.first().code and 0xFF) {
                    0xA7, 0xA8, 0x6E -> "▪"
                    0xD8, 0xFC -> "➢"
                    else -> listOf("•", "◦", "▪")[level % 3]
                }
                else -> t
            }
        }

        private fun format(fmt: String, n: Int): String = when (fmt) {
            "lowerLetter" -> letters(n).lowercase()
            "upperLetter" -> letters(n)
            "lowerRoman" -> roman(n).lowercase()
            "upperRoman" -> roman(n)
            "russianLower" -> russian(n)
            "russianUpper" -> russian(n).uppercase()
            "decimalZero" -> n.toString().padStart(2, '0')
            else -> n.toString()
        }

        private fun letters(n: Int): String { val c = 'A' + (n - 1) % 26; return c.toString().repeat((n - 1) / 26 + 1) }
        private fun russian(n: Int): String { val a = "абвгдежзиклмнопрстуфхцчшщэюя"; return a[(n - 1) % a.length].toString() }
        private fun roman(n: Int): String {
            var x = n
            val sb = StringBuilder()
            for ((v, s) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) {
                while (x >= v) { sb.append(s); x -= v }
            }
            return sb.toString()
        }

        // ——— Картинки ———

        private fun drawing(d: Element, pp: PProps): WordBlock.Picture? {
            val holder = d.child("inline") ?: d.child("anchor")
            if (d.child("anchor") != null) warnings += "плавающие картинки — в потоке текста"
            val ext = holder?.child("extent")
            val blip = all(d, "blip").firstOrNull() ?: return null
            val id = blip.getAttributeNS(NS_R, "embed").takeIf { it.isNotEmpty() } ?: blip.attr("r:embed") ?: return null
            val path = rels[id] ?: return null
            if (path.lowercase().let { it.endsWith(".emf") || it.endsWith(".wmf") }) { warnings += "рисунки EMF и WMF не переносятся"; return null }
            val w = (ext?.attr("cx")?.toDoubleOrNull() ?: return null) / EMU_PER_PT
            val h = (ext.attr("cy")?.toDoubleOrNull() ?: return null) / EMU_PER_PT
            return WordBlock.Picture(path, w.toFloat(), h.toFloat(), pp.align ?: TextAlign.LEFT)
        }

        // ——— Таблицы ———

        private fun table(t: Element): WordBlock.Table? {
            val grid = t.child("tblGrid")?.children("gridCol")?.mapNotNull { twips(it.attrW("w")) }.orEmpty()
            val tblPr = t.child("tblPr")
            val styleBorders = tblPr?.child("tblStyle")?.wval()?.let { id -> chain(id).mapNotNull { it.table?.child("tblBorders") }.lastOrNull() }
            val borders = (tblPr?.child("tblBorders") ?: styleBorders)?.let { b ->
                val lines = b.elements().filter { it.wval().let { v -> v != null && v != "nil" && v != "none" } }
                if (lines.isEmpty()) null else DeckLine(lines.firstNotNullOfOrNull { hex(it.attr("w:color")) } ?: BLACK, ((lines.first().attr("w:sz")?.toFloatOrNull() ?: 4f) / 8f).coerceAtLeast(0.5f))
            }
            val rows = t.children("tr").map { tr ->
                tr.children("tc").map { tc ->
                    val tcPr = tc.child("tcPr")
                    val paras = mutableListOf<WordBlock>()
                    for (c in tc.elements()) when (c.localName) {
                        "p" -> paragraph(c, paras, inCell = true)
                        "tbl" -> warnings += "таблицы внутри таблиц — без рамок"
                        "sdt" -> c.child("sdtContent")?.elements()?.filter { it.localName == "p" }?.forEach { paragraph(it, paras, inCell = true) }
                    }
                    val vMerge = tcPr?.child("vMerge")
                    WordCell(
                        paras.filterIsInstance<WordBlock.Para>().map { it.para },
                        tcPr?.child("shd")?.let { hex(it.attr("w:fill")) },
                        tcPr?.child("gridSpan")?.wval()?.toIntOrNull() ?: 1,
                        continued = vMerge != null && vMerge.wval().let { it == null || it == "continue" },
                    )
                }
            }
            if (rows.isEmpty()) return null
            val cols = grid.ifEmpty { List(rows.maxOf { r -> r.sumOf { it.span } }) { 100f } }
            return WordBlock.Table(cols, rows, borders)
        }

        // ——— Свойства ———

        private fun rProps(r: Element): RProps {
            val fonts = r.child("rFonts")
            val theme = fonts?.attr("w:asciiTheme") ?: fonts?.attr("w:hAnsiTheme")
            val font = fonts?.attr("w:ascii") ?: fonts?.attr("w:hAnsi")
                ?: theme?.let { if (it.startsWith("major")) majorFont else minorFont }
            return RProps(
                font = font,
                size = r.child("sz")?.wval()?.toFloatOrNull()?.div(2f),
                bold = r.child("b")?.toggle(),
                italic = r.child("i")?.toggle(),
                underline = r.child("u")?.wval()?.let { it != "none" } ?: if (r.child("u") != null) true else null,
                strike = (r.child("strike") ?: r.child("dstrike"))?.toggle(),
                color = r.child("color")?.attr("w:val")?.let { if (it == "auto") BLACK else hex(it) },
                caps = (r.child("caps") ?: r.child("smallCaps"))?.toggle(),
                hidden = r.child("vanish")?.toggle(),
            )
        }

        private fun pProps(p: Element): PProps {
            val ind = p.child("ind")
            val sp = p.child("spacing")
            val num = p.child("numPr")
            val lineRule = sp?.attr("w:lineRule") ?: "auto"
            val line = sp?.attr("w:line")?.toFloatOrNull()
            val hanging = twips(ind?.attr("w:hanging"))
            return PProps(
                align = when (p.child("jc")?.wval()) {
                    "center" -> TextAlign.CENTER
                    "right", "end" -> TextAlign.RIGHT
                    "both", "distribute", "lowKashida", "mediumKashida", "highKashida" -> TextAlign.JUSTIFY
                    "left", "start" -> TextAlign.LEFT
                    else -> null
                },
                left = twips(ind?.attr("w:left") ?: ind?.attr("w:start")),
                right = twips(ind?.attr("w:right") ?: ind?.attr("w:end")),
                firstLine = hanging?.let { -it } ?: twips(ind?.attr("w:firstLine")),
                before = if (sp?.attr("w:beforeAutospacing").isTrue()) 14f else twips(sp?.attr("w:before")),
                after = if (sp?.attr("w:afterAutospacing").isTrue()) 14f else twips(sp?.attr("w:after")),
                line = if (line != null && lineRule == "auto") line / 240f else null,
                linePt = if (line != null && lineRule != "auto") line / 20f else null,
                numId = num?.child("numId")?.wval(),
                level = num?.child("ilvl")?.wval()?.toIntOrNull(),
                pageBreakBefore = p.child("pageBreakBefore")?.toggle(),
                contextual = p.child("contextualSpacing")?.toggle(),
            )
        }

        private fun String?.isTrue() = this == "1" || this == "true" || this == "on"
        private fun Element.toggle(): Boolean = wval().let { it == null || it == "1" || it == "true" || it == "on" }
    }

    // ——— Помощники XML ———

    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val BLACK = 0xFF000000.toInt()

    private val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        isExpandEntityReferences = false
    }

    private fun twips(v: String?): Float? = v?.toFloatOrNull()?.div(20f)
    private fun hex(v: String?): Int? = v?.takeIf { it.length == 6 && it != "auto" }?.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }

    private fun Element.elements(): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) { if (n is Element) out += n; n = n.nextSibling }
        return out
    }
    private val Element.localName: String get() = (this as Node).localName ?: nodeName.substringAfter(':')
    private fun Element.child(name: String): Element? = elements().firstOrNull { it.localName == name }
    private fun Element.children(name: String): List<Element> = elements().filter { it.localName == name }
    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { hasAttribute(name) }
    /** Атрибут w:имя (в документах Word у атрибутов есть префикс). */
    private fun Element.attrW(name: String): String? = attr("w:$name") ?: attr(name)
    private fun Element.wval(): String? = attrW("val")
    private fun all(el: Element, name: String): List<Element> {
        val out = mutableListOf<Element>()
        fun rec(e: Element) { for (c in e.elements()) { if (c.localName == name) out += c; rec(c) } }
        rec(el)
        return out
    }

    private fun resolve(from: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = from.substringBeforeLast('/').split('/').toMutableList()
        for (seg in target.split('/')) when (seg) { ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); ".", "" -> {}; else -> parts += seg }
        return parts.joinToString("/")
    }
}

/**
 * Вёрстка документа по страницам: строки абзацев переносятся теми же правилами, что при отрисовке
 * ([DeckRender.lines]), абзац может продолжиться на следующей странице, строка таблицы — нет.
 * Результат — колода страниц: её рисует та же кисть, что слайды PowerPoint.
 */
object DocxLayout {

    fun deck(doc: WordDoc, p: DeckPainter): Deck {
        val page = doc.page
        val contentW = (page.width - page.left - page.right).coerceAtLeast(72f)
        val top = page.top
        val bottom = page.height - page.bottom
        val pages = mutableListOf<MutableList<DeckItem>>(mutableListOf())
        var y = top
        var prev: WordBlock.Para? = null

        fun newPage() { pages += mutableListOf<DeckItem>(); y = top }
        fun place(item: DeckItem) { pages.last() += item }

        for (block in doc.blocks) when (block) {
            is WordBlock.PageBreak -> { newPage(); prev = null }
            is WordBlock.Picture -> {
                var w = block.w
                var h = block.h
                val k = minOf(1f, contentW / w.coerceAtLeast(1f), (bottom - top) / h.coerceAtLeast(1f))
                w *= k; h *= k
                if (y + h > bottom && y > top) newPage()
                val x = page.left + when (block.align) {
                    TextAlign.CENTER -> (contentW - w) / 2
                    TextAlign.RIGHT -> contentW - w
                    else -> 0f
                }
                place(DeckItem.Picture(Box(x, y, w, h), block.image))
                y += h
                prev = null
            }
            is WordBlock.Para -> {
                if (block.pageBreakBefore && y > top) newPage()
                val para = block.para
                // Между абзацами одного стиля с «не добавлять интервал» (списки) — без отступов.
                val tight = block.contextual && prev?.let { it.contextual && it.style == block.style } == true
                val before = if (tight || y <= top) 0f else para.spaceBefore
                if (tight) y -= prev?.para?.spaceAfter ?: 0f
                y += before
                val width = (contentW - block.indentRight).coerceAtLeast(36f)
                val lines = DeckRender.lines(para.copy(spaceBefore = 0f, spaceAfter = 0f), width - 0.5f, p)
                var chunk = mutableListOf<DeckRender.TextLine>()
                var chunkTop = y
                var first = true
                fun emit() {
                    if (chunk.isNotEmpty() && chunk.any { l -> l.runs.any { it.text.isNotBlank() } } || first && para.bullet != null) {
                        val runs = mutableListOf<DeckRun>()
                        chunk.forEachIndexed { i, l ->
                            if (i > 0) {
                                val last = runs.lastOrNull()
                                if (last != null) runs[runs.lastIndex] = last.copy(text = last.text + "\n")
                                else runs += DeckRun("\n", FontSpec("Calibri", para.emptySize, false, false), 0xFF000000.toInt())
                            }
                            runs += l.runs
                        }
                        val chunkPara = if (first) para.copy(runs = runs, spaceBefore = 0f, spaceAfter = 0f)
                        else para.copy(runs = runs, spaceBefore = 0f, spaceAfter = 0f, indent = 0f, bullet = null)
                        val h = chunk.sumOf { it.height.toDouble() }.toFloat()
                        place(DeckItem.Shape(Box(page.left, chunkTop, width, h + 1f), Geom.RECT, null, null, DeckText(listOf(chunkPara), Box(0f, 0f, 0f, 0f), wrap = false)))
                    }
                    first = false
                    chunk = mutableListOf()
                }
                for (line in lines) {
                    if (y + line.height > bottom && y > top) {
                        emit()
                        newPage()
                        chunkTop = y
                    }
                    chunk += line
                    y += line.height
                }
                emit()
                y += para.spaceAfter
                prev = block
            }
            is WordBlock.Table -> {
                prev = null
                val scale = minOf(1f, contentW / block.cols.sum().coerceAtLeast(1f))
                val cols = block.cols.map { it * scale }
                val inset = Box(5.4f, 1.5f, 5.4f, 1.5f)
                var rows = mutableListOf<Float>()
                var cells = mutableListOf<List<TableCell>>()
                var tableTop = y
                fun emitTable() {
                    if (rows.isEmpty()) return
                    place(DeckItem.Table(Box(page.left, tableTop, cols.sum(), rows.sum()), cols, rows.toList(), cells.toList()))
                    rows = mutableListOf(); cells = mutableListOf()
                }
                for (row in block.rows) {
                    var c = 0
                    val out = mutableListOf<TableCell>()
                    var height = 0f
                    for (cell in row) {
                        val span = cell.span.coerceAtLeast(1)
                        val w = cols.drop(c).take(span).sum()
                        val text = if (cell.continued || cell.paras.isEmpty()) null else DeckText(cell.paras, inset)
                        text?.let { height = maxOf(height, DeckRender.textHeight(it, w, p)) }
                        out += TableCell(text, cell.fill?.let { DeckPaint.Solid(it) }, block.borders, span)
                        repeat(span - 1) { out += TableCell(null, null, null, merged = true) }
                        c += span
                    }
                    while (c < cols.size) { out += TableCell(null, null, block.borders); c++ }
                    height = maxOf(height, 14f)
                    if (y + height > bottom && y > top) {
                        emitTable()
                        newPage()
                        tableTop = y
                    }
                    rows += height
                    cells += out
                    y += height
                }
                emitTable()
                y += 6f
            }
        }
        if (pages.size > 1 && pages.last().isEmpty()) pages.removeAt(pages.lastIndex)
        val slides = pages.mapIndexed { i, items -> DeckSlide(i + 1, null, items) }
        return Deck(page.width, page.height, slides, doc.warnings, doc.images)
    }
}
