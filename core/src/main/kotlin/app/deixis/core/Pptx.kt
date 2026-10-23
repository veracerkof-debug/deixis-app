package app.deixis.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Встроенный перевод PPTX: своё чтение Office Open XML и своя отрисовка, без чужого кода.
 * Модель слайда (фигуры, картинки, текст, таблицы) рисует любая «кисть» — Canvas на Android
 * или Graphics2D в проверке. Единицы — пункты (1/72 дюйма), как в PDF.
 */

const val EMU_PER_PT = 12700.0

data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
}

/** Заливка: цвет ARGB, линейный градиент, картинка. */
sealed interface DeckPaint {
    data class Solid(val argb: Int) : DeckPaint
    data class Linear(val stops: List<Pair<Float, Int>>, val angle: Float) : DeckPaint
    data class Picture(val image: String) : DeckPaint
}

data class DeckLine(val argb: Int, val width: Float, val dash: Boolean = false)

/** Обрезка картинки в долях: слева, сверху, справа, снизу. */
data class Crop(val l: Float = 0f, val t: Float = 0f, val r: Float = 0f, val b: Float = 0f)

data class FontSpec(val family: String, val size: Float, val bold: Boolean, val italic: Boolean)

data class DeckRun(val text: String, val font: FontSpec, val argb: Int, val underline: Boolean = false, val strike: Boolean = false)

enum class TextAlign { LEFT, CENTER, RIGHT, JUSTIFY }
enum class TextAnchor { TOP, MIDDLE, BOTTOM }

data class DeckPara(
    val runs: List<DeckRun>,
    val align: TextAlign = TextAlign.LEFT,
    val marL: Float = 0f,
    val indent: Float = 0f,
    val bullet: DeckRun? = null,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 0f,
    /** Межстрочный: доля (1.0 = одинарный) или пункты, если [lineSpacingPt] задан. */
    val lineSpacing: Float = 1f,
    val lineSpacingPt: Float? = null,
    /** Размер пустого абзаца (по endParaRPr). */
    val emptySize: Float = 18f,
)

data class DeckText(
    val paras: List<DeckPara>,
    val insets: Box,
    val anchor: TextAnchor = TextAnchor.TOP,
    val wrap: Boolean = true,
    val fontScale: Float = 1f,
    val spacingReduction: Float = 0f,
    /** «Сжимать текст при переполнении»: подбирается при отрисовке. */
    val shrink: Boolean = false,
    val vertical: Boolean = false,
)

enum class Geom { CUSTOM, RECT, ROUND_RECT, ELLIPSE, TRIANGLE, RT_TRIANGLE, DIAMOND, PARALLELOGRAM, TRAPEZOID, PENTAGON, HEXAGON, CHEVRON, RIGHT_ARROW, LEFT_ARROW, LINE }

sealed interface DeckItem {
    val box: Box
    val rotation: Float
    val flipH: Boolean
    val flipV: Boolean

    data class Shape(
        override val box: Box, val geom: Geom, val fill: DeckPaint?, val line: DeckLine?, val text: DeckText?,
        override val rotation: Float = 0f, override val flipH: Boolean = false, override val flipV: Boolean = false,
        val corner: Float = 0.16667f,
        /** Произвольная фигура: контуры в долях рамки. */
        val paths: List<List<PathCmd>> = emptyList(),
    ) : DeckItem

    data class Picture(
        override val box: Box, val image: String, val crop: Crop = Crop(), val line: DeckLine? = null,
        override val rotation: Float = 0f, override val flipH: Boolean = false, override val flipV: Boolean = false,
        val geom: Geom = Geom.RECT,
    ) : DeckItem

    data class Table(
        override val box: Box, val cols: List<Float>, val rows: List<Float>, val cells: List<List<TableCell>>,
        override val rotation: Float = 0f, override val flipH: Boolean = false, override val flipV: Boolean = false,
    ) : DeckItem
}

/** Команда контура в долях рамки (0..1). */
sealed interface PathCmd {
    data class Move(val x: Float, val y: Float) : PathCmd
    data class Line(val x: Float, val y: Float) : PathCmd
    data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathCmd
    data object Close : PathCmd
}

data class TableCell(val text: DeckText?, val fill: DeckPaint?, val borders: DeckLine?, val span: Int = 1, val rowSpan: Int = 1, val merged: Boolean = false)

data class DeckSlide(val number: Int, val background: DeckPaint?, val items: List<DeckItem>)

/** Презентация для отрисовки. Картинки читаются по требованию из [images] (архив PPTX или поток PPT). */
class Deck(val width: Float, val height: Float, val slides: List<DeckSlide>, val warnings: List<String>, private val images: (String) -> ByteArray?) {
    private val cache = mutableMapOf<String, ByteArray?>()

    /** Картинка: читается один раз. */
    @Synchronized
    fun image(path: String): ByteArray? = cache.getOrPut(path) { runCatching { images(path) }.getOrNull() }

    companion object {
        /** Картинки из архива PPTX. */
        fun zipImages(zip: File): (String) -> ByteArray? = { path -> ZipFile(zip).use { z -> z.getEntry(path)?.let { e -> z.getInputStream(e).use { it.readBytes() } } } }
    }
}

/** Ошибка чтения презентации с понятным текстом. */
class PptxException(message: String) : Exception(message)

object Pptx {
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun read(file: File): Deck = ZipFile(file).use { zip -> Reader(zip, file).deck() }

    private class Part(val path: String, val root: Element, val rels: Map<String, Rel>)
    private data class Rel(val type: String, val target: String)

    private class Theme(val colors: Map<String, Int>, val major: String, val minor: String)

    /** Мастер или макет: фигуры-заполнители, стили текста, фон. */
    private class Template(val part: Part, val placeholders: List<Element>, val parent: Template?)

    private class Reader(private val zip: ZipFile, private val file: File) {
        private val warnings = linkedSetOf<String>()
        private val parts = mutableMapOf<String, Part?>()
        private lateinit var theme: Theme
        private var clrMap: Map<String, String> = DEFAULT_MAP
        private var defaultText: Element? = null

