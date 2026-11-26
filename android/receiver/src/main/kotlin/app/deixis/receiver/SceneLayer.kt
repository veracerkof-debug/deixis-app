package app.deixis.receiver

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import app.deixis.core.SceneBackground
import app.deixis.core.effects.QualityGovernor
import app.deixis.core.effects.SceneCost
import app.deixis.core.effects.SceneRenderer
import app.deixis.core.effects.SceneText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Живой фон под слайдом: кадры считает ядро ([SceneRenderer], общий с компьютером) в фоновом потоке, здесь они рисуются
 * на весь экран. Качество подбирает сторож по времени кадра этого устройства. Сбой гасит только фон: слайд остаётся.
 */
@Composable
fun SceneLayer(bg: SceneBackground) {
    val metrics = LocalContext.current.resources.displayMetrics
    var tick by remember { mutableLongStateOf(0L) }
    val holder = remember { arrayOfNulls<Bitmap>(1) }
    LaunchedEffect(bg.key, bg.energy, bg.quiet) {
        val scene = SceneText.sceneFor(bg) ?: return@LaunchedEffect
        val governor = QualityGovernor()
        var renderer: SceneRenderer? = null
        var pixels = IntArray(0)
        var calibration = 1.0
        var ema = 0.0
        var frames = 0
        var statNs = System.nanoTime()
        var last = statNs
        var fps = 0.0
        var built = false
        try {
            withContext(Dispatchers.Default) {
                while (isActive) {
                    val t0 = System.nanoTime()
                    if (!built) {
                        val tier = governor.tier
                        val w = (tier.width * CRISP).coerceIn(MIN_W, MAX_W)
                        val h = (w.toLong() * metrics.heightPixels / metrics.widthPixels.coerceAtLeast(1)).toInt().coerceAtLeast(MIN_W * 9 / 16)
                        val old = renderer
                        val r = SceneRenderer(scene, w, h, tier.width)
                        if (old != null) r.restore(old.time, old.energy) else r.jumpEnergy(bg.energy.toDouble().coerceIn(0.0, 1.0))
                        r.contourEnabled = tier.contour
                        r.decorEnabled = tier.fx
                        r.particleScale = tier.particles
                        r.dropped = SceneCost.plan(scene, BUDGET_MS, calibration, tier.width).dropped.toSet()
                        renderer = r; pixels = IntArray(w * h); built = true; ema = 0.0
                        holder[0] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    }
                    val r = renderer ?: break
                    r.quiet = bg.quiet?.toDouble() ?: scene.quiet
                    r.advance((t0 - last) / 1e9, bg.energy.toDouble().coerceIn(0.0, 1.0))
                    last = t0
                    r.render(pixels, null)
                    val bmp = holder[0]
                    if (bmp != null && bmp.width * bmp.height == pixels.size) {
                        for (i in pixels.indices) pixels[i] = pixels[i] or (0xFF shl 24)
                        bmp.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                        tick++
                    }
                    val ms = (System.nanoTime() - t0) / 1e6
                    ema = if (ema == 0.0) ms else ema * 0.9 + ms * 0.1
                    frames++
                    val now = System.nanoTime()
                    if (now - statNs >= 500_000_000L) {
                        fps = frames / ((now - statNs) / 1e9)
                        frames = 0; statNs = now
                        val pred = SceneCost.plan(scene, 0.0, 1.0, r.softW).keptMs
                        if (pred > 0.5) calibration = (calibration * 0.7 + (ema / pred) * 0.3).coerceIn(0.25, 6.0)
                        if (governor.tick(ema, fps, 30, now / 1_000_000) != null) built = false
                    }
                    delay((FRAME_MS - ms.toLong()).coerceAtLeast(2))
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            holder[0] = null // фон гаснет, слайд остаётся
        }
    }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    Canvas(Modifier.fillMaxSize()) {
        tick.let { }
        val bmp = holder[0] ?: return@Canvas
        drawIntoCanvas { it.nativeCanvas.drawBitmap(bmp, null, android.graphics.Rect(0, 0, size.width.toInt(), size.height.toInt()), paint) }
    }
}

private const val FRAME_MS = 33L
private const val BUDGET_MS = 20.0
private const val CRISP = 2
private const val MIN_W = 320
private const val MAX_W = 960
