package app.deixis.core.effects

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Где на кадре текст слайда, в долях кадра (0…1): центр и половины сторон. По нему слои с «не мешать тексту»
 * гасятся под текстом. Если текста нет, берётся положение текстового слоя сцены.
 */
class TextBox(val cx: Double, val cy: Double, val halfW: Double, val halfH: Double)

/**
 * Рисовальщик сцены фона: один кадр в массив пикселей, на чистом Kotlin (без Android и Swing), поэтому одинаков
 * на компьютере, телефоне и в тестах. Текст слайда рисует платформа поверх этого кадра своим движком.
 *
 * Устройство (docs/dev/SCENE.md, 2.1): **чёткие** слои (образы, фигуры, огоньки, лучи, кольца, нити) растеризуются
 * векторно в размере кадра [width]×[height] с сглаживанием; **мягкие** (краски, контур) считаются в малом размере
 * [softWidth] и растягиваются. Кадр готовится в `Float`, на выходе — RGB с лёгким дизерингом против полос.
 *
 * Сбой слоя (ошибка в формуле, неожиданное значение) выключает только этот слой ([failed]); кадр всё равно выходит.
 * Всё детерминировано: два рисовальщика с одной сценой и одинаковыми вызовами дают одинаковые кадры.
 *
 * Использование: раз в кадр [advance] (время, энергия, темп), затем [render]. Не потокобезопасен.
 */
class SceneRenderer(val scene: Scene, val width: Int, val height: Int, softWidth: Int = 192) {
    val softW: Int = softWidth.coerceIn(32, width.coerceAtLeast(32))
    val softH: Int = max(18, (softW.toDouble() * height / width).roundToInt())

    /** Секунды сцены (с учётом скорости и «тише»). */
    var time: Double = 0.0
        private set
    var energy: Double = 0.5
        private set
    private val clock = BeatClock(scene.bpm.toDouble())

    /** Мастер «тише» 0…1 (по умолчанию из сцены). */
    var quiet: Double = scene.quiet
    /** Строгий режим: подложка, краски, один образ. */
    var strict: Boolean = scene.strict
    /** Слои, отключённые ради бюджета кадра ([SceneCost.plan]). */
    var dropped: Set<String> = emptySet()
    /** Уровень качества выключил контур. */
    var contourEnabled: Boolean = true
    /** Уровень качества выключил лучи и кольца. */
    var decorEnabled: Boolean = true
    /** Доля огоньков (уровень качества). */
    var particleScale: Double = 1.0

    /** Слои, которые упали с ошибкой и больше не рисуются; id → причина. */
    val failed: MutableMap<String, String> = LinkedHashMap()
    /** Сколько слоёв нарисовано в последнем кадре. */
    var layersDrawn: Int = 0
        private set

    private val ps = height / 540.0
    private val frame = FloatArray(width * height * 3)
    private val surface = Surface(width, height)
    private val scratch = Surface(width, height)
    private val cov = Coverage(width, height)
    private val cov2 = Coverage(width, height)
    private val carve = Coverage(width, height)

    private val palette: List<FloatArray> = scene.palette.map { hex ->
        val v = hex.substring(1).toInt(16)
        floatArrayOf(((v shr 16) and 255) / 255f, ((v shr 8) and 255) / 255f, (v and 255) / 255f)
    }
    private val perCopy: Map<String, Boolean> = scene.layers.associate { l ->
        l.id to (l.type == "shape" && l.bind.values.any { it.expr.isNotEmpty() && COPY_VARS.containsMatchIn(it.expr) })
    }

    // мягкие буферы
    private val softBuf = FloatArray(softW * softH * 3)
    private val sdfA = FloatArray(softW * softH)
    private val sdfB = FloatArray(softW * softH)
    private val maskCov = Coverage(softW, softH)
    private val xi = IntArray(width); private val xf = FloatArray(width)
    private val yi = IntArray(height); private val yf = FloatArray(height)
    private val gradCache = HashMap<String, FloatArray>()
    private val trails = HashMap<String, FloatArray>()
    private var fxA = FloatArray(0)
    private var fxB = FloatArray(0)
    private var lowA = FloatArray(0)
    private var lowB = FloatArray(0)
    private var avoidShape: FloatArray? = null
    private var avoidKey: String? = null
    private val tmp3 = FloatArray(3)

    init {
        for (x in 0 until width) { val sx = ((x + 0.5) * softW / width - 0.5).coerceIn(0.0, softW - 1.0); xi[x] = min(sx.toInt(), softW - 2).coerceAtLeast(0); xf[x] = (sx - xi[x]).toFloat() }
        for (y in 0 until height) { val sy = ((y + 0.5) * softH / height - 0.5).coerceIn(0.0, softH - 1.0); yi[y] = min(sy.toInt(), softH - 2).coerceAtLeast(0); yf[y] = (sy - yi[y]).toFloat() }
    }

    /** Время вперёд на [dtSec]; [targetEnergy] — куда плавно идёт энергия; [bpm] — темп (0 — доли нет). */
    fun advance(dtSec: Double, targetEnergy: Double, bpm: Double = scene.bpm.toDouble(), energyTauSec: Double = 1.2) {
        val dt = dtSec.coerceIn(0.0, 0.25)
        time += dt * scene.speed * (1 - 0.45 * quiet)
        energy = Energy.approach(energy, targetEnergy.coerceIn(0.0, 1.0), dt, energyTauSec)
        clock.advance(dt, bpm)
    }

    /** Перенести состояние из прежнего рисовальщика (смена уровня качества не должна сбрасывать движение). */
    fun restore(fromTime: Double, fromEnergy: Double) { time = fromTime; energy = fromEnergy }

    /** Поставить энергию сразу, без плавного перехода (первый кадр, перемотка). */
    fun jumpEnergy(e: Double) { energy = e.coerceIn(0.0, 1.0) }

    /** Нарисовать кадр в [out] (RGB 0xFFRRGGBB, [width]×[height] подряд по строкам). */
    fun render(out: IntArray, text: TextBox? = null) {
        require(out.size >= width * height) { "Массив кадра мал: ${out.size} < ${width * height}" }
        updateText(text ?: sceneTextBox())
        val env = Modulation.env(scene, time, energy, if (scene.bpm > 0 || clock.bpm > 0) clock.envelope(clock.bpm) else 0.0)
        java.util.Arrays.fill(frame, 0f)
        val active = activeLayers()
        var drawn = 0
        for (l in active) {
            if (l.id in failed) continue
            try { drawLayer(l, env); drawn++ } catch (e: Exception) { failed[l.id] = e.message ?: e.javaClass.simpleName }
        }
        layersDrawn = drawn
        quantize(out)
    }

    private fun activeLayers(): List<Layer> {
        val strictIds = if (strict) scene.strictLayers().map { it.id }.toSet() else null
        return scene.layers.filter { l ->
            l.on && l.type != "text" && l.id !in dropped && (strictIds == null || l.id in strictIds) &&
                (contourEnabled || l.type != "contour") && (decorEnabled || (l.type != "rays" && l.type != "rings"))
        }
    }

    // ---------------------------------------------------------------- цвет