        fun deck(): Deck {
            val pres = part("ppt/presentation.xml") ?: throw PptxException("Это не презентация PowerPoint (PPTX)")
            val sz = pres.root.child("sldSz")
            val w = (sz?.attr("cx")?.toLongOrNull() ?: 12192000L) / EMU_PER_PT
            val h = (sz?.attr("cy")?.toLongOrNull() ?: 6858000L) / EMU_PER_PT
            defaultText = pres.root.child("defaultTextStyle")
            val ids = pres.root.child("sldIdLst")?.children("sldId").orEmpty()
            val slides = mutableListOf<DeckSlide>()
            var n = 0
            for (id in ids) {
                val rel = pres.rels[id.attrNs("id")] ?: continue
                val sp = part(resolve(pres.path, rel.target)) ?: continue
                n++
                if (sp.root.attr("show") == "0") continue
                slides += slide(sp, n)
            }
            if (slides.isEmpty()) throw PptxException("В презентации нет слайдов для показа")
            return Deck(w.toFloat(), h.toFloat(), slides, warnings.toList(), Deck.zipImages(file))
        }

        // ——— Части архива ———

        private fun part(path: String): Part? = parts.getOrPut(path) {
            val entry = zip.getEntry(path) ?: return@getOrPut null
            val doc = zip.getInputStream(entry).use { factory.newDocumentBuilder().parse(it) }
            val relsPath = path.substringBeforeLast('/') + "/_rels/" + path.substringAfterLast('/') + ".rels"
            val rels = zip.getEntry(relsPath)?.let { re ->
                val r = zip.getInputStream(re).use { factory.newDocumentBuilder().parse(it) }.documentElement
                r.children("Relationship").associate { it.attr("Id").orEmpty() to Rel(it.attr("Type").orEmpty(), it.attr("Target").orEmpty()) }
            }.orEmpty()
            Part(path, doc.documentElement, rels)
        }

        private fun relPart(p: Part, typeSuffix: String): Part? =
            p.rels.values.firstOrNull { it.type.endsWith(typeSuffix) }?.let { part(resolve(p.path, it.target)) }

        // ——— Слайд ———

        private fun slide(sp: Part, number: Int): DeckSlide {
            val layoutPart = relPart(sp, "/slideLayout")
            val masterPart = layoutPart?.let { relPart(it, "/slideMaster") }
            if (masterPart != null && !::theme.isInitialized) {
                theme = relPart(masterPart, "/theme")?.let(::readTheme) ?: Theme(DEFAULT_THEME, "Calibri Light", "Calibri")
            }
            if (!::theme.isInitialized) theme = Theme(DEFAULT_THEME, "Calibri Light", "Calibri")
            clrMap = masterPart?.root?.child("clrMap")?.let { m -> m.attributeMap() }?.takeIf { it.isNotEmpty() } ?: DEFAULT_MAP
            sp.root.child("clrMapOvr")?.child("overrideClrMapping")?.attributeMap()?.takeIf { it.isNotEmpty() }?.let { clrMap = it }

            val master = masterPart?.let { Template(it, placeholdersOf(it), null) }
            val layout = layoutPart?.let { Template(it, placeholdersOf(it), master) }

            val background = listOfNotNull(sp, layoutPart, masterPart).firstNotNullOfOrNull { backgroundOf(it) }
            val items = mutableListOf<DeckItem>()
            // Фигуры мастера и макета (не заполнители) лежат под слайдом, если их не скрыли.
            val showMaster = sp.root.attr("showMasterSp") != "0" && layoutPart?.root?.attr("showMasterSp") != "0"
            if (masterPart != null && showMaster) items += tree(masterPart, null, decorOnly = true)
            if (layoutPart != null && sp.root.attr("showMasterSp") != "0") items += tree(layoutPart, master, decorOnly = true)
            items += tree(sp, layout, decorOnly = false)
            if (sp.root.child("timing") != null) warnings += "анимация и переходы не переносятся: каждый слайд показывается целиком"
            return DeckSlide(number, background, items)
        }

        private fun placeholdersOf(p: Part): List<Element> =
            p.root.child("cSld")?.child("spTree")?.let { all(it, "sp") }.orEmpty().filter { ph(it) != null }

        private fun tree(p: Part, parent: Template?, decorOnly: Boolean): List<DeckItem> {
            val tree = p.root.child("cSld")?.child("spTree") ?: return emptyList()
            val out = mutableListOf<DeckItem>()
            walk(p, tree, parent, Transform.ID, decorOnly, out)
            return out
        }

        /** Обход дерева фигур с учётом групп и альтернативного содержимого. */
        private fun walk(p: Part, node: Element, parent: Template?, t: Transform, decorOnly: Boolean, out: MutableList<DeckItem>) {
            for (el in node.elements()) {
                when (el.localName) {
                    "sp" -> {
                        val ph = ph(el)
                        if (decorOnly && ph != null) continue
                        shape(p, el, parent, t)?.let { out += it }
                    }
                    "pic" -> if (!(decorOnly && ph(el) != null)) picture(p, el, parent, t)?.let { out += it }
                    "grpSp" -> {
                        val x = el.child("grpSpPr")?.child("xfrm")
                        walk(p, el, parent, t.then(groupTransform(x)), decorOnly, out)
                    }
                    "graphicFrame" -> if (!decorOnly || ph(el) == null) frame(p, el, t, out)
                    "AlternateContent" -> {
                        val alt = el.child("Fallback") ?: el.child("Choice")
                        if (alt != null) walk(p, alt, parent, t, decorOnly, out)
                    }
                    "cxnSp" -> connector(el, t)?.let { out += it }
                }
            }
        }

        // ——— Фигура с текстом ———

