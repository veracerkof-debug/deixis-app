package app.deixis.core.ai

import app.deixis.core.DeixisJson
import app.deixis.core.effects.Scene
import app.deixis.core.effects.SceneIssue
import app.deixis.core.effects.SceneLooks
import app.deixis.core.effects.SceneValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Одна операция предложения: [op] — её имя из каталога, [args] — остальные поля как их прислала нейросеть или редактор. */
data class SceneOp(val op: String, val args: JsonObject) {
    companion object {
        fun parse(e: JsonElement): SceneOp? {
            val o = e as? JsonObject ?: return null
            val name = (o["op"] as? JsonPrimitive)?.contentOrNull ?: return null
            return SceneOp(name, JsonObject(o.filterKeys { it != "op" }))
        }
    }
}

/** Результат одной операции: новое дерево сцены и описание «было → стало» для человека. */
sealed interface OpResult {
    data class Done(val root: JsonObject, val line: String, val dangerous: Boolean) : OpResult
    data class Failed(val message: String) : OpResult
}

/**
 * Каталог операций над сценой (docs/dev/SCENE.md, раздел 5). Один и тот же код у редактора слоёв и у нейросети.
 * Операции работают над деревом JSON, а итог целиком проходит [SceneValidator]: потолки формата и ссылки проверяются там,
 * поэтому операция не может создать сцену, которую нельзя показать. Вкус не проверяется: это дело инструкции нейросети.
 */
object SceneOps {
    /** Операции каталога; неизвестное имя — отказ с этим списком в сообщении. */
    val NAMES = listOf(
        "scene.set", "layer.add", "layer.set", "layer.remove", "layer.move", "layer.duplicate",
        "effect.add", "effect.set", "effect.remove", "layer.bind", "layer.unbind", "mod.add", "mod.remove", "look.apply",
    )
    private val SCENE_KEYS = setOf("title", "about", "seed", "bpm", "speed", "quiet", "strict", "palette")
    private val LAYER_META = setOf("name", "on", "prio", "blend")

    fun toTree(scene: Scene): JsonObject = DeixisJson.encodeToJsonElement(Scene.serializer(), scene).jsonObject

    fun apply(root: JsonObject, op: SceneOp): OpResult = try {
        run(root, op)
    } catch (e: Exception) {
        OpResult.Failed("${op.op}: ${e.message ?: "не получилось"}")
    }

    /** Применить все операции подряд к [scene] и проверить итог. Ошибка любой операции или проверки отменяет всё. */
    fun applyAll(scene: Scene, ops: List<SceneOp>): Outcome {
        var root = toTree(scene)
        val lines = ArrayList<String>()
        var dangerous = false
        for ((i, op) in ops.withIndex()) {
            when (val r = apply(root, op)) {
                is OpResult.Failed -> return Outcome(null, lines, false, listOf("[$i] ${r.message}"))
                is OpResult.Done -> { root = r.root; lines += r.line; dangerous = dangerous || r.dangerous }
            }
        }
        val report = SceneValidator.validateElement(root)
        val made = report.scene ?: return Outcome(null, lines, dangerous, report.errors.map(::describe))
        return Outcome(made, lines, dangerous, emptyList(), report.warnings.map(::describe))
    }

    class Outcome(val scene: Scene?, val lines: List<String>, val dangerous: Boolean, val errors: List<String>, val warnings: List<String> = emptyList())

    private fun describe(i: SceneIssue) = "${i.path.ifEmpty { "сцена" }}: ${i.message} (${i.code})"

    // ───────────── операции ─────────────