    private fun colorAt(ci: Double, out: FloatArray) {
        val n = palette.size
        var x = ci % n; if (x < 0) x += n
        val i = x.toInt(); val f = (x - i).toFloat()
        val a = palette[i]; val b = palette[(i + 1) % n]
        var r = a[0] + (b[0] - a[0]) * f; var g = a[1] + (b[1] - a[1]) * f; var bl = a[2] + (b[2] - a[2]) * f
        if (quiet > 0) { val l = 0.3f * r + 0.59f * g + 0.11f * bl; val k = (quiet * 0.6).toFloat(); r += (l - r) * k; g += (l - g) * k; bl += (l - bl) * k }
        out[0] = r; out[1] = g; out[2] = bl
    }

    private fun hex(h: String, out: FloatArray) { val v = h.substring(1).toInt(16); out[0] = ((v shr 16) and 255) / 255f; out[1] = ((v shr 8) and 255) / 255f; out[2] = (v and 255) / 255f }

    // ---------------------------------------------------------------- параметры

    private inner class Q(val layer: Layer, val env: Map<String, Double>, val i: Int = 0, val n: Int = 1) {
        private val kind = SceneCatalog.layers.getValue(layer.type)
        operator fun get(key: String): Double {
            val base = layer.num(key)
            val b = layer.bind[key] ?: return base
            return Bindings.resolve(kind.byKey.getValue(key), base, b, env, i, n)
        }
        fun str(key: String): String = layer.str(key)
    }

    private inner class EQ(val e: Effect, val env: Map<String, Double>) {
        private val kind = SceneCatalog.effects.getValue(e.type)
        operator fun get(key: String): Double {
            val base = e.p[key] ?: kind.byKey.getValue(key).def
            val b = e.bind[key] ?: return base
            return Bindings.resolve(kind.byKey.getValue(key), base, b, env)
        }
    }

    // ---------------------------------------------------------------- слои

    private fun drawLayer(l: Layer, env: Map<String, Double>) {
        val q = Q(l, env)
        val opacity = (q["opacity"] * (1 - quiet * (CALM[l.type] ?: 0.0))).coerceIn(0.0, 1.0).toFloat()
        val avoid = q["avoid"].toFloat()
        val blend = BLEND_MODES[l.blend] ?: 0
        val fx = l.fx.filter { !it.off }
        when (l.type) {
            "gradient" -> {
                val g = gradient(l, q)
                if (fx.isEmpty()) compositeRgb(g, width, height, blend, opacity, avoid)
                else { rgbToSurface(g, width, height); crispFinish(l, env, fx, opacity, avoid, blend) }
            }
            "field" -> {
                drawField(l, q)
                if (fx.isEmpty()) compositeRgb(softBuf, softW, softH, blend, opacity, avoid)
                else { rgbToSurface(softBuf, softW, softH); crispFinish(l, env, fx, opacity, avoid, blend) }
            }
            "contour" -> {
                if (!drawContour(l, q, env)) return
                if (fx.isEmpty()) compositeRgb(softBuf, softW, softH, blend, opacity, avoid)
                else { rgbToSurface(softBuf, softW, softH); crispFinish(l, env, fx, opacity, avoid, blend) }
            }
            else -> {
                surface.clear()
                when (l.type) {
                    "shape" -> drawShape(l, q, env)
                    "emitter" -> drawEmitter(l, q)
                    "rays" -> drawRays(q)
                    "rings" -> drawRings(q)
                    "thread" -> drawThread(q)
                }
                crispFinish(l, env, fx, opacity, avoid, blend)
            }
        }
    }

    // ---------- подложка

    private fun gradient(l: Layer, q: Q): FloatArray {
        val key = "${l.str("kind")}|${l.str("c1")}|${l.str("c2")}|${q["angle"]}"
        return gradCache.getOrPut(key) {
            val a = FloatArray(3); val b = FloatArray(3); hex(l.str("c1"), a); hex(l.str("c2"), b)
            val buf = FloatArray(width * height * 3)
            val radial = l.str("kind") == "radial"
            val an = q["angle"] * PI / 180
            val dx = cos(an) * width / 2; val dy = sin(an) * height / 2
            val len2 = max(1e-9, dx * dx + dy * dy)
            val maxR = hypot(width.toDouble(), height.toDouble()) / 2
            var o = 0
            for (y in 0 until height) for (x in 0 until width) {
                val px = x + 0.5 - width / 2.0; val py = y + 0.5 - height / 2.0
                val t = if (radial) (hypot(px, py) / maxR).coerceIn(0.0, 1.0) else ((px * dx + py * dy) / len2 * 0.5 + 0.5).coerceIn(0.0, 1.0)
                val tf = t.toFloat()
                buf[o++] = a[0] + (b[0] - a[0]) * tf; buf[o++] = a[1] + (b[1] - a[1]) * tf; buf[o++] = a[2] + (b[2] - a[2]) * tf
            }
            buf
        }
    }

    // ---------- краски (мягкие пятна)

    private fun drawField(l: Layer, q: Q) {
        java.util.Arrays.fill(softBuf, 0f)
        val n = q["count"].toInt()
        val tt = time * q["speed"]
        val move = l.str("move")
        val soft = q["soft"].toFloat()
        val col = FloatArray(3)
        for (i in 0 until n) {
            val h1 = SceneMath.hash(i.toDouble(), scene.seed + 20.0); val h2 = SceneMath.hash(i.toDouble(), scene.seed + 21.0)
            val h3 = SceneMath.hash(i.toDouble(), scene.seed + 22.0); val h4 = SceneMath.hash(i.toDouble(), scene.seed + 23.0)
            val h5 = SceneMath.hash(i.toDouble(), scene.seed + 24.0)
            val x: Double; val y: Double
            when (move) {
                "orbit" -> { val a = tt * 0.15 * (h1 + 0.4) + h2 * 2 * PI; val r = 0.15 + h3 * 0.3; x = 0.5 + cos(a) * r * height / width * 1.6; y = 0.5 + sin(a) * r }
                "drift" -> { x = ((h1 + tt * 0.012 * (h2 + 0.3)) % 1.3) - 0.15; y = 0.5 + sin(tt * 0.2 * (h3 + 0.3) + h4 * 2 * PI) * 0.28 }
                else -> { x = h1 * 0.7 + 0.15 + sin(tt * (0.1 + h2 * 0.16) + h3 * 2 * PI) * 0.22; y = h4 * 0.6 + 0.2 + cos(tt * (0.09 + h3 * 0.14) * 1.3 + h5 * 2 * PI) * 0.2 }
            }
            val rad = q["size"] * softH * (0.6 + h5 * 0.6) * (1 + 0.1 * sin(tt * 0.5 + h4 * 2 * PI))
            colorAt(q["c"] + q["spread"] * h2 * 3, col)
            val cx = x * softW; val cy = y * softH
            val x0 = max(0, floor(cx - rad).toInt()); val x1 = min(softW - 1, ceil(cx + rad).toInt())
            val y0 = max(0, floor(cy - rad).toInt()); val y1 = min(softH - 1, ceil(cy + rad).toInt())
            for (py in y0..y1) for (px in x0..x1) {
                val d = (hypot(px + 0.5 - cx, py + 0.5 - cy) / rad).toFloat()
                if (d >= 1f) continue
                val a = if (d < 0.5f) soft * (1f - 1.16f * d) else soft * 0.42f * (1f - (d - 0.5f) * 2f)
                val o = (py * softW + px) * 3
                softBuf[o] = 1f - (1f - softBuf[o]) * (1f - col[0] * a)
                softBuf[o + 1] = 1f - (1f - softBuf[o + 1]) * (1f - col[1] * a)
                softBuf[o + 2] = 1f - (1f - softBuf[o + 2]) * (1f - col[2] * a)
            }
        }
    }