        private fun shape(p: Part, el: Element, parent: Template?, t: Transform): DeckItem? {
            val spPr = el.child("spPr")
            val ph = ph(el)
            val inherited = ph?.let { findPlaceholder(parent, it) }.orEmpty()
            val xfrm = spPr?.child("xfrm") ?: inherited.firstNotNullOfOrNull { it.child("spPr")?.child("xfrm") } ?: return null
            val (box, rot, fh, fv) = place(xfrm, t)
            val geomEl = spPr?.child("prstGeom") ?: inherited.firstNotNullOfOrNull { it.child("spPr")?.child("prstGeom") }
            val prst = geomEl?.attr("prst")
            val geom = geomOf(prst, spPr?.child("custGeom") != null)
            val corner = geomEl?.child("avLst")?.children("gd")?.firstOrNull()?.attr("fmla")?.substringAfter("val ", "")?.toFloatOrNull()?.div(100000f) ?: 0.16667f
            val style = el.child("style")
            val fill = paintOf(spPr, p) ?: inherited.firstNotNullOfOrNull { paintOf(it.child("spPr"), p) }
                ?: style?.child("fillRef")?.takeIf { (it.attr("idx")?.toIntOrNull() ?: 0) > 0 }?.let { colorOf(it)?.let { c -> DeckPaint.Solid(c) } }
            val line = lineOf(spPr?.child("ln"), style) ?: inherited.firstNotNullOfOrNull { lineOf(it.child("spPr")?.child("ln"), null) }
            val text = el.child("txBody")?.let { text(it, ph, inherited, style, parent) }?.takeIf { tx -> tx.paras.any { it.runs.any { r -> r.text.isNotBlank() } } }
            if (fill == null && line == null && text == null) return null
            val paths = if (geom == Geom.CUSTOM) spPr?.child("custGeom")?.let(::customPaths).orEmpty() else emptyList()
            return DeckItem.Shape(box, if (geom == Geom.CUSTOM && paths.isEmpty()) Geom.RECT else geom, fill, line, text, rot, fh, fv, corner, paths)
        }

        /** Контуры произвольной фигуры: moveTo, lnTo, cubicBezTo, quadBezTo, close. */
        private fun customPaths(cg: Element): List<List<PathCmd>> = cg.child("pathLst")?.children("path").orEmpty().mapNotNull { path ->
            val w = path.attr("w")?.toFloatOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val h = path.attr("h")?.toFloatOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            fun pts(e: Element) = e.children("pt").map { (it.attr("x")?.toFloatOrNull() ?: 0f) / w to (it.attr("y")?.toFloatOrNull() ?: 0f) / h }
            var last = 0f to 0f
            path.elements().mapNotNull { c ->
                val p = pts(c)
                when (c.localName) {
                    "moveTo" -> p.firstOrNull()?.let { last = it; PathCmd.Move(it.first, it.second) }
                    "lnTo" -> p.firstOrNull()?.let { last = it; PathCmd.Line(it.first, it.second) }
                    "cubicBezTo" -> if (p.size == 3) { last = p[2]; PathCmd.Cubic(p[0].first, p[0].second, p[1].first, p[1].second, p[2].first, p[2].second) } else null
                    "quadBezTo" -> if (p.size == 2) {
                        val (x0, y0) = last
                        val c1 = (x0 + 2f / 3 * (p[0].first - x0)) to (y0 + 2f / 3 * (p[0].second - y0))
                        val c2 = (p[1].first + 2f / 3 * (p[0].first - p[1].first)) to (p[1].second + 2f / 3 * (p[0].second - p[1].second))
                        last = p[1]; PathCmd.Cubic(c1.first, c1.second, c2.first, c2.second, p[1].first, p[1].second)
                    } else null
                    "close" -> PathCmd.Close
                    "arcTo" -> { warnings += "дуги в произвольных фигурах упрощаются"; null }
                    else -> null
                }
            }.takeIf { it.isNotEmpty() }
        }

        private fun connector(el: Element, t: Transform): DeckItem? {
            val spPr = el.child("spPr") ?: return null
            val (box, rot, fh, fv) = place(spPr.child("xfrm") ?: return null, t)
            val line = lineOf(spPr.child("ln"), el.child("style")) ?: return null
            return DeckItem.Shape(box, Geom.LINE, null, line, null, rot, fh, fv)
        }

        private fun picture(p: Part, el: Element, parent: Template?, t: Transform): DeckItem? {
            val spPr = el.child("spPr")
            val ph = ph(el)
            val inherited = ph?.let { findPlaceholder(parent, it) }.orEmpty()
            val xfrm = spPr?.child("xfrm") ?: inherited.firstNotNullOfOrNull { it.child("spPr")?.child("xfrm") } ?: return null
            val (box, rot, fh, fv) = place(xfrm, t)
            val blipFill = el.child("blipFill") ?: return null
            val image = blipOf(blipFill, p) ?: return null
            if (el.child("nvPicPr")?.child("nvPr")?.let { it.child("videoFile") ?: it.child("audioFile") } != null) {
                warnings += "видео и звук внутри презентации не играют: показывается их заставка; видео лучше показать отдельным файлом"
            }
            val src = blipFill.child("srcRect")
            val crop = Crop(
                (src?.attr("l")?.toFloatOrNull() ?: 0f) / 100000f, (src?.attr("t")?.toFloatOrNull() ?: 0f) / 100000f,
                (src?.attr("r")?.toFloatOrNull() ?: 0f) / 100000f, (src?.attr("b")?.toFloatOrNull() ?: 0f) / 100000f,
            )
            val geom = geomOf(spPr?.child("prstGeom")?.attr("prst"), false)
            return DeckItem.Picture(box, image, crop, lineOf(spPr?.child("ln"), null), rot, fh, fv, geom)
        }

        private fun blipOf(blipFill: Element, p: Part): String? {
            val blip = blipFill.child("blip") ?: return null
            val id = blip.attrNs("embed") ?: return null
            val target = p.rels[id]?.target ?: return null
            val path = resolve(p.path, target)
            val ext = path.substringAfterLast('.').lowercase()
            if (ext in setOf("emf", "wmf", "tif", "tiff", "svg")) {
                warnings += "картинки ${ext.uppercase()} встроенный перевод не рисует: их лучше заменить на PNG или JPEG"
                return null
            }
            return path
        }

