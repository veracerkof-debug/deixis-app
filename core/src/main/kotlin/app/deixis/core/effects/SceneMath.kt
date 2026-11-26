package app.deixis.core.effects

import kotlin.math.floor
import kotlin.math.sin

/**
 * Общая математика сцены. Всё детерминировано: никакой настоящей случайности и текущего времени,
 * поэтому сцена выглядит одинаково при каждом показе и в каждой репетиции (docs/dev/SCENE.md, 3.5).
 * Формулы совпадают с прототипом `prototype/effects-scene.html`, чтобы картинка не расходилась.
 */
object SceneMath {
    fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v

    fun fract(x: Double): Double = x - floor(x)

    /** Псевдослучайное число 0…1 по номеру и зерну. */
    fun hash(i: Double, seed: Double): Double = fract(sin(i * 127.1 + seed * 311.7 + 13.37) * 43758.5453)

    /** Гладкий шум по одной оси. */
    fun valueNoise(x: Double): Double {
        val i = floor(x)
        val f = x - i
        val u = f * f * (3 - 2 * f)
        return hash(i, 1.0) * (1 - u) + hash(i + 1, 1.0) * u
    }

    fun smoothstep(a: Double, b: Double, x: Double): Double {
        val t = clamp((x - a) / (b - a), 0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
}
