package app.deixis.core

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.TextAttribute
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Кисть на Graphics2D: та же отрисовка, что на Android, для проверки на компьютере. */
class AwtPainter(private val g: Graphics2D) : DeckPainter {
    private val stack = ArrayDeque<Pair<AffineTransform, java.awt.Shape?>>()

    private fun font(f: FontSpec): Font {
        val family = when {
            f.family.startsWith("Calibri", true) -> "Carlito"
            f.family.startsWith("Cambria", true) -> "Caladea"
            f.family.contains("Times", true) -> "Liberation Serif"
            f.family.contains("Courier", true) -> "Liberation Mono"
            else -> "Liberation Sans"
        }
        val style = (if (f.bold) Font.BOLD else 0) or (if (f.italic) Font.ITALIC else 0)
        return Font(family, style, 1).deriveFont(f.size)
    }

    override fun measure(text: String, font: FontSpec) = g.getFontMetrics(font(font)).stringWidth(text).toFloat().let {
        font(font).getStringBounds(text, g.fontRenderContext).width.toFloat()
    }
    override fun ascent(font: FontSpec) = font(font).getLineMetrics("Ag", g.fontRenderContext).ascent
    override fun descent(font: FontSpec) = font(font).getLineMetrics("Ag", g.fontRenderContext).descent
    override fun save() { stack.addLast(g.transform to g.clip) }
    override fun restore() { stack.removeLastOrNull()?.let { (t, c) -> g.transform = t; g.clip = c } }
    override fun transform(degrees: Float, flipH: Boolean, flipV: Boolean, cx: Float, cy: Float) {
        g.translate(cx.toDouble(), cy.toDouble())
        g.rotate(Math.toRadians(degrees.toDouble()))
        g.scale(if (flipH) -1.0 else 1.0, if (flipV) -1.0 else 1.0)
        g.translate(-cx.toDouble(), -cy.toDouble())
    }
    override fun clip(box: Box) = g.clip(Rectangle2D.Float(box.x, box.y, box.w, box.h))

    private fun shape(o: Outline): java.awt.Shape = when (o) {
        is Outline.Rect -> Rectangle2D.Float(o.box.x, o.box.y, o.box.w, o.box.h)
        is Outline.RoundRect -> RoundRectangle2D.Float(o.box.x, o.box.y, o.box.w, o.box.h, o.radius * 2, o.radius * 2)
        is Outline.Oval -> Ellipse2D.Float(o.box.x, o.box.y, o.box.w, o.box.h)
        is Outline.Path -> GeneralPath().apply {
            for (c in o.cmds) when (c) {
                is PathCmd.Move -> moveTo(c.x, c.y)
                is PathCmd.Line -> lineTo(c.x, c.y)
                is PathCmd.Cubic -> curveTo(c.x1, c.y1, c.x2, c.y2, c.x, c.y)
                PathCmd.Close -> closePath()
            }
        }
    }

    override fun fill(outline: Outline, paint: DeckPaint, box: Box) {
        g.paint = when (paint) {
            is DeckPaint.Solid -> Color(paint.argb, true)
            is DeckPaint.Linear -> {
                val a = Math.toRadians(paint.angle.toDouble())
                val dx = cos(a).toFloat() * box.w / 2
                val dy = sin(a).toFloat() * box.h / 2
                GradientPaint(box.cx - dx, box.cy - dy, Color(paint.stops.first().second, true), box.cx + dx, box.cy + dy, Color(paint.stops.last().second, true))
            }
            is DeckPaint.Picture -> Color.LIGHT_GRAY
        }
        g.fill(shape(outline))
    }

    override fun stroke(outline: Outline, line: DeckLine) {
        g.paint = Color(line.argb, true)
        g.stroke = BasicStroke(line.width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, if (line.dash) floatArrayOf(line.width * 3, line.width * 2) else null, 0f)
        g.draw(shape(outline))
    }

    override fun image(bytes: ByteArray, key: String, dst: Box, crop: Crop, clip: Outline?) {
        val img = ImageIO.read(bytes.inputStream()) ?: return
        val sx = (crop.l * img.width).toInt()
        val sy = (crop.t * img.height).toInt()
        val ex = (img.width - crop.r * img.width).toInt()
        val ey = (img.height - crop.b * img.height).toInt()
        val old = g.clip
        clip?.let { g.clip(shape(it)) }
        g.drawImage(img, dst.x.toInt(), dst.y.toInt(), (dst.x + dst.w).toInt(), (dst.y + dst.h).toInt(), sx, sy, ex, ey, null)
        g.clip = old
    }

    override fun text(text: String, x: Float, baseline: Float, font: FontSpec, argb: Int, underline: Boolean, strike: Boolean) {
        g.paint = Color(argb, true)
        var f = font(font)
        if (underline || strike) f = f.deriveFont(mapOf(TextAttribute.UNDERLINE to if (underline) TextAttribute.UNDERLINE_ON else -1, TextAttribute.STRIKETHROUGH to strike))
        g.font = f
        g.drawString(text, x, baseline)
    }
}

class PptxTest {
    private fun sample(name: String) = File("../desktop/src/test/resources/$name").takeIf { it.isFile } ?: File("desktop/src/test/resources/$name")

    /** Рисует слайды в PNG. Для сравнения глазами: -Ddeixis.render=<папка>. */
    private fun render(deck: Deck, out: File?, scale: Float = 1.5f): List<BufferedImage> = deck.slides.map { s ->
        val img = BufferedImage((deck.width * scale).toInt(), (deck.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.composite = AlphaComposite.SrcOver
        g.scale(scale.toDouble(), scale.toDouble())
        DeckRender.paint(deck, s, AwtPainter(g))
        g.dispose()
        out?.let { d -> d.mkdirs(); ImageIO.write(img, "png", File(d, "slide-${s.number}.png")) }
        img
    }

    @Test
    fun readsLectureDeck() {
        val deck = Pptx.read(sample("lecture16x9.pptx"))
        assertTrue(deck.slides.isNotEmpty())
        assertEquals(16f / 9f, deck.width / deck.height, 0.02f)
        val texts = deck.slides.flatMap { s -> s.items.filterIsInstance<DeckItem.Shape>().mapNotNull { it.text } }
            .flatMap { it.paras }.joinToString(" ") { p -> p.runs.joinToString("") { it.text } }
        assertTrue(texts.isNotBlank(), "текст слайдов прочитан")
        val out = System.getProperty("deixis.render")?.let { File(it, "lecture") }
        val images = render(deck, out)
        // Слайд не пустой: на картинке есть не только фон.
        val img = images.first()
        val colors = (0 until img.width step 7).flatMap { x -> (0 until img.height step 7).map { y -> img.getRGB(x, y) } }.toSet()
        assertTrue(colors.size > 3)
    }

    /** Ручная проверка любого файла: -Ddeixis.renderFile=файл.pptx -Ddeixis.render=папка. */
    @Test
    fun rendersGivenFile() {
        val f = System.getProperty("deixis.renderFile")?.let { File(it) } ?: return
        val deck = Pptx.read(f)
        render(deck, File(System.getProperty("deixis.render") ?: "build/render"))
        println("Предупреждения: " + deck.warnings.joinToString("; "))
    }

    @Test
    fun rejectsNonPresentation() {
        val f = File.createTempFile("not", ".pptx").apply { writeBytes(byteArrayOf(80, 75, 5, 6) + ByteArray(18)) }
        val e = runCatching { Pptx.read(f) }.exceptionOrNull()
        assertTrue(e != null)
    }
}