    // ---------- образы и фигуры

    private fun layoutOff(layout: String, k: Int, n: Int, spread: Double, out: DoubleArray) {
        out[0] = 0.0; out[1] = 0.0; out[2] = 0.0
        if (n < 2) return
        val w = width.toDouble(); val h = height.toDouble()
        when (layout) {
            "line" -> out[0] = ((k.toDouble() / (n - 1)) - 0.5) * spread * w
            "circle" -> { val a = k.toDouble() / n * 2 * PI; out[0] = cos(a) * spread * h * 0.5; out[1] = sin(a) * spread * h * 0.5 }
            "grid" -> {
                val cols = max(1, ceil(sqrt(n * w / h)).toInt()); val rows = ceil(n.toDouble() / cols).toInt()
                out[0] = ((k % cols) - (cols - 1) / 2.0) * spread * w / cols; out[1] = ((k / cols) - (rows - 1) / 2.0) * spread * h / rows
            }
            "spiral" -> { val r = spread * h * 0.5 * sqrt((k + 0.5) / n); val a = k * 2.39996; out[0] = cos(a) * r; out[1] = sin(a) * r }
            "scatter" -> { out[0] = (SceneMath.hash(k.toDouble(), scene.seed.toDouble()) - 0.5) * spread * w; out[1] = (SceneMath.hash(k.toDouble(), scene.seed + 9.0) - 0.5) * spread * h }
            "fan" -> out[2] = (k - (n - 1) / 2.0) * spread * 60
        }
    }

    private val off3 = DoubleArray(3)
    private val col3 = FloatArray(3)

    /** Образы в [surface]; в режиме [maskCov] — только заливка контуров в покрытие (для контура) в масштабе [sc]. */
    private fun drawShape(l: Layer, q0: Q, env: Map<String, Double>, maskCov: Coverage? = null, sc: Double = 1.0) {
        val kind = l.str("kind")
        val glyphs = SceneGlyphs.of(kind, q0["pn"].toInt()) ?: return
        val n = max(1, q0["copies"].toInt())
        val per = perCopy[l.id] == true
        val symbol = kind in SceneGlyphs.SYMBOLS
        val layout = l.str("layout")
        for (k in 0 until n) {
            val q = if (per) Q(l, env, k, n) else q0
            layoutOff(layout, k, n, q["spread"], off3)
            val jx = if (q["jitter"] > 0) (SceneMath.hash(k.toDouble(), scene.seed + 3.0) - 0.5) * q["jitter"] * width * 0.1 else 0.0
            val jy = if (q["jitter"] > 0) (SceneMath.hash(k.toDouble(), scene.seed + 4.0) - 0.5) * q["jitter"] * height * 0.1 else 0.0
            val s = max(0.5, q["size"] * height / 2 * q["stepScale"].pow(k)) * sc
            val rot = (q["rot"] + q["stepRot"] * k + q["spin"] * time + off3[2]) * PI / 180
            val ox = (q["x"] * width + off3[0] + jx) * sc; val oy = (q["y"] * height + off3[1] + jy) * sc
            val alpha = (q["alpha"] * (1 - q["fade"] * (if (n > 1) k.toDouble() / (n - 1) else 0.0))).toFloat()
            val cr = cos(rot); val sr = sin(rot)
            if (maskCov != null) {
                for (g in glyphs) {
                    if (g.carve > 0) continue
                    if (g.strokeW != 0.0) maskCov.polyline(transform(g.pts, ox, oy, s, cr, sr), g.closed, if (g.strokeW > 0) g.strokeW * s else max(q["stroke"], 4.0) * ps * sc)
                    else maskCov.fillPoly(transform(g.pts, ox, oy, s, cr, sr))
                }
                continue
            }
            colorAt(q["c"] + q["cstep"] * k, col3)
            val fill = q["fill"].toFloat(); val glow = q["glow"] * ps; val stroke = q["stroke"] * ps
            // заливка
            cov.clear(); carve.clear(); cov2.clear()
            for (g in glyphs) {
                val pts = transform(g.pts, ox, oy, s, cr, sr)
                when {
                    g.carve > 0 -> carve.fillPoly(pts)
                    g.strokeW > 0 -> cov2.polyline(pts, g.closed, g.strokeW * s)
                    g.strokeW < 0 -> cov2.polyline(pts, g.closed, max(q["stroke"], 2.0) * ps)
                    else -> cov.fillPoly(pts)
                }
            }
            if (!carve.empty && !cov.empty) {
                val w = width
                for (y in max(cov.minY, carve.minY)..min(cov.maxY, carve.maxY)) for (x in max(cov.minX, carve.minX)..min(cov.maxX, carve.maxX)) {
                    val i = y * w + x
                    cov.a[i] *= (1f - 0.5f * carve.a[i])
                }
            }
            if (glow > 0 && alpha > 0f) glowFromCoverage(if (cov.empty) cov2 else cov, glow, min(1f, alpha))
            if (fill > 0f) surface.paint(cov, col3[0], col3[1], col3[2], alpha * fill)
            if (!cov2.empty) surface.paint(cov2, col3[0], col3[1], col3[2], alpha)
            if (stroke > 0 && !symbol && glyphs.none { it.strokeW != 0.0 }) {
                cov.clear()
                for (g in glyphs) if (g.carve == 0.0) cov.polyline(transform(g.pts, ox, oy, s, cr, sr), true, stroke)
                surface.paint(cov, col3[0], col3[1], col3[2], alpha)
            }
        }
    }

    private fun transform(pts: DoubleArray, ox: Double, oy: Double, s: Double, cr: Double, sr: Double): DoubleArray {
        val out = DoubleArray(pts.size)
        var i = 0
        while (i < pts.size) { val x = pts[i]; val y = pts[i + 1]; out[i] = ox + (x * cr - y * sr) * s; out[i + 1] = oy + (x * sr + y * cr) * s; i += 2 }
        return out
    }

