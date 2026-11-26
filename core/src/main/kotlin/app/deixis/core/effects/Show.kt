package app.deixis.core.effects

import app.deixis.core.DeixisJson
import app.deixis.core.quiz.IssueLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Показ песни (`*.deixis-show.json`): песни с частями (куплет, припев, мост), у каждой своя энергия, образ, темп.
 * Формат и границы — docs/dev/EFFECTS.md, раздел 4. Данные, а не код.
 */
@Serializable
data class ShowFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val songs: List<ShowSong>,
) {
    companion object {
        const val FORMAT = "deixis-show"
        const val VERSION = 1
        const val FILE_SUFFIX = ".deixis-show.json"
    }
}

@Serializable
data class ShowSong(
    val id: String,
    val title: String,
    /** Темп песни, ударов в минуту; 0 — не задан (берётся темп режима). */
    val bpm: Int = 0,
    val sections: List<ShowSection>,
)

@Serializable
data class ShowSection(
    val tag: String,
    /** Строки текста; пусто — только фон (вступление). */
    val lines: List<String> = emptyList(),
    /** Энергия фона в начале и в конце части, 0…1. */
    val e0: Double = 0.0,
    val e1: Double = 0.0,
    /** Длительность части, секунд. */
    val d: Double = 12.0,
    /** Образ части; null — без образа. */
    val emb: String? = null,
    /** Расположение образа: right, left, center или пусто (как в настройках). */
    val place: String = "",
    val bpm: Int = 0,
    /** Размер текста слайда, % от общего. */
    val ts: Int = 100,
    /** Шрифт слайда или пусто. */
    val font: String = "",
)

data class ShowReport(val show: ShowFile?, val issues: List<SceneIssue>) {
    val errors get() = issues.filter { it.level == IssueLevel.ERROR }
    val warnings get() = issues.filter { it.level == IssueLevel.WARNING }
    val ok get() = show != null
}

object ShowValidator {
    const val MAX_SONGS = 40
    const val MAX_SECTIONS = 60
    const val MAX_LINES = 12
    const val MAX_LINE = 140
    const val MAX_FILE_BYTES = 512 * 1024
    val EMBLEMS = setOf("cross", "dove", "angel", "flame", "star", "book")
    val PLACES = setOf("right", "left", "center")
    private val ID = Regex("[A-Za-z0-9_-]{1,40}")