        /** Таблицы, SmartArt (по готовому рисунку внутри файла), диаграммы — предупреждением. */
        private fun frame(p: Part, el: Element, t: Transform, out: MutableList<DeckItem>) {
            val data = el.child("graphic")?.child("graphicData") ?: return
            val (box, rot, fh, fv) = place(el.child("xfrm") ?: return, t)
            val uri = data.attr("uri").orEmpty()
            when {
                uri.endsWith("/table") -> data.child("tbl")?.let { out += table(it, box, rot, fh, fv, p) }
                uri.endsWith("/diagram") -> {
                    val drawing = smartArtDrawing(p, data)
                    if (drawing == null) warnings += "SmartArt без готового рисунка не переносится"
                    else {
                        val tree = drawing.root.child("drawing")?.child("spTree") ?: drawing.root.child("spTree")
                        // Фигуры рисунка SmartArt заданы относительно рамки.
                        if (tree != null) walk(drawing, tree, null, Transform.ID.relative(box), false, out)
                    }
                }
                uri.endsWith("/chart") -> warnings += "диаграммы встроенный перевод не рисует: точный перевод — PowerPoint или LibreOffice на компьютере"
                else -> data.elements().firstOrNull()?.let { if (it.localName == "oleObj") warnings += "вложенные объекты (OLE) не переносятся" }
            }
        }

        private fun smartArtDrawing(p: Part, data: Element): Part? {
            val ids = data.child("relIds") ?: return null
            val dm = ids.attrNs("dm")?.let { p.rels[it] }?.let { part(resolve(p.path, it.target)) } ?: return null
            val ext = all(dm.root, "dataModelExt").firstOrNull()?.attr("relId") ?: return null
            val rel = p.rels[ext] ?: return null
            return part(resolve(p.path, rel.target))
        }

        private fun table(tbl: Element, box: Box, rot: Float, fh: Boolean, fv: Boolean, p: Part): DeckItem {
            val cols = tbl.child("tblGrid")?.children("gridCol").orEmpty().map { (it.attr("w")?.toLongOrNull() ?: 0L) / EMU_PER_PT.toFloat() }
            val rows = tbl.children("tr")
            val heights = rows.map { (it.attr("h")?.toLongOrNull() ?: 0L) / EMU_PER_PT.toFloat() }
            val props = tbl.child("tblPr")
            val styleId = props?.child("tableStyleId")?.textContent?.trim()
            val firstRow = props?.attr("firstRow") == "1"
            val banded = props?.attr("bandRow") == "1"
            // Стиль «Средний 2» (по умолчанию в PowerPoint): шапка — акцент, полосы — светлый оттенок.
            val accent = theme.colors["accent1"] ?: 0xFF4472C4.toInt()
            val styled = styleId == null || styleId in MEDIUM_STYLES
            val cells = rows.mapIndexed { r, tr ->
                tr.children("tc").map { tc ->
                    val tcPr = tc.child("tcPr")
                    val header = firstRow && r == 0
                    val fill = paintOf(tcPr, p) ?: if (!styled) null else when {
                        header -> DeckPaint.Solid(accent)
                        banded && (r - if (firstRow) 1 else 0) % 2 == 0 -> DeckPaint.Solid(tint(accent, 0.4f))
                        else -> DeckPaint.Solid(tint(accent, 0.2f))
                    }
                    val textColor = if (styled && header && paintOf(tcPr, p) == null) 0xFFFFFFFF.toInt() else null
                    val insets = Box(
                        (tcPr?.attr("marL")?.toLongOrNull() ?: 91440L) / EMU_PER_PT.toFloat(), (tcPr?.attr("marT")?.toLongOrNull() ?: 45720L) / EMU_PER_PT.toFloat(),
                        (tcPr?.attr("marR")?.toLongOrNull() ?: 91440L) / EMU_PER_PT.toFloat(), (tcPr?.attr("marB")?.toLongOrNull() ?: 45720L) / EMU_PER_PT.toFloat(),
                    )
                    val anchor = when (tcPr?.attr("anchor")) { "ctr" -> TextAnchor.MIDDLE; "b" -> TextAnchor.BOTTOM; else -> TextAnchor.TOP }
                    val text = tc.child("txBody")?.let { body -> text(body, null, emptyList(), null, null, textColor) }
                        ?.copy(insets = insets, anchor = anchor)
                    TableCell(
                        text, fill, DeckLine(0xFFFFFFFF.toInt(), 1f),
                        span = tc.attr("gridSpan")?.toIntOrNull() ?: 1, rowSpan = tc.attr("rowSpan")?.toIntOrNull() ?: 1,
                        merged = tc.attr("hMerge") == "1" || tc.attr("vMerge") == "1",
                    )
                }
            }
            val total = heights.sum()
            // Строки растут под текст; если в файле высота меньше рамки — растягиваются.
            val scaled = if (total > 0 && total < box.h) heights.map { it * box.h / total } else heights
            return DeckItem.Table(box, cols, scaled, cells, rot, fh, fv)
        }

        // ——— Текст ———

        private class Level(
            val algn: String? = null, val marL: Float? = null, val indent: Float? = null,
            val bullet: Element? = null, val buNone: Boolean? = null, val buFont: String? = null, val buColor: Int? = null, val buSize: Float? = null,
            val spcBef: Spacing? = null, val spcAft: Spacing? = null, val lnSpc: Spacing? = null,
            val size: Float? = null, val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null,
            val color: Int? = null, val font: String? = null,
        ) {
            fun over(base: Level) = Level(
                algn ?: base.algn, marL ?: base.marL, indent ?: base.indent,
                bullet ?: if (buNone == true) null else base.bullet, buNone ?: base.buNone, buFont ?: base.buFont, buColor ?: base.buColor, buSize ?: base.buSize,
                spcBef ?: base.spcBef, spcAft ?: base.spcAft, lnSpc ?: base.lnSpc,
                size ?: base.size, bold ?: base.bold, italic ?: base.italic, underline ?: base.underline, color ?: base.color, font ?: base.font,
            )
        }

        private data class Spacing(val pct: Float? = null, val pt: Float? = null)

        private fun spacing(el: Element?): Spacing? {
            el ?: return null
            el.child("spcPct")?.attr("val")?.toFloatOrNull()?.let { return Spacing(pct = it / 100000f) }
            el.child("spcPts")?.attr("val")?.toFloatOrNull()?.let { return Spacing(pt = it / 100f) }
            return null
        }