    /** Мягкое свечение вокруг покрытия [c]: уменьшенная копия, размытие, наложение цвета [col3] под фигуру. */
    private fun glowFromCoverage(c: Coverage, radiusPx: Double, alpha: Float) {
        if (c.empty || radiusPx < 1) return
        val f = 4
        val pad = ceil(radiusPx * 1.6).toInt()
        val rx0 = max(0, c.minX - pad); val ry0 = max(0, c.minY - pad); val rx1 = min(width - 1, c.maxX + pad); val ry1 = min(height - 1, c.maxY + pad)
        val lw = (rx1 - rx0) / f + 1; val lh = (ry1 - ry0) / f + 1
        if (lowA.size < lw * lh) { lowA = FloatArray(lw * lh); lowB = FloatArray(lw * lh) }
        java.util.Arrays.fill(lowA, 0, lw * lh, 0f)
        val inv = 1f / (f * f)
        for (y in c.minY..c.maxY) for (x in c.minX..c.maxX) {
            val v = c.a[y * width + x]
            if (v > 0f) lowA[((y - ry0) / f) * lw + (x - rx0) / f] += v * inv
        }
        val r = max(1, round(radiusPx / 2 / f / 0.8).toInt())
        boxBlur1(lowA, lowB, lw, lh, r); boxBlur1(lowA, lowB, lw, lh, r)
        for (y in ry0..ry1) {
            val fy = ((y - ry0 + 0.5f) / f - 0.5f).coerceIn(0f, lh - 1f); val y0 = min(fy.toInt(), max(0, lh - 2)); val ty = fy - y0
            for (x in rx0..rx1) {
                val fx = ((x - rx0 + 0.5f) / f - 0.5f).coerceIn(0f, lw - 1f); val x0 = min(fx.toInt(), max(0, lw - 2)); val tx = fx - x0
                val x1 = min(x0 + 1, lw - 1); val y1 = min(y0 + 1, lh - 1)
                val v = (lowA[y0 * lw + x0] * (1 - tx) + lowA[y0 * lw + x1] * tx) * (1 - ty) + (lowA[y1 * lw + x0] * (1 - tx) + lowA[y1 * lw + x1] * tx) * ty
                if (v > 0.003f) surface.over(x, y, col3[0], col3[1], col3[2], min(1f, v * alpha))
            }
        }
    }

    // ---------- излучатель

    private fun drawEmitter(l: Layer, q: Q) {
        val n = (q["count"] * (1 - 0.6 * quiet) * particleScale).toInt()
        val a0 = q["dir"] * PI / 180
        val kind = l.str("kind")
        val col = FloatArray(3)
        val w = width.toDouble(); val h = height.toDouble()
        val size = q["size"]; val speed = q["speed"]; val spread = q["spread"]; val twinkle = q["twinkle"]; val alphaMax = q["alpha"]; val c0 = q["c"]; val cs = q["cspread"]
        for (i in 0 until n) {
            val s = scene.seed.toDouble(); val d = i.toDouble()
            val h1 = SceneMath.hash(d, s); val h2 = SceneMath.hash(d, s + 1); val h3 = SceneMath.hash(d, s + 2)
            val h4 = SceneMath.hash(d, s + 3); val h5 = SceneMath.hash(d, s + 4); val h6 = SceneMath.hash(d, s + 5)
            val sp = 0.35 + h2 * 0.65
            val u = (((h3 + time * 0.05 * speed * sp) % 1) + 1) % 1
            val a = a0 + (h4 - 0.5) * spread * PI / 180
            val dx = cos(a); val dy = sin(a); val px = -dy; val py = dx
            val x = w / 2 + (-dx * 0.75 * h + px * (h1 - 0.5) * 1.5 * h) + dx * u * 1.5 * h + sin(time * 0.4 + h6 * 2 * PI) * 0.01 * h
            val y = h / 2 + (-dy * 0.75 * h + py * (h1 - 0.5) * 1.5 * h) + dy * u * 1.5 * h
            if (x < -40 || x > w + 40 || y < -40 || y > h + 40) continue
            val r = size * h * (0.5 + h5)
            val fade = sin(PI * u); val tw = 1 - twinkle + twinkle * (0.5 + 0.5 * sin(time * 3 + h6 * 2 * PI))
            val al = (alphaMax * fade * tw).toFloat()
            if (al < 0.01f) continue
            colorAt(c0 + cs * h6, col)
            if (kind == "dot") dot(x, y, r * 2.2, col, al)
            else {
                val glyph = SceneGlyphs.of(if (kind == "star") "star" else if (kind == "cross") "cross" else "ring", 4) ?: continue
                cov.clear()
                val rot = time * 0.3 + h6 * 2 * PI; val cr = cos(rot); val sr = sin(rot); val sz = r * 1.4
                for (g in glyph) { val pts = transform(g.pts, x, y, sz, cr, sr); if (g.strokeW != 0.0) cov.polyline(pts, true, max(1.5 * ps, 1.0)) else cov.fillPoly(pts) }
                surface.paint(cov, col[0], col[1], col[2], al)
            }
        }
    }

    private fun dot(x: Double, y: Double, rad: Double, col: FloatArray, al: Float) {
        val x0 = max(0, floor(x - rad).toInt()); val x1 = min(width - 1, ceil(x + rad).toInt())
        val y0 = max(0, floor(y - rad).toInt()); val y1 = min(height - 1, ceil(y + rad).toInt())
        for (py in y0..y1) for (px in x0..x1) {
            val t = (hypot(px + 0.5 - x, py + 0.5 - y) / rad).toFloat()
            if (t >= 1f) continue
            val cr: Float; val cg: Float; val cb: Float; val a: Float
            if (t < 0.35f) { val m = t / 0.35f; cr = 1f + (col[0] - 1f) * m; cg = 1f + (col[1] - 1f) * m; cb = 1f + (col[2] - 1f) * m; a = al * (1f - m * 0.4f) }
            else { cr = col[0]; cg = col[1]; cb = col[2]; a = al * 0.6f * (1f - (t - 0.35f) / 0.65f) }
            surface.over(px, py, cr, cg, cb, a)
        }
    }

    // ---------- лучи, кольца, нити

    private fun drawRays(q: Q) {
        val n = q["count"].toInt(); val ox = q["ox"] * width; val oy = q["oy"] * height
        colorAt(q["c"], col3)
        cov.clear()
        val sp = q["spin"] * time * PI / 180
        val w = max(1.0, q["width"] * height)
        for (i in 0 until n) {
            val a = i.toDouble() / n * 2 * PI + sp
            cov.segment(ox + cos(a) * q["inner"] * height, oy + sin(a) * q["inner"] * height, ox + cos(a) * q["len"] * height, oy + sin(a) * q["len"] * height, w)
        }
        surface.paint(cov, col3[0], col3[1], col3[2], q["alpha"].toFloat())
    }

    private fun drawRings(q: Q) {
        val n = q["count"].toInt(); val ox = q["ox"] * width; val oy = q["oy"] * height
        colorAt(q["c"], col3)
        val hw = max(1.0, q["width"] * height) / 2
        val dash = q["dash"]
        val on = dash * 40 + 4; val off = dash * 30 + 4
        for (k in 0 until n) {
            val u = (((k.toDouble() / n) + time * 0.08 * q["speed"]) % 1 + 1) % 1
            val r = u * q["gap"] * n * height
            if (r < 1) continue
            val al = (q["alpha"] * (1 - u)).toFloat()
            val ro = r + hw + 1; val ri = max(0.0, r - hw - 1)
            val y0 = max(0, floor(oy - ro).toInt()); val y1 = min(height - 1, ceil(oy + ro).toInt())
            for (py in y0..y1) {
                val dy = py + 0.5 - oy
                if (abs(dy) > ro) continue
                val xo = sqrt(max(0.0, ro * ro - dy * dy)); val xin = sqrt(max(0.0, ri * ri - dy * dy))
                for (side in 0..1) {
                    val xa: Int; val xb: Int
                    if (side == 0) { xa = floor(ox - xo).toInt(); xb = ceil(ox - xin).toInt() } else { xa = floor(ox + xin).toInt(); xb = ceil(ox + xo).toInt() }
                    for (px in max(0, xa)..min(width - 1, xb)) {
                        val dx = px + 0.5 - ox
                        val d = hypot(dx, dy)
                        var cv = (hw + 0.5 - abs(d - r)).coerceIn(0.0, 1.0)
                        if (cv <= 0.0) continue
                        if (dash > 0) { val ang = atan2(dy, dx) + PI; val t = (ang * r) % (on + off); if (t > on) continue }
                        surface.over(px, py, col3[0], col3[1], col3[2], (cv * al).toFloat())
                    }
                }
            }
        }
    }

