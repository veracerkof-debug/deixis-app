package app.deixis.desktop

import app.deixis.core.FileStore
import app.deixis.core.Corner
import app.deixis.core.OverlayBox
import app.deixis.core.OverlayFrame
import app.deixis.core.tickerHeight
import app.deixis.core.clockFont
import app.deixis.core.clockText
import app.deixis.core.cornerBox
import app.deixis.core.logoBox
import app.deixis.core.lowerThirdAccent
import app.deixis.core.lowerThirdBox
import app.deixis.core.lowerThirdPad
import app.deixis.core.lowerThirdSubtitleFont
import app.deixis.core.lowerThirdTitleFont
import app.deixis.core.tickerBox
import app.deixis.core.tickerFont
import app.deixis.core.tickerX
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.TextLayout
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Слой поверх показа на компьютере: логотип, плашка, бегущая строка, часы. Раскладка — из ядра, общая
 * со всеми экранами. Картинка логотипа читается один раз; файла нет или он не читается — логотипа нет,
 * остальное рисуется.
 */
class OverlayPainter(private val files: FileStore) {
    private var logoSha: String? = null
    private var logo: BufferedImage? = null

    /** Слою нужна перерисовка чаще кадра слайда: при движении и бегущей строке — плавно, часы — раз в полсекунды. */
    fun tickMs(f: OverlayFrame): Int? = when {
        f.moving || f.ticker != null -> MOTION_TICK_MS
        f.clock != null -> CLOCK_TICK_MS
        else -> null
    }

    /**
     * Кадр слоя. Плашка въезжает слева, бегущая строка поднимается снизу, логотип и часы проявляются;
     * то, что стоит над строкой, поднимается вместе с ней.
     */
    fun paint(g: Graphics2D, w: Float, h: Float, f: OverlayFrame, now: Long) {
        if (f.isEmpty || w <= 0f || h <= 0f) return
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        // Насколько поднято всё, что стоит над бегущей строкой.
        val lift = tickerHeight(w, h) * (f.ticker?.presence ?: 0f)
        f.ticker?.let { (t, p) ->
            val box = tickerBox(w, h)
            val top = box.top + box.height * (1f - p)
            faded(g, p) {
                g.color = argb(t.background)
                g.fill(java.awt.geom.Rectangle2D.Float(box.left, top, box.width, box.height))
                val font = Fonts.slide.deriveFont(tickerFont(w, h))
                val text = t.text.replace('\n', ' ')
                val layout = TextLayout(text, font, g.fontRenderContext)
                val x = tickerX(w, layout.advance, t.speedPercent, now)
                val clip = g.clip
                g.clipRect(box.left.toInt(), top.toInt(), box.width.toInt() + 1, box.height.toInt() + 1)
                g.color = argb(t.color)
                layout.draw(g, x, top + (box.height + layout.ascent - layout.descent) / 2)
                g.clip = clip
            }
        }
        f.lowerThird?.let { (lt, p) ->
            val titleFont = Fonts.slide.deriveFont(lowerThirdTitleFont(w, h))
            val subFont = Fonts.ui.deriveFont(Font.PLAIN, lowerThirdSubtitleFont(w, h))
            val frc = g.fontRenderContext
            val title = lt.title.ifBlank { " " }
            val tw = TextLayout(title, titleFont, frc).advance
            val sub = lt.subtitle?.takeIf { it.isNotBlank() }
            val sw = sub?.let { TextLayout(it, subFont, frc).advance } ?: 0f
            val base = lowerThirdBox(w, h, maxOf(tw, sw), sub != null, aboveTicker = false)
            // Въезжает слева из-за края экрана.
            val box = base.copy(left = base.left - base.right * (1f - p), top = base.top - lift)
            val pad = lowerThirdPad(w, h)
            val accent = lt.accent?.let { lowerThirdAccent(w, h) } ?: 0f
            faded(g, p) {
                g.color = argb(lt.background)
                g.fill(RoundRectangle2D.Float(box.left, box.top, box.width, box.height, pad, pad))
                lt.accent?.let { a ->
                    g.color = argb(a)
                    g.fill(java.awt.geom.Rectangle2D.Float(box.left, box.top, accent, box.height))
                }
                val clip = g.clip
                g.clipRect(box.left.toInt(), box.top.toInt(), box.width.toInt(), box.height.toInt())
                g.color = argb(lt.color)
                val titleLayout = TextLayout(title, titleFont, frc)
                val x = box.left + accent + pad
                var y = box.top + pad + titleLayout.ascent
                titleLayout.draw(g, x, y)
                if (sub != null) {
                    val s = TextLayout(sub, subFont, frc)
                    y += titleLayout.descent + lowerThirdTitleFont(w, h) * 0.2f + s.ascent
                    g.color = argb(lt.color).let { Color(it.red, it.green, it.blue, (it.alpha * SUBTITLE_ALPHA).toInt()) }
                    s.draw(g, x, y)
                }
                g.clip = clip
            }
        }
        f.clock?.let { (c, p) ->
            val font = Fonts.slide.deriveFont(clockFont(w, h))
            val layout = TextLayout(clockText(now, c.seconds), font, g.fontRenderContext)
            val box = cornerBox(w, h, layout.advance, layout.ascent + layout.descent, c.corner, aboveTicker = false).lifted(c.corner, lift)
            val base = box.top + layout.ascent
            faded(g, p) {
                // Тень, как у текста слайдов: часы читаются и на светлом фоне.
                g.color = Color(0, 0, 0, SHADOW_ALPHA)
                layout.draw(g, box.left + h * SHADOW, base + h * SHADOW)
                g.color = argb(c.color)
                layout.draw(g, box.left, base)
            }
        }
        f.logo?.let { (l, p) ->
            val img = logoImage(l.file.sha256) ?: return@let
            val box = logoBox(w, h, img.width.toFloat(), img.height.toFloat(), l, aboveTicker = false).lifted(l.corner, lift)
            // Проявляется и чуть вырастает до своего размера.
            val k = LOGO_GROW + (1f - LOGO_GROW) * p
            val bw = box.width * k
            val bh = box.height * k
            faded(g, p * l.opacity.coerceIn(0f, 1f)) {
                g.drawImage(img, (box.left + (box.width - bw) / 2).toInt(), (box.top + (box.height - bh) / 2).toInt(), bw.toInt(), bh.toInt(), null)
            }
        }
    }

    private fun OverlayBox.lifted(corner: Corner, lift: Float) =
        if (corner == Corner.BOTTOM_LEFT || corner == Corner.BOTTOM_RIGHT) copy(top = top - lift) else this

    private inline fun faded(g: Graphics2D, alpha: Float, draw: () -> Unit) {
        val old = g.composite
        if (alpha < 1f) g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
        draw()
        g.composite = old
    }

    private fun logoImage(sha: String): BufferedImage? {
        if (sha != logoSha) {
            logoSha = sha
            logo = runCatching { files.file(sha)?.let(ImageIO::read) }.getOrNull()
        }
        return logo
    }

    private fun argb(v: Long) = Color(v.toInt(), true)

    private companion object {
        const val MOTION_TICK_MS = 16
        /** С какой доли размера логотип вырастает при появлении. */
        const val LOGO_GROW = 0.85f
        const val CLOCK_TICK_MS = 500
        const val SUBTITLE_ALPHA = 0.8f
        const val SHADOW_ALPHA = 150
        /** Сдвиг тени — доля высоты экрана. */
        const val SHADOW = 0.0025f
    }
}