    fun validate(text: String): ShowReport {
        if (text.toByteArray().size > MAX_FILE_BYTES) return ShowReport(null, listOf(SceneIssue(IssueLevel.ERROR, "file_too_big", "", "Файл больше ${MAX_FILE_BYTES / 1024} КБ")))
        val root = try {
            DeixisJson.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return ShowReport(null, listOf(SceneIssue(IssueLevel.ERROR, "bad_json", "", "Это не JSON или корень не объект")))
        }
        val issues = ArrayList<SceneIssue>()
        fun err(code: String, path: String, msg: String) { issues += SceneIssue(IssueLevel.ERROR, code, path, msg) }
        fun warn(code: String, path: String, msg: String) { issues += SceneIssue(IssueLevel.WARNING, code, path, msg) }
        fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun ranged(o: JsonObject, key: String, path: String, def: Double, min: Double, max: Double): Double {
            val e = o[key] ?: return def
            val v = num(e)
            if (v == null) { err("bad_number", "$path.$key", "Ожидалось число"); return def }
            if (v < min || v > max) { warn("out_of_range", "$path.$key", "Значение вне пределов $min…$max, приведено"); return SceneMath.clamp(v, min, max) }
            return v
        }

        str(root["format"])?.let { if (it != ShowFile.FORMAT) err("wrong_format", "format", "Поле format должно быть \"${ShowFile.FORMAT}\"") }
        val songsArr = root["songs"] as? JsonArray
        if (songsArr == null) { err("bad_structure", "songs", "Нужен список songs"); return ShowReport(null, issues) }
        if (songsArr.isEmpty()) err("no_songs", "songs", "Нужна хотя бы одна песня")
        if (songsArr.size > MAX_SONGS) err("too_many_songs", "songs", "Песен не больше $MAX_SONGS")
        val songs = ArrayList<ShowSong>()
        val seen = HashSet<String>()
        songsArr.take(MAX_SONGS).forEachIndexed { si, se ->
            val sp = "songs[$si]"
            val so = se as? JsonObject
            if (so == null) { err("bad_structure", sp, "Песня должна быть объектом"); return@forEachIndexed }
            val id = str(so["id"]) ?: "song${si + 1}"
            if (!ID.matches(id)) err("bad_id", "$sp.id", "id песни: латиница, цифры, «_» и «-», до 40 знаков")
            else if (!seen.add(id)) err("duplicate_id", "$sp.id", "id «$id» уже занят")
            val title = (str(so["title"]) ?: "Песня").take(80)
            val secArr = so["sections"] as? JsonArray
            if (secArr == null || secArr.isEmpty()) { err("no_sections", "$sp.sections", "У песни нет частей"); return@forEachIndexed }
            if (secArr.size > MAX_SECTIONS) err("too_many_sections", "$sp.sections", "Частей не больше $MAX_SECTIONS")
            val sections = ArrayList<ShowSection>()
            secArr.take(MAX_SECTIONS).forEachIndexed { ci, ce ->
                val cp = "$sp.sections[$ci]"
                val co = ce as? JsonObject
                if (co == null) { err("bad_structure", cp, "Часть должна быть объектом"); return@forEachIndexed }
                val lines = (co["lines"] as? JsonArray)?.map { str(it).orEmpty() } ?: emptyList()
                if (lines.size > MAX_LINES) err("too_many_lines", "$cp.lines", "Строк не больше $MAX_LINES")
                lines.forEachIndexed { li, l -> if (l.length > MAX_LINE) warn("text_too_long", "$cp.lines[$li]", "Строка обрезана до $MAX_LINE знаков") }
                val emb = str(co["emb"])
                if (emb != null && emb !in EMBLEMS) err("bad_option", "$cp.emb", "Образ: ${EMBLEMS.joinToString()} или null")
                val place = str(co["place"]).orEmpty()
                if (place.isNotEmpty() && place !in PLACES) err("bad_option", "$cp.place", "Расположение: ${PLACES.joinToString()} или пусто")
                val font = str(co["font"]).orEmpty()
                if (font.isNotEmpty() && SceneCatalog.FONTS.none { it.first == font }) err("bad_option", "$cp.font", "Шрифт: ${SceneCatalog.FONTS.joinToString { it.first }} или пусто")
                sections += ShowSection(
                    tag = (str(co["tag"]) ?: "Слайд").take(40),
                    lines = lines.take(MAX_LINES).map { it.take(MAX_LINE) },
                    e0 = ranged(co, "e0", cp, 0.0, 0.0, 1.0),
                    e1 = ranged(co, "e1", cp, num(co["e0"]) ?: 0.0, 0.0, 1.0),
                    d = ranged(co, "d", cp, 12.0, 3.0, 180.0),
                    emb = emb?.takeIf { it in EMBLEMS },
                    place = place.takeIf { it in PLACES }.orEmpty(),
                    bpm = ranged(co, "bpm", cp, 0.0, 0.0, 240.0).toInt(),
                    ts = ranged(co, "ts", cp, 100.0, 50.0, 160.0).toInt(),
                    font = font.takeIf { f -> SceneCatalog.FONTS.any { it.first == f } }.orEmpty(),
                )
            }
            songs += ShowSong(id, title, ranged(so, "bpm", sp, 0.0, 0.0, 240.0).toInt(), sections)
        }
        val hasErrors = issues.any { it.level == IssueLevel.ERROR }
        return ShowReport(if (hasErrors) null else ShowFile(songs = songs), issues)
    }
}

/**
 * Время песни: где начинается каждая часть, какая часть идёт в момент [pos] и какую энергию она задаёт.
 * [durationScale] растягивает части (режим «Поклонение» длиннее, слайды «Короче/Длиннее»).
 */
class ShowTimeline(val song: ShowSong, durationScale: Double = 1.0) {
    val starts: DoubleArray
    val total: Double

    init {
        var acc = 0.0
        starts = DoubleArray(song.sections.size) { i -> val s = acc; acc += song.sections[i].d * durationScale; s }
        total = acc
    }

    fun duration(index: Int): Double = (if (index + 1 < starts.size) starts[index + 1] else total) - starts[index]

    /** Номер части в момент [pos]; за пределами песни — последняя или первая часть. */
    fun indexAt(pos: Double): Int {
        var i = starts.size - 1
        while (i > 0 && pos < starts[i]) i--
        return i
    }

    /** Доля пройденного внутри части 0…1. */
    fun fraction(index: Int, pos: Double): Double = SceneMath.clamp((pos - starts[index]) / duration(index), 0.0, 1.0)

    /** Целевая энергия: кривая части × [cap] режима × [power] ручки, не больше 1. */
    fun targetEnergy(index: Int, pos: Double, cap: Double = 1.0, power: Double = 1.0): Double {
        val s = song.sections[index]
        return minOf(1.0, Energy.ramp(s.e0, s.e1, fraction(index, pos)) * cap * power)
    }
}