    private fun drawThread(q: Q) {
        val lines = q["lines"].toInt()
        val isWave = q.str("kind") != "lissajous"
        val m = 90
        val pts = DoubleArray((m + 1) * 2)
        val w = width.toDouble(); val h = height.toDouble()
        val amp = q["amp"]; val freq = q["freq"]; val speed = q["speed"]; val y0 = q["y"]; val thick = max(1.0, q["thick"] * ps)
        for (j in 0 until lines) {
            colorAt(q["c"] + q["cspread"] * j, col3)
            for (s in 0..m) {
                val u = s.toDouble() / m
                val x: Double; val y: Double
                if (isWave) { x = (u * 1.1 - 0.05) * w; y = y0 * h + sin(u * 2 * PI * freq + time * speed * 1.2 + j * 0.9) * amp * h * (0.6 + 0.4 * sin(time * 0.3 + j)) }
                else { val ang = u * 2 * PI; x = w / 2 + sin(ang * freq + time * speed * 0.5 + j) * amp * h * 1.6; y = y0 * h + sin(ang * (freq * 0.5 + 1) + time * speed * 0.4) * amp * h * 1.2 + (j - lines / 2.0) * 6 * ps }
                pts[s * 2] = x; pts[s * 2 + 1] = y
            }
            cov.clear(); cov.polyline(pts, false, thick)
            val glow = q["glow"] * ps
            if (glow > 0) glowFromCoverage(cov, glow, q["alpha"].toFloat())
            surface.paint(cov, col3[0], col3[1], col3[2], q["alpha"].toFloat())
        }
    }

    // ---------- контур: расстояние до границы фигуры, считается в малом размере каждый кадр

    private fun drawContour(l: Layer, q: Q, env: Map<String, Double>): Boolean {
        val src = scene.layer(l.str("src")) ?: return false
        if (src.type != "shape" || !src.on) return false
        maskCov.clear()
        drawShape(src, Q(src, env), env, maskCov, softW.toDouble() / width)
        val n = softW * softH
        for (i in 0 until n) { val inside = maskCov.a[i] > 0.43f; sdfA[i] = if (inside) 0f else 1e6f; sdfB[i] = if (inside) 1e6f else 0f }
        chamfer(sdfA, softW, softH); chamfer(sdfB, softW, softH)
        val ring = q["rings"]; val irid = q["irid"].toFloat(); val str = q["strength"].toFloat(); val reach = q["reach"]; val sp = q["speed"]
        val lut = paletteLut()
        val c0 = q["c"]
        var o = 0
        for (i in 0 until n) {
            val dd = (sdfA[i] - sdfB[i]) / softH.toDouble()
            val ad = abs(dd)
            val edge = exp(-ad * 38)
            val rs = sin(dd * ring - time * 2.2 * sp); val rip = 0.5 + 0.5 * rs
            val fall = exp(-(if (dd > 0) dd else 0.0) * reach) * (if (dd < 0) 0.5 else 1.0)
            val v = (0.5 * edge + 0.95 * rip * rip * fall).toFloat()
            val ph = dd * 1.3 - time * 0.07 * sp
            val idx = ((((c0 + ph * 3) % 5.0) + 5.0) % 5.0 / 5.0 * (LUT_N - 1)).toInt()
            val pr = lut[idx * 3]; val pg = lut[idx * 3 + 1]; val pb = lut[idx * 3 + 2]
            val rr = (((127 + 128 * cos(2 * PI * ph * 1.6)) * 0.75 + 64) / 255.0).toFloat()
            val rg = (((127 + 128 * cos(2 * PI * (ph * 1.6 + 0.33))) * 0.75 + 64) / 255.0).toFloat()
            val rb = (((127 + 128 * cos(2 * PI * (ph * 1.6 + 0.67))) * 0.75 + 64) / 255.0).toFloat()
            var r = pr + (rr - pr) * irid; var g = pg + (rg - pg) * irid; var b = pb + (rb - pb) * irid
            val hl = (edge * 0.55).toFloat()
            r += (1f - r) * hl; g += (1f - g) * hl; b += (1f - b) * hl
            softBuf[o++] = min(1f, r * v * str); softBuf[o++] = min(1f, g * v * str); softBuf[o++] = min(1f, b * v * str)
        }
        return true
    }

    private var lutQuiet = Double.NaN
    private val lut = FloatArray(LUT_N * 3)
    private fun paletteLut(): FloatArray {
        if (lutQuiet != quiet) {
            lutQuiet = quiet
            for (i in 0 until LUT_N) { colorAt(i.toDouble() / (LUT_N - 1) * 5.0, tmp3); lut[i * 3] = tmp3[0]; lut[i * 3 + 1] = tmp3[1]; lut[i * 3 + 2] = tmp3[2] }
        }
        return lut
    }

    private fun chamfer(a: FloatArray, w: Int, h: Int) {
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; var v = a[i]
            if (x > 0 && a[i - 1] + 1 < v) v = a[i - 1] + 1
            if (y > 0) {
                if (a[i - w] + 1 < v) v = a[i - w] + 1
                if (x > 0 && a[i - w - 1] + 1.414f < v) v = a[i - w - 1] + 1.414f
                if (x < w - 1 && a[i - w + 1] + 1.414f < v) v = a[i - w + 1] + 1.414f
            }
            a[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x; var v = a[i]
            if (x < w - 1 && a[i + 1] + 1 < v) v = a[i + 1] + 1
            if (y < h - 1) {
                if (a[i + w] + 1 < v) v = a[i + w] + 1
                if (x < w - 1 && a[i + w + 1] + 1.414f < v) v = a[i + w + 1] + 1.414f
                if (x > 0 && a[i + w - 1] + 1.414f < v) v = a[i + w - 1] + 1.414f
            }
            a[i] = v
        }
    }

    // ---------------------------------------------------------------- «не мешать тексту»

    private fun sceneTextBox(): TextBox {
        val t = scene.layers.firstOrNull { it.type == "text" }
        return if (t != null) TextBox(t.num("x"), t.num("y"), 0.28, 0.16) else TextBox(0.5, 0.45, 0.28, 0.16)
    }

