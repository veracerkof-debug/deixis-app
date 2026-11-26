package app.deixis.core.ai

import app.deixis.core.SongSlide
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Песня или текст как список слайдов ([SongSlide]): операции помощника над ними (docs/dev/AI.md, раздел 4).
 * Работает только с текстом песни; показ, библиотека и сетлисты в каталог не входят. Текст из ответа модели — данные:
 * он проверяется по длине и ложится в предложение, ничего не исполняется.
 */
object SongTarget : OpTarget<List<SongSlide>> {
    const val MAX_SLIDES = 200
    const val MAX_TEXT = 2000
    const val MAX_LABEL = 40
    val NAMES = listOf("slide.add", "slide.update", "slide.move", "slide.duplicate", "slide.delete", "song.replace")

    override val readTools = listOf("song.get")
    override fun read(tool: String, state: List<SongSlide>): String? = if (tool == "song.get") describe(state) else null
    override fun describe(state: List<SongSlide>): String =
        if (state.isEmpty()) "Песня пуста" else state.mapIndexed { i, s -> "[$i] ${s.label ?: "(без подписи)"}\n${s.text}" }.joinToString("\n\n")

    override fun applyAll(state: List<SongSlide>, ops: List<SceneOp>): OpOutcome<List<SongSlide>> {
        var cur = state
        val lines = ArrayList<String>()
        var dangerous = false
        for ((n, op) in ops.withIndex()) {
            try {
                val (next, line, danger) = run(cur, op)
                cur = next; lines += line; dangerous = dangerous || danger
            } catch (e: Exception) {
                return OpOutcome(null, lines, false, listOf("[$n] ${op.op}: ${e.message ?: "не получилось"}"))
            }
        }
        if (cur.size > MAX_SLIDES) return OpOutcome(null, lines, dangerous, listOf("Слайдов ${cur.size}, можно не больше $MAX_SLIDES"))
        return OpOutcome(cur, lines, dangerous, emptyList())
    }

    private data class R(val slides: List<SongSlide>, val line: String, val danger: Boolean)

    private fun run(s: List<SongSlide>, op: SceneOp): R {
        val a = op.args
        return when (op.op) {
            "slide.add" -> {
                val slide = slideOf(a, null)
                val at = (a["at"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, s.size) ?: s.size
                R(s.toMutableList().apply { add(at, slide) }, "новый слайд ${at + 1}: ${preview(slide)}", false)
            }
            "slide.update" -> {
                val i = index(a, s); val old = s[i]
                val new = slideOf(a, old)
                R(s.toMutableList().apply { this[i] = new }, "слайд ${i + 1}: ${preview(old)} → ${preview(new)}", false)
            }
            "slide.move" -> {
                val i = index(a, s)
                val to = ((a["to"] as? JsonPrimitive)?.intOrNull ?: error("нет to")).coerceIn(0, s.size - 1)
                R(s.toMutableList().apply { add(to, removeAt(i)) }, "слайд ${i + 1}: место ${i + 1} → ${to + 1}", false)
            }
            "slide.duplicate" -> {
                val i = index(a, s)
                R(s.toMutableList().apply { add(i + 1, s[i]) }, "слайд ${i + 1}: копия", false)
            }
            "slide.delete" -> {
                val i = index(a, s)
                R(s.toMutableList().apply { removeAt(i) }, "слайд ${i + 1} удалён: ${preview(s[i])}", true)
            }
            "song.replace" -> {
                val text = (a["text"] as? JsonPrimitive)?.contentOrNull ?: error("нет text")
                require(text.length <= MAX_SLIDES * MAX_TEXT) { "текст слишком велик" }
                val parsed = app.deixis.core.SongText.parse(text)
                R(parsed, "вся песня заменена (${s.size} → ${parsed.size} слайдов)", true)
            }
            else -> error("неизвестная операция «${op.op}»; есть: ${NAMES.joinToString()}")
        }
    }

    private fun index(a: JsonObject, s: List<SongSlide>): Int {
        val i = (a["index"] as? JsonPrimitive)?.intOrNull ?: error("нет index")
        require(i in s.indices) { "нет слайда $i (в песне ${s.size})" }
        return i
    }

    /** Слайд из аргументов: поля, которых нет, берутся из [base]. */
    private fun slideOf(a: JsonObject, base: SongSlide?): SongSlide {
        val label = if ("label" in a) (a["label"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } else base?.label
        val text = if ("text" in a) (a["text"] as? JsonPrimitive)?.contentOrNull ?: error("text должен быть строкой") else base?.text.orEmpty()
        require(text.length <= MAX_TEXT) { "текст слайда длиннее $MAX_TEXT знаков" }
        require((label?.length ?: 0) <= MAX_LABEL) { "подпись длиннее $MAX_LABEL знаков" }
        require(label != null || text.isNotBlank()) { "слайд пуст" }
        return SongSlide(label, text.trim())
    }

    private fun preview(s: SongSlide) = "«${(s.label?.let { "$it: " } ?: "") + s.text.replace('\n', ' ').take(40)}»"
}