        private fun level(pPr: Element?, rPr: Element? = pPr?.child("defRPr")): Level {
            val bu = pPr?.let { p -> p.child("buChar") ?: p.child("buAutoNum") }
            return Level(
                algn = pPr?.attr("algn"), marL = pPr?.attr("marL")?.toFloatOrNull()?.div(EMU_PER_PT.toFloat()), indent = pPr?.attr("indent")?.toFloatOrNull()?.div(EMU_PER_PT.toFloat()),
                bullet = bu, buNone = if (pPr?.child("buNone") != null) true else if (bu != null) false else null,
                buFont = pPr?.child("buFont")?.attr("typeface"), buColor = pPr?.child("buClr")?.let(::colorOf),
                buSize = pPr?.child("buSzPct")?.attr("val")?.toFloatOrNull()?.div(100000f),
                spcBef = spacing(pPr?.child("spcBef")), spcAft = spacing(pPr?.child("spcAft")), lnSpc = spacing(pPr?.child("lnSpc")),
                size = rPr?.attr("sz")?.toFloatOrNull()?.div(100f), bold = rPr?.attr("b")?.let { it == "1" || it == "true" },
                italic = rPr?.attr("i")?.let { it == "1" || it == "true" }, underline = rPr?.attr("u")?.let { it != "none" },
                color = rPr?.let { r -> r.child("solidFill")?.let(::colorOf) }, font = rPr?.child("latin")?.attr("typeface"),
            )
        }

        /** Уровень [n] (1–9) списка стилей. */
        private fun listLevel(lst: Element?, n: Int): Level? = lst?.child("lvl${n}pPr")?.let { level(it) }

        private fun text(
            body: Element, ph: Ph?, inherited: List<Element>, style: Element?, parent: Template?,
            forceColor: Int? = null, forceBold: Boolean = false,
        ): DeckText {
            // Свойства рамки: своя → заполнитель макета → заполнитель мастера.
            val bodies = listOfNotNull(body.child("bodyPr")) + inherited.mapNotNull { it.child("txBody")?.child("bodyPr") }
            fun bp(name: String) = bodies.firstNotNullOfOrNull { it.attr(name) }
            val insets = Box(
                (bp("lIns")?.toFloatOrNull() ?: 91440f) / EMU_PER_PT.toFloat(), (bp("tIns")?.toFloatOrNull() ?: 45720f) / EMU_PER_PT.toFloat(),
                (bp("rIns")?.toFloatOrNull() ?: 91440f) / EMU_PER_PT.toFloat(), (bp("bIns")?.toFloatOrNull() ?: 45720f) / EMU_PER_PT.toFloat(),
            )
            val anchor = when (bp("anchor")) { "ctr" -> TextAnchor.MIDDLE; "b" -> TextAnchor.BOTTOM; else -> TextAnchor.TOP }
            val autofit = bodies.firstNotNullOfOrNull { it.child("normAutofit") }
            val fontScale = autofit?.attr("fontScale")?.toFloatOrNull()?.div(100000f) ?: 1f
            val reduction = autofit?.attr("lnSpcReduction")?.toFloatOrNull()?.div(100000f) ?: 0f
            val vert = bp("vert")?.let { it != "horz" } ?: false
            if (vert) warnings += "вертикальный текст показывается горизонтально"

            // Цепочка стилей уровня: своя → заполнители → мастер → презентация.
            val masterStyles = generateSequence(parent) { it.parent }.lastOrNull()?.part?.root?.child("txStyles")
            val kind = ph?.type ?: if (ph != null) "body" else null
            val masterList = when (kind) {
                null -> defaultText
                "title", "ctrTitle" -> masterStyles?.child("titleStyle")
                "body", "subTitle", "obj" -> masterStyles?.child("bodyStyle")
                else -> masterStyles?.child("otherStyle")
            }
            val chain = listOfNotNull(body.child("lstStyle")) + inherited.mapNotNull { it.child("txBody")?.child("lstStyle") }
            val fontRef = style?.child("fontRef")
            val refColor = fontRef?.let(::colorOf)
            val refFont = when (fontRef?.attr("idx")) { "major" -> theme.major; "minor" -> theme.minor; else -> null }
            val major = kind == "title" || kind == "ctrTitle"

            val paras = mutableListOf<DeckPara>()
            var autoNum = 0
            for (p in body.children("p")) {
                val pPr = p.child("pPr")
                val n = (pPr?.attr("lvl")?.toIntOrNull() ?: 0) + 1
                var lv = Level()
                lv = level(pPr).over(lv)
                for (l in chain) listLevel(l, n)?.let { lv = lv.over(it) }
                if (refColor != null || refFont != null) lv = lv.over(Level(color = refColor, font = refFont))
                listLevel(masterList, n)?.let { lv = lv.over(it) }
                if (masterList !== defaultText) listLevel(defaultText, n)?.let { lv = lv.over(it) }

                val runs = mutableListOf<DeckRun>()
                for (r in p.elements()) {
                    val txt = when (r.localName) {
                        "r", "fld" -> r.child("t")?.textContent.orEmpty()
                        "br" -> "\n"
                        else -> continue
                    }
                    val rl = level(null, r.child("rPr")).over(lv)
                    runs += run(txt, rl, major, fontScale, forceColor, forceBold)
                }
                val endSize = level(null, p.child("endParaRPr")).over(lv).size ?: 18f
                val bulletRun = if (lv.bullet != null && runs.any { it.text.isNotBlank() }) {
                    val b = lv.bullet!!
                    val first = runs.first()
                    val txt = if (b.localName == "buAutoNum") { autoNum++; numbering(b.attr("type"), autoNum + (b.attr("startAt")?.toIntOrNull() ?: 1) - 1) } else b.attr("char") ?: "•"
                    val family = lv.buFont?.let { f -> if (f.startsWith("+")) theme.minor else f } ?: first.font.family
                    DeckRun(bulletChar(txt, family), first.font.copy(family = safeBulletFont(family), size = first.font.size * (lv.buSize ?: 1f)), lv.buColor ?: first.argb)
                } else { if (lv.bullet == null) autoNum = 0; null }
                val align = when (lv.algn) { "ctr" -> TextAlign.CENTER; "r" -> TextAlign.RIGHT; "just", "dist" -> TextAlign.JUSTIFY; else -> TextAlign.LEFT }
                val size = runs.firstOrNull()?.font?.size ?: endSize * fontScale
                paras += DeckPara(
                    runs, align, lv.marL ?: 0f, lv.indent ?: 0f, bulletRun,
                    spaceBefore = lv.spcBef?.let { it.pt ?: ((it.pct ?: 0f) * size * 1.2f) } ?: 0f,
                    spaceAfter = lv.spcAft?.let { it.pt ?: ((it.pct ?: 0f) * size * 1.2f) } ?: 0f,
                    lineSpacing = ((lv.lnSpc?.pct ?: 1f) - reduction).coerceAtLeast(0.5f),
                    lineSpacingPt = lv.lnSpc?.pt,
                    emptySize = endSize * fontScale,
                )
            }
            return DeckText(paras, insets, anchor, bp("wrap") != "none", fontScale, reduction, shrink = autofit != null && autofit.attr("fontScale") == null, vertical = vert)
        }

