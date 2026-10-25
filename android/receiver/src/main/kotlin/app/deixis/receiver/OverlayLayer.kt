package app.deixis.receiver

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import app.deixis.core.Corner
import app.deixis.core.OverlayBox
import app.deixis.core.OverlayMotion
import app.deixis.core.Overlays
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Слой поверх показа: логотип, плашка, бегущая строка, часы. Раскладка из ядра — та же, что на компьютере
 * и в браузере. Рисуется над всем, в том числе над видео (SurfaceView лежит под Compose).
 */
@Composable
fun OverlayLayer(runtime: ReceiverRuntime, o: Overlays, visible: Boolean) {
    val context = LocalContext.current
    val sans = remember { context.resources.getFont(R.font.noto_sans_semibold) }
    val metrics = context.resources.displayMetrics
    val motion = remember { OverlayMotion() }
    // «Чёрный» и «Заставка» убирают слой сразу; остальное — плавно.
    val shown = if (visible) o else Overlays()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    remember(shown, visible) { motion.update(shown, System.currentTimeMillis(), instant = !visible); now = System.currentTimeMillis() }
    // Пока что-то движется или бежит строка — каждый кадр; часы — дважды в секунду; стоит — не перерисовывается.
    LaunchedEffect(shown, visible) {
        while (true) {
            val f = motion.frame(System.currentTimeMillis())
            when {
                f.moving || f.ticker != null -> withFrameMillis { }
                f.clock != null -> delay(CLOCK_TICK_MS)
                else -> { now = System.currentTimeMillis(); break }
            }
            now = System.currentTimeMillis()
        }
    }
    val frame = motion.frame(now)
    // Логотип держится, пока он виден, в том числе пока уходит.
    val logoSha = frame.logo?.value?.file?.sha256 ?: o.logo?.file?.sha256
    val logo by produceState<Bitmap?>(null, logoSha) {
        value = logoSha?.let { sha ->
            withContext(Dispatchers.IO) {
                runCatching { runtime.core.files.file(sha)?.let { decodeSampled(it, metrics.widthPixels, metrics.heightPixels) } }.getOrNull()
            }
        }
    }
    if (frame.isEmpty) return
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Насколько поднято всё, что стоит над бегущей строкой.
        val lift = tickerHeight(w, h) * (frame.ticker?.presence ?: 0f)
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            fun alpha(color: Long, p: Float) = ((color ushr 24).toInt() * p).toInt().coerceIn(0, 255)
            frame.ticker?.let { (t, p) ->
                val box = tickerBox(w, h)
                val top = box.top + box.height * (1f - p)
                fill.color = t.background.toInt(); fill.alpha = alpha(t.background, p)
                nc.drawRect(box.left, top, box.right, top + box.height, fill)
                paint.textSize = tickerFont(w, h)
                val text = t.text.replace('\n', ' ')
                val x = tickerX(w, paint.measureText(text), t.speedPercent, now)
                val fm = paint.fontMetrics
                paint.color = t.color.toInt(); paint.alpha = alpha(t.color, p)
                nc.save()
                nc.clipRect(box.left, top, box.right, top + box.height)
                nc.drawText(text, x, top + (box.height - fm.ascent - fm.descent) / 2, paint)
                nc.restore()
            }
            frame.lowerThird?.let { (lt, p) ->
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
                val base = lowerThirdBox(w, h, maxOf(tw, sw), sub != null, aboveTicker = false)
                // Въезжает слева из-за края экрана.
                val left = base.left - base.right * (1f - p)
                val top = base.top - lift
                val right = left + base.width
                val bottom = top + base.height
                val pad = lowerThirdPad(w, h)
                val accent = lt.accent?.let { lowerThirdAccent(w, h) } ?: 0f
                fill.color = lt.background.toInt(); fill.alpha = alpha(lt.background, p)
                nc.drawRoundRect(left, top, right, bottom, pad / 2, pad / 2, fill)
                lt.accent?.let { a ->
                    fill.color = a.toInt(); fill.alpha = alpha(a, p)
                    nc.drawRect(left, top, left + accent, bottom, fill)
                }
                nc.save()
                nc.clipRect(left, top, right, bottom)
                val x = left + accent + pad
                var y = top + pad + titleAscent
                paint.textSize = titleSize
                paint.color = lt.color.toInt(); paint.alpha = alpha(lt.color, p)
                nc.drawText(lt.title, x, y, paint)
                if (sub != null) {
                    y += titleDescent + titleSize * 0.2f + subAscent
                    paint.textSize = subSize
                    paint.alpha = (alpha(lt.color, p) * SUBTITLE_ALPHA).toInt()
                    nc.drawText(sub, x, y, paint)
                }
                nc.restore()
            }
            frame.clock?.let { (c, p) ->
                paint.textSize = clockFont(w, h)
                paint.color = c.color.toInt(); paint.alpha = alpha(c.color, p)
                val text = clockText(now, c.seconds)
                val fm = paint.fontMetrics
                val box = cornerBox(w, h, paint.measureText(text), fm.descent - fm.ascent, c.corner, aboveTicker = false).lifted(c.corner, lift)
                // Тень, как у текста слайдов: часы читаются и на светлом фоне.
                paint.setShadowLayer(h * SHADOW, h * SHADOW / 2, h * SHADOW / 2, SHADOW_COLOR)
                nc.drawText(text, box.left, box.top - fm.ascent, paint)
                paint.clearShadowLayer()
            }
            val img = logo
            frame.logo?.let { (l, p) ->
                if (img == null) return@let
                val box = logoBox(w, h, img.width.toFloat(), img.height.toFloat(), l, aboveTicker = false).lifted(l.corner, lift)
                // Проявляется и чуть вырастает до своего размера.
                val k = LOGO_GROW + (1f - LOGO_GROW) * p
                val bw = box.width * k
                val bh = box.height * k
                val lp = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (l.opacity.coerceIn(0f, 1f) * p * 255).toInt() }
                val x = box.left + (box.width - bw) / 2
                val y = box.top + (box.height - bh) / 2
                nc.drawBitmap(img, null, android.graphics.RectF(x, y, x + bw, y + bh), lp)
            }
        }
    }
}

private fun OverlayBox.lifted(corner: Corner, lift: Float) =
    if (corner == Corner.BOTTOM_LEFT || corner == Corner.BOTTOM_RIGHT) copy(top = top - lift) else this

private const val LOGO_GROW = 0.85f
private const val CLOCK_TICK_MS = 500L
private const val SUBTITLE_ALPHA = 0.8f
/** Размытие тени часов — доля высоты экрана. */
private const val SHADOW = 0.004f
private const val SHADOW_COLOR = 0x96000000.toInt()
