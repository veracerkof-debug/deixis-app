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
    // Реакция на звук: микрофон нужен только пока она включена; считается лишь громкость, запись не ведётся.
    val ctx = LocalContext.current
    val micLevel = remember { doubleArrayOf(0.0) }
    var hasMic by remember { androidx.compose.runtime.mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val askMic = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { hasMic = it }
    val micWanted = bg.mic > 0f
    LaunchedEffect(micWanted, hasMic) {
        if (!micWanted) { micLevel[0] = 0.0; return@LaunchedEffect }
        if (!hasMic) { runCatching { askMic.launch(android.Manifest.permission.RECORD_AUDIO) }; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            val rate = 16000
            val min = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 2))
            } catch (e: Throwable) { return@withContext }
            val meter = app.deixis.core.effects.MicMeter()
            val buf = ShortArray(800)
            try {
                if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) return@withContext
                rec.startRecording()
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) micLevel[0] = meter.feed(buf, n, n / rate.toDouble())
                }
            } catch (e: Throwable) { /* нет микрофона — фон работает как обычно */ }
            finally { runCatching { rec.stop(); rec.release() }; micLevel[0] = 0.0 }
        }
    }
    LaunchedEffect(bg.key, bg.energy, bg.quiet, bg.energyTo, bg.rampSec, bg.mic) {
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
        val rampStart = System.nanoTime()
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
                    r.advance((t0 - last) / 1e9, bg.energyWithMic((t0 - rampStart) / 1e9, if (bg.mic > 0f) micLevel[0] else 0.0), energyTauSec = if (bg.mic > 0f) 0.25 else 1.2)
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