        private fun run(txt: String, l: Level, major: Boolean, scale: Float, forceColor: Int?, forceBold: Boolean): DeckRun {
            val family = when (val f = l.font) {
                null -> if (major) theme.major else theme.minor
                "+mj-lt", "+mj-ea", "+mj-cs" -> theme.major
                "+mn-lt", "+mn-ea", "+mn-cs" -> theme.minor
                else -> f
            }
            val size = (l.size ?: 18f) * scale
            return DeckRun(
                txt, FontSpec(family, size, l.bold == true || forceBold, l.italic == true),
                forceColor ?: l.color ?: (theme.colors[clrMap["tx1"] ?: "dk1"] ?: 0xFF000000.toInt()), l.underline == true,
            )
        }

        // ——— Цвет, заливка, линия ———

        private fun backgroundOf(p: Part): DeckPaint? {
            val bg = p.root.child("cSld")?.child("bg") ?: return null
            bg.child("bgPr")?.let { return paintOf(it, p) }
            bg.child("bgRef")?.let { ref -> colorOf(ref)?.let { return DeckPaint.Solid(it) } }
            return null
        }

        private fun paintOf(el: Element?, p: Part): DeckPaint? {
            el ?: return null
            if (el.child("noFill") != null) return null
            el.child("solidFill")?.let { f -> return colorOf(f)?.let { DeckPaint.Solid(it) } }
            el.child("gradFill")?.let { g ->
                val stops = g.child("gsLst")?.children("gs").orEmpty().mapNotNull { gs ->
                    val c = colorOf(gs) ?: return@mapNotNull null
                    (gs.attr("pos")?.toFloatOrNull() ?: 0f) / 100000f to c
                }.sortedBy { it.first }
                if (stops.isEmpty()) return null
                if (stops.size == 1) return DeckPaint.Solid(stops[0].second)
                val ang = g.child("lin")?.attr("ang")?.toFloatOrNull()?.div(60000f) ?: 90f
                if (g.child("path") != null) warnings += "радиальные градиенты показываются линейными"
                return DeckPaint.Linear(stops, ang)
            }
            el.child("blipFill")?.let { b -> return blipOf(b, p)?.let { DeckPaint.Picture(it) } }
            el.child("pattFill")?.let { pf -> return (pf.child("fgClr")?.let(::colorOf))?.let { DeckPaint.Solid(it) } }
            return null
        }

        private fun lineOf(ln: Element?, style: Element?): DeckLine? {
            if (ln != null) {
                if (ln.child("noFill") != null) return null
                val w = (ln.attr("w")?.toFloatOrNull() ?: 9525f) / EMU_PER_PT.toFloat()
                val c = ln.child("solidFill")?.let(::colorOf)
                    ?: ln.child("gradFill")?.child("gsLst")?.children("gs")?.firstOrNull()?.let(::colorOf)
                    ?: style?.child("lnRef")?.takeIf { (it.attr("idx")?.toIntOrNull() ?: 0) > 0 }?.let(::colorOf)
                    ?: return null
                val dash = ln.child("prstDash")?.attr("val")?.let { it != "solid" } ?: false
                return DeckLine(c, w, dash)
            }
            val ref = style?.child("lnRef") ?: return null
            val idx = ref.attr("idx")?.toIntOrNull() ?: 0
            if (idx <= 0) return null
            return colorOf(ref)?.let { DeckLine(it, 0.75f * idx) }
        }

        /** Цвет из srgbClr, schemeClr, sysClr, prstClr со сдвигами яркости и прозрачностью. */
        fun colorOf(holder: Element): Int? {
            val c = holder.elements().firstOrNull { it.localName in COLOR_TAGS } ?: return null
            var argb = when (c.localName) {
                "srgbClr" -> c.attr("val")?.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
                "schemeClr" -> {
                    val name = c.attr("val").orEmpty()
                    val key = when (name) { "bg1", "tx1", "bg2", "tx2" -> clrMap[name] ?: DEFAULT_MAP[name]; "phClr" -> "accent1"; else -> name }
                    theme.colors[key]
                }
                "sysClr" -> (c.attr("lastClr") ?: if (c.attr("val") == "window") "FFFFFF" else "000000").toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
                "prstClr" -> PRESET[c.attr("val")]
                "scrgbClr" -> {
                    fun ch(n: String) = ((c.attr(n)?.toFloatOrNull() ?: 0f) / 100000f * 255).roundToInt().coerceIn(0, 255)
                    (0xFF shl 24) or (ch("r") shl 16) or (ch("g") shl 8) or ch("b")
                }
                else -> null
            } ?: return null
            for (m in c.elements()) {
                val v = (m.attr("val")?.toFloatOrNull() ?: 0f) / 100000f
                argb = when (m.localName) {
                    "lumMod" -> hsl(argb) { h, s, l -> Triple(h, s, l * v) }
                    "lumOff" -> hsl(argb) { h, s, l -> Triple(h, s, l + v) }
                    "satMod" -> hsl(argb) { h, s, l -> Triple(h, s * v, l) }
                    "tint" -> linTint(argb, v)
                    "shade" -> linShade(argb, v)
                    "alpha" -> (argb and 0x00FFFFFF) or ((v * 255).roundToInt().coerceIn(0, 255) shl 24)
                    else -> argb
                }
            }
            return argb
        }

