package app.deixis.core

import kotlin.math.max
import kotlin.math.min

/**
 * Кисть для слайда презентации. Координаты — пункты слайда. Реализации: Canvas на Android (в PDF),
 * Graphics2D в проверке.
 */
interface DeckPainter {
    /** Ширина строки в пунктах. */
    fun measure(text: String, font: FontSpec): Float
    /** Подъём и спуск шрифта (доли кегля): для базовой линии. */
    fun ascent(font: FontSpec): Float = font.size * 0.9f
    fun descent(font: FontSpec): Float = font.size * 0.22f

    fun save()
    fun restore()
    /** Поворот на [degrees] и отражение относительно центра [cx], [cy]. */
    fun transform(degrees: Float, flipH: Boolean, flipV: Boolean, cx: Float, cy: Float)
    fun clip(box: Box)

    fun fill(outline: Outline, paint: DeckPaint, box: Box)
    fun stroke(outline: Outline, line: DeckLine)
    fun image(bytes: ByteArray, key: String, dst: Box, crop: Crop, clip: Outline?)
    fun text(text: String, x: Float, baseline: Float, font: FontSpec, argb: Int, underline: Boolean, strike: Boolean)
}

/** Контур фигуры в пунктах слайда. */
sealed interface Outline {
    data class Rect(val box: Box) : Outline
    data class RoundRect(val box: Box, val radius: Float) : Outline
    data class Oval(val box: Box) : Outline
    /** Многоугольник и кривые: точки в пунктах. */
    data class Path(val cmds: List<PathCmd>, val closed: Boolean = true) : Outline
}

object DeckRender {

    fun paint(deck: Deck, slide: DeckSlide, p: DeckPainter) {
        val page = Box(0f, 0f, deck.width, deck.height)
        p.fill(Outline.Rect(page), DeckPaint.Solid(0xFFFFFFFF.toInt()), page)
        slide.background?.let { bg -> paintFill(deck, p, Outline.Rect(page), bg, page) }
        for (item in slide.items) runCatching { item(deck, item, p) }
    }

    private fun item(deck: Deck, it: DeckItem, p: DeckPainter) {
        val b = it.box
        val turned = it.rotation != 0f || it.flipH || it.flipV
        if (turned) { p.save(); p.transform(it.rotation, it.flipH, it.flipV, b.cx, b.cy) }
        when (it) {
            is DeckItem.Shape -> {
                val o = outline(it)
                it.fill?.let { f -> if (it.geom != Geom.LINE) paintFill(deck, p, o, f, b) }
                it.line?.let { l -> p.stroke(o, l) }
                // Текст не отражается вместе с фигурой: он остаётся читаемым.
                it.text?.let { t ->
                    if (it.flipH || it.flipV) { p.save(); p.transform(0f, it.flipH, it.flipV, b.cx, b.cy) }
                    text(t, textBox(it), p)
                    if (it.flipH || it.flipV) p.restore()
                }
            }
            is DeckItem.Picture -> {
                deck.image(it.image)?.let { bytes -> p.image(bytes, it.image, b, it.crop, if (it.geom == Geom.RECT) null else outline(DeckItem.Shape(b, it.geom, null, null, null))) }
                it.line?.let { l -> p.stroke(Outline.Rect(b), l) }
            }
            is DeckItem.Table -> table(it, p, deck)
        }
        if (turned) p.restore()
    }

    private fun paintFill(deck: Deck, p: DeckPainter, o: Outline, f: DeckPaint, b: Box) {
        when (f) {
            is DeckPaint.Picture -> deck.image(f.image)?.let { p.image(it, f.image, b, Crop(), o) }
            else -> p.fill(o, f, b)
        }
    }

