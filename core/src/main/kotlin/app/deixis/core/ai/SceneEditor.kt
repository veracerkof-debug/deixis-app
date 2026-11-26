package app.deixis.core.ai

import app.deixis.core.DeixisJson
import app.deixis.core.SceneBackground
import app.deixis.core.effects.Scene
import app.deixis.core.effects.SceneText
import kotlinx.serialization.json.JsonObject

/**
 * Правка живого фона человеком (редактор слоёв в пульте): те же операции [SceneOps], что у нейросети.
 * Результат — тот же [SceneBackground] со своей сценой внутри ([SceneBackground.scene]); готовая сцена при первой правке копируется.
 */
object SceneEditor {
    fun sceneOf(bg: SceneBackground): Scene? = SceneText.sceneFor(bg)

    /** Применить операции; null — сцены нет или итог не прошёл проверку (тогда фон остаётся прежним). */
    fun edit(bg: SceneBackground, ops: List<SceneOp>): SceneBackground? {
        val scene = sceneOf(bg) ?: return null
        val made = SceneOps.applyAll(scene, ops).scene ?: return null
        val json = DeixisJson.encodeToString(JsonObject.serializer(), SceneOps.toTree(made))
        return bg.copy(scene = json)
    }

    /** Свободный id для копии слоя: `copy1`, `copy2`… (до 20 знаков, как у остальных). */
    fun freeId(scene: Scene, base: String = "copy"): String =
        generateSequence(1) { it + 1 }.map { "$base$it" }.first { id -> scene.layers.none { it.id == id } }

    fun toggle(bg: SceneBackground, layerId: String, on: Boolean) =
        edit(bg, listOf(SceneOp("layer.set", obj("id", layerId, "set" to JsonObject(mapOf("on" to kotlinx.serialization.json.JsonPrimitive(on)))))))

    fun move(bg: SceneBackground, layerId: String, by: Int): SceneBackground? {
        val i = sceneOf(bg)?.layers?.indexOfFirst { it.id == layerId } ?: return null
        if (i < 0) return null
        return edit(bg, listOf(SceneOp("layer.move", obj("id", layerId, "to" to kotlinx.serialization.json.JsonPrimitive(i + by)))))
    }

    fun duplicate(bg: SceneBackground, layerId: String): SceneBackground? {
        val s = sceneOf(bg) ?: return null
        return edit(bg, listOf(SceneOp("layer.duplicate", obj("id", layerId, "newId", freeId(s)))))
    }

    fun remove(bg: SceneBackground, layerId: String) = edit(bg, listOf(SceneOp("layer.remove", obj("id", layerId))))

    private fun obj(vararg kv: Any): JsonObject {
        val m = HashMap<String, kotlinx.serialization.json.JsonElement>()
        var i = 0
        while (i < kv.size) {
            val a = kv[i]
            if (a is Pair<*, *>) { m[a.first as String] = a.second as kotlinx.serialization.json.JsonElement; i += 1 }
            else { m[a as String] = kotlinx.serialization.json.JsonPrimitive(kv[i + 1] as String); i += 2 }
        }
        return JsonObject(m)
    }
}
