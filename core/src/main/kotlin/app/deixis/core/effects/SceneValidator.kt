package app.deixis.core.effects

import app.deixis.core.DeixisJson
import app.deixis.core.quiz.IssueLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Замечание к файлу сцены. [code] — стабильный код (для переводов и для нейросети), [path] — где
 * (`layers[2].bind.size.expr`), [message] — понятная фраза.
 */
@Serializable
data class SceneIssue(val level: IssueLevel, val code: String, val path: String, val message: String)

/** Итог проверки: [scene] есть, только если ошибок нет. Предупреждения (приведённые числа, лишние поля) файл не блокируют. */
data class SceneReport(val scene: Scene?, val issues: List<SceneIssue>) {
    val errors get() = issues.filter { it.level == IssueLevel.ERROR }
    val warnings get() = issues.filter { it.level == IssueLevel.WARNING }
    val ok get() = scene != null
}

/**
 * Проверка файла сцены. Файл не доверенный (его мог написать человек или нейросеть): размеры ограничены
 * ([SceneLimits]), формулы разбираются безопасно ([Expr]), ссылки проверяются. Число вне пределов не ошибка:
 * оно приводится к границе с предупреждением. Неизвестный тип, неверная ссылка, превышение потолка — ошибка.
 */
object SceneValidator {
    private val ROOT_KEYS = setOf("format", "version", "id", "title", "about", "seed", "bpm", "speed", "quiet", "strict", "palette", "mods", "layers")
    private val MOD_KEYS = setOf("id", "type", "rate", "shape", "phase")
    private val LAYER_KEYS = setOf("id", "type", "name", "on", "prio", "blend", "p", "bind", "fx")
    private val FX_KEYS = setOf("type", "off", "p", "bind")

    fun validate(text: String): SceneReport {
        if (text.toByteArray().size > SceneLimits.MAX_FILE_BYTES) {
            return SceneReport(null, listOf(SceneIssue(IssueLevel.ERROR, "file_too_big", "", "Файл больше ${SceneLimits.MAX_FILE_BYTES / 1024} КБ")))
        }
        val root = try {
            DeixisJson.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return SceneReport(null, listOf(SceneIssue(IssueLevel.ERROR, "bad_json", "", "Это не JSON или корень не объект: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")))
        }
        return validateElement(root)
    }

    fun validateElement(root: JsonObject): SceneReport = Run().check(root)

    private class Run {
        val issues = ArrayList<SceneIssue>()

        /** Исходный номер каждого принятого слоя в файле: пути ошибок должны указывать на строку файла, а не на сокращённый список. */
        val layerRaw = ArrayList<Int>()

        fun err(code: String, path: String, msg: String) { issues += SceneIssue(IssueLevel.ERROR, code, path, msg) }
        fun warn(code: String, path: String, msg: String) { issues += SceneIssue(IssueLevel.WARNING, code, path, msg) }
        fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

        fun unknownKeys(o: JsonObject, allowed: Set<String>, path: String) {
            for (k in o.keys) if (k !in allowed) warn("unknown_key", join(path, k), "Неизвестное поле «$k» пропущено")
        }