        private fun readTheme(p: Part): Theme {
            val el = p.root.child("themeElements")
            val scheme = el?.child("clrScheme")
            val colors = scheme?.elements().orEmpty().associate { e -> e.localName to (colorOfRaw(e) ?: 0) }
            val fonts = el?.child("fontScheme")
            val major = fonts?.child("majorFont")?.child("latin")?.attr("typeface")?.takeIf { it.isNotBlank() } ?: "Calibri Light"
            val minor = fonts?.child("minorFont")?.child("latin")?.attr("typeface")?.takeIf { it.isNotBlank() } ?: "Calibri"
            return Theme(DEFAULT_THEME + colors, major, minor)
        }

        private fun colorOfRaw(e: Element): Int? {
            val c = e.elements().firstOrNull() ?: return null
            return when (c.localName) {
                "srgbClr" -> c.attr("val")?.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
                "sysClr" -> (c.attr("lastClr") ?: "000000").toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
                else -> null
            }
        }

        // ——— Положение и заполнители ———

        private data class Ph(val type: String?, val idx: String?)

        private fun ph(el: Element): Ph? {
            val nv = el.elements().firstOrNull { it.localName.startsWith("nv") } ?: return null
            val p = nv.child("nvPr")?.child("ph") ?: return null
            return Ph(p.attr("type"), p.attr("idx"))
        }

        /** Заполнитель макета и мастера, от которого фигура слайда берёт место и стиль. */
        private fun findPlaceholder(t: Template?, ph: Ph): List<Element> {
            val out = mutableListOf<Element>()
            var cur = t
            var want = ph
            while (cur != null) {
                val match = cur.placeholders.firstOrNull { val o = ph(it)!!; want.idx != null && o.idx == want.idx && (want.type == null || o.type == null || o.type == want.type) }
                    ?: cur.placeholders.firstOrNull { val o = ph(it)!!; (o.type ?: "obj") == normType(want.type) }
                    ?: cur.placeholders.firstOrNull { val o = ph(it)!!; normType(o.type) == normType(want.type) }
                if (match != null) { out += match; want = ph(match)!!.let { Ph(it.type ?: want.type, null) } }
                cur = cur.parent
            }
            return out
        }

        private fun normType(t: String?) = when (t) { null, "obj" -> "body"; "ctrTitle" -> "title"; "subTitle" -> "body"; else -> t }

        private data class Placed(val box: Box, val rot: Float, val flipH: Boolean, val flipV: Boolean)

        private fun place(xfrm: Element, t: Transform): Placed {
            val off = xfrm.child("off")
            val ext = xfrm.child("ext")
            val x = (off?.attr("x")?.toDoubleOrNull() ?: 0.0)
            val y = (off?.attr("y")?.toDoubleOrNull() ?: 0.0)
            val w = (ext?.attr("cx")?.toDoubleOrNull() ?: 0.0)
            val h = (ext?.attr("cy")?.toDoubleOrNull() ?: 0.0)
            val (gx, gy) = t.point(x, y)
            val box = Box((gx / EMU_PER_PT).toFloat(), (gy / EMU_PER_PT).toFloat(), (w * t.sx / EMU_PER_PT).toFloat(), (h * t.sy / EMU_PER_PT).toFloat())
            val rot = (xfrm.attr("rot")?.toFloatOrNull() ?: 0f) / 60000f
            return Placed(box, rot, xfrm.attr("flipH") == "1", xfrm.attr("flipV") == "1")
        }

        private fun groupTransform(x: Element?): Transform {
            x ?: return Transform.ID
            fun v(el: String, a: String) = x.child(el)?.attr(a)?.toDoubleOrNull() ?: 0.0
            val cw = v("chExt", "cx").takeIf { it > 0 } ?: v("ext", "cx")
            val ch = v("chExt", "cy").takeIf { it > 0 } ?: v("ext", "cy")
            val sx = if (cw > 0) v("ext", "cx") / cw else 1.0
            val sy = if (ch > 0) v("ext", "cy") / ch else 1.0
            return Transform(v("off", "x"), v("off", "y"), sx, sy, v("chOff", "x"), v("chOff", "y"))
        }
    }

    /** Сдвиг и масштаб группы: точка ребёнка → точка родителя (в EMU). */
    private data class Transform(val dx: Double, val dy: Double, val sx: Double, val sy: Double, val ox: Double, val oy: Double) {
        fun point(x: Double, y: Double) = Pair(dx + (x - ox) * sx, dy + (y - oy) * sy)
        fun then(inner: Transform) = Transform(
            dx + (inner.dx - ox) * sx, dy + (inner.dy - oy) * sy, sx * inner.sx, sy * inner.sy, inner.ox, inner.oy,
        )
        /** Для рисунка SmartArt: координаты внутри рамки [frame] (в пунктах). */
        fun relative(frame: Box) = Transform(frame.x * EMU_PER_PT, frame.y * EMU_PER_PT, 1.0, 1.0, 0.0, 0.0)
        companion object { val ID = Transform(0.0, 0.0, 1.0, 1.0, 0.0, 0.0) }
    }

    // ——— Помощники ———

