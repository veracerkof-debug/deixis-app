package app.deixis.receiver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.deixis.core.Content
import app.deixis.core.Mode
import app.deixis.core.ScriptureContent
import app.deixis.core.TextContent
import app.deixis.core.layoutScripture
import app.deixis.core.layoutSlideText
import app.deixis.core.TextStyle as SlideStyle

/**
 * Слайд картинкой — для экранов, которые умеют показывать только фото (DLNA-телевизоры).
 * Рисуется тем же расчётом, что и на Deixis TV, поэтому выглядит так же. [background] — картинка фона оформления.
 */
fun renderSlideBitmap(
    context: Context, content: Content?, mode: Mode, width: Int = 1920, height: Int = 1080, background: Bitmap? = null,
    logo: app.deixis.core.LogoStyle = app.deixis.core.LogoStyle.WORDMARK,
    /** Своя картинка на «Логотипе» вместо знака. */
    logoPicture: Bitmap? = null,
): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(android.graphics.Color.BLACK)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = context.resources.getFont(R.font.noto_sans_semibold) }
    when {
        mode == Mode.BLACK || mode == Mode.AWAY -> Unit
        mode == Mode.LOGO && logoPicture != null -> drawFit(c, logoPicture, width, height)
        mode == Mode.LOGO -> drawLogoMark(c, width.toFloat(), height.toFloat(), logo, context.resources.getFont(R.font.gfs_didot), android.graphics.Color.WHITE)
        content is TextContent -> {
            background(c, content.style, background, width, height)
            if (mode != Mode.CLEAR) {
                val l = layoutSlideText(
                    content.text, content.ref, width.toFloat(), height.toFloat(), content.style,
                    measure = { s, sz -> text.textSize = sz; text.measureText(s) },
                    ascent = { sz -> text.textSize = sz; -text.fontMetrics.ascent },
                    descent = { sz -> text.textSize = sz; text.fontMetrics.descent },
                )
                l.block?.let { b -> drawBox(c, content.style, b.left, b.top, b.right, b.bottom, l.fontSize) }
                for (line in l.lines) drawStyledText(c, line.text, line.x, line.baseline, l.fontSize, text, content.style)
                l.ref?.let {
                    val rp = Paint(text).apply { textSize = l.refFontSize; clearShadowLayer(); color = content.style.color.toInt(); alpha = 190; textAlign = Paint.Align.RIGHT }
                    c.drawText(it, l.refRight, l.refBaseline, rp)
                }
            }
        }
        content is ScriptureContent -> {
            background(c, content.style, background, width, height)
            if (mode != Mode.CLEAR) {
                val lines = layoutScripture(
                    content, width.toFloat(), height.toFloat(),
                    measure = { s, sz -> text.textSize = sz; text.measureText(s) },
                    ascent = { sz -> text.textSize = sz; -text.fontMetrics.ascent },
                    descent = { sz -> text.textSize = sz; text.fontMetrics.descent },
                )
                for (l in lines) {
                    if (l.ref) {
                        val rp = Paint(text).apply { textSize = l.size; clearShadowLayer(); color = content.style.color.toInt(); alpha = 190 }
                        c.drawText(l.text, l.x, l.baseline, rp)
                    } else {
                        drawStyledText(c, l.text, l.x, l.baseline, l.size, text, content.style)
                    }
                }
            }
        }
    }
    return bmp
}

private fun background(c: Canvas, style: SlideStyle, bg: Bitmap?, w: Int, h: Int) {
    c.drawColor(style.background.toInt())
    bg ?: return
    val scale = maxOf(w.toFloat() / bg.width, h.toFloat() / bg.height)
    val dw = bg.width * scale
    val dh = bg.height * scale
    c.drawBitmap(bg, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
}

/** Картинка целиком посреди кадра, с чёрными полями: своя картинка «Логотипа». */
private fun drawFit(c: Canvas, img: Bitmap, w: Int, h: Int) {
    val scale = minOf(w.toFloat() / img.width, h.toFloat() / img.height)
    val dw = img.width * scale
    val dh = img.height * scale
    c.drawBitmap(img, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
}
