package app.deixis.core.effects

import kotlin.math.roundToInt

/**
 * Оценка цены сцены и порядок отключения слоёв под бюджет кадра (docs/dev/SCENE.md, 3.1–3.3).
 *
 * Единица — миллисекунды на эталонном кадре 192×108 по замеру прототипа без видеокарты. Это порядок величин:
 * приложение сверяет оценку с измеренным временем кадра и держит поправку ([plan]: `calibration`).
 */
object SceneCost {
    const val REF_WIDTH = 192
    private const val REF_PIXELS = 192.0 * 108.0
    private val SYMBOLS = setOf("cross", "dove", "angel", "flame", "starburst", "book")

    /** Во сколько раз мягкий слой при ширине [softWidth] дороже эталонного. */
    fun softScale(softWidth: Int): Double {
        val h = (softWidth * 9.0 / 16.0).roundToInt()
        return softWidth * h / REF_PIXELS
    }

    fun layerCost(l: Layer, softWidth: Int = REF_WIDTH): Double {
        val ss = softScale(softWidth)
        var c = when (l.type) {
            "gradient" -> 0.3
            "field" -> 0.4 + 0.08 * l.num("count") * ss
            "shape" -> 0.05 + 0.03 * l.num("copies") * (if (l.num("glow") > 0) 3 else 1) * (if (l.str("kind") in SYMBOLS) 1.5 else 1.0)
            "emitter" -> 0.1 + 0.012 * l.num("count")
            "rays" -> 0.1 + 0.02 * l.num("count")
            "rings" -> 0.1 + 0.04 * l.num("count")
            "thread" -> 0.1 + 0.12 * l.num("lines") + (if (l.num("glow") > 0) 0.2 * l.num("lines") else 0.0)
            "contour" -> 3.0 * ss + 0.4
            "text" -> 0.3
            else -> 0.0
        }
        for (e in l.fx) {
            if (e.off) continue
            val k = SceneCatalog.effects[e.type] ?: continue
            c += k.cost * (if (k.pixel) ss else 1.0) * (if (e.type == "kaleido") (e.p["n"] ?: 6.0) / 6.0 else 1.0)
        }
        if (l.num("avoid") > 0) c += 1.2
        return c
    }

    /** Итог планирования: какие слои отключены ради бюджета и сколько стоит то, что осталось. */
    data class Plan(val dropped: List<String>, val totalMs: Double, val keptMs: Double)

    /**
     * Что отключить, чтобы сцена уложилась в [budgetMs] (0 — без ограничения). Сначала уходят слои с наименьшим приоритетом
     * и среди равных самые дорогие; подложка, текст и слои с приоритетом 5 не отключаются никогда.
     * В [strict] считаются только слои строгого режима.
     */
    fun plan(scene: Scene, budgetMs: Double, calibration: Double = 1.0, softWidth: Int = REF_WIDTH, strict: Boolean = scene.strict): Plan {
        val strictIds = if (strict) scene.strictLayers().map { it.id }.toSet() else null
        val active = scene.layers.filter { it.on && (strictIds == null || it.id in strictIds) }
        var total = active.sumOf { layerCost(it, softWidth) }
        val all = total
        val dropped = ArrayList<String>()
        if (budgetMs > 0) {
            val candidates = active.filter { it.prio < 5 && it.type != "text" && it.type != "gradient" }
                .sortedWith(compareBy<Layer> { it.prio }.thenByDescending { layerCost(it, softWidth) })
            for (l in candidates) {
                if (total * calibration <= budgetMs) break
                dropped += l.id
                total -= layerCost(l, softWidth)
            }
        }
        return Plan(dropped, all, total)
    }
}