    fun outline(s: DeckItem.Shape): Outline {
        val b = s.box
        fun pts(vararg xy: Float): Outline {
            val c = mutableListOf<PathCmd>()
            for (i in xy.indices step 2) {
                val x = b.x + xy[i] * b.w
                val y = b.y + xy[i + 1] * b.h
                c += if (i == 0) PathCmd.Move(x, y) else PathCmd.Line(x, y)
            }
            c += PathCmd.Close
            return Outline.Path(c)
        }
        return when (s.geom) {
            Geom.RECT -> Outline.Rect(b)
            Geom.ROUND_RECT -> Outline.RoundRect(b, min(b.w, b.h) * s.corner.coerceIn(0f, 0.5f))
            Geom.ELLIPSE -> Outline.Oval(b)
            Geom.TRIANGLE -> pts(0.5f, 0f, 1f, 1f, 0f, 1f)
            Geom.RT_TRIANGLE -> pts(0f, 0f, 1f, 1f, 0f, 1f)
            Geom.DIAMOND -> pts(0.5f, 0f, 1f, 0.5f, 0.5f, 1f, 0f, 0.5f)
            Geom.PARALLELOGRAM -> pts(0.25f, 0f, 1f, 0f, 0.75f, 1f, 0f, 1f)
            Geom.TRAPEZOID -> pts(0.25f, 0f, 0.75f, 0f, 1f, 1f, 0f, 1f)
            Geom.PENTAGON -> pts(0f, 0f, 0.8f, 0f, 1f, 0.5f, 0.8f, 1f, 0f, 1f)
            Geom.HEXAGON -> pts(0.25f, 0f, 0.75f, 0f, 1f, 0.5f, 0.75f, 1f, 0.25f, 1f, 0f, 0.5f)
            Geom.CHEVRON -> pts(0f, 0f, 0.8f, 0f, 1f, 0.5f, 0.8f, 1f, 0f, 1f, 0.2f, 0.5f)
            Geom.RIGHT_ARROW -> pts(0f, 0.25f, 0.7f, 0.25f, 0.7f, 0f, 1f, 0.5f, 0.7f, 1f, 0.7f, 0.75f, 0f, 0.75f)
            Geom.LEFT_ARROW -> pts(1f, 0.25f, 0.3f, 0.25f, 0.3f, 0f, 0f, 0.5f, 0.3f, 1f, 0.3f, 0.75f, 1f, 0.75f)
            Geom.LINE -> Outline.Path(listOf(PathCmd.Move(b.x, b.y), PathCmd.Line(b.x + b.w, b.y + b.h)), closed = false)
            Geom.CUSTOM -> Outline.Path(
                s.paths.flatMap { path ->
                    path.map { c ->
                        when (c) {
                            is PathCmd.Move -> PathCmd.Move(b.x + c.x * b.w, b.y + c.y * b.h)
                            is PathCmd.Line -> PathCmd.Line(b.x + c.x * b.w, b.y + c.y * b.h)
                            is PathCmd.Cubic -> PathCmd.Cubic(b.x + c.x1 * b.w, b.y + c.y1 * b.h, b.x + c.x2 * b.w, b.y + c.y2 * b.h, b.x + c.x * b.w, b.y + c.y * b.h)
                            PathCmd.Close -> PathCmd.Close
                        }
                    }
                },
                closed = s.paths.any { p -> p.lastOrNull() == PathCmd.Close },
            )
        }
    }

    /** Область текста внутри фигуры: у эллипса, треугольника, ромба она меньше рамки. */
    private fun textBox(s: DeckItem.Shape): Box {
        val b = s.box
        fun f(x: Float, y: Float, r: Float, bt: Float) = Box(b.x + x * b.w, b.y + y * b.h, (r - x) * b.w, (bt - y) * b.h)
        return when (s.geom) {
            Geom.ELLIPSE -> f(0.146f, 0.146f, 0.854f, 0.854f)
            Geom.TRIANGLE -> f(0.25f, 0.5f, 0.75f, 1f)
            Geom.RT_TRIANGLE -> f(0.083f, 0.5f, 0.5f, 0.917f)
            Geom.DIAMOND -> f(0.25f, 0.25f, 0.75f, 0.75f)
            Geom.CHEVRON -> f(0.2f, 0f, 0.8f, 1f)
            Geom.PARALLELOGRAM, Geom.TRAPEZOID -> f(0.2f, 0.2f, 0.8f, 0.8f)
            Geom.HEXAGON -> f(0.15f, 0.15f, 0.85f, 0.85f)
            Geom.RIGHT_ARROW -> f(0f, 0.25f, 0.85f, 0.75f)
            Geom.LEFT_ARROW -> f(0.15f, 0.25f, 1f, 0.75f)
            else -> b
        }
    }

