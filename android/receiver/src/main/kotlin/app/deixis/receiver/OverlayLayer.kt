package app.deixis.receiver

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import app.deixis.core.Overlays
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Слой поверх показа: логотип, плашка, бегущая строка, часы. Раскладка из ядра — та же, что на компьютере
 * и в браузере. Рисуется над всем, в том числе над видео (SurfaceView лежит под Compose).
 */
@Composable
fun OverlayLayer(runtime: ReceiverRuntime, o: Overlays) {
    val context = LocalContext.current
    val sans = remember { context.resources.getFont(R.font.noto_sans_semibold) }
    val metrics = context.resources.displayMetrics
    val logoSha = o.logo?.file?.sha256
    val logo by produceState<Bitmap?>(null, logoSha) {
        value = logoSha?.let { sha ->
            withContext(Dispatchers.IO) {
                runCatching { runtime.core.files.file(sha)?.let { decodeSampled(it, metrics.widthPixels, metrics.heightPixels) } }.getOrNull()
            }
        }
    }
    // Бегущая строка — каждый кадр; часы — дважды в секунду; без них слой не перерисовывается.
    val now by produceState(System.currentTimeMillis(), o.ticker != null, o.clock != null) {
        while (o.ticker != null || o.clock != null) {
            if (o.ticker != null) withFrameMillis { } else delay(CLOCK_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val ticker = o.ticker != null
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            o.ticker?.let { t ->
                val box = tickerBox(w, h)
                fill.color = t.background.toInt()
                nc.drawRect(box.left, box.top, box.right, box.bottom, fill)
                paint.textSize = tickerFont(w, h)
                val text = t.text.replace('\n', ' ')
                val x = tickerX(w, paint.measureText(text), t.speedPercent, now)
                val fm = paint.fontMetrics
                paint.color = t.color.toInt()
                nc.save()
                nc.clipRect(box.left, box.top, box.right, box.bottom)
                nc.drawText(text, x, box.top + (box.height - fm.ascent - fm.descent) / 2, paint)
                nc.restore()
            }
            o.lowerThird?.let { lt ->
                val titleSize = lowerThirdTitleFont(w, h)
                val subSize = lowerThirdSubtitleFont(w, h)
                val sub = lt.subtitle?.takeIf { it.isNotBlank() }
                paint.textSize = titleSize
                val tw = paint.measureText(lt.title)
                val titleAscent = -paint.fontMetrics.ascent
                val titleDescent = paint.fontMetrics.descent
                paint.textSize = subSize
                val sw = sub?.let { paint.measureText(it) } ?: 0f
                val subAscent = -paint.fontMetrics.ascent
                val box = lowerThirdBox(w, h, maxOf(tw, sw), sub != null, ticker)
                val pad = lowerThirdPad(w, h)
                val accent = lt.accent?.let { lowerThirdAccent(w, h) } ?: 0f
                fill.color = lt.background.toInt()
                nc.drawRoundRect(box.left, box.top, box.right, box.bottom, pad / 2, pad / 2, fill)
                lt.accent?.let { a ->
                    fill.color = a.toInt()
                    nc.drawRect(box.left, box.top, box.left + accent, box.bottom, fill)
                }
                nc.save()
                nc.clipRect(box.left, box.top, box.right, box.bottom)
                val x = box.left + accent + pad
                var y = box.top + pad + titleAscent
                paint.textSize = titleSize
                paint.color = lt.color.toInt()
                nc.drawText(lt.title, x, y, paint)
                if (sub != null) {
                    y += titleDescent + titleSize * 0.2f + subAscent
                    paint.textSize = subSize
                    paint.alpha = (paint.alpha * SUBTITLE_ALPHA).toInt()
                    nc.drawText(sub, x, y, paint)
                }
                nc.restore()
            }
            o.clock?.let { c ->
                paint.textSize = clockFont(w, h)
                paint.color = c.color.toInt()
                val text = clockText(now, c.seconds)
                val fm = paint.fontMetrics
                val box = cornerBox(w, h, paint.measureText(text), fm.descent - fm.ascent, c.corner, ticker)
                // Тень, как у текста слайдов: часы читаются и на светлом фоне.
                paint.setShadowLayer(h * SHADOW, h * SHADOW / 2, h * SHADOW / 2, SHADOW_COLOR)
                nc.drawText(text, box.left, box.top - fm.ascent, paint)
                paint.clearShadowLayer()
            }
            val img = logo
            if (o.logo != null && img != null) {
                val box = logoBox(w, h, img.width.toFloat(), img.height.toFloat(), o.logo!!, ticker)
                val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (o.logo!!.opacity.coerceIn(0f, 1f) * 255).toInt() }
                nc.drawBitmap(img, null, android.graphics.RectF(box.left, box.top, box.right, box.bottom), p)
            }
        }
    }
}

private const val CLOCK_TICK_MS = 500L
private const val SUBTITLE_ALPHA = 0.8f
/** Размытие тени часов — доля высоты экрана. */
private const val SHADOW = 0.004f
private const val SHADOW_COLOR = 0x96000000.toInt()