    private fun run(root: JsonObject, op: SceneOp): OpResult {
        val a = op.args
        return when (op.op) {
            "scene.set" -> {
                val key = str(a, "key"); require(key in SCENE_KEYS) { "параметр сцены «$key» не из списка $SCENE_KEYS" }
                val v = a["value"] ?: error("нет value")
                val old = root[key]
                done(JsonObject(root + (key to v)), "сцена: $key ${show(old)} → ${show(v)}")
            }
            "layer.add" -> {
                val layer = a["layer"] as? JsonObject ?: error("нет layer")
                val id = (layer["id"] as? JsonPrimitive)?.contentOrNull ?: error("у слоя нет id")
                require(layers(root).none { id(it) == id }) { "слой «$id» уже есть" }
                val list = layers(root).toMutableList()
                val at = (a["at"] as? JsonPrimitive)?.intOrNull ?: list.size
                list.add(at.coerceIn(0, list.size), layer)
                done(setLayers(root, list), "слой «${(layer["name"] as? JsonPrimitive)?.contentOrNull ?: id}»: добавлен (${(layer["type"] as? JsonPrimitive)?.contentOrNull})")
            }
            "layer.set" -> {
                val id = str(a, "id"); val set = a["set"] as? JsonObject ?: error("нет set")
                require(set.isNotEmpty()) { "set пуст" }
                val changes = ArrayList<String>()
                done(mapLayer(root, id) { l ->
                    var cur = l
                    var p = (l["p"] as? JsonObject) ?: JsonObject(emptyMap())
                    for ((k, v) in set) {
                        if (k in LAYER_META) { changes += "$k ${show(cur[k])} → ${show(v)}"; cur = JsonObject(cur + (k to v)) }
                        else { changes += "$k ${show(p[k])} → ${show(v)}"; p = JsonObject(p + (k to v)) }
                    }
                    JsonObject(cur + ("p" to p))
                }, "слой «$id»: ${changes.joinToString(", ")}")
            }
            "layer.remove" -> {
                val id = str(a, "id"); require(layers(root).any { id(it) == id }) { "нет слоя «$id»" }
                OpResult.Done(setLayers(root, layers(root).filter { id(it) != id }), "слой «$id»: удалён", dangerous = true)
            }
            "layer.move" -> {
                val id = str(a, "id"); val list = layers(root).toMutableList()
                val from = list.indexOfFirst { id(it) == id }; require(from >= 0) { "нет слоя «$id»" }
                val to = ((a["to"] as? JsonPrimitive)?.intOrNull ?: error("нет to")).coerceIn(0, list.size - 1)
                val l = list.removeAt(from); list.add(to, l)
                done(setLayers(root, list), "слой «$id»: место ${from + 1} → ${to + 1}")
            }
            "layer.duplicate" -> {
                val id = str(a, "id"); val nid = str(a, "newId")
                require(layers(root).none { id(it) == nid }) { "слой «$nid» уже есть" }
                val list = layers(root).toMutableList()
                val i = list.indexOfFirst { id(it) == id }; require(i >= 0) { "нет слоя «$id»" }
                list.add(i + 1, JsonObject(list[i] + ("id" to JsonPrimitive(nid))))
                done(setLayers(root, list), "слой «$id»: копия «$nid»")
            }
            "effect.add" -> {
                val layer = str(a, "layer"); val fx = a["effect"] as? JsonObject ?: error("нет effect")
                done(mapLayer(root, layer) { l ->
                    val list = fxList(l).toMutableList()
                    val at = (a["at"] as? JsonPrimitive)?.intOrNull ?: list.size
                    list.add(at.coerceIn(0, list.size), fx)
                    JsonObject(l + ("fx" to JsonArray(list)))
                }, "слой «$layer»: эффект «${(fx["type"] as? JsonPrimitive)?.contentOrNull}» добавлен")
            }
            "effect.set" -> {
                val layer = str(a, "layer"); val index = (a["index"] as? JsonPrimitive)?.intOrNull ?: error("нет index")
                val set = a["set"] as? JsonObject ?: error("нет set")
                val changes = ArrayList<String>()
                done(mapLayer(root, layer) { l ->
                    val list = fxList(l).toMutableList(); require(index in list.indices) { "нет эффекта $index" }
                    var fx = list[index].jsonObject
                    var p = (fx["p"] as? JsonObject) ?: JsonObject(emptyMap())
                    for ((k, v) in set) {
                        if (k == "off") { changes += "off ${show(fx[k])} → ${show(v)}"; fx = JsonObject(fx + (k to v)) }
                        else { changes += "$k ${show(p[k])} → ${show(v)}"; p = JsonObject(p + (k to v)) }
                    }
                    list[index] = JsonObject(fx + ("p" to p))
                    JsonObject(l + ("fx" to JsonArray(list)))
                }, "слой «$layer», эффект $index: ${changes.joinToString(", ")}")
            }
            "effect.remove" -> {
                val layer = str(a, "layer"); val index = (a["index"] as? JsonPrimitive)?.intOrNull ?: error("нет index")
                done(mapLayer(root, layer) { l ->
                    val list = fxList(l).toMutableList(); require(index in list.indices) { "нет эффекта $index" }
                    list.removeAt(index); JsonObject(l + ("fx" to JsonArray(list)))
                }, "слой «$layer»: эффект $index убран")
            }
            "layer.bind" -> {
                val layer = str(a, "layer"); val param = str(a, "param")
                val b = buildJsonObject {
                    (a["src"] as? JsonPrimitive)?.let { put("src", it) }
                    (a["amt"] as? JsonPrimitive)?.let { put("amt", it) }
                    (a["expr"] as? JsonPrimitive)?.let { put("expr", it) }
                }
                require(b.isNotEmpty()) { "нужен src с amt или expr" }
                done(mapLayer(root, layer) { l ->
                    val bind = (l["bind"] as? JsonObject) ?: JsonObject(emptyMap())
                    JsonObject(l + ("bind" to JsonObject(bind + (param to b))))
                }, "слой «$layer»: $param привязан (${b.entries.joinToString(", ") { "${it.key}=${show(it.value)}" }})")
            }
            "layer.unbind" -> {
                val layer = str(a, "layer"); val param = str(a, "param")
                done(mapLayer(root, layer) { l ->
                    val bind = (l["bind"] as? JsonObject) ?: JsonObject(emptyMap())
                    JsonObject(l + ("bind" to JsonObject(bind - param)))
                }, "слой «$layer»: привязка $param снята")
            }
            "mod.add" -> {
                val m = a["mod"] as? JsonObject ?: error("нет mod")
                val mods = ((root["mods"] as? JsonArray)?.toList() ?: emptyList()) + m
                done(JsonObject(root + ("mods" to JsonArray(mods))), "источник «${(m["id"] as? JsonPrimitive)?.contentOrNull}» добавлен")
            }
            "mod.remove" -> {
                val id = str(a, "id")
                val mods = (root["mods"] as? JsonArray)?.toList().orEmpty()
                require(mods.any { (it as? JsonObject)?.get("id").let { v -> (v as? JsonPrimitive)?.contentOrNull } == id }) { "нет источника «$id»" }
                OpResult.Done(JsonObject(root + ("mods" to JsonArray(mods.filter { (it as? JsonObject)?.get("id").let { v -> (v as? JsonPrimitive)?.contentOrNull } != id }))), "источник «$id»: удалён", dangerous = true)
            }
            "look.apply" -> {
                val look = str(a, "look")
                require(look in SceneLooks.IDS) { "нет готовой сцены «$look»" }
                OpResult.Done(toTree(SceneLooks.load(look)), "вся сцена заменена готовой «$look»", dangerous = true)
            }
            else -> OpResult.Failed("неизвестная операция «${op.op}»; есть: ${NAMES.joinToString()}")
        }
    }

    // ───────────── помощники ─────────────

    private fun done(root: JsonObject, line: String) = OpResult.Done(root, line, dangerous = false)
    private fun str(a: JsonObject, k: String) = (a[k] as? JsonPrimitive)?.contentOrNull ?: error("нет «$k»")
    private fun layers(root: JsonObject): List<JsonObject> = (root["layers"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
    private fun id(l: JsonObject) = (l["id"] as? JsonPrimitive)?.contentOrNull
    private fun fxList(l: JsonObject): List<JsonElement> = (l["fx"] as? JsonArray)?.toList() ?: emptyList()
    private fun setLayers(root: JsonObject, list: List<JsonObject>) = JsonObject(root + ("layers" to JsonArray(list)))
    private fun mapLayer(root: JsonObject, id: String, f: (JsonObject) -> JsonObject): JsonObject {
        val list = layers(root); require(list.any { id(it) == id }) { "нет слоя «$id»" }
        return setLayers(root, list.map { if (id(it) == id) f(it) else it })
    }
    private fun show(e: JsonElement?): String = when (e) {
        null, JsonNull -> "—"
        is JsonPrimitive -> e.contentOrNull ?: "—"
        else -> e.toString().take(40)
    }
}