    private fun table(t: DeckItem.Table, p: DeckPainter, deck: Deck) {
        val b = t.box
        val colX = t.cols.runningFold(b.x) { acc, w -> acc + w }
        val rowY = t.rows.runningFold(b.y) { acc, h -> acc + h }
        for ((r, row) in t.cells.withIndex()) {
            var c = 0
            for (cell in row) {
                if (c >= t.cols.size || r >= t.rows.size) break
                if (!cell.merged) {
                    val x0 = colX[c]
                    val x1 = colX[min(c + cell.span, t.cols.size)]
                    val y0 = rowY[r]
                    val y1 = rowY[min(r + cell.rowSpan, t.rows.size)]
                    val box = Box(x0, y0, x1 - x0, y1 - y0)
                    cell.fill?.let { paintFill(deck, p, Outline.Rect(box), it, box) }
                    cell.borders?.let { p.stroke(Outline.Rect(box), it) }
                    cell.text?.let { text(it, box, p) }
                }
                c += 1
            }
        }
    }

    // ——— Текст: перенос по словам, маркеры, выравнивание, сжатие при переполнении ———

    private class Piece(val text: String, val run: DeckRun, val width: Float)
    private class Line(val pieces: List<Piece>, val width: Float, val ascent: Float, val descent: Float, val size: Float, val lastOfPara: Boolean)
    private class Laid(val lines: List<Line>, val para: DeckPara, val first: Boolean)

    fun text(t: DeckText, box: Box, p: DeckPainter) {
        val inner = Box(box.x + t.insets.x, box.y + t.insets.y, box.w - t.insets.x - t.insets.w, box.h - t.insets.y - t.insets.h)
        if (inner.w <= 0f) return
        var scale = 1f
        var laid = layout(t, inner, p, scale)
        // «Сжимать текст при переполнении» без готового масштаба: подбираем сами.
        if (t.shrink) {
            // Небольшой выход за рамку PowerPoint терпит: сжимается только явное переполнение.
            if (height(laid, scale) > inner.h * 1.3f) while (height(laid, scale) > inner.h && scale > 0.3f) { scale -= 0.05f; laid = layout(t, inner, p, scale) }
        }
        val total = height(laid, scale)
        var y = when (t.anchor) {
            TextAnchor.TOP -> inner.y
            TextAnchor.MIDDLE -> inner.y + (inner.h - total) / 2
            TextAnchor.BOTTOM -> inner.y + inner.h - total
        }
        for (block in laid) {
            val para = block.para
            if (!block.first) y += para.spaceBefore * scale
            for ((i, line) in block.lines.withIndex()) {
                val lh = lineHeight(line, para, scale)
                val base = y + line.ascent + (lh - (line.ascent + line.descent)) * 0.5f
                // С маркером первая строка начинается с отступа абзаца, маркер — левее (висячий отступ).
                val left = inner.x + para.marL + if (i == 0 && (para.bullet == null || para.indent > 0f)) para.indent else 0f
                val avail = inner.x + inner.w - left
                var x = when (para.align) {
                    TextAlign.CENTER -> left + (avail - line.width) / 2
                    TextAlign.RIGHT -> left + avail - line.width
                    else -> left
                }
                val gaps = if (para.align == TextAlign.JUSTIFY && !line.lastOfPara) line.pieces.count { it.text.isBlank() } else 0
                val extra = if (gaps > 0) (avail - line.width) / gaps else 0f
                if (i == 0) para.bullet?.let { bu ->
                    val bx = inner.x + para.marL + para.indent
                    val f = bu.font.copy(size = bu.font.size * scale)
                    p.text(bu.text, bx, base, f, bu.argb, false, false)
                }
                for (pc in line.pieces) {
                    val f = pc.run.font.copy(size = pc.run.font.size * scale)
                    if (pc.text.isNotBlank()) p.text(pc.text, x, base, f, pc.run.argb, pc.run.underline, pc.run.strike)
                    x += pc.width + if (pc.text.isBlank()) extra else 0f
                }
                y += lh
            }
            y += para.spaceAfter * scale
        }
    }

