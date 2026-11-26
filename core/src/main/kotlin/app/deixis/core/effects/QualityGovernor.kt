package app.deixis.core.effects

/** Уровень качества фона (docs/dev/EFFECTS.md, 5а). [width] — ширина расчёта мягких слоёв; [crispAdj] — поправка чёткости образа. */
data class QualityTier(
    val name: String,
    val width: Int,
    val fx: Boolean,
    val contour: Boolean,
    val particles: Double,
    val crispAdj: Int,
)

object QualityTiers {
    val ALL: List<QualityTier> = listOf(
        QualityTier("Минимум", 96, fx = false, contour = false, particles = 0.3, crispAdj = -15),
        QualityTier("Лёгкий", 128, fx = true, contour = false, particles = 0.6, crispAdj = -10),
        QualityTier("Обычный", 192, fx = true, contour = true, particles = 1.0, crispAdj = 0),
        QualityTier("Хороший", 320, fx = true, contour = true, particles = 1.0, crispAdj = 7),
        QualityTier("Лучший", 480, fx = true, contour = true, particles = 1.0, crispAdj = 11),
    )
    const val DEFAULT_INDEX = 2
    /** Безопасное умолчание, когда замер не удался. */
    const val SAFE_INDEX = 1
}

/**
 * Сторож качества: чистый конечный автомат без Android и Swing. Платформа раз в полсекунды отдаёт время кадра и
 * частоту ([tick]) и применяет уровень, если сторож его сменил. Поведение проверено на прототипе в обоих направлениях:
 * вниз быстро, вверх медленно и по прогнозу, после спада подъём на оставленный уровень запрещён на минуту.
 */
class QualityGovernor(
    startIndex: Int = QualityTiers.DEFAULT_INDEX,
    private val tiers: List<QualityTier> = QualityTiers.ALL,
) {
    var index: Int = startIndex.coerceIn(0, tiers.size - 1)
        private set
    val tier: QualityTier get() = tiers[index]

    private var hi = 0
    private var lo = 0
    private var cool = 0
    private var ticks = 0
    private val ban = HashMap<Int, Long>()

    data class Decision(val index: Int, val reason: String)

    /** Начать заново (включён «Авто», сменился монитор). */
    fun reset(startIndex: Int = QualityTiers.DEFAULT_INDEX) {
        index = startIndex.coerceIn(0, tiers.size - 1)
        hi = 0; lo = 0; cool = 0; ticks = 0; ban.clear()
    }

    /**
     * Один отсчёт (раз в 0,5 с). [frameMs] — сглаженное время расчёта кадра, [fps] — реальная частота, [targetFps] — цель,
     * [nowMs] — монотонное время. Возвращает решение о смене уровня или null.
     */
    fun tick(frameMs: Double, fps: Double, targetFps: Int, nowMs: Long): Decision? {
        val budget = 1000.0 / targetFps
        val load = frameMs / budget
        ticks++
        if (cool > 0) cool--
        val bad = load > 0.6 || fps < 0.8 * targetFps
        when {
            bad -> { hi++; lo = 0 }
            load < 0.2 -> { lo++; hi = 0 }
            else -> { hi = 0; lo = 0 }
        }
        if (hi >= 2 && index > 0) {
            ban[index] = nowMs + BAN_MS
            hi = 0; cool = 8
            index--
            return Decision(index, "нагрузка ${(load * 100).toInt()} %, ${fps.toInt()} к/с")
        }
        val need = if (ticks < 14) 2 else 6
        if (lo >= need && cool <= 0 && index < tiers.size - 1 && (ban[index + 1] ?: 0L) <= nowMs) {
            val cur = tiers[index]
            val nx = tiers[index + 1]
            val k = (nx.width.toDouble() / cur.width).let { it * it } *
                (if (nx.contour && !cur.contour) 1.45 else 1.0) * (if (nx.fx && !cur.fx) 1.2 else 1.0)
            if (frameMs * k < PROJECTION_SHARE * budget) {
                lo = 0; cool = 12
                index++
                return Decision(index, "запас ${((1 - load) * 100).toInt()} %, прогноз ${"%.1f".format(frameMs * k)} мс")
            }
        }
        return null
    }

    companion object {
        /** Сколько после спада нельзя подниматься на оставленный уровень. */
        const val BAN_MS = 60_000L
        /** Доля бюджета кадра, в которую должен укладываться прогноз для подъёма (прототип показал: прогноз оптимистичен на треть). */
        const val PROJECTION_SHARE = 0.36
    }
}