    private val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        isExpandEntityReferences = false
    }

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
    private fun Element.attrNs(name: String): String? = getAttributeNS(NS_R, name).takeIf { it.isNotEmpty() } ?: attr("r:$name")
    private fun Element.attributeMap(): Map<String, String> {
        val m = mutableMapOf<String, String>()
        for (i in 0 until attributes.length) attributes.item(i).let { a -> if (!a.nodeName.startsWith("xmlns")) m[a.localName ?: a.nodeName] = a.nodeValue }
        return m
    }
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

    private fun geomOf(prst: String?, custom: Boolean): Geom = when (prst) {
        null -> Geom.RECT
        "rect", "flowChartProcess", "snip1Rect", "frame" -> Geom.RECT
        "roundRect", "round2SameRect", "round1Rect", "flowChartAlternateProcess" -> Geom.ROUND_RECT
        "ellipse", "flowChartConnector", "donut", "pie", "arc", "chord" -> Geom.ELLIPSE
        "triangle", "flowChartExtract" -> Geom.TRIANGLE
        "rtTriangle" -> Geom.RT_TRIANGLE
        "diamond", "flowChartDecision" -> Geom.DIAMOND
        "parallelogram", "flowChartInputOutput" -> Geom.PARALLELOGRAM
        "trapezoid" -> Geom.TRAPEZOID
        "pentagon", "homePlate" -> Geom.PENTAGON
        "hexagon" -> Geom.HEXAGON
        "chevron" -> Geom.CHEVRON
        "rightArrow" -> Geom.RIGHT_ARROW
        "leftArrow" -> Geom.LEFT_ARROW
        "line", "straightConnector1" -> Geom.LINE
        else -> Geom.RECT
    }.let { if (custom && prst == null) Geom.CUSTOM else it }

    private fun numbering(type: String?, n: Int): String = when (type) {
        "alphaLcPeriod" -> "${('a' + (n - 1) % 26)}."
        "alphaUcPeriod" -> "${('A' + (n - 1) % 26)}."
        "alphaLcParenR" -> "${('a' + (n - 1) % 26)})"
        "romanUcPeriod" -> "${roman(n)}."
        "romanLcPeriod" -> "${roman(n).lowercase()}."
        "arabicParenR" -> "$n)"
        "arabicParenBoth" -> "($n)"
        "arabicPlain" -> "$n"
        else -> "$n."
    }

    private fun roman(n: Int): String {
        val v = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        var x = n
        return buildString { for ((k, s) in v) while (x >= k) { append(s); x -= k } }
    }

    /** Значки маркеров из шрифтов Wingdings и Symbol — в обычные символы. */
    private fun bulletChar(c: String, font: String): String = when {
        font.contains("Wingdings", true) -> when (c) { "§" -> "■"; "Ø" -> "➢"; "ü" -> "✓"; "q" -> "❑"; "v" -> "❖"; "n" -> "■"; "l" -> "●"; "Ш" -> "■"; else -> "•" }
        font.contains("Symbol", true) -> when (c) { "·" -> "•"; else -> "•" }
        else -> c
    }

    private fun safeBulletFont(f: String) = if (f.contains("Wingdings", true) || f.contains("Symbol", true)) "Arial" else f

    private val COLOR_TAGS = setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr", "hslClr")
    private val MEDIUM_STYLES = setOf("{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}", "{073A0DAA-6AF3-43AB-8588-CEC1D06C72B9}")
    private val DEFAULT_MAP = mapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2")
    private val DEFAULT_THEME = mapOf(
        "dk1" to 0xFF000000.toInt(), "lt1" to 0xFFFFFFFF.toInt(), "dk2" to 0xFF44546A.toInt(), "lt2" to 0xFFE7E6E6.toInt(),
        "accent1" to 0xFF4472C4.toInt(), "accent2" to 0xFFED7D31.toInt(), "accent3" to 0xFFA5A5A5.toInt(), "accent4" to 0xFFFFC000.toInt(),
        "accent5" to 0xFF5B9BD5.toInt(), "accent6" to 0xFF70AD47.toInt(), "hlink" to 0xFF0563C1.toInt(), "folHlink" to 0xFF954F72.toInt(),
    )
    private val PRESET = mapOf(
        "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(), "red" to 0xFFFF0000.toInt(), "green" to 0xFF008000.toInt(),
        "blue" to 0xFF0000FF.toInt(), "yellow" to 0xFFFFFF00.toInt(), "gray" to 0xFF808080.toInt(), "grey" to 0xFF808080.toInt(),
        "orange" to 0xFFFFA500.toInt(), "darkGray" to 0xFFA9A9A9.toInt(), "lightGray" to 0xFFD3D3D3.toInt(),
    )

    /** Смесь с белым в sRGB: [t] — доля цвета (для полос таблиц). */
    fun tint(argb: Int, t: Float): Int = mapRgb(argb) { c -> (c * t + 255 * (1 - t)) }

    /** Модификаторы DrawingML tint и shade считаются в линейном RGB, как в PowerPoint. */
    private fun linTint(argb: Int, t: Float): Int = mapRgb(argb) { c -> toSrgb(toLinear(c) * t + (1 - t)) }
    private fun linShade(argb: Int, s: Float): Int = mapRgb(argb) { c -> toSrgb(toLinear(c) * s) }
    private fun toLinear(c: Float): Float { val v = c / 255f; return if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() }
    private fun toSrgb(l: Float): Float { val v = l.coerceIn(0f, 1f); return 255f * if (v <= 0.0031308f) v * 12.92f else (1.055f * Math.pow(v.toDouble(), 1 / 2.4).toFloat() - 0.055f) }

    private inline fun mapRgb(argb: Int, f: (Float) -> Float): Int {
        val a = argb ushr 24
        val r = f(((argb shr 16) and 0xFF).toFloat()).roundToInt().coerceIn(0, 255)
        val g = f(((argb shr 8) and 0xFF).toFloat()).roundToInt().coerceIn(0, 255)
        val b = f((argb and 0xFF).toFloat()).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun hsl(argb: Int, f: (Float, Float, Float) -> Triple<Float, Float, Float>): Int {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        val d = mx - mn
        val s = if (d == 0f) 0f else d / (1 - kotlin.math.abs(2 * l - 1))
        val h = when {
            d == 0f -> 0f
            mx == r -> 60 * (((g - b) / d) % 6)
            mx == g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }.let { if (it < 0) it + 360 else it }
        val (h2, s2, l2) = f(h, s, l)
        val ls = l2.coerceIn(0f, 1f)
        val ss = s2.coerceIn(0f, 1f)
        val c = (1 - kotlin.math.abs(2 * ls - 1)) * ss
        val x = c * (1 - kotlin.math.abs((h2 / 60) % 2 - 1))
        val m = ls - c / 2
        val (rr, gg, bb) = when ((h2 / 60).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return (argb and 0xFF000000.toInt()) or (ch(rr) shl 16) or (ch(gg) shl 8) or ch(bb)
    }
}
