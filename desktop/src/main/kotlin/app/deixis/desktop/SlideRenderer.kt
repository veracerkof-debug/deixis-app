package app.deixis.desktop

import app.deixis.core.Content
import app.deixis.core.FileStore
import app.deixis.core.ImageContent
import app.deixis.core.Mode
import app.deixis.core.PdfContent
import app.deixis.core.ScriptureContent
import app.deixis.core.layoutScripture
import app.deixis.core.TextContent
import app.deixis.core.TextStyle
import app.deixis.core.VideoContent
import app.deixis.core.style
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
class SlideRenderer(
    private val files: FileStore,
    /** Прозрачность подписи (адреса). Для хромакея — 1: полупрозрачный текст после вырезания зелёного становится зелёным. */
    private val refAlpha: Float = REF_ALPHA,
) {

    private val frc = FontRenderContext(null, true, true)
    private var imageCache: Pair<String, BufferedImage>? = null
    private var pdfCache: Pair<String, BufferedImage>? = null

    /** Бросает исключение, если файл нельзя прочитать: вызывающий покажет чёрный экран и сообщит пульту. */
    fun render(width: Int, height: Int, content: Content?, mode: Mode, idle: IdleInfo?, now: Long = System.currentTimeMillis()): BufferedImage {
        // Слайд поверх видеофона — прозрачный: фон рисует окно из кадров видео, здесь только текст.
        val overVideo = content?.style?.backgroundVideo != null && (mode == Mode.NORMAL || mode == Mode.CLEAR)
        val img = BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), if (overVideo) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        transparentBackground = overVideo
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            if (!overVideo) {
                g.color = Color.BLACK
                g.fillRect(0, 0, width, height)
            }
            when {
                mode == Mode.BLACK -> Unit
                mode == Mode.LOGO -> drawWordmark(g, width, height, height / 2f, app.deixis.core.LogoMark.size(width.toFloat(), height.toFloat()))
                content == null -> if (idle != null) drawIdle(g, width, height, idle)
                content is TextContent -> drawText(g, width, height, content, clear = mode == Mode.CLEAR)
                content is ScriptureContent -> drawScripture(g, width, height, content, clear = mode == Mode.CLEAR)
                content is app.deixis.core.CountdownContent -> drawCountdown(g, width, height, content, now, clear = mode == Mode.CLEAR)
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

    /**
     * Экран ожидания: знак, имя, QR-код и PIN. На широком экране (16:9, 4:3, 32:9) — QR слева от PIN,
     * размеры от высоты 16:9-кадра внутри экрана; на квадратном и вертикальном — всё столбиком по ширине.
     * Строки, которые не помещаются по ширине, уменьшаются, а не обрезаются.
     */
    private fun drawIdle(g: Graphics2D, w: Int, h: Int, idle: IdleInfo) {
        val hint = if (idle.pairingUri != null) "Наведите камеру телефона на QR-код — откроется пульт, приложение не нужно"
        else "Подключите компьютер к сети Wi-Fi, чтобы появился QR-код для телефона"
        val maxW = w * 0.9f
        if (w < h * app.deixis.core.SIDE_BY_SIDE_MIN_ASPECT) { drawIdleTall(g, w, h, idle, hint, maxW); return }
        // Кадр 16:9 внутри экрана: на 32:9 он по высоте, на 4:3 — по ширине.
        val u = minOf(h.toFloat(), w * 9f / 16f)
        val top = (h - u) / 2f
        drawWordmark(g, w, h, top + u * 0.36f, fitMark(u * 0.12f, maxW))
        val ui = Fonts.ui
        drawCentered(g, idle.name, fitFont(idle.name, ui.deriveFont(u * 0.04f), maxW), w, top + u * 0.50f, Color(255, 255, 255, 220))

        val qrSize = (u * 0.18f).toInt()
        val blockTop = (top + u * 0.58f).toInt()
        val textX: Int
        if (idle.pairingUri != null) {
            val qrX = w / 2 - qrSize - (u * 0.03f).toInt()
            drawQr(g, idle.pairingUri, qrX, blockTop, qrSize)
            textX = w / 2 + (u * 0.01f).toInt()
        } else {
            textX = w / 2 - (u * 0.15f).toInt()
        }
        g.color = Color(255, 255, 255, 150)
        g.font = ui.deriveFont(u * 0.025f)
        g.drawString("PIN для подключения", textX, blockTop + (u * 0.04f).toInt())
        g.color = Color.WHITE
        g.font = ui.deriveFont(mapOf(TextAttribute.TRACKING to 0.2f)).deriveFont(u * 0.07f)
        g.drawString(idle.pin.toCharArray().joinToString(" "), textX, blockTop + (u * 0.12f).toInt())
        g.color = Color(255, 255, 255, 150)
        g.font = ui.deriveFont(u * 0.025f)
        g.drawString(idle.address, textX, blockTop + (u * 0.17f).toInt())
        drawCentered(g, hint, fitFont(hint, ui.deriveFont(u * 0.025f), maxW), w, top + u * 0.88f, Color(255, 255, 255, 110))
    }

    /** Вертикальный и квадратный экран: знак, имя, QR, PIN, адрес и подсказка столбиком по центру. */
    private fun drawIdleTall(g: Graphics2D, w: Int, h: Int, idle: IdleInfo, hint: String, maxW: Float) {
        val u = minOf(w.toFloat(), h * 9f / 16f)
        val ui = Fonts.ui
        val qr = if (idle.pairingUri != null) (u * 0.5f).toInt() else 0
        // Высота столбика в долях u: знак, имя, QR, подпись PIN, PIN, адрес.
        val total = u * 0.22f + u * 0.1f + qr + u * 0.08f + u * 0.14f + u * 0.08f
        var y = (h - total) / 2f
        drawWordmark(g, w, h, y + u * 0.08f, fitMark(u * 0.16f, maxW))
        y += u * 0.22f
        drawCentered(g, idle.name, fitFont(idle.name, ui.deriveFont(u * 0.06f), maxW), w, y, Color(255, 255, 255, 220))
        y += u * 0.1f
        if (idle.pairingUri != null) { drawQr(g, idle.pairingUri, (w - qr) / 2, y.toInt(), qr); y += qr + u * 0.06f }
        drawCentered(g, "PIN для подключения", ui.deriveFont(u * 0.04f), w, y, Color(255, 255, 255, 150))
        y += u * 0.12f
        val pin = idle.pin.toCharArray().joinToString(" ")
        drawCentered(g, pin, ui.deriveFont(mapOf(TextAttribute.TRACKING to 0.2f)).deriveFont(u * 0.11f), w, y, Color.WHITE)
        y += u * 0.1f
        drawCentered(g, idle.address, fitFont(idle.address, ui.deriveFont(u * 0.04f), maxW), w, y, Color(255, 255, 255, 150))
        // Подсказка внизу экрана, в две строки, если не помещается.
        val hf = ui.deriveFont(u * 0.035f)
        val lines = app.deixis.core.wrapLine(hint, maxW) { TextLayout(it, hf, frc).advance }
        lines.forEachIndexed { i, line -> drawCentered(g, line, hf, w, h - u * 0.08f - (lines.size - 1 - i) * u * 0.05f, Color(255, 255, 255, 110)) }
    }

    /** Шрифт уменьшается, пока строка не уместится в [maxW]. */
    private fun fitFont(text: String, font: Font, maxW: Float): Font {
        val adv = TextLayout(text.ifEmpty { " " }, font, frc).advance
        return if (adv <= maxW) font else font.deriveFont(font.size2D * maxW / adv)
    }

    /** Кегль знака ΔΕΙΞΙΣ, при котором он умещается в [maxW]. */
    private fun fitMark(size: Float, maxW: Float): Float = minOf(size, maxW / app.deixis.core.LogoMark.WIDTH_EM)

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

    /** Фон оформления: цвет, поверх — картинка во весь экран с обрезкой краёв, как фон темы. */
    private var transparentBackground = false

    private fun drawBackground(g: Graphics2D, w: Int, h: Int, style: TextStyle) {
        if (transparentBackground) return
        g.color = Color(style.background.toInt(), true)
        g.fillRect(0, 0, w, h)
        val ref = style.backgroundImage ?: return
        val img = runCatching { loadCached(ref.sha256, ref.name) }.getOrNull() ?: return
        val scale = maxOf(w.toDouble() / img.width, h.toDouble() / img.height)
        val dw = (img.width * scale).toInt()
        val dh = (img.height * scale).toInt()
        g.drawImage(img, (w - dw) / 2, (h - dh) / 2, dw, dh, null)
    }

    private fun drawBox(g: Graphics2D, style: TextStyle, x: Float, y: Float, w: Float, h: Float, size: Float) {
        val box = style.box ?: return
        g.color = Color(box.toInt(), true)
        g.fill(java.awt.geom.RoundRectangle2D.Float(x, y, w, h, size * 0.2f, size * 0.2f))
    }

    private fun drawText(g: Graphics2D, w: Int, h: Int, c: TextContent, clear: Boolean) {
        drawBackground(g, w, h, c.style)
        if (clear) return
        val color = Color(c.style.color.toInt(), true)
        val base = Fonts.slide
        val layout = layoutSlideText(
            c.text, c.ref, w.toFloat(), h.toFloat(), c.style,
            measure = { s, size -> base.deriveFont(size).getStringBounds(s, frc).width.toFloat() },
            ascent = { size -> base.deriveFont(size).getLineMetrics("Ag", frc).ascent },
            descent = { size -> base.deriveFont(size).getLineMetrics("Ag", frc).descent },
        )
        val font = base.deriveFont(layout.fontSize)
        layout.block?.let { b -> drawBox(g, c.style, b.left, b.top, b.width, b.height, layout.fontSize) }
        for (line in layout.lines) {
            drawStyledLine(g, TextLayout(line.text, font, frc), line.x, line.baseline, color, layout.fontSize, c.style)
        }
        layout.ref?.let {
            val ref = TextLayout(it, Fonts.ui.deriveFont(layout.refFontSize), frc)
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, refAlpha)
            g.color = color
            ref.draw(g, layout.refRight - ref.advance, layout.refBaseline)
            g.composite = AlphaComposite.SrcOver
        }
    }

    private fun drawScripture(g: Graphics2D, w: Int, h: Int, c: ScriptureContent, clear: Boolean) {
        drawBackground(g, w, h, c.style)
        if (clear) return
        val color = Color(c.style.color.toInt(), true)
        val base = Fonts.slide
        val lines = layoutScripture(
            c, w.toFloat(), h.toFloat(),
            measure = { s, size -> base.deriveFont(size).getStringBounds(s, frc).width.toFloat() },
            ascent = { size -> base.deriveFont(size).getLineMetrics("Ag", frc).ascent },
            descent = { size -> base.deriveFont(size).getLineMetrics("Ag", frc).descent },
        )
        if (c.style.box != null && lines.isNotEmpty()) {
            val size = lines.filter { !it.ref }.maxOfOrNull { it.size } ?: lines.first().size
            val left = lines.minOf { it.x }
            val right = lines.maxOf { it.x + base.deriveFont(it.size).getStringBounds(it.text, frc).width.toFloat() }
            val top = lines.minOf { it.baseline - it.size }
            val bottom = lines.maxOf { it.baseline + it.size * 0.3f }
            val pad = size * 0.35f
            drawBox(g, c.style, left - pad, top - pad * 0.6f, right - left + 2 * pad, bottom - top + 1.2f * pad, size)
        }
        for (l in lines) {
            val layout = TextLayout(l.text, base.deriveFont(l.size), frc)
            if (l.ref) {
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, refAlpha)
                g.color = color
                layout.draw(g, l.x, l.baseline)
                g.composite = AlphaComposite.SrcOver
            } else {
                drawStyledLine(g, layout, l.x, l.baseline, color, l.size, c.style)
            }
        }
    }

    /** Обратный отсчёт: цифры в ячейках одной ширины (не дрожат), подпись сверху или снизу, после нуля — свой цвет. */
    private fun drawCountdown(g: Graphics2D, w: Int, h: Int, c: app.deixis.core.CountdownContent, now: Long, clear: Boolean) {
        drawBackground(g, w, h, c.style)
        val face = app.deixis.core.countdownFace(c, now)
        if (clear || face.hidden) return
        val base = if (c.font == "serif") Fonts.mark else Fonts.slide
        val text = if (c.style.caps) face.text.uppercase() else face.text
        val l = app.deixis.core.layoutCountdown(
            c, text, w.toFloat(), h.toFloat(),
            measure = { s, size -> base.deriveFont(size).getStringBounds(s, frc).width.toFloat() },
            ascent = { size -> base.deriveFont(size).getLineMetrics("0", frc).ascent },
            descent = { size -> base.deriveFont(size).getLineMetrics("0", frc).descent },
        )
        val color = Color((if (face.overtime) c.overtimeColor else c.style.color).toInt(), true)
        val font = base.deriveFont(l.size)
        for ((ch, x) in l.cells) drawStyledLine(g, TextLayout(ch, font, frc), x, l.baseline, color, l.size, c.style)
        c.label?.takeIf { it.isNotBlank() }?.let { label ->
            val text2 = if (c.style.caps) label.uppercase() else label
            drawStyledLine(g, TextLayout(text2, Fonts.slide.deriveFont(l.labelSize), frc), l.labelX, l.labelBaseline, Color(c.style.color.toInt(), true), l.labelSize, c.style)
        }
    }

    private fun drawStyledLine(g: Graphics2D, layout: TextLayout, x: Float, y: Float, color: Color, size: Float, style: TextStyle) {
        val shape = layout.getOutline(AffineTransform.getTranslateInstance(x.toDouble(), y.toDouble()))
        if (style.shadow) {
            // Мягкая тень из нескольких полупрозрачных слоёв цвета тени оформления.
            val off = size * 0.04f
            val sc = Color(style.shadowColor.toInt(), true)
            for (i in 3 downTo 1) {
                g.color = Color(sc.red, sc.green, sc.blue, (sc.alpha * 0.28f).toInt().coerceIn(0, 255))
                val t = AffineTransform.getTranslateInstance(off * 0.5, off * i * 0.6)
                g.fill(t.createTransformedShape(shape))
            }
        }
        if (style.outline) {
            g.color = Color(style.outlineColor.toInt(), true)
            g.stroke = BasicStroke(size * style.outlineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
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

    private fun loadImage(c: ImageContent): BufferedImage = loadCached(c.file.sha256, c.file.name)

    private val backgrounds = object : LinkedHashMap<String, BufferedImage>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage>?) = size > 3
    }

    private fun loadCached(sha: String, name: String): BufferedImage {
        imageCache?.let { (key, img) -> if (key == sha) return img }
        synchronized(backgrounds) { backgrounds[sha] }?.let { return it }
        val file = files.file(sha) ?: error("Нет файла «$name»")
        val img = ImageIO.read(file) ?: error("Формат «$name» не поддерживается")
        imageCache = sha to img
        synchronized(backgrounds) { backgrounds[sha] = img }
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

/** Подпись чуть тише основного текста. */
const val REF_ALPHA = 0.75f

object Fonts {
    val mark: Font by lazy { load("/web/GFSDidot-Regular.ttf") }
    val slide: Font by lazy { load("/web/NotoSans-SemiBold.ttf") }
    val ui: Font by lazy { load("/web/NotoSans-SemiBold.ttf") }

    private fun load(path: String): Font =
        Fonts::class.java.getResourceAsStream(path)!!.use { Font.createFont(Font.TRUETYPE_FONT, it) }
}

/** Страница PDF в PNG для браузеров зрителей. */
fun renderPdfPagePng(file: java.io.File, page: Int, maxWidth: Int): ByteArray = Loader.loadPDF(file).use { doc ->
    require(page in 0 until doc.numberOfPages) { "Нет страницы ${page + 1}" }
    val scale = maxWidth / doc.getPage(page).cropBox.width
    val img = PDFRenderer(doc).renderImage(page, scale, ImageType.RGB)
    java.io.ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
}