        fun number(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        fun limit(v: Double, min: Double, max: Double, path: String): Double {
            if (v.isNaN()) { err("bad_number", path, "Ожидалось число"); return min }
            if (v < min || v > max) {
                warn("out_of_range", path, "Значение ${fmt(v)} вне пределов ${fmt(min)}…${fmt(max)}, приведено к границе")
                return SceneMath.clamp(v, min, max)
            }
            return v
        }

        fun fmt(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString() else v.toString()

        fun numField(o: JsonObject, key: String, path: String, def: Double, min: Double, max: Double): Double {
            val e = o[key] ?: return def
            val v = number(e)
            if (v == null) { err("bad_number", join(path, key), "Ожидалось число"); return def }
            return limit(v, min, max, join(path, key))
        }

        fun check(root: JsonObject): SceneReport {
            unknownKeys(root, ROOT_KEYS, "")
            text(root["format"])?.let { if (it != Scene.FORMAT) err("wrong_format", "format", "Поле format должно быть \"${Scene.FORMAT}\"") }
            val version = number(root["version"])?.toInt() ?: Scene.VERSION
            if (version > Scene.VERSION) err("newer_version", "version", "Файл версии $version, эта программа понимает до ${Scene.VERSION}")
            else if (version < 1) err("bad_version", "version", "Версия должна быть 1 или больше")

            val id = text(root["id"]).orEmpty()
            if (id.isNotEmpty() && !SceneLimits.ID.matches(id)) err("bad_id", "id", "id сцены: латиница, цифры, «_» и «-», до 20 знаков")
            val title = text(root["title"]).orEmpty().let { if (it.length > SceneLimits.MAX_TITLE) { warn("text_too_long", "title", "Название обрезано до ${SceneLimits.MAX_TITLE} знаков"); it.take(SceneLimits.MAX_TITLE) } else it }
            val about = text(root["about"]).orEmpty().let { if (it.length > SceneLimits.MAX_ABOUT) { warn("text_too_long", "about", "Описание обрезано до ${SceneLimits.MAX_ABOUT} знаков"); it.take(SceneLimits.MAX_ABOUT) } else it }
            val seed = numField(root, "seed", "", 7.0, 0.0, 9999.0).toInt()
            val bpm = numField(root, "bpm", "", 96.0, 0.0, 240.0).toInt()
            val speed = numField(root, "speed", "", 1.0, 0.1, 2.5)
            val quiet = numField(root, "quiet", "", 0.35, 0.0, 1.0)
            val strict = (root["strict"] as? JsonPrimitive)?.booleanOrNull ?: false

            val palette = parsePalette(root["palette"])
            val mods = parseMods(root["mods"])
            val sources = SceneCatalog.builtinSources + mods.map { it.id }
            val vars = Expr.BUILTIN_VARS + mods.map { it.id }
            val layers = parseLayers(root["layers"], sources, vars)

            // ссылки между слоями
            layers.forEachIndexed { i, l ->
                if (l.type == "contour") {
                    val src = l.str("src")
                    val target = layers.firstOrNull { it.id == src }
                    if (target == null || target.type != "shape") err("bad_contour_source", "layers[${layerRaw[i]}].p.src", "Источник контура — id слоя-фигуры этой сцены")
                }
            }
            val particles = layers.filter { it.type == "emitter" }.sumOf { it.num("count") }
            if (particles > SceneLimits.MAX_PARTICLES_TOTAL) err("too_many_particles", "layers", "Огоньков на сцену не больше ${SceneLimits.MAX_PARTICLES_TOTAL}, сейчас ${particles.toInt()}")
            val pixel = layers.sumOf { l -> l.fx.count { SceneCatalog.effects[it.type]?.pixel == true } }
            if (pixel > SceneLimits.MAX_PIXEL_FX) err("too_many_pixel_effects", "layers", "Эффектов по пикселям на сцену не больше ${SceneLimits.MAX_PIXEL_FX}, сейчас $pixel")

            val hasErrors = issues.any { it.level == IssueLevel.ERROR }
            val scene = if (hasErrors) null else Scene(
                id = id, title = title, about = about, seed = seed, bpm = bpm, speed = speed, quiet = quiet, strict = strict,
                palette = palette, mods = mods, layers = layers,
            )
            return SceneReport(scene, issues)
        }

        fun parsePalette(e: JsonElement?): List<String> {
            if (e == null) return SceneCatalog.DEFAULT_PALETTE
            val list = (e as? JsonArray)?.map { text(it).orEmpty() }
            if (list == null || list.size != 5 || list.any { !SceneLimits.HEX.matches(it) }) {
                err("bad_palette", "palette", "Нужно ровно 5 цветов вида #rrggbb")
                return SceneCatalog.DEFAULT_PALETTE
            }
            return list
        }

        fun parseMods(e: JsonElement?): List<Modulator> {
            if (e == null) return emptyList()
            val arr = e as? JsonArray
            if (arr == null) { err("bad_structure", "mods", "mods должен быть списком"); return emptyList() }
            if (arr.size > SceneLimits.MAX_MODS) err("too_many_mods", "mods", "Источников движения не больше ${SceneLimits.MAX_MODS}")
            val out = ArrayList<Modulator>()
            val seen = HashSet<String>()
            arr.take(SceneLimits.MAX_MODS).forEachIndexed { i, me ->
                val path = "mods[$i]"
                val mo = me as? JsonObject
                if (mo == null) { err("bad_structure", path, "Источник должен быть объектом"); return@forEachIndexed }
                unknownKeys(mo, MOD_KEYS, path)
                val type = text(mo["type"]) ?: "lfo"
                if (type !in SceneCatalog.modTypes) { err("unknown_mod_type", "$path.type", "Тип источника: ${SceneCatalog.modTypes.joinToString()}"); return@forEachIndexed }
                val id = text(mo["id"]) ?: (type + (i + 1))
                if (!SceneLimits.MOD_ID.matches(id) || id in Expr.BUILTIN_VARS) { err("bad_id", "$path.id", "id источника: строчные буквы и номер, например lfo1"); return@forEachIndexed }
                if (!seen.add(id)) { err("duplicate_id", "$path.id", "id «$id» уже занят"); return@forEachIndexed }
                val shape = text(mo["shape"]) ?: "sine"
                if (shape !in SceneCatalog.modShapes) err("bad_option", "$path.shape", "Допустимо: ${SceneCatalog.modShapes.joinToString()}")
                out += Modulator(id, type, numField(mo, "rate", path, 0.2, 0.01, 5.0), if (shape in SceneCatalog.modShapes) shape else "sine", numField(mo, "phase", path, 0.0, 0.0, 1.0))
            }
            return out
        }

        fun parseLayers(e: JsonElement?, sources: Set<String>, vars: Set<String>): List<Layer> {
            val arr = e as? JsonArray
            if (arr == null) { err("bad_structure", "layers", "Нужен список layers"); return emptyList() }
            if (arr.isEmpty()) err("no_layers", "layers", "Нужен хотя бы один слой")
            if (arr.size > SceneLimits.MAX_LAYERS) err("too_many_layers", "layers", "Слоёв не больше ${SceneLimits.MAX_LAYERS}, сейчас ${arr.size}")
            val out = ArrayList<Layer>()
            val seen = HashSet<String>()
            arr.take(SceneLimits.MAX_LAYERS).forEachIndexed { i, le ->
                val path = "layers[$i]"
                val lo = le as? JsonObject
                if (lo == null) { err("bad_structure", path, "Слой должен быть объектом"); return@forEachIndexed }
                unknownKeys(lo, LAYER_KEYS, path)
                val type = text(lo["type"])
                val kind = type?.let { SceneCatalog.layers[it] }
                if (kind == null) { err("unknown_layer_type", "$path.type", "Тип слоя «${type.orEmpty()}» неизвестен (есть: ${SceneCatalog.layers.keys.joinToString()})"); return@forEachIndexed }
                val id = text(lo["id"]) ?: (kind.type + (i + 1))
                if (!SceneLimits.ID.matches(id)) { err("bad_id", "$path.id", "id слоя: латиница, цифры, «_» и «-», до 20 знаков"); return@forEachIndexed }
                if (!seen.add(id)) { err("duplicate_id", "$path.id", "id «$id» уже занят другим слоем"); return@forEachIndexed }
                val name = (text(lo["name"]) ?: kind.name.substringBefore(" (")).let {
                    if (it.length > SceneLimits.MAX_NAME) { warn("text_too_long", "$path.name", "Название обрезано до ${SceneLimits.MAX_NAME} знаков"); it.take(SceneLimits.MAX_NAME) } else it
                }
                val on = (lo["on"] as? JsonPrimitive)?.booleanOrNull ?: true
                val soloKind = kind.type == "gradient" || kind.type == "text"
                val prio = numField(lo, "prio", path, if (soloKind) 5.0 else 3.0, 1.0, 5.0).toInt()
                val blendRaw = text(lo["blend"])
                val blend = when {
                    blendRaw == null -> if (soloKind) "source-over" else "screen"
                    SceneCatalog.BLENDS.any { it.first == blendRaw } -> blendRaw
                    else -> { err("bad_option", "$path.blend", "Допустимо: ${SceneCatalog.BLENDS.joinToString { it.first }}"); "screen" }
                }
                val p = parseParams(lo["p"] as? JsonObject, kind.params, "$path.p")
                if (lo["p"] != null && lo["p"] !is JsonObject) err("bad_structure", "$path.p", "p должен быть объектом")
                val bind = parseBind(lo["bind"], "$path.bind", kind.params, sources, vars)
                val fx = parseFx(lo["fx"], "$path.fx", sources, vars)
                out += Layer(id, kind.type, name, on, prio, blend, p, bind, fx)
                layerRaw += i
            }
            return out
        }

        fun parseParams(po: JsonObject?, defs: List<ParamDef>, path: String): Map<String, JsonPrimitive> {
            if (po != null) {
                val keys = defs.map { it.key }.toSet()
                for (k in po.keys) if (k !in keys) warn("unknown_param", join(path, k), "Неизвестный параметр «$k» пропущен")
            }
            val out = LinkedHashMap<String, JsonPrimitive>()
            for (d in defs) {
                val e = po?.get(d.key)
                val pp = join(path, d.key)
                out[d.key] = when (d.kind) {
                    ParamKind.NUM, ParamKind.COLOR -> {
                        val v = if (e == null) d.def else number(e)?.let { limit(it, d.min, d.max, pp) } ?: run { err("bad_number", pp, "Ожидалось число"); d.def }
                        JsonPrimitive(v)
                    }
                    ParamKind.SELECT -> {
                        val s = text(e)
                        when {
                            e == null -> JsonPrimitive(d.defText)
                            s != null && d.options.any { it.first == s } -> JsonPrimitive(s)
                            else -> { err("bad_option", pp, "Допустимо: ${d.options.joinToString { it.first }}"); JsonPrimitive(d.defText) }
                        }
                    }
                    ParamKind.HEX -> {
                        val s = text(e)
                        when {
                            e == null -> JsonPrimitive(d.defText)
                            s != null && SceneLimits.HEX.matches(s) -> JsonPrimitive(s)
                            else -> { err("bad_color", pp, "Нужен цвет вида #rrggbb"); JsonPrimitive(d.defText) }
                        }
                    }
                    ParamKind.TEXT -> {
                        val s = if (e == null) d.defText else text(e) ?: run { err("bad_text", pp, "Нужна строка"); d.defText }
                        if (s.length > SceneLimits.MAX_TEXT) { warn("text_too_long", pp, "Текст обрезан до ${SceneLimits.MAX_TEXT} знаков"); JsonPrimitive(s.take(SceneLimits.MAX_TEXT)) } else JsonPrimitive(s)
                    }
                    ParamKind.LAYER -> JsonPrimitive(if (e == null) "" else text(e) ?: run { err("bad_text", pp, "Нужна строка (id слоя)"); "" })
                }
            }
            return out
        }

        fun parseBind(raw: JsonElement?, path: String, defs: List<ParamDef>, sources: Set<String>, vars: Set<String>): Map<String, Binding> {
            if (raw == null) return emptyMap()
            val o = raw as? JsonObject
            if (o == null) { err("bad_structure", path, "bind должен быть объектом"); return emptyMap() }
            val out = LinkedHashMap<String, Binding>()
            for ((key, be) in o) {
                val bp = join(path, key)
                val d = defs.firstOrNull { it.key == key }
                if (d == null || !d.bindable) { err("bad_bind_target", bp, "К параметру «$key» нельзя привязать источник"); continue }
                val bo = be as? JsonObject
                if (bo == null) { err("bad_structure", bp, "Привязка — объект {src, amt, expr}"); continue }
                unknownKeys(bo, setOf("src", "amt", "expr"), bp)
                val src = text(bo["src"]).orEmpty()
                val expr = text(bo["expr"]).orEmpty()
                var ok = true
                if (src.isNotEmpty() && src !in sources) { err("unknown_source", "$bp.src", "Источника «$src» нет (есть: ${sources.sorted().joinToString()})"); ok = false }
                var exprOk = ""
                if (expr.isNotBlank()) {
                    try { Expr.compile(expr, vars); exprOk = expr } catch (e: ExprException) { err("bad_expr", "$bp.expr", e.message ?: "Ошибка в формуле"); ok = false }
                }
                val amt = numField(bo, "amt", bp, 0.0, -1.0, 1.0)
                if (ok && src.isEmpty() && exprOk.isEmpty()) warn("empty_bind", bp, "Привязка без источника и формулы пропущена")
                else if (ok) out[key] = Binding(src, amt, exprOk)
            }
            return out
        }

        fun parseFx(raw: JsonElement?, path: String, sources: Set<String>, vars: Set<String>): List<Effect> {
            if (raw == null) return emptyList()
            val arr = raw as? JsonArray
            if (arr == null) { err("bad_structure", path, "fx должен быть списком"); return emptyList() }
            if (arr.size > SceneLimits.MAX_FX_PER_LAYER) err("too_many_effects", path, "Эффектов на слой не больше ${SceneLimits.MAX_FX_PER_LAYER}")
            val out = ArrayList<Effect>()
            arr.take(SceneLimits.MAX_FX_PER_LAYER).forEachIndexed { j, fe ->
                val p = "$path[$j]"
                val fo = fe as? JsonObject
                if (fo == null) { err("bad_structure", p, "Эффект должен быть объектом"); return@forEachIndexed }
                unknownKeys(fo, FX_KEYS, p)
                val type = text(fo["type"])
                val kind = type?.let { SceneCatalog.effects[it] }
                if (kind == null) { err("unknown_effect_type", "$p.type", "Эффект «${type.orEmpty()}» неизвестен (есть: ${SceneCatalog.effects.keys.joinToString()})"); return@forEachIndexed }
                val params = parseParams(fo["p"] as? JsonObject, kind.params, "$p.p")
                val nums = params.mapValues { it.value.doubleOrNull ?: 0.0 }
                val bind = parseBind(fo["bind"], "$p.bind", kind.params, sources, vars)
                out += Effect(kind.type, (fo["off"] as? JsonPrimitive)?.booleanOrNull ?: false, nums, bind)
            }
            return out
        }
    }
}
