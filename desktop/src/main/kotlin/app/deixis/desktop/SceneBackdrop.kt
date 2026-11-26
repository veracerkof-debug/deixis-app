package app.deixis.desktop

import app.deixis.core.SceneBackground
import app.deixis.core.effects.QualityGovernor
import app.deixis.core.effects.QualityTiers
import app.deixis.core.effects.Scene
import app.deixis.core.effects.SceneCost
import app.deixis.core.effects.SceneRenderer
import app.deixis.core.effects.SceneText
import app.deixis.core.effects.TextBox
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Живой фон слайдов: сцену ([app.deixis.core.effects.SceneRenderer]) считает отдельный поток 30 раз в секунду, окно рисует
 * последний кадр под прозрачным слайдом (как видеофон, [Backdrop]). Качество подбирает сторож
 * ([QualityGovernor]) по времени кадра этого компьютера: слабый считает мельче, сильный — крупнее.
 *
 * Сбой не трогает вывод: если кадр не получился, остаётся прежний (или чёрный, пока кадров не было); ошибка пишется в журнал один раз.
 */
class SceneBackdrop(private val onFrame: () -> Unit) : Backdrop {
    @Volatile private var image: BufferedImage? = null
    override val frame: BufferedImage? get() = image

    private class Config(val bg: SceneBackground, val look: String, val energy: Double, val quiet: Double?, val w: Int, val h: Int, val text: TextBox?)

