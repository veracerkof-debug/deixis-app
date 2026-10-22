package app.deixis.desktop

import app.deixis.core.Content
import app.deixis.core.FileStore
import app.deixis.core.ImageContent
import app.deixis.core.Mode
import app.deixis.core.PdfContent
import app.deixis.core.TextContent
import app.deixis.core.VideoContent
import app.deixis.core.layoutSlideText
import app.deixis.core.qrMatrix
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Что показывает экран, пока источник не подключился: знак, имя, PIN и адрес. */
data class IdleInfo(val name: String, val pin: String, val address: String, val pairingUri: String?)

/**
 * Рисует кадр приёмника в картинку. Отдельно от окна, поэтому кадр можно проверить в тесте
 * и сохранить снимком. Видео рисует VLC, сюда оно попадает только чёрным фоном.
 */
class SlideRenderer(private val files: FileStore) {

    private val frc = FontRenderContext(null, true, true)
    private var imageCache: Pair<String, BufferedImage>? = null
    private var pdfCache: Pair<String, BufferedImage>? = null

    /** Бросает исключение, если файл нельзя прочитать: вызывающий покажет чёрный экран и сообщит пульту. */
    fun render(width: Int, height: Int, content: Content?, mode: Mode, idle: IdleInfo?): BufferedImage {
        val img = BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = Color.BLACK
            g.fillRect(0, 0, width, height)
            when {
                mode == Mode.BLACK -> Unit
                mode == Mode.LOGO -> drawWordmark(g, width, height, height / 2f, height * 0.13f)
                content == null -> if (idle != null) drawIdle(g, width, height, idle)
                content is TextContent -> drawText(g, width, height, content, clear = mode == Mode.CLEAR)
                mode == Mode.CLEAR -> Unit
                content is ImageContent -> drawFitted(g, width, height, loadImage(content))
                content is PdfContent -> drawFitted(g, width, height, loadPdfPage(content, width, height))
                content is VideoContent -> Unit
            }
        } finally {
            g.dispose()
        }
        return img
    }

    private fun drawIdle(g: Graphics2D, w: Int, h: Int, idle: IdleInfo) {
        drawWordmark(g, w, h, h * 0.36f, h * 0.12f)
        val ui = Fonts.ui
        drawCentered(g, idle.name, ui.deriveFont(h * 0.04f), w, h * 0.50f, Color(255, 255, 255, 220))

        val qrSize = (h * 0.18f).toInt()
        val blockTop = (h * 0.58f).toInt()
        val textX: Int
        if (idle.pairingUri != null) {
            val qrX = w / 2 - qrSize - (h * 0.03f).toInt()
            drawQr(g, idle.pairingUri, qrX, blockTop, qrSize)
            textX = w / 2 + (h * 0.01f).toInt()
        } else {
            textX = w / 2 - (h * 0.15f).toInt()
        }
        g.color = Color(255, 255, 255, 150)
        g.font = ui.deriveFont(h * 0.025f)
        g.drawString("PIN для подключения", textX, blockTop + (h * 0.04f).toInt())
        g.color = Color.WHITE
        g.font = ui.deriveFont(mapOf(TextAttribute.TRACKING to 0.2f)).deriveFont(h * 0.07f)
        g.drawString(idle.pin.toCharArray().joinToString(" "), textX, blockTop + (h * 0.12f).toInt())
        g.color = Color(255, 255, 255, 150)
        g.font = ui.deriveFont(h * 0.025f)
        g.drawString(idle.address, textX, blockTop + (h * 0.17f).toInt())

        drawCentered(g, "Откройте Deixis на телефоне и выберите это устройство · Выход — Ctrl+Q", ui.deriveFont(h * 0.025f), w, h * 0.88f, Color(255, 255, 255, 110))
    }

    private fun drawWordmark(g: Graphics2D, w: Int, h: Int, centerY: Float, size: Float) {
        val font = Fonts.mark.deriveFont(mapOf(TextAttribute.TRACKING to 0.18f)).deriveFont(size)
        val layout = TextLayout("ΔΕΙΞΙΣ", font, frc)
        // Трекинг добавляет пробел и после последней буквы — вычитаем его, чтобы знак стоял ровно по центру.
        val width = layout.advance - size * 0.18f
        g.color = Color.WHITE
        layout.draw(g, (w - width) / 2f, centerY + layout.ascent / 2f - layout.descent / 2f)
    }

    private fun drawQr(g: Graphics2D, text: String, x: Int, y: Int, size: Int) {
        val m = qrMatrix(text)
        val pad = size / 12
        g.color = Color.WHITE
        g.fillRoundRect(x, y, size, size, size / 10, size / 10)
        val cell = (size - 2 * pad).toFloat() / m.size
        g.color = Color.BLACK
        for (r in m.indices) for (c in m[r].indices) if (m[r][c]) {
            g.fillRect((x + pad + c * cell).toInt(), (y + pad + r * cell).toInt(), kotlin.math.ceil(cell).toInt(), kotlin.math.ceil(cell).toInt())
        }
    }

    private fun drawText(g: Graphics2D, w: Int, h: Int, c: TextContent, clear: Boolean) {
        g.color = Color(c.style.background.toInt(), true)
        g.fillRect(0, 0, w, h)
        if (clear) return
        val color = Color(c.style.color.toInt(), true)
        val base = Fonts.slide
        val layout = layoutSlideText(
            c.text, c.ref != null, w.toFloat(), h.toFloat(), c.style.sizePercent,
            measure = { s, size -> base.deriveFont(size).getStringBounds(s, frc).width.toFloat() },
            ascent = { size -> base.deriveFont(size).getLineMetrics("Ag", frc).ascent },
            descent = { size -> base.deriveFont(size).getLineMetrics("Ag", frc).descent },
        )
        val font = base.deriveFont(layout.fontSize)
        for (line in layout.lines) {
            drawStyledLine(g, TextLayout(line.text, font, frc), line.x, line.baseline, color, layout.fontSize, c.style.shadow, c.style.outline)
        }
        c.ref?.let {
            val ref = TextLayout(it, Fonts.ui.deriveFont(layout.refFontSize), frc)
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f)
            g.color = color
            ref.draw(g, layout.refRight - ref.advance, layout.refBaseline)
            g.composite = AlphaComposite.SrcOver
        }
    }

    private fun drawStyledLine(g: Graphics2D, layout: TextLayout, x: Float, y: Float, color: Color, size: Float, shadow: Boolean, outline: Boolean) {
        val shape = layout.getOutline(AffineTransform.getTranslateInstance(x.toDouble(), y.toDouble()))
        if (shadow) {
            // Мягкая тень из нескольких полупрозрачных слоёв.
            val off = size * 0.04f
            for (i in 3 downTo 1) {
                g.color = Color(0, 0, 0, 50)
                val t = AffineTransform.getTranslateInstance(off * 0.5, off * i * 0.6)
                g.fill(t.createTransformedShape(shape))
            }
        }
        if (outline) {
            g.color = Color(0, 0, 0, 230)
            g.stroke = BasicStroke(size * 0.06f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(shape)
        }
        g.color = color
        g.fill(shape)
    }

    private fun drawCentered(g: Graphics2D, text: String, font: Font, w: Int, centerY: Float, color: Color) {
        val layout = TextLayout(text, font, frc)
        g.color = color
        layout.draw(g, (w - layout.advance) / 2f, centerY + layout.ascent / 2f)
    }

    private fun drawFitted(g: Graphics2D, w: Int, h: Int, img: BufferedImage) {
        val scale = minOf(w.toDouble() / img.width, h.toDouble() / img.height)
        val dw = (img.width * scale).toInt()
        val dh = (img.height * scale).toInt()
        g.drawImage(img, (w - dw) / 2, (h - dh) / 2, dw, dh, null)
    }

    private fun loadImage(c: ImageContent): BufferedImage {
        imageCache?.let { (key, img) -> if (key == c.file.sha256) return img }
        val file = files.file(c.file.sha256) ?: error("Нет файла «${c.file.name}»")
        val img = ImageIO.read(file) ?: error("Формат «${c.file.name}» не поддерживается")
        imageCache = c.file.sha256 to img
        return img
    }

    private fun loadPdfPage(c: PdfContent, w: Int, h: Int): BufferedImage {
        val key = "${c.file.sha256}:${c.page}:${w}x$h"
        pdfCache?.let { (k, img) -> if (k == key) return img }
        val file = files.file(c.file.sha256) ?: error("Нет файла «${c.file.name}»")
        val img = Loader.loadPDF(file).use { doc ->
            require(c.page in 0 until doc.numberOfPages) { "В «${c.file.name}» нет страницы ${c.page + 1}" }
            val box = doc.getPage(c.page).cropBox
            val scale = minOf(w / box.width, h / box.height)
            PDFRenderer(doc).renderImage(c.page, scale, ImageType.RGB)
        }
        pdfCache = key to img
        return img
    }
}

object Fonts {
    val mark: Font by lazy { load("/fonts/GFSDidot-Regular.ttf") }
    val slide: Font by lazy { load("/fonts/NotoSans-SemiBold.ttf") }
    val ui: Font by lazy { load("/fonts/NotoSans-SemiBold.ttf") }

    private fun load(path: String): Font =
        Fonts::class.java.getResourceAsStream(path)!!.use { Font.createFont(Font.TRUETYPE_FONT, it) }
}
