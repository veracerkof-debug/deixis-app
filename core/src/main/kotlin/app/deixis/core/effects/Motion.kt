package app.deixis.core.effects

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Откуда взят темп: приоритет сверху вниз (docs/dev/EFFECTS.md, раздел 3, п. 7). */
enum class TempoSource { TAP, SLIDE, SONG, MODE }

object Tempo {
    data class Resolved(val bpm: Int, val source: TempoSource)

    /** Темп: тап (вручную) → темп слайда → темп песни → темп режима. Ноль и меньше означает «не задан». */
    fun resolve(tap: Int, slide: Int, song: Int, mode: Int): Resolved = when {
        tap > 0 -> Resolved(tap, TempoSource.TAP)
        slide > 0 -> Resolved(slide, TempoSource.SLIDE)
        song > 0 -> Resolved(song, TempoSource.SONG)
        else -> Resolved(mode, TempoSource.MODE)
    }
}

/**
 * Часы доли: фаза копится, а темп сглаживается, поэтому смена темпа не дёргает картинку. Ритм даёт движение
 * (ускорение хода и лёгкое «дыхание» масштаба), а не мигание яркости.
 */
class BeatClock(startBpm: Double = 96.0) {
    var phase: Double = 0.0
        private set
    var bpm: Double = startBpm
        private set

    fun advance(dtSec: Double, targetBpm: Double) {
        bpm += (targetBpm - bpm) * (1 - exp(-dtSec / BPM_SMOOTH_SEC))
        phase = (phase + dtSec * bpm / 60.0) % 1.0
    }

    /** Импульс доли 0…1: плавный, с мягким пиком. При темпе 0 доли нет. */
    fun envelope(targetBpm: Double = bpm): Double {
        if (targetBpm <= 0.0) return 0.0
        val c = 0.5 + 0.5 * cos(phase * 2 * PI)
        return c * c
    }

    companion object { const val BPM_SMOOTH_SEC = 0.4 }
}

object Energy {
    /** Плавное приближение текущего значения к цели; [tauSec] — постоянная времени. */
    fun approach(current: Double, target: Double, dtSec: Double, tauSec: Double): Double =
        current + (target - current) * (1 - exp(-dtSec / tauSec))

    /** Энергия внутри части: плавный (сглаженный) переход от [e0] к [e1] по доле части [fraction] 0…1. */
    fun ramp(e0: Double, e1: Double, fraction: Double): Double {
        val f = SceneMath.clamp(fraction, 0.0, 1.0)
        val s = f * f * (3 - 2 * f)
        return e0 + (e1 - e0) * s
    }
}

/** Значения источников движения в момент [t] (секунды сцены). */
object Modulation {
    fun value(m: Modulator, t: Double): Double {
        val ph = m.rate * t + m.phase
        val f = SceneMath.fract(ph)
        return when {
            m.type == "noise" -> SceneMath.valueNoise(ph * 3 + m.phase * 17)
            m.shape == "tri" -> 1 - abs(f * 2 - 1)
            m.shape == "saw" -> f
            else -> 0.5 + 0.5 * sin(f * 2 * PI)
        }
    }

    /** Все источники сцены разом: `energy`, `beat`, `t`, `pi` и `lfoN`/`noiseN`. */
    fun env(scene: Scene, t: Double, energy: Double, beat: Double): Map<String, Double> {
        val m = HashMap<String, Double>()
        m["t"] = t; m["energy"] = energy; m["beat"] = beat; m["pi"] = PI
        for (mod in scene.mods) m[mod.id] = value(mod, t)
        return m
    }
}

/** Вычисление параметра с привязкой: «база + источник × сила × диапазон» или формула. */
object Bindings {
    private val compiled = ConcurrentHashMap<String, Expr>()

    /**
     * @param base значение параметра без привязки
     * @param vars источники (`Modulation.env`); имена `base`, `i`, `n` добавляются здесь
     * @param i номер копии повторителя, [n] — число копий
     */
    fun resolve(def: ParamDef, base: Double, b: Binding?, vars: Map<String, Double>, i: Int = 0, n: Int = 1): Double {
        if (b == null) return base
        var v = base
        if (b.expr.isNotEmpty()) {
            val e = compiled.getOrPut(b.expr) { Expr.compile(b.expr, null) }
            val r = e.eval { name ->
                when (name) { "base" -> base; "i" -> i.toDouble(); "n" -> n.toDouble(); else -> vars[name] ?: 0.0 }
            }
            v = if (r.isNaN()) base else r
        } else if (b.src.isNotEmpty()) {
            val s = vars[b.src] ?: return base
            v = base + s * b.amt * (def.max - def.min)
        }
        return if (def.kind == ParamKind.COLOR) v else SceneMath.clamp(v, def.min, def.max)
    }
}
