package app.deixis.core.ai

import app.deixis.core.effects.Scene

/** Итог пакета операций над состоянием [S]: новое состояние (null при ошибке), описание «было → стало», ошибки с путями. */
class OpOutcome<S : Any>(val state: S?, val lines: List<String>, val dangerous: Boolean, val errors: List<String>, val warnings: List<String> = emptyList())

/**
 * То, над чем работает помощник: сцена фона или текст песни. Цель знает свои операции и проверяет итог; движок предложений
 * ([ProposalEngine]) и цикл модели ([Assistant]) от неё не зависят.
 */
interface OpTarget<S : Any> {
    /** Применить все операции подряд; любая ошибка отменяет всё. */
    fun applyAll(state: S, ops: List<SceneOp>): OpOutcome<S>
    /** Состояние текстом для модели (читается свободно). */
    fun describe(state: S): String
    /** Имена инструментов чтения этой цели (кроме общего `propose_change`). */
    val readTools: List<String>
    /** Выполнить инструмент чтения; null — такого нет. */
    fun read(tool: String, state: S): String?
}

object SceneTarget : OpTarget<Scene> {
    override fun applyAll(state: Scene, ops: List<SceneOp>): OpOutcome<Scene> {
        val o = SceneOps.applyAll(state, ops)
        return OpOutcome(o.scene, o.lines, o.dangerous, o.errors, o.warnings)
    }
    override fun describe(state: Scene): String = app.deixis.core.DeixisJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), SceneOps.toTree(state))
    override val readTools = listOf("scene.get", "scene.preflight", "catalog.get")
    override fun read(tool: String, state: Scene): String? = when (tool) {
        "scene.get" -> describe(state)
        "scene.preflight" -> {
            val plan = app.deixis.core.effects.SceneCost.plan(state, 20.0, 1.0, 192)
            "слоёв ${state.layers.size}; цена кадра около ${"%.1f".format(plan.keptMs)} мс; при нехватке отключатся: ${plan.dropped.joinToString().ifEmpty { "ничего" }}"
        }
        "catalog.get" -> app.deixis.core.effects.SceneCatalog.toJson().toString()
        else -> null
    }
}