    private fun updateText(box: TextBox) {
        val key = "${box.cx}|${box.cy}|${box.halfW}|${box.halfH}"
        if (key == avoidKey) return
        avoidKey = key
        val shape = avoidShape ?: FloatArray(width * height).also { avoidShape = it }
        val rx = max(box.halfW * 1.25, 0.14) * width; val ry = max(box.halfH * 1.35, 0.08) * height
        val cx = box.cx * width; val cy = box.cy * height
        for (y in 0 until height) {
            val dy = (y + 0.5 - cy) / ry
            for (x in 0 until width) {
                val dx = (x + 0.5 - cx) / rx
                val r = sqrt(dx * dx + dy * dy)
                shape[y * width + x] = (if (r <= 0.65) 0.95 + (0.6 - 0.95) * (r / 0.65) else if (r < 1.0) 0.6 * (1 - (r - 0.65) / 0.35) else 0.0).toFloat()
            }
        }
    }

    // ---------------------------------------------------------------- наложение на кадр

    /** Результат смешивания канала: [d] — кадр, [c] — цвет слоя, [ca] — сила, [mode] — режим. */
    private fun mixc(mode: Int, d: Float, c: Float, ca: Float): Float = when (mode) {
        1 -> 1f - (1f - d) * (1f - c * ca)
        2 -> min(1f, d + c * ca)
        3 -> d * (1f - ca + c * ca)
        4 -> { val ov = if (d < 0.5f) 2f * d * c else 1f - 2f * (1f - d) * (1f - c); d + (ov - d) * ca }
        5 -> { val sl = (1f - 2f * c) * d * d + 2f * c * d; d + (sl - d) * ca }
        else -> d + (c - d) * ca
    }

    /** Наложить цветной буфер (мягкий слой или подложка) на кадр; другой размер растягивается с сглаживанием. */
    private fun compositeRgb(src: FloatArray, sw: Int, sh: Int, mode: Int, opacity: Float, avoid: Float) {
        val same = sw == width && sh == height
        val shape = if (avoid > 0f) avoidShape else null
        val full = mode == 0 && opacity >= 0.999f && shape == null && same
        if (full) { System.arraycopy(src, 0, frame, 0, frame.size); return }
        var i = 0
        for (y in 0 until height) {
            val y0 = if (same) y else yi[y]; val fy = if (same) 0f else yf[y]
            for (x in 0 until width) {
                val cr: Float; val cg: Float; val cb: Float
                if (same) { val o = i * 3; cr = src[o]; cg = src[o + 1]; cb = src[o + 2] }
                else {
                    val x0 = xi[x]; val fx = xf[x]
                    val o00 = (y0 * sw + x0) * 3; val o10 = o00 + 3; val o01 = o00 + sw * 3; val o11 = o01 + 3
                    val w00 = (1 - fx) * (1 - fy); val w10 = fx * (1 - fy); val w01 = (1 - fx) * fy; val w11 = fx * fy
                    cr = src[o00] * w00 + src[o10] * w10 + src[o01] * w01 + src[o11] * w11
                    cg = src[o00 + 1] * w00 + src[o10 + 1] * w10 + src[o01 + 1] * w01 + src[o11 + 1] * w11
                    cb = src[o00 + 2] * w00 + src[o10 + 2] * w10 + src[o01 + 2] * w01 + src[o11 + 2] * w11
                }
                val ca = if (shape != null) opacity * (1f - avoid * shape[i]) else opacity
                val o = i * 3
                frame[o] = mixc(mode, frame[o], cr, ca); frame[o + 1] = mixc(mode, frame[o + 1], cg, ca); frame[o + 2] = mixc(mode, frame[o + 2], cb, ca)
                i++
            }
        }
    }

    /** Слой с прозрачностью на кадр; [filt] — цветовой фильтр (матрица 3×3 и яркость) или null. */
    private fun compositeSurface(s: Surface, mode: Int, opacity: Float, avoid: Float, filt: FloatArray?) {
        if (s.empty) return
        val shape = if (avoid > 0f) avoidShape else null
        for (y in s.y0..s.y1) {
            var i = y * width + s.x0
            for (x in s.x0..s.x1) {
                val o4 = i * 4
                val a = s.px[o4 + 3]
                if (a > 0.0015f) {
                    var pr = s.px[o4]; var pg = s.px[o4 + 1]; var pb = s.px[o4 + 2]
                    if (filt != null) {
                        val r = filt[0] * pr + filt[1] * pg + filt[2] * pb; val g = filt[3] * pr + filt[4] * pg + filt[5] * pb; val b = filt[6] * pr + filt[7] * pg + filt[8] * pb
                        pr = (r * filt[9]).coerceIn(0f, a); pg = (g * filt[9]).coerceIn(0f, a); pb = (b * filt[9]).coerceIn(0f, a)
                    }
                    val k = if (shape != null) opacity * (1f - avoid * shape[i]) else opacity
                    val ca = a * k
                    val o = i * 3
                    if (mode >= 4) {
                        val inv = 1f / a
                        frame[o] = mixc(mode, frame[o], pr * inv, ca); frame[o + 1] = mixc(mode, frame[o + 1], pg * inv, ca); frame[o + 2] = mixc(mode, frame[o + 2], pb * inv, ca)
                    } else {
                        frame[o] = pm(mode, frame[o], pr * k, ca); frame[o + 1] = pm(mode, frame[o + 1], pg * k, ca); frame[o + 2] = pm(mode, frame[o + 2], pb * k, ca)
                    }
                }
                i++
            }
        }
    }

    private fun pm(mode: Int, d: Float, p: Float, ca: Float): Float = when (mode) {
        1 -> 1f - (1f - d) * (1f - p)
        2 -> min(1f, d + p)
        3 -> d * (1f - ca) + d * p
        else -> d * (1f - ca) + p
    }

    /** Цветной буфер в поверхность: прозрачность = самый яркий канал (чёрное прозрачно, как при осветлении). */
    private fun rgbToSurface(src: FloatArray, sw: Int, sh: Int) {
        surface.clear(); surface.touchAll()
        var i = 0
        for (y in 0 until height) {
            val y0 = if (sh == height) y else yi[y]; val fy = if (sh == height) 0f else yf[y]
            for (x in 0 until width) {
                val cr: Float; val cg: Float; val cb: Float
                if (sw == width && sh == height) { val o = i * 3; cr = src[o]; cg = src[o + 1]; cb = src[o + 2] }
                else {
                    val x0 = xi[x]; val fx = xf[x]
                    val o00 = (y0 * sw + x0) * 3; val o10 = o00 + 3; val o01 = o00 + sw * 3; val o11 = o01 + 3
                    val w00 = (1 - fx) * (1 - fy); val w10 = fx * (1 - fy); val w01 = (1 - fx) * fy; val w11 = fx * fy
                    cr = src[o00] * w00 + src[o10] * w10 + src[o01] * w01 + src[o11] * w11
                    cg = src[o00 + 1] * w00 + src[o10 + 1] * w10 + src[o01 + 1] * w01 + src[o11 + 1] * w11
                    cb = src[o00 + 2] * w00 + src[o10 + 2] * w10 + src[o01 + 2] * w01 + src[o11 + 2] * w11
                }
                val o4 = i * 4
                surface.px[o4] = cr; surface.px[o4 + 1] = cg; surface.px[o4 + 2] = cb; surface.px[o4 + 3] = max(cr, max(cg, cb))
                i++
            }
        }
    }

    // ---------------------------------------------------------------- эффекты слоя

