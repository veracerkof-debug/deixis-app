package app.deixis.desktop

import app.deixis.core.Content
import app.deixis.core.FileStore
import app.deixis.core.HAlign
import app.deixis.core.ImageContent
import app.deixis.core.Margins
import app.deixis.core.Mode
import app.deixis.core.OutputLayout
import app.deixis.core.PdfContent
import app.deixis.core.ScreenContent
import app.deixis.core.ScriptureContent
import app.deixis.core.TextContent
import app.deixis.core.TextStyle
import app.deixis.core.VAlign
import app.deixis.core.VideoContent
import app.deixis.core.VideoState
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Токены вывода на мониторы. Зал, титры и хромакей — содержимое (DESIGN.md, «Вывод на экран»);
 * вид «Сцена» смотрит только ведущий, его подписи берут оттенки тёмной темы пульта.
 */
object OutputTokens {
    val black = Color(0x000000)
    val ink = Color(0xFFFFFF)
    /** `--l2` тёмной темы: подписи. */
    val l2 = Color(0x8D8D93)
    /** `--cell` тёмной темы: подложка окон «Сейчас» и «Далее». */
    val cell = Color(0x1C1C1E)
    /** `--tint` тёмной темы: время показа, полоса видео. */
    val tint = Color(0xBC9361)
    /** `--live` тёмной темы: метка «в зале чёрный экран» и подобные. */
    val live = Color(0xB96E65)
    /** Хромакей: чистый зелёный для OBS и vMix. */
    const val CHROMA_ARGB = 0xFF00FF00
    const val BLACK_ARGB = 0xFF000000
    const val INK_ARGB = 0xFFFFFFFF
    const val OUTLINE_ARGB = 0xFF000000

    // Доли меньшей стороны экрана.
    const val STAGE_PAD = 0.035f
    const val STAGE_LABEL = 0.032f
    const val STAGE_CLOCK = 0.17f
    const val STAGE_TIMER = 0.085f
    const val STAGE_NOTE = 0.045f
    const val STAGE_RADIUS = 0.018f
    /** Доля ширины под окно «Сейчас». */
    const val STAGE_CURRENT_WIDTH = 0.62f

    // Титры: поля в процентах от экрана (низ экрана), предел кегля — доля меньшей стороны.
    val CAPTION_MARGINS = Margins(left = 6f, top = 64f, right = 6f, bottom = 7f)
    const val CAPTION_SIZE = 8f
    const val CAPTION_OUTLINE = 0.08f
}

/** Всё, что нужно для кадра любой раскладки. */
data class FrameState(
    val content: Content?,
    val mode: Mode,
    val next: Content? = null,
    val video: VideoState? = null,
    val startedAt: Long? = null,
    val now: Long = System.currentTimeMillis(),
    val idle: IdleInfo? = null,
)

/**
 * Текст и Писание титрами внизу экрана: на чёрном (проектор поверх сцены) или на чистом зелёном (хромакей).
 * Фото, PDF и видео в титры не попадают; «Чёрный», «Логотип» и «Очистить» убирают титры.
 */
fun captionContent(content: Content?, mode: Mode, chroma: Boolean): Content? {
    if (mode != Mode.NORMAL) return null
    fun style(src: TextStyle) = TextStyle(
        background = if (chroma) OutputTokens.CHROMA_ARGB else OutputTokens.BLACK_ARGB,
        color = OutputTokens.INK_ARGB,
        sizePercent = OutputTokens.CAPTION_SIZE,
        // Тень на хромакее даёт грязный край после вырезания фона: вместо неё — обводка.
        shadow = !chroma,
        outline = chroma,
        outlineColor = OutputTokens.OUTLINE_ARGB,
        outlineWidth = OutputTokens.CAPTION_OUTLINE,
        caps = src.caps,
        align = HAlign.CENTER,
        valign = VAlign.BOTTOM,
        margins = OutputTokens.CAPTION_MARGINS,
        lineHeight = src.lineHeight,
    )
    return when (content) {
        is TextContent -> content.copy(style = style(content.style))
        is ScriptureContent -> content.copy(style = style(content.style), refPosition = app.deixis.core.RefPosition.BOTTOM)
        else -> null
    }
}

/** Текст для вида «Сцена»: крупно и без оформления зала, чтобы читалось с расстояния. */
internal fun readableContent(content: Content): Content = when (content) {
    is TextContent -> content.copy(style = TextStyle(caps = content.style.caps, shadow = false))
    is ScriptureContent -> content.copy(style = TextStyle(caps = content.style.caps, shadow = false))
    else -> content
}

/**
 * Кадры раскладок монитора. Зал рисует [SlideRenderer] как есть; «Сцена», «Титры» и «Хромакей» — здесь.
 * Один экземпляр на окно: кэш картинок своё у каждого монитора.
 */
