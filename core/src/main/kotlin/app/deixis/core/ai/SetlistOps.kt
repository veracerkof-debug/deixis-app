package app.deixis.core.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Элемент библиотеки или сетлиста для помощника: id и название. Содержимое файлов ему не отдаётся. */
data class SetEntry(val id: String, val title: String)

/** Сетлист для правки: название и порядок элементов. */
data class SetlistDraft(val title: String, val items: List<SetEntry>)

/**
 * Сетлист: операции помощника (`setlist.rename/add/remove/move/clear`). Добавлять можно только то, что уже есть в библиотеке
 * ([library]): элемент находится по id или по точному названию. Показ и управление залом в каталог не входят.
 */
class SetlistTarget(private val library: List<SetEntry>) : OpTarget<SetlistDraft> {
    override val readTools = listOf("setlist.get", "library.list")
    override fun read(tool: String, state: SetlistDraft): String? = when (tool) {
        "setlist.get" -> describe(state)
        "library.list" -> library.joinToString("\n") { "${it.id}: ${it.title}" }.ifEmpty { "Библиотека пуста" }
        else -> null
    }
    override fun describe(state: SetlistDraft) = "Сетлист «${state.title}»\n" + state.items.mapIndexed { i, e -> "[$i] ${e.title} (${e.id})" }.joinToString("\n").ifEmpty { "пуст" }

    override fun applyAll(state: SetlistDraft, ops: List<SceneOp>): OpOutcome<SetlistDraft> {
        var cur = state
        val lines = ArrayList<String>()
        var danger = false
        for ((n, op) in ops.withIndex()) {
            try {
                val a = op.args
                when (op.op) {
                    "setlist.rename" -> {
                        val t = str(a, "title").trim().take(80); require(t.isNotEmpty()) { "пустое название" }
                        lines += "название: «${cur.title}» → «$t»"; cur = cur.copy(title = t)
                    }
                    "setlist.add" -> {
                        val ref = str(a, "item")
                        val e = library.firstOrNull { it.id == ref } ?: library.firstOrNull { it.title == ref } ?: error("в библиотеке нет «$ref»")
                        val at = (a["at"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, cur.items.size) ?: cur.items.size
                        cur = cur.copy(items = cur.items.toMutableList().apply { add(at, e) }); lines += "добавлено: «${e.title}» на место ${at + 1}"
                    }
                    "setlist.remove" -> {
                        val i = index(a, cur)
                        lines += "убрано: «${cur.items[i].title}»"; cur = cur.copy(items = cur.items.toMutableList().apply { removeAt(i) })
                    }
                    "setlist.move" -> {
                        val i = index(a, cur); val to = ((a["to"] as? JsonPrimitive)?.intOrNull ?: error("нет to")).coerceIn(0, cur.items.size - 1)
                        lines += "«${cur.items[i].title}»: место ${i + 1} → ${to + 1}"; cur = cur.copy(items = cur.items.toMutableList().apply { add(to, removeAt(i)) })
                    }
                    "setlist.clear" -> { lines += "сетлист очищен (${cur.items.size} элементов)"; cur = cur.copy(items = emptyList()); danger = true }
                    else -> error("неизвестная операция «${op.op}»; есть: setlist.rename, setlist.add, setlist.remove, setlist.move, setlist.clear")
                }
            } catch (e: Exception) {
                return OpOutcome(null, lines, false, listOf("[$n] ${op.op}: ${e.message ?: "не получилось"}"))
            }
        }
        if (cur.items.size > MAX_ITEMS) return OpOutcome(null, lines, danger, listOf("В сетлисте ${cur.items.size} элементов, можно не больше $MAX_ITEMS"))
        return OpOutcome(cur, lines, danger, emptyList())
    }

    private fun str(a: JsonObject, k: String) = (a[k] as? JsonPrimitive)?.contentOrNull ?: error("нет «$k»")
    private fun index(a: JsonObject, s: SetlistDraft): Int {
        val i = (a["index"] as? JsonPrimitive)?.intOrNull ?: error("нет index")
        require(i in s.items.indices) { "нет элемента $i (в сетлисте ${s.items.size})" }
        return i
    }

    companion object { const val MAX_ITEMS = 300 }
}
