package app.deixis.core.ai

import app.deixis.core.effects.Layer
import app.deixis.core.effects.ParamDef
import app.deixis.core.effects.ParamKind
import app.deixis.core.effects.Scene
import app.deixis.core.effects.SceneCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Раздел параметров в студии: человеку понятнее «Положение», «Размер», «Движение», «Цвет», чем список ключей. */
enum class ParamGroup { LOOK, PLACE, SIZE, MOTION, COLOR }

/** Готовые виды движения параметра: человек выбирает «Пульс» или «Дыхание», а не источник и коэффициент. */
enum class Motion { PULSE, SONG, BREATH, DRIFT, SHIMMER }

/**
 * Логика ручной студии фона (docs/dev/SCENE.md, раздел 12), без рисования: разбивка параметров по разделам, новые слои
 * и эффекты с умолчаниями каталога, движение параметра готовыми видами. Всё превращается в обычные операции [SceneOps],
 * поэтому у человека и у нейросети один путь и одна проверка.
 */
object SceneStudio {
    val GROUP_ORDER = listOf(ParamGroup.LOOK, ParamGroup.PLACE, ParamGroup.SIZE, ParamGroup.MOTION, ParamGroup.COLOR)

    private val PLACE = setOf("x", "y", "cx", "cy", "ox", "oy")
    private val SIZE = setOf("size", "r", "width", "thick", "len", "inner", "gap", "stroke", "glow")
    private val MOTION = setOf("speed", "spin", "rot", "freq", "zoom", "move", "amp", "stepRot", "twinkle", "dir", "jitter", "decay")

    fun group(def: ParamDef): ParamGroup = when {
        def.kind == ParamKind.COLOR || def.kind == ParamKind.HEX -> ParamGroup.COLOR
        def.label.contains("цвет", ignoreCase = true) -> ParamGroup.COLOR
        def.key in PLACE -> ParamGroup.PLACE
        def.key in SIZE -> ParamGroup.SIZE
        def.key in MOTION -> ParamGroup.MOTION
        else -> ParamGroup.LOOK
    }

    /** Параметры слоя (без общих «видимость» и «уступать тексту», они вынесены наверх) по разделам. */
    fun grouped(layer: Layer): List<Pair<ParamGroup, List<ParamDef>>> {
        val kind = SceneCatalog.layers[layer.type] ?: return emptyList()
        val own = kind.own.filter { it.kind != ParamKind.LAYER && !(layer.type == "text" && it.key == "text" && layer.str("source") != "fixed") }
        return GROUP_ORDER.mapNotNull { g -> own.filter { group(it) == g }.takeIf { it.isNotEmpty() }?.let { g to it } }
    }

    /** Слой нового вида с умолчаниями каталога и свободным id (`fld1`, `shp2`…). */
    fun newLayer(scene: Scene, type: String): JsonObject {
        val kind = requireNotNull(SceneCatalog.layers[type]) { "нет вида слоя «$type»" }
        val prefix = type.take(3)
        val id = generateSequence(1) { it + 1 }.map { "$prefix$it" }.first { id -> scene.layers.none { it.id == id } }
        return buildJsonObject {
            put("id", id); put("type", type); put("name", kind.name.substringBefore(" (").take(40)); put("on", true)
            put("prio", if (kind.rendering == app.deixis.core.effects.Rendering.CRISP) 3 else 4)
            put("p", JsonObject(kind.params.associate { d ->
                // Контуру нужен образ: берём первый в сцене (без образа слой не добавить, см. [canAdd]).
                d.key to if (d.kind == ParamKind.LAYER) JsonPrimitive(scene.layers.firstOrNull { it.type == "shape" }?.id.orEmpty()) else defaultOf(d)
            }))
        }
    }

    /** Слой этого вида сейчас можно добавить: контур волнами идёт от образа, поэтому без образа в сцене он недоступен. */
    fun canAdd(scene: Scene, type: String): Boolean = type != "contour" || scene.layers.any { it.type == "shape" }

    fun newEffect(type: String): JsonObject {
        val kind = requireNotNull(SceneCatalog.effects[type]) { "нет эффекта «$type»" }
        return buildJsonObject { put("type", type); put("p", JsonObject(kind.params.associate { it.key to JsonPrimitive(it.def) })) }
    }

    private fun defaultOf(d: ParamDef): JsonPrimitive = when (d.kind) {
        ParamKind.NUM, ParamKind.COLOR -> JsonPrimitive(d.def)
        else -> JsonPrimitive(d.defText)
    }

    /** Какой готовый вид движения у параметра сейчас; null — параметр неподвижен или привязан по-своему (формула). */
    fun motionOf(scene: Scene, layer: Layer, key: String): Motion? {
        val b = layer.bind[key] ?: return null
        if (b.expr.isNotEmpty()) return null
        return when (b.src) {
            "beat" -> Motion.PULSE
            "energy" -> Motion.SONG
            else -> {
                val m = scene.mods.firstOrNull { it.id == b.src } ?: return null
                when (m.type) { "lfo" -> Motion.BREATH; else -> if (m.rate >= 0.3) Motion.SHIMMER else Motion.DRIFT }
            }
        }
    }

    fun strengthOf(layer: Layer, key: String): Double = layer.bind[key]?.amt ?: 0.0

    /** Операции, которые включают движение [motion] у параметра [key] слоя с силой [strength] (доля диапазона, 0…1). */
    fun motionOps(scene: Scene, layerId: String, key: String, motion: Motion, strength: Double): List<SceneOp> {
        val amt = Math.round(strength.coerceIn(0.0, 1.0) * 100) / 100.0
        val ops = ArrayList<SceneOp>()
        val src = when (motion) {
            Motion.PULSE -> "beat"
            Motion.SONG -> "energy"
            Motion.BREATH, Motion.DRIFT, Motion.SHIMMER -> {
                val type = if (motion == Motion.BREATH) "lfo" else "noise"
                val rate = when (motion) { Motion.BREATH -> 0.15; Motion.DRIFT -> 0.08; else -> 0.6 }
                // Есть подходящий источник — берём его, иначе заводим новый.
                scene.mods.firstOrNull { it.type == type && (rate >= 0.3) == (it.rate >= 0.3) }?.id
                    ?: generateSequence(1) { it + 1 }.map { "$type$it" }.first { id -> scene.mods.none { it.id == id } }.also { id ->
                        ops += SceneOp("mod.add", buildJsonObject { put("mod", buildJsonObject { put("id", id); put("type", type); put("rate", rate); put("shape", "sine"); put("phase", 0.0) }) })
                    }
            }
        }
        ops += SceneOp("layer.bind", buildJsonObject { put("layer", layerId); put("param", key); put("src", src); put("amt", amt) })
        return ops
    }

    fun stillOps(layerId: String, key: String): List<SceneOp> =
        listOf(SceneOp("layer.unbind", buildJsonObject { put("layer", layerId); put("param", key) }))

    /** Пустая сцена для «с нуля»: тёмная подложка и текст слайда; остальное человек добавляет сам. */
    fun blank(): Scene {
        val base = app.deixis.core.effects.SceneLooks.load("plain")
        val keep = base.layers.filter { it.type == "gradient" || it.type == "text" }
        return base.copy(id = "mine", title = "", about = "", mods = emptyList(), layers = keep.map { it.copy(fx = emptyList(), bind = emptyMap()) })
    }
}