    private fun lineHeight(l: Line, para: DeckPara, scale: Float): Float =
        para.lineSpacingPt?.let { it * scale } ?: (l.size * 1.2f * para.lineSpacing)

    private fun height(laid: List<Laid>, scale: Float): Float = laid.sumOf { b ->
        val lines = b.lines.sumOf { lineHeight(it, b.para, scale).toDouble() }.toFloat()
        (lines + (if (b.first) 0f else b.para.spaceBefore * scale) + b.para.spaceAfter * scale).toDouble()
    }.toFloat()

    private fun layout(t: DeckText, inner: Box, p: DeckPainter, scale: Float): List<Laid> = t.paras.mapIndexed { i, para ->
        val width = inner.w - para.marL
        val lines = mutableListOf<Line>()
        var cur = mutableListOf<Piece>()
        var w = 0f
        fun flush(last: Boolean) {
            // Пробелы в конце строки не занимают места.
            while (cur.isNotEmpty() && cur.last().text.isBlank() && cur.size > 1) { w -= cur.last().width; cur.removeAt(cur.lastIndex) }
            val fonts = cur.map { it.run.font.copy(size = it.run.font.size * scale) }
            val size = fonts.maxOfOrNull { it.size } ?: para.emptySize * scale
            val asc = fonts.maxOfOrNull { p.ascent(it) } ?: size * 0.9f
            val desc = fonts.maxOfOrNull { p.descent(it) } ?: size * 0.22f
            lines += Line(cur, w, asc, desc, size, last)
            cur = mutableListOf(); w = 0f
        }
        for (run in para.runs) {
            val f = run.font.copy(size = run.font.size * scale)
            for ((k, hard) in run.text.split('\n').withIndex()) {
                if (k > 0) flush(false)
                for (word in tokens(hard)) {
                    val ww = p.measure(word, f)
                    val firstLine = lines.isEmpty()
                    val limit = width - if (firstLine && (para.bullet == null || para.indent > 0f)) para.indent else 0f
                    if (t.wrap && w + ww > limit && cur.isNotEmpty() && word.isNotBlank()) flush(false)
                    if (word.isBlank() && cur.isEmpty() && lines.isNotEmpty()) continue
                    // Слово длиннее строки разбивается по буквам.
                    if (t.wrap && ww > limit && word.length > 1) {
                        var chunk = ""
                        for (ch in word) {
                            val next = chunk + ch
                            if (p.measure(next, f) > limit && chunk.isNotEmpty()) { cur += Piece(chunk, run, p.measure(chunk, f)); w += p.measure(chunk, f); flush(false); chunk = "$ch" }
                            else chunk = next
                        }
                        if (chunk.isNotEmpty()) { val cw = p.measure(chunk, f); cur += Piece(chunk, run, cw); w += cw }
                        continue
                    }
                    cur += Piece(word, run, ww)
                    w += ww
                }
            }
        }
        flush(true)
        Laid(lines, para, i == 0)
    }

    /** Слова и пробелы по отдельности: перенос только между словами. */
    private fun tokens(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in s) {
            if (ch == ' ' || ch == '\t') {
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                out += if (ch == '\t') "    " else " "
            } else sb.append(ch)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }
}
