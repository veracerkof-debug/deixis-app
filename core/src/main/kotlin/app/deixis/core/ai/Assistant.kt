package app.deixis.core.ai

import app.deixis.core.effects.Scene
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Один вызов инструмента от модели: имя из каталога и аргументы. */
data class ToolCall(val name: String, val args: JsonObject = JsonObject(emptyMap()))

/** Ход модели: либо вызовы инструментов, либо ответ человеку (тогда разговор окончен). */
sealed interface ModelTurn {
    data class Calls(val calls: List<ToolCall>) : ModelTurn
    data class Say(val text: String) : ModelTurn
}

/** Поставщик модели. Настоящие (OpenAI-совместимый, Anthropic) подключаются отдельно; в тестах — [ScriptedProvider]. */
fun interface ModelProvider {
    /** [transcript] — всё, что видела модель: реплики человека, её вызовы и результаты; результаты помечены как данные. */
    fun next(system: String, transcript: List<Message>): ModelTurn
}

data class Message(val role: String, val text: String)

/** Поддельный поставщик: ходы заранее записаны, для проверки без сети и ключа. */
class ScriptedProvider(private val turns: List<ModelTurn>) : ModelProvider {
    var calls = 0
        private set
    val seen = ArrayList<List<Message>>()
    override fun next(system: String, transcript: List<Message>): ModelTurn {
        seen += transcript
        return turns.getOrNull(calls++) ?: ModelTurn.Say("Готово")
    }
}

/**
 * Цикл помощника: запрос человека → ходы модели → инструменты. Чтение (`scene.get`, `scene.preflight`, `catalog.get`)
 * выполняется свободно; запись только через `propose_change` ([ProposalEngine]); ошибка проверки возвращается модели с путём,
 * и она исправляется (не больше [MAX_FIXES] раз подряд). Показ и управление залом в каталоге отсутствуют.
 * Данные из сцены и результатов помечаются как данные (в сообщении роли `data`), а не как указания.
 */
class Assistant<S : Any>(private val engine: ProposalEngine<S>, private val provider: ModelProvider, private val system: String, private val target: OpTarget<S>) {
    val transcript = ArrayList<Message>()

    class Reply(val text: String, val proposals: List<Proposal<*>>)

    fun ask(request: String): Reply {
        transcript += Message("user", request)
        val made = ArrayList<Proposal<*>>()
        var fixes = 0
        var steps = 0
        while (steps++ < MAX_STEPS) {
            when (val turn = provider.next(system, transcript)) {
                is ModelTurn.Say -> { transcript += Message("assistant", turn.text); return Reply(turn.text, made) }
                is ModelTurn.Calls -> for (c in turn.calls) {
                    transcript += Message("assistant", "call ${c.name} ${c.args}")
                    val (res, p, failed) = run(c)
                    if (p != null) made += p
                    if (failed) fixes++ else fixes = 0
                    transcript += Message("data", res)
                    if (fixes > MAX_FIXES) return Reply("Не получилось подобрать правку без ошибок: $res", made)
                }
            }
        }
        return Reply("Слишком много шагов без ответа", made)
    }

    private data class Run(val text: String, val proposal: Proposal<*>?, val failed: Boolean)

    private fun run(c: ToolCall): Run {
        if (c.name == "propose_change") return propose(c.args)
        target.read(c.name, engine.state)?.let { return Run(it, null, false) }
        return Run("Нет такого инструмента «${c.name}». Есть: ${(target.readTools + "propose_change").joinToString()}", null, true)
    }

    private fun propose(a: JsonObject): Run {
        val summary = (a["summary"] as? JsonPrimitive)?.contentOrNull ?: "Правка сцены"
        val ops = (a["ops"] as? JsonArray)?.map { SceneOp.parse(it) } ?: return Run("Нужен список ops", null, true)
        if (ops.any { it == null }) return Run("В ops есть элемент без поля op", null, true)
        return when (val r = engine.propose(summary, ops.filterNotNull())) {
            is Proposed.Rejected -> Run("Отказ: ${r.errors.joinToString("; ")}", null, true)
            is Proposed.Ok -> Run(if (r.autoApplied) "Применено автоматически (предложение ${r.proposal.id})" else "Предложение ${r.proposal.id} ждёт ответа человека", r.proposal, false)
        }
    }

    companion object {
        const val MAX_STEPS = 12
        const val MAX_FIXES = 3
    }
}

/** Помощник для сцены фона. */
fun Assistant(engine: ProposalEngine<Scene>, provider: ModelProvider, system: String): Assistant<Scene> = Assistant(engine, provider, system, SceneTarget)