class OutputFrames(files: FileStore) {
    private val slides = SlideRenderer(files)
    private val chromaSlides = SlideRenderer(files, refAlpha = 1f)
    private val frc = FontRenderContext(null, true, true)
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    // Окна «Сейчас» и «Далее» перерисовываются раз в секунду из-за часов: сами слайды берутся из кэша.
    private val panels = object : LinkedHashMap<Triple<Content, Int, Int>, BufferedImage>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Content, Int, Int>, BufferedImage>?) = size > 6
    }

    @Synchronized
    fun render(layout: OutputLayout, width: Int, height: Int, s: FrameState): BufferedImage = when (layout) {
        OutputLayout.HALL -> slides.render(
            width, height,
            s.content.takeUnless { (it is VideoContent || it is ScreenContent) && s.mode == Mode.NORMAL },
            s.mode, s.idle,
        )
        OutputLayout.LOWER_THIRD -> caption(width, height, s, chroma = false)
        OutputLayout.CHROMA -> caption(width, height, s, chroma = true)
        OutputLayout.STAGE -> stage(width, height, s)
    }

    private fun caption(w: Int, h: Int, s: FrameState, chroma: Boolean): BufferedImage {
        val c = captionContent(s.content, s.mode, chroma)
        if (c != null) return (if (chroma) chromaSlides else slides).render(w, h, c, Mode.NORMAL, null)
        val img = BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(if (chroma) OutputTokens.CHROMA_ARGB.toInt() else OutputTokens.BLACK_ARGB.toInt(), true)
        g.fillRect(0, 0, w, h)
        g.dispose()
        return img
    }

    // Вид «Сцена»: слева крупно то, что сейчас в зале, справа — следующий слайд, часы и время показа.
    // «Чёрный», «Логотип» и «Очистить» видны меткой: сам слайд у ведущего не гаснет никогда.
    private fun stage(w: Int, h: Int, s: FrameState): BufferedImage {
        val img = BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.color = OutputTokens.black
            g.fillRect(0, 0, w, h)
            val short = minOf(w, h).toFloat()
            val pad = short * OutputTokens.STAGE_PAD
            val label = short * OutputTokens.STAGE_LABEL
            val labelH = label * 2f
            val leftW = w * OutputTokens.STAGE_CURRENT_WIDTH - pad * 1.5f
            val rightX = pad + leftW + pad
            val rightW = w - rightX - pad

            // Сейчас
            drawLabel(g, "Сейчас", pad, pad, label, OutputTokens.l2)
            modeLabel(s.mode)?.let { drawPill(g, it, pad + leftW, pad, label) }
            val curTop = pad + labelH
            drawPanel(g, s.content, s.video, pad, curTop, leftW, h - curTop - pad, short, current = true)

            // Далее
            drawLabel(g, "Далее", rightX, pad, label, OutputTokens.l2)
            val nextH = minOf(rightW * 9f / 16f, (h - curTop - pad) * 0.45f)
            if (s.next != null) drawPanel(g, s.next, null, rightX, curTop, rightW, nextH, short, current = false)
            else drawNote(g, "Нет следующего слайда", rightX, curTop, rightW, nextH, short, OutputTokens.l2)

            // Часы и время с начала показа
            val clockSize = minOf(short * OutputTokens.STAGE_CLOCK, rightW / 3.2f)
            val timerSize = minOf(short * OutputTokens.STAGE_TIMER, rightW / 4.5f)
            val bottom = h - pad
            val timerBase = bottom - label * 1.6f
            drawLabel(g, "С начала показа", rightX, bottom - label * 1.2f, label, OutputTokens.l2)
            drawText(g, elapsed(s.startedAt, s.now), rightX, timerBase, timerSize, OutputTokens.tint)
            drawText(g, clock.format(Instant.ofEpochMilli(s.now)), rightX, timerBase - timerSize * 1.25f, clockSize, OutputTokens.ink)
        } finally {
            g.dispose()
        }
        return img
    }

    private fun modeLabel(mode: Mode): String? = when (mode) {
        Mode.NORMAL -> null
        Mode.BLACK -> "В зале чёрный экран"
        Mode.LOGO -> "В зале логотип"
        Mode.CLEAR -> "В зале текст скрыт"
    }

    private fun drawPanel(g: Graphics2D, c: Content?, video: VideoState?, x: Float, y: Float, w: Float, h: Float, short: Float, current: Boolean) {
        val radius = short * OutputTokens.STAGE_RADIUS
        when (c) {
            null -> drawNote(g, "Ожидание показа", x, y, w, h, short, OutputTokens.l2)
            is TextContent, is ScriptureContent -> {
                fill(g, x, y, w, h, radius, OutputTokens.black)
                blit(g, readableContent(c), x, y, w, h)
            }
            is ImageContent, is PdfContent -> {
                // Слайд в пропорциях зала, по центру своего окна.
                val sw = minOf(w, h * 16f / 9f)
                val sh = sw * 9f / 16f
                blit(g, c, x + (w - sw) / 2f, y + (h - sh) / 2f, sw, sh)
            }
            is VideoContent -> drawVideo(g, c, if (current) video else null, x, y, w, h, short)
            is ScreenContent -> drawNote(g, "Трансляция экрана", x, y, w, h, short, OutputTokens.ink)
        }
    }

    private fun blit(g: Graphics2D, c: Content, x: Float, y: Float, w: Float, h: Float) {
        val pw = w.toInt().coerceAtLeast(1)
        val ph = h.toInt().coerceAtLeast(1)
        val key = Triple(c, pw, ph)
        val img = panels[key] ?: runCatching { slides.render(pw, ph, c, Mode.NORMAL, null) }.getOrNull()?.also { panels[key] = it } ?: return
        g.drawImage(img, x.toInt(), y.toInt(), null)
    }

    private fun drawVideo(g: Graphics2D, c: VideoContent, v: VideoState?, x: Float, y: Float, w: Float, h: Float, short: Float) {
        fill(g, x, y, w, h, short * OutputTokens.STAGE_RADIUS, OutputTokens.cell)
        val size = short * OutputTokens.STAGE_NOTE * 1.4f
        val inner = w * 0.08f
        val left = x + inner
        val right = x + w - inner
        val mid = y + h / 2f
        // Знак «идёт» или «пауза» рисуется фигурой: в шрифте слайдов нет таких символов.
        val icon = size * 0.9f
        g.color = OutputTokens.ink
        if (v?.playing == false) {
            g.fill(RoundRectangle2D.Float(left, mid - size * 1.6f, icon * 0.32f, icon, 2f, 2f))
            g.fill(RoundRectangle2D.Float(left + icon * 0.55f, mid - size * 1.6f, icon * 0.32f, icon, 2f, 2f))
        } else {
            val p = Path2D.Float()
            p.moveTo(left, mid - size * 1.6f); p.lineTo(left + icon * 0.85f, mid - size * 1.6f + icon / 2f); p.lineTo(left, mid - size * 1.6f + icon); p.closePath()
            g.fill(p)
        }
        drawText(g, fitLine(c.file.name, size, right - left - icon * 1.4f), left + icon * 1.4f, mid - size * 1.6f + icon * 0.85f, size, OutputTokens.ink)
        if (v != null) {
            val barY = mid + size * 0.2f
            val barH = size * 0.18f
            fill(g, left, barY, right - left, barH, barH, OutputTokens.l2.darker())
            val part = if (v.durationMs > 0) (v.positionMs.toFloat() / v.durationMs).coerceIn(0f, 1f) else 0f
            if (part > 0f) fill(g, left, barY, (right - left) * part, barH, barH, OutputTokens.tint)
            val time = duration(v.positionMs) + if (v.durationMs > 0) " / " + duration(v.durationMs) else ""
            drawText(g, time, left, barY + barH + size * 1.4f, size, OutputTokens.ink)
        }
    }

    private fun drawNote(g: Graphics2D, text: String, x: Float, y: Float, w: Float, h: Float, short: Float, color: Color) {
        fill(g, x, y, w, h, short * OutputTokens.STAGE_RADIUS, OutputTokens.cell)
        val size = minOf(short * OutputTokens.STAGE_NOTE, h / 4f)
        val line = fitLine(text, size, w * 0.9f)
        val layout = TextLayout(line, font(size), frc)
        g.color = color
        layout.draw(g, x + (w - layout.advance) / 2f, y + h / 2f + layout.ascent / 2f - layout.descent / 2f)
    }

    private fun drawPill(g: Graphics2D, text: String, right: Float, top: Float, size: Float) {
        val layout = TextLayout(text, font(size), frc)
        val padX = size * 0.7f
        val pillH = size * 1.6f
        val pillW = layout.advance + padX * 2
        fill(g, right - pillW, top - size * 0.3f, pillW, pillH, pillH, OutputTokens.live)
        g.color = OutputTokens.ink
        layout.draw(g, right - pillW + padX, top - size * 0.3f + pillH / 2f + layout.ascent / 2f - layout.descent / 2f)
    }

    private fun drawLabel(g: Graphics2D, text: String, x: Float, top: Float, size: Float, color: Color) =
        drawText(g, text, x, top + size, size, color)

    private fun drawText(g: Graphics2D, text: String, x: Float, baseline: Float, size: Float, color: Color) {
        if (text.isEmpty()) return
        g.color = color
        TextLayout(text, font(size), frc).draw(g, x, baseline)
    }

    /** Строка, которая не помещается, укорачивается с многоточием. */
    private fun fitLine(text: String, size: Float, maxW: Float): String {
        val f = font(size)
        fun width(s: String) = f.getStringBounds(s, frc).width.toFloat()
        if (text.isEmpty() || width(text) <= maxW) return text
        var n = text.length
        while (n > 1 && width(text.take(n) + "…") > maxW) n--
        return text.take(n) + "…"
    }

    private fun fill(g: Graphics2D, x: Float, y: Float, w: Float, h: Float, r: Float, color: Color) {
        g.color = color
        g.fill(RoundRectangle2D.Float(x, y, w, h, r, r))
    }

    private fun font(size: Float): Font = Fonts.ui.deriveFont(size)

    companion object {
        /** Время с начала показа: 0:00:00. До первого слайда — нули. */
        fun elapsed(startedAt: Long?, now: Long): String {
            val s = if (startedAt == null) 0 else ((now - startedAt) / 1000).coerceAtLeast(0)
            return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        }

        fun duration(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
