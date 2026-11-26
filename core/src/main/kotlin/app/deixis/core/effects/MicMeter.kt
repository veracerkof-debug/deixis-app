package app.deixis.core.effects

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Уровень звука для живого фона: из блоков отсчётов микрофона получается число 0…1, плавное и само подстраивающееся под зал
 * (тихий зал и громкий дают одинаковый размах). Записи нет и быть не может: сюда попадает только громкость, звук не сохраняется
 * и никуда не уходит.
 */
class MicMeter {
    private var floor = -60.0
    private var peak = -30.0
    var level = 0.0
        private set

    /** Новый блок отсчётов 16 бит; [dtSec] — его длительность. Возвращает сглаженный уровень 0…1. */
    fun feed(samples: ShortArray, count: Int = samples.size, dtSec: Double = 0.05): Double {
        if (count <= 0) return level
        var sum = 0.0
        for (i in 0 until count) { val v = samples[i] / 32768.0; sum += v * v }
        val db = 20 * ln(sqrt(sum / count).coerceAtLeast(1e-5)) / LN10
        // Дно и пик подстраиваются: дно ползёт к тишине медленно вверх, пик — к громкому медленно вниз.
        floor = if (db < floor) db else floor + (db - floor) * 0.002
        peak = if (db > peak) db else peak + (db - peak) * 0.01
        val span = (peak - floor).coerceAtLeast(MIN_SPAN_DB)
        val raw = ((db - floor) / span).coerceIn(0.0, 1.0)
        // Быстро вверх (удар слышен сразу), медленно вниз (не дрожит).
        val k = if (raw > level) 1 - Math.exp(-dtSec / 0.04) else 1 - Math.exp(-dtSec / 0.35)
        level += (raw - level) * k
        return level
    }

    fun reset() { floor = -60.0; peak = -30.0; level = 0.0 }

    private companion object {
        val LN10 = ln(10.0)
        const val MIN_SPAN_DB = 12.0
    }
}