    private fun crispFinish(l: Layer, env: Map<String, Double>, fx: List<Effect>, opacity: Float, avoid: Float, blend: Int) {
        var blurR = 0.0; var bloomR = 0.0; var bloomA = 0.0
        var filt: FloatArray? = null
        for (e in fx) {
            val eq = EQ(e, env)
            when (e.type) {
                "blur" -> blurR = eq["r"] * ps
                "bloom" -> { bloomR = eq["r"] * ps; bloomA = eq["amt"] }
                "color" -> filt = colorMatrix(eq["hue"], eq["sat"] / 100, eq["bri"] / 100)
                "warp" -> warpSurface(eq)
                "kaleido" -> kaleidoSurface(eq)
                "trails" -> trailsSurface(l.id, eq)
            }
        }
        if (bloomA > 0 && !surface.empty) { blurInto(surface, scratch, bloomR); compositeSurface(scratch, 2, (opacity * bloomA).toFloat(), avoid, null) }
        if (blurR > 0 && !surface.empty) { blurInto(surface, scratch, blurR); compositeSurface(scratch, blend, opacity, avoid, filt) }
        else compositeSurface(surface, blend, opacity, avoid, filt)
    }

    /** Матрица 3×3 (оттенок, затем насыщенность) по строкам и яркость в [9]. */
    private fun colorMatrix(hueDeg: Double, sat: Double, bri: Double): FloatArray {
        val a = hueDeg * PI / 180; val c = cos(a); val s = sin(a)
        val h = arrayOf(
            doubleArrayOf(0.213 + c * 0.787 - s * 0.213, 0.715 - c * 0.715 - s * 0.715, 0.072 - c * 0.072 + s * 0.928),
            doubleArrayOf(0.213 - c * 0.213 + s * 0.143, 0.715 + c * 0.285 + s * 0.140, 0.072 - c * 0.072 - s * 0.283),
            doubleArrayOf(0.213 - c * 0.213 - s * 0.787, 0.715 - c * 0.715 + s * 0.715, 0.072 + c * 0.928 + s * 0.072),
        )
        val lum = doubleArrayOf(0.213, 0.715, 0.072)
        val m = FloatArray(10)
        for (r in 0 until 3) for (cc in 0 until 3) {
            var v = 0.0
            for (k in 0 until 3) v += ((1 - sat) * lum[k] + (if (r == k) sat else 0.0)) * h[k][cc]
            m[r * 3 + cc] = v.toFloat()
        }
        m[9] = bri.toFloat()
        return m
    }

    /** Размытая копия [src] в [dst] (уменьшение в 4 раза, размытие рамкой, растяжение): для свечения и размытия слоя. */
    private fun blurInto(src: Surface, dst: Surface, radiusPx: Double) {
        dst.clear()
        if (src.empty) return
        val f = 4
        val pad = ceil(radiusPx * 1.6).toInt()
        val rx0 = max(0, src.x0 - pad); val ry0 = max(0, src.y0 - pad); val rx1 = min(width - 1, src.x1 + pad); val ry1 = min(height - 1, src.y1 + pad)
        val lw = (rx1 - rx0) / f + 1; val lh = (ry1 - ry0) / f + 1
        val need = lw * lh * 4
        if (fxA.size < need) { fxA = FloatArray(need); fxB = FloatArray(need) }
        java.util.Arrays.fill(fxA, 0, need, 0f)
        val inv = 1f / (f * f)
        for (y in src.y0..src.y1) for (x in src.x0..src.x1) {
            val o = (y * width + x) * 4
            if (src.px[o + 3] <= 0f && src.px[o] <= 0f) continue
            val l = (((y - ry0) / f) * lw + (x - rx0) / f) * 4
            fxA[l] += src.px[o] * inv; fxA[l + 1] += src.px[o + 1] * inv; fxA[l + 2] += src.px[o + 2] * inv; fxA[l + 3] += src.px[o + 3] * inv
        }
        val r = max(1, round(radiusPx / 2 / f / 0.8).toInt())
        boxBlur4(fxA, fxB, lw, lh, r); boxBlur4(fxA, fxB, lw, lh, r)
        for (y in ry0..ry1) {
            val fy = ((y - ry0 + 0.5f) / f - 0.5f).coerceIn(0f, lh - 1f); val y0 = min(fy.toInt(), max(0, lh - 2)); val ty = fy - y0; val y1 = min(y0 + 1, lh - 1)
            for (x in rx0..rx1) {
                val fx = ((x - rx0 + 0.5f) / f - 0.5f).coerceIn(0f, lw - 1f); val x0 = min(fx.toInt(), max(0, lw - 2)); val tx = fx - x0; val x1 = min(x0 + 1, lw - 1)
                val o = (y * width + x) * 4
                for (ch in 0 until 4) {
                    val v = (fxA[(y0 * lw + x0) * 4 + ch] * (1 - tx) + fxA[(y0 * lw + x1) * 4 + ch] * tx) * (1 - ty) + (fxA[(y1 * lw + x0) * 4 + ch] * (1 - tx) + fxA[(y1 * lw + x1) * 4 + ch] * tx) * ty
                    dst.px[o + ch] = v
                }
            }
        }
        dst.touch(rx0, ry0, rx1, ry1)
    }

    private fun boxBlur1(a: FloatArray, tmp: FloatArray, w: Int, h: Int, r: Int) {
        val k = 1f / (2 * r + 1)
        for (y in 0 until h) {
            var acc = 0f
            for (i in -r..r) acc += a[y * w + i.coerceIn(0, w - 1)]
            for (x in 0 until w) { tmp[y * w + x] = acc * k; acc += a[y * w + min(x + r + 1, w - 1)] - a[y * w + max(x - r, 0)] }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (i in -r..r) acc += tmp[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) { a[y * w + x] = acc * k; acc += tmp[min(y + r + 1, h - 1) * w + x] - tmp[max(y - r, 0) * w + x] }
        }
    }

    private fun boxBlur4(a: FloatArray, tmp: FloatArray, w: Int, h: Int, r: Int) {
        val k = 1f / (2 * r + 1)
        for (ch in 0 until 4) {
            for (y in 0 until h) {
                var acc = 0f
                for (i in -r..r) acc += a[(y * w + i.coerceIn(0, w - 1)) * 4 + ch]
                for (x in 0 until w) { tmp[(y * w + x) * 4 + ch] = acc * k; acc += a[(y * w + min(x + r + 1, w - 1)) * 4 + ch] - a[(y * w + max(x - r, 0)) * 4 + ch] }
            }
            for (x in 0 until w) {
                var acc = 0f
                for (i in -r..r) acc += tmp[(i.coerceIn(0, h - 1) * w + x) * 4 + ch]
                for (y in 0 until h) { a[(y * w + x) * 4 + ch] = acc * k; acc += tmp[(min(y + r + 1, h - 1) * w + x) * 4 + ch] - tmp[(max(y - r, 0) * w + x) * 4 + ch] }
            }
        }
    }

