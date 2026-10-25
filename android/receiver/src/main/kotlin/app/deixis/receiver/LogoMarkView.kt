package app.deixis.receiver

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import app.deixis.core.LogoMark
import app.deixis.core.LogoStyle

/** Заставка «Логотип» во весь экран: ΔΕΙΞΙΣ, большая Δ значка, δεῖξις или Deixis. */
@Composable
fun LogoFull(style: LogoStyle = LogoStyle.WORDMARK, color: Color = Color.White) {
    val context = LocalContext.current
    val didot = remember { context.resources.getFont(R.font.gfs_didot) }
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { drawLogoMark(it.nativeCanvas, size.width, size.height, style, didot, color.toArgb()) }
    }
}

/** Знак по раскладке ядра ([LogoMark]); общий для экрана, превью пульта и картинок для DLNA. */
fun drawLogoMark(c: android.graphics.Canvas, w: Float, h: Float, style: LogoStyle, didot: Typeface, color: Int) {
    val text = LogoMark.text(style)
    if (text == null) {
        val (outer, inner) = LogoMark.delta(w, h)
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            for (tri in listOf(outer, inner)) {
                moveTo(tri[0].first, tri[0].second)
                tri.drop(1).forEach { (x, y) -> lineTo(x, y) }
                close()
            }
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        return
    }
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = didot
        letterSpacing = LogoMark.tracking(style)
        this.color = color
        textAlign = Paint.Align.CENTER
        textSize = PROBE
    }
    // Разрядка Android добавляет место и после последней буквы: вычитаем его из ширины и из сдвига.
    val em = (p.measureText(text) - PROBE * p.letterSpacing) / PROBE
    p.textSize = LogoMark.size(w, h, em, style)
    c.drawText(text, w / 2f + p.textSize * p.letterSpacing / 2f, h / 2f - (p.ascent() + p.descent()) / 2, p)
}

private const val PROBE = 100f
