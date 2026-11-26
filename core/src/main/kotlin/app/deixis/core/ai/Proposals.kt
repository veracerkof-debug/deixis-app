package app.deixis.core.ai

import app.deixis.core.effects.Scene

/** Состояние предложения: ждёт человека, принято (целиком или частично), отклонено, истекло. */
enum class ProposalState { PENDING, APPLIED, REJECTED, EXPIRED }

/**
 * Предложение нейросети: пакет операций над сценой с описанием «было → стало». Пока не принято, сцена не меняется.
 * [dangerous] — есть удаление или замена целиком: такое подтверждается человеком всегда, даже при автоподтверждении.
 */
class Proposal<S : Any>(
    val id: Int,
    val summary: String,
    val ops: List<SceneOp>,
    val lines: List<String>,
    val dangerous: Boolean,
    val preview: S,
    val warnings: List<String>,
    val createdMs: Long,
) {
    var state: ProposalState = ProposalState.PENDING
        internal set
}

/** Запись журнала применённого: снимок сцены до применения — для «Отменить». */
class JournalEntry<S : Any>(val proposal: Proposal<S>, val before: S, val after: S, val auto: Boolean, var undone: Boolean = false)

/** Исход попытки предложить: либо предложение, либо понятные ошибки (они возвращаются нейросети, и она исправляется). */
sealed interface Proposed<out S : Any> {
    data class Ok<S : Any>(val proposal: Proposal<S>, val autoApplied: Boolean) : Proposed<S>
    data class Rejected(val errors: List<String>) : Proposed<Nothing>
}

/**
 * Работа с предложениями (docs/dev/AI.md, разделы 2 и 5). Хранит текущую сцену и журнал, ничего не знает о сети и экране.
 * Лимиты: до [MAX_OPS] операций в предложении, до [MAX_PER_MINUTE] предложений в минуту, жизнь предложения [TTL_MS].
 * Автоподтверждение ([autoConfirm]) применяет обычные предложения сразу, опасные — никогда. [readOnly] — только советы.
 */
class ProposalEngine<S : Any>(initial: S, private val target: OpTarget<S>, private val now: () -> Long = System::currentTimeMillis) {
    var state: S = initial
        private set
    var autoConfirm = false
    var readOnly = false

    private var nextId = 1
    private val stamps = ArrayDeque<Long>()
    private val open = LinkedHashMap<Int, Proposal<S>>()
    val journal = ArrayList<JournalEntry<S>>()

    @Synchronized fun pending(): List<Proposal<S>> { expire(); return open.values.toList() }

    @Synchronized fun propose(summary: String, ops: List<SceneOp>): Proposed<S> {
        expire()
        if (readOnly) return Proposed.Rejected(listOf("Включён режим «только чтение»: правки не принимаются"))
        if (ops.isEmpty()) return Proposed.Rejected(listOf("В предложении нет операций"))
        if (ops.size > MAX_OPS) return Proposed.Rejected(listOf("Операций ${ops.size}, можно не больше $MAX_OPS: разбейте на части"))
        val t = now()
        while (stamps.isNotEmpty() && t - stamps.first() > 60_000) stamps.removeFirst()
        if (stamps.size >= MAX_PER_MINUTE) return Proposed.Rejected(listOf("Слишком много предложений за минуту (не больше $MAX_PER_MINUTE): подождите"))
        val out = target.applyAll(state, ops)
        val made = out.state ?: return Proposed.Rejected(out.errors)
        stamps.addLast(t)
        val dangerous = out.dangerous || ops.size > DANGEROUS_OPS
        val p = Proposal(nextId++, summary.take(120), ops, out.lines, dangerous, made, out.warnings, t)
        if (autoConfirm && !dangerous) {
            apply(p, auto = true)
            return Proposed.Ok(p, autoApplied = true)
        }
        open[p.id] = p
        return Proposed.Ok(p, autoApplied = false)
    }

    /** Принять: [only] — номера операций («Принять частично»); null — все. Частичный набор проверяется заново, ошибка возвращается. */
    @Synchronized fun accept(id: Int, only: Set<Int>? = null): List<String> {
        expire()
        val p = open[id] ?: return listOf("Предложение уже не ждёт ответа")
        val ops = if (only == null) p.ops else p.ops.filterIndexed { i, _ -> i in only }
        if (ops.isEmpty()) return listOf("Ни одной операции не выбрано")
        val out = target.applyAll(state, ops)
        val made = out.state ?: return out.errors
        open.remove(id)
        val applied = if (only == null) p else Proposal(p.id, p.summary, ops, out.lines, out.dangerous, made, out.warnings, p.createdMs)
        apply(applied, auto = false)
        p.state = ProposalState.APPLIED
        return emptyList()
    }

    @Synchronized fun reject(id: Int) { open.remove(id)?.state = ProposalState.REJECTED }

    /** «Отменить»: вернуть сцену, какой она была до записи. Работает с последней неотменённой записью, чтобы цепочка не ломалась. */
    @Synchronized fun undoLast(): Boolean {
        val e = journal.lastOrNull { !it.undone } ?: return false
        state = e.before
        e.undone = true
        return true
    }

    /** Человек сам поменял сцену (редактор слоёв): предложения, построенные на старой, больше не годятся. */
    @Synchronized fun replaceState(s: S) { state = s; open.values.forEach { it.state = ProposalState.EXPIRED }; open.clear() }

    private fun apply(p: Proposal<S>, auto: Boolean) {
        val before = state
        state = p.preview
        p.state = ProposalState.APPLIED
        journal += JournalEntry(p, before, state, auto)
        // сцена изменилась: остальные открытые предложения строились на прежней
        open.values.filter { it !== p }.forEach { it.state = ProposalState.EXPIRED }
        open.keys.retainAll { open[it] === p }
        open.remove(p.id)
    }

    private fun expire() {
        val t = now()
        val dead = open.values.filter { t - it.createdMs > TTL_MS }
        dead.forEach { it.state = ProposalState.EXPIRED; open.remove(it.id) }
    }

    companion object {
        const val MAX_OPS = 50
        const val DANGEROUS_OPS = 20
        const val MAX_PER_MINUTE = 4
        const val TTL_MS = 10 * 60_000L
    }
}

/** Сцена фона: операции [SceneOps]. */
fun ProposalEngine(scene: Scene, now: () -> Long = System::currentTimeMillis): ProposalEngine<Scene> = ProposalEngine(scene, SceneTarget, now)

val ProposalEngine<Scene>.scene: Scene get() = state