    /** Поверхность → мягкий буфер RGBA (усреднение блоков). */
    private fun surfaceToSoft(dst: FloatArray) {
        val bx = ceil(width.toDouble() / softW).toInt(); val by = ceil(height.toDouble() / softH).toInt()
        for (sy in 0 until softH) for (sx in 0 until softW) {
            var r = 0f; var g = 0f; var b = 0f; var a = 0f; var cnt = 0
            val x0 = sx * width / softW; val y0 = sy * height / softH
            for (yy in y0 until min(height, y0 + by)) for (xx in x0 until min(width, x0 + bx)) {
                val o = (yy * width + xx) * 4
                r += surface.px[o]; g += surface.px[o + 1]; b += surface.px[o + 2]; a += surface.px[o + 3]; cnt++
            }
            val o = (sy * softW + sx) * 4; val inv = if (cnt > 0) 1f / cnt else 0f
            dst[o] = r * inv; dst[o + 1] = g * inv; dst[o + 2] = b * inv; dst[o + 3] = a * inv
        }
    }

    /** Мягкий буфер RGBA → поверхность (растяжение с сглаживанием). */
    private fun softToSurface(src: FloatArray) {
        surface.clear(); surface.touchAll()
        for (y in 0 until height) {
            val y0 = yi[y]; val fy = yf[y]
            for (x in 0 until width) {
                val x0 = xi[x]; val fx = xf[x]
                val o00 = (y0 * softW + x0) * 4; val o10 = o00 + 4; val o01 = o00 + softW * 4; val o11 = o01 + 4
                val w00 = (1 - fx) * (1 - fy); val w10 = fx * (1 - fy); val w01 = (1 - fx) * fy; val w11 = fx * fy
                val o = (y * width + x) * 4
                for (ch in 0 until 4) surface.px[o + ch] = src[o00 + ch] * w00 + src[o10 + ch] * w10 + src[o01 + ch] * w01 + src[o11 + ch] * w11
            }
        }
    }

    private fun sampleSoft(a: FloatArray, x: Double, y: Double, out: FloatArray) {
        val sx = x.coerceIn(0.0, softW - 1.0); val sy = y.coerceIn(0.0, softH - 1.0)
        val x0 = min(sx.toInt(), softW - 2).coerceAtLeast(0); val y0 = min(sy.toInt(), softH - 2).coerceAtLeast(0)
        val fx = (sx - x0).toFloat(); val fy = (sy - y0).toFloat()
        val o00 = (y0 * softW + x0) * 4; val o10 = o00 + 4; val o01 = o00 + softW * 4; val o11 = o01 + 4
        for (ch in 0 until 4) out[ch] = (a[o00 + ch] * (1 - fx) + a[o10 + ch] * fx) * (1 - fy) + (a[o01 + ch] * (1 - fx) + a[o11 + ch] * fx) * fy
    }

    private fun ensureSoft4() { val n = softW * softH * 4; if (fxA.size < n) { fxA = FloatArray(n); fxB = FloatArray(n) } }

    private val px4 = FloatArray(4)

    private fun warpSurface(eq: EQ) {
        if (surface.empty) return
        ensureSoft4(); val src = FloatArray(softW * softH * 4)
        surfaceToSoft(src)
        val dst = FloatArray(softW * softH * 4)
        val a = eq["amt"]; val f = eq["freq"]; val tt = time * eq["speed"]
        for (y in 0 until softH) {
            val v = (y + 0.5) / softH
            for (x in 0 until softW) {
                val u = (x + 0.5) / softW
                val p = sin(v * f + tt * 0.8 + 1.7 * sin(u * f * 0.75 - tt * 0.6)); val r = cos(u * f - tt * 0.7 + 1.7 * cos(v * f * 0.75 + tt * 0.5))
                sampleSoft(src, (u + a * 0.12 * p) * (softW - 1), (v + a * 0.12 * r) * (softH - 1), px4)
                val o = (y * softW + x) * 4
                dst[o] = px4[0]; dst[o + 1] = px4[1]; dst[o + 2] = px4[2]; dst[o + 3] = px4[3]
            }
        }
        softToSurface(dst)
    }

    private fun kaleidoSurface(eq: EQ) {
        if (surface.empty) return
        val src = FloatArray(softW * softH * 4); surfaceToSoft(src)
        val dst = FloatArray(softW * softH * 4)
        val n = max(2, eq["n"].toInt()); val wedge = 2 * PI / n
        val cx = eq["cx"] * softW; val cy = eq["cy"] * softH
        for (y in 0 until softH) for (x in 0 until softW) {
            val dx = x + 0.5 - cx; val dy = y + 0.5 - cy
            val r = hypot(dx, dy)
            var ang = atan2(dy, dx); if (ang < 0) ang += 2 * PI
            val k = floor(ang / wedge).toInt(); var local = ang - k * wedge
            if (k % 2 == 1) local = wedge - local
            sampleSoft(src, cx + r * cos(local) - 0.5, cy + r * sin(local) - 0.5, px4)
            val o = (y * softW + x) * 4
            dst[o] = px4[0]; dst[o + 1] = px4[1]; dst[o + 2] = px4[2]; dst[o + 3] = px4[3]
        }
        softToSurface(dst)
    }

    private fun trailsSurface(id: String, eq: EQ) {
        val n = softW * softH * 4
        val cur = FloatArray(n); surfaceToSoft(cur)
        val prev = trails.getOrPut(id) { FloatArray(n) }
        val next = FloatArray(n)
        val decay = eq["decay"].toFloat().coerceAtMost(0.96f); val zoom = eq["zoom"]; val rot = eq["rot"] * PI / 180
        val cx = softW / 2.0; val cy = softH / 2.0; val c = cos(-rot); val s = sin(-rot)
        for (y in 0 until softH) for (x in 0 until softW) {
            val dx = (x + 0.5 - cx) / zoom; val dy = (y + 0.5 - cy) / zoom
            sampleSoft(prev, cx + dx * c - dy * s - 0.5, cy + dx * s + dy * c - 0.5, px4)
            val o = (y * softW + x) * 4
            for (ch in 0 until 4) next[o + ch] = min(1f, px4[ch] * decay + cur[o + ch])
        }
        System.arraycopy(next, 0, prev, 0, n)
        softToSurface(next)
    }

    // ---------------------------------------------------------------- вывод

    private fun quantize(out: IntArray) {
        var i = 0
        for (y in 0 until height) {
            val row = (y and 63) * 64
            for (x in 0 until width) {
                val d = DITHER[row + (x and 63)]
                val o = i * 3
                val r = (frame[o] * 255f + d).toInt().coerceIn(0, 255)
                val g = (frame[o + 1] * 255f + d).toInt().coerceIn(0, 255)
                val b = (frame[o + 2] * 255f + d).toInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                i++
            }
        }
    }

    private fun Double.roundToInt(): Int = round(this).toInt()

    companion object {
        private val COPY_VARS = Regex("\\b(i|n)\\b")
        private const val LUT_N = 512
        private val CALM = mapOf("field" to 0.45, "contour" to 0.6, "emitter" to 0.8, "rays" to 0.8, "rings" to 0.6, "thread" to 0.5)
        private val BLEND_MODES = mapOf("source-over" to 0, "screen" to 1, "lighter" to 2, "multiply" to 3, "overlay" to 4, "soft-light" to 5)
        /** Шум 64×64 в [0,1): дизеринг при переводе в 8 бит, чтобы в плавных градиентах не было полос. */
        private val DITHER = FloatArray(4096) { SceneMath.hash(it.toDouble(), 5.0).toFloat() }
    }
}
