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

    /** Положить готовую сцену [scene] в оформление [bg] (результат принятого предложения). */
    fun withScene(bg: SceneBackground, scene: Scene): SceneBackground =
        bg.copy(scene = DeixisJson.encodeToString(JsonObject.serializer(), SceneOps.toTree(scene)))

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

    /** Число параметра слоя (`opacity`, `avoid`…): шаг в пределах каталога обеспечивает проверка сцены. */
    fun setParam(bg: SceneBackground, layerId: String, key: String, value: Double) =
        edit(bg, listOf(SceneOp("layer.set", obj("id", layerId, "set" to JsonObject(mapOf(key to kotlinx.serialization.json.JsonPrimitive(Math.round(value * 100) / 100.0)))))))

    /** Текст или выбор из списка (форма, шрифт, раскладка…) параметра слоя. */
    fun setText(bg: SceneBackground, layerId: String, key: String, value: String) =
        edit(bg, listOf(SceneOp("layer.set", obj("id", layerId, "set" to JsonObject(mapOf(key to kotlinx.serialization.json.JsonPrimitive(value)))))))

    fun rename(bg: SceneBackground, layerId: String, name: String) =
        edit(bg, listOf(SceneOp("layer.set", obj("id", layerId, "set" to JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive(name.trim().take(40).ifEmpty { return null })))))))

    /** Новый слой вида [type] с умолчаниями каталога; возвращает оформление и id слоя. */
    fun addLayer(bg: SceneBackground, type: String): Pair<SceneBackground, String>? {
        val scene = sceneOf(bg) ?: return null
        if (!SceneStudio.canAdd(scene, type)) return null
        val layer = SceneStudio.newLayer(scene, type)
        val id = (layer["id"] as kotlinx.serialization.json.JsonPrimitive).content
        return edit(bg, listOf(SceneOp("layer.add", obj("layer" to layer))))?.let { it to id }
    }

    fun addEffect(bg: SceneBackground, layerId: String, type: String) =
        edit(bg, listOf(SceneOp("effect.add", obj("layer", layerId, "effect" to SceneStudio.newEffect(type)))))

    fun fxParam(bg: SceneBackground, layerId: String, index: Int, key: String, value: Double) =
        edit(bg, listOf(SceneOp("effect.set", obj("layer", layerId, "index" to kotlinx.serialization.json.JsonPrimitive(index), "set" to JsonObject(mapOf(key to kotlinx.serialization.json.JsonPrimitive(Math.round(value * 1000) / 1000.0)))))))

    /** Включить у параметра готовое движение ([motion] = null — остановить). */
    fun motion(bg: SceneBackground, layerId: String, key: String, motion: Motion?, strength: Double): SceneBackground? {
        val scene = sceneOf(bg) ?: return null
        return edit(bg, if (motion == null) SceneStudio.stillOps(layerId, key) else SceneStudio.motionOps(scene, layerId, key, motion, strength))
    }

    /** Включить или выключить эффект [index] слоя. */
    fun fxOff(bg: SceneBackground, layerId: String, index: Int, off: Boolean) =
        edit(bg, listOf(SceneOp("effect.set", obj("layer", layerId, "index" to kotlinx.serialization.json.JsonPrimitive(index), "set" to JsonObject(mapOf("off" to kotlinx.serialization.json.JsonPrimitive(off)))))))

    fun fxRemove(bg: SceneBackground, layerId: String, index: Int) =
        edit(bg, listOf(SceneOp("effect.remove", obj("layer", layerId, "index" to kotlinx.serialization.json.JsonPrimitive(index)))))

    /** Строка `Binding` слоя: «параметр ← источник» — для показа человеку; снять — [unbind]. */
    fun unbind(bg: SceneBackground, layerId: String, param: String) =
        edit(bg, listOf(SceneOp("layer.unbind", obj("layer", layerId, "param", param))))

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

/** Инструкция помощнику (docs/ai/SCENE_PROMPT.md): лежит в ресурсах ядра, тест сверяет копию с документом. */
object AssistantPrompt {
    val text: String by lazy {
        AssistantPrompt::class.java.getResourceAsStream("/ai/scene_prompt.md")?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
    }

    /** Инструкция для сетлиста: порядок и состав служения из того, что уже есть в библиотеке. */
    val setlist: String = """Ты помощник в планировании сетлиста приложения для показа. Читаешь сетлист (setlist.get) и библиотеку (library.list) и предлагаешь правки инструментом propose_change с операциями setlist.add (item — id или точное название из библиотеки, at — место), setlist.remove, setlist.move, setlist.rename, setlist.clear. Добавлять можно только то, что есть в библиотеке; ничего не выдумывай. Порядок строй по просьбе человека; очистку предлагай только по явной просьбе. Каждое изменение человек подтверждает сам: в summary коротко пиши, что и зачем."""

    /** Инструкция для работы над текстом песни: правка опечаток, подписей, разбивки; смысл и вероучение — на человеке. */
    val song: String = """Ты помощник в редакторе песен приложения для показа. Работаешь только с текстом слайдов песни: читаешь его инструментом song.get и предлагаешь правки инструментом propose_change с операциями slide.add, slide.update, slide.move, slide.duplicate, slide.delete, song.replace. Слайд — подпись (label: «Куплет 1», «Припев», «Бридж»…) и строки текста (text), на слайде обычно 2–4 строки. Исправляй опечатки и знаки, не меняй смысл и слова автора без просьбы; переводи и подбирай разбивку только по просьбе. Не удаляй и не заменяй всё без явной просьбы. Каждое изменение человек подтверждает сам: пиши в summary коротко, что и зачем."""

}
