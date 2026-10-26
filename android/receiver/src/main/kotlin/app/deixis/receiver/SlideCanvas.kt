package app.deixis.receiver

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import app.deixis.core.ScriptureContent
import app.deixis.core.layoutScripture
import app.deixis.core.layoutSlideText
import app.deixis.core.TextStyle as SlideStyle

/**
 * Слайд с текстом по раскладке ядра ([layoutSlideText]) — один и тот же для зала, превью пульта и любых
 * размеров: превью на телефоне выглядит ровно как экран, только меньше. [drawBackground] = false — фон рисует
 * тот, кто вызывает (превью с фото фона с телефона).
 */
@Composable
fun TextSlideCanvas(
    text: String, ref: String?, style: SlideStyle, clear: Boolean = false, overscan: Boolean = false,
    bg: Bitmap? = null, drawBackground: Boolean = true,
) {
    val context = LocalContext.current
    val typeface = remember { context.resources.getFont(R.font.noto_sans_semibold) }
    Canvas(Modifier.fillMaxSize()) {
        if (drawBackground) drawStyleBackground(style, bg)
        if (clear) return@Canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface }
        val layout = layoutSlideText(
            text, ref, size.width, size.height, style,
            measure = { s, sz -> paint.textSize = sz; paint.measureText(s) },
            ascent = { sz -> paint.textSize = sz; -paint.fontMetrics.ascent },
            descent = { sz -> paint.textSize = sz; paint.fontMetrics.descent },
            overscan = overscan,
        )
        paint.textSize = layout.fontSize
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            layout.block?.let { b -> drawBox(nc, style, b.left, b.top, b.right, b.bottom, layout.fontSize) }
            for (line in layout.lines) drawStyledText(nc, line.text, line.x, line.baseline, layout.fontSize, paint, style)
            layout.ref?.let {
                val refPaint = Paint(paint).apply {
                    textSize = layout.refFontSize
                    clearShadowLayer()
                    alpha = 190
                    textAlign = Paint.Align.RIGHT
                }
                nc.drawText(it, layout.refRight, layout.refBaseline, refPaint)
            }
        }
    }
}

/** Писание по раскладке ядра ([layoutScripture]) — для зала и превью пульта. */
@Composable
fun ScriptureSlideCanvas(c: ScriptureContent, clear: Boolean = false, overscan: Boolean = false, bg: Bitmap? = null, drawBackground: Boolean = true) {
    val context = LocalContext.current
    val typeface = remember { context.resources.getFont(R.font.noto_sans_semibold) }
    Canvas(Modifier.fillMaxSize()) {
        if (drawBackground) drawStyleBackground(c.style, bg)
        if (clear) return@Canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface }
        val lines = layoutScripture(
            c, size.width, size.height,
            measure = { s, sz -> paint.textSize = sz; paint.measureText(s) },
            ascent = { sz -> paint.textSize = sz; -paint.fontMetrics.ascent },
            descent = { sz -> paint.textSize = sz; paint.fontMetrics.descent },
            overscan = overscan,
        )
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            if (c.style.box != null && lines.isNotEmpty()) {
                val size = lines.filter { !it.ref }.maxOfOrNull { it.size } ?: lines.first().size
                val right = lines.maxOf { paint.textSize = it.size; it.x + paint.measureText(it.text) }
                val pad = size * 0.35f
                drawBox(nc, c.style, lines.minOf { it.x } - pad, lines.minOf { it.baseline - it.size } - pad * 0.6f, right + pad, lines.maxOf { it.baseline + it.size * 0.3f } + pad * 0.6f, size)
            }
            for (l in lines) {
                if (l.ref) {
                    paint.textSize = l.size
                    paint.color = c.style.color.toInt()
                    paint.clearShadowLayer()
                    paint.alpha = 190
                    nc.drawText(l.text, l.x, l.baseline, paint)
                } else {
                    drawStyledText(nc, l.text, l.x, l.baseline, l.size, paint, c.style)
                }
            }
        }
    }
}