    @Volatile private var cfg: Config? = null
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "scene-backdrop").apply { isDaemon = true } }
    @Volatile private var rampStart = System.nanoTime()
    private var task: ScheduledFuture<*>? = null
    private var released = false

    // Всё ниже трогает только поток рисования.
    private var built: Config? = null
    private var scene: Scene? = null
    private var renderer: SceneRenderer? = null
    private var buffers = arrayOfNulls<BufferedImage>(3)
    private var next = 0
    private var pixels = IntArray(0)
    private val governor = QualityGovernor()
    private var lastNs = 0L
    private var ema = 0.0
    private var frames = 0
    private var statNs = 0L
    private var calibration = 1.0
    private var failedOnce = false
    private val mic = DesktopMic()

    /** Текущий уровень качества (для диагностики и проверок). */
    @Volatile var tierName: String = governor.tier.name
        private set
    /** Сколько кадров в секунду реально выходит. */
    @Volatile var fps: Double = 0.0
        private set

    /**
     * Показать фон [bg] размером [w]×[h] (логические пиксели окна); [text] — где текст слайда. Повторный вызов с теми же данными
     * ничего не пересоздаёт; новая сцена или размер — перестраивают рисовальщик, движение продолжается.
     */
    @Synchronized
    fun show(bg: SceneBackground, w: Int, h: Int, text: TextBox?) {
        if (released || w < 16 || h < 16) return
        val prev = cfg?.bg
        if (prev == null || prev.energy != bg.energy || prev.energyTo != bg.energyTo || prev.rampSec != bg.rampSec) rampStart = System.nanoTime()
        cfg = Config(bg, bg.key, bg.energy.toDouble().coerceIn(0.0, 1.0), bg.quiet?.toDouble(), w, h, text)
        if (task == null) {
            lastNs = System.nanoTime(); statNs = lastNs; frames = 0
            task = exec.scheduleAtFixedRate({ tick() }, 0, FRAME_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Остановить расчёт (слайд без живого фона); последний кадр остаётся, пока окно его не уберёт. */
    @Synchronized
    fun stop() {
        task?.cancel(false); task = null
        cfg = null
        mic.stop()
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        stop()
        exec.shutdownNow()
        image = null
    }

    private fun tick() {
        try {
            val c = cfg ?: return
            val t0 = System.nanoTime()
            if (renderer == null || needRebuild(c)) rebuild(c)
            val r = renderer ?: return
            val dt = (t0 - lastNs) / 1e9
            lastNs = t0
            r.quiet = c.quiet ?: r.scene.quiet
            // Реакция на звук: микрофон включается только пока она нужна слайду; энергия реагирует быстрее обычного.
            val micOn = c.bg.mic > 0f
            if (micOn) mic.start() else mic.stop()
            val target = if (micOn) c.bg.energyWithMic((t0 - rampStart) / 1e9, mic.level) else c.bg.energyAt((t0 - rampStart) / 1e9).coerceIn(0.0, 1.0)
            r.advance(dt, target, energyTauSec = if (micOn) 0.25 else 1.2)
            r.render(pixels, c.text)
            val img = buffers[next] ?: return
            System.arraycopy(pixels, 0, (img.raster.dataBuffer as DataBufferInt).data, 0, pixels.size)
            image = img
            next = (next + 1) % buffers.size
            onFrame()
            account((System.nanoTime() - t0) / 1e6)
        } catch (e: Throwable) {
            if (!failedOnce) { failedOnce = true; app.deixis.diag.Log.w("scene", "живой фон не нарисован: ${e.message}") }
        }
    }

    private fun needRebuild(c: Config): Boolean {
        val b = built ?: return true
        return b.look != c.look || b.w != c.w || b.h != c.h
    }

    private fun rebuild(c: Config) {
        val sc = SceneText.sceneFor(c.bg) ?: run { renderer = null; built = c; return }
        val tier = governor.tier
        val w = (tier.width * CRISP_FACTOR).coerceIn(MIN_W, MAX_W).coerceAtMost(c.w.coerceAtLeast(MIN_W))
        val h = (w.toLong() * c.h / c.w).toInt().coerceAtLeast(MIN_W * 9 / 16)
        val old = renderer
        val r = SceneRenderer(sc, w, h, tier.width)
        if (old != null && old.scene === sc) r.restore(old.time, old.energy) else r.jumpEnergy(c.energy)
        applyTier(r, sc)
        scene = sc; renderer = r; built = c
        pixels = IntArray(w * h)
        buffers = Array(buffers.size) { BufferedImage(w, h, BufferedImage.TYPE_INT_RGB) }
        next = 0
        ema = 0.0
    }

    private fun applyTier(r: SceneRenderer, sc: Scene) {
        val tier = governor.tier
        r.contourEnabled = tier.contour
        r.decorEnabled = tier.fx
        r.particleScale = tier.particles
        r.dropped = SceneCost.plan(sc, BUDGET_MS, calibration, tier.width).dropped.toSet()
        tierName = tier.name
    }

    /** Учёт времени кадра раз в полсекунды: поправка цены и решение сторожа. */
    private fun account(ms: Double) {
        ema = if (ema == 0.0) ms else ema * 0.9 + ms * 0.1
        frames++
        val now = System.nanoTime()
        if (now - statNs < STAT_NS) return
        val sec = (now - statNs) / 1e9
        fps = frames / sec
        frames = 0; statNs = now
        val r = renderer ?: return
        val sc = scene ?: return
        val pred = SceneCost.plan(sc, 0.0, 1.0, r.softW).keptMs
        if (pred > 0.5) calibration = (calibration * 0.7 + (ema / pred) * 0.3).coerceIn(0.25, 6.0)
        val decision = governor.tick(ema, fps, TARGET_FPS, now / 1_000_000)
        if (decision != null) {
            app.deixis.diag.Log.i("scene", "качество живого фона: ${governor.tier.name} (${decision.reason})")
            built = null // перестроить с новым размером при следующем кадре
        } else {
            applyTier(r, sc) // бюджет мог измениться вместе с поправкой
        }
    }

    private companion object {
        const val FRAME_MS = 33L
        const val TARGET_FPS = 30
        const val STAT_NS = 500_000_000L
        /** Бюджет расчёта кадра: 60 % интервала, остальное — окну и системе. */
        const val BUDGET_MS = 20.0
        /** Кадр в 4 раза шире мягких слоёв: образы резкие, а «мыло» дёшево. */
        const val CRISP_FACTOR = 4
        const val MIN_W = 384
        const val MAX_W = 1920
    }
}
