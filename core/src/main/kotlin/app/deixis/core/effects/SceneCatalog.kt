package app.deixis.core.effects

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Вид параметра: от него зависит проверка и поле в редакторе. */
enum class ParamKind {
    /** Число в пределах [ParamDef.min]…[ParamDef.max]. */
    NUM,
    /** Положение в палитре сцены 0…5 (дробное — плавный переход между соседними цветами). */
    COLOR,
    /** Один из [ParamDef.options]. */
    SELECT,
    /** Цвет `#rrggbb`. */
    HEX,
    /** Свободный текст до [SceneLimits.MAX_TEXT] знаков. */
    TEXT,
    /** id слоя-фигуры этой же сцены. */
    LAYER,
}

/** Как рисуется слой (docs/dev/SCENE.md, 2.1): мягкий — в малом разрешении, чёткий — векторно, текст — всегда сверху. */
enum class Rendering { SOFT, CRISP, TEXT }

/** Описание одного параметра слоя или эффекта. [def] — число для NUM и COLOR, иначе [defText]. */
data class ParamDef(
    val key: String,
    val label: String,
    val kind: ParamKind,
    val min: Double = 0.0,
    val max: Double = 1.0,
    val step: Double = 0.01,
    val def: Double = 0.0,
    val defText: String = "",
    val options: List<Pair<String, String>> = emptyList(),
) {
    /** К числовому параметру можно привязать источник или формулу. */
    val bindable: Boolean get() = kind == ParamKind.NUM || kind == ParamKind.COLOR
}

class LayerKind(val type: String, val name: String, val rendering: Rendering, val own: List<ParamDef>) {
    /** Все параметры: общие для слоёв (непрозрачность, «не мешать тексту») и собственные. */
    val params: List<ParamDef> = SceneCatalog.COMMON + own
}

class EffectKind(val type: String, val name: String, val cost: Double, val pixel: Boolean, val params: List<ParamDef>)

/** Жёсткие потолки формата. Превышение — ошибка проверки с путём, а не молчаливая обрезка. */
object SceneLimits {
    const val MAX_LAYERS = 12
    const val MAX_FX_PER_LAYER = 4
    const val MAX_PIXEL_FX = 3
    const val MAX_MODS = 8
    const val MAX_PARTICLES_TOTAL = 400
    const val MAX_TEXT = 600
    const val MAX_NAME = 40
    const val MAX_TITLE = 80
    const val MAX_ABOUT = 300
    const val MAX_FILE_BYTES = 256 * 1024
    val ID = Regex("[A-Za-z0-9_-]{1,20}")
    val MOD_ID = Regex("[a-z]+[0-9]+")
    val HEX = Regex("#[0-9a-fA-F]{6}")
}

/**
 * Каталог: какие слои, эффекты и параметры бывают. Единственный источник для проверки файла, для редактора и для
 * описания, которое получает нейросеть ([toJson]). Новый инструмент попадает сюда только по правилу приёма
 * (docs/dev/SCENE.md, 8.4): класс цены, потолки, детерминизм, упрощённый вариант, тест.
 */
object SceneCatalog {
    val BLENDS: List<Pair<String, String>> = listOf(
        "screen" to "screen (осветление)", "source-over" to "обычный", "lighter" to "сложение",
        "multiply" to "умножение", "overlay" to "overlay", "soft-light" to "мягкий свет",
    )
    val SHAPES: List<Pair<String, String>> = listOf(
        "circle" to "Круг", "ring" to "Кольцо", "rect" to "Прямоугольник", "polygon" to "Многоугольник", "star" to "Звезда с лучами",
        "heart" to "Сердце", "drop" to "Капля", "moon" to "Месяц", "cross" to "Крест", "dove" to "Голубь", "angel" to "Ангел",
        "flame" to "Пламя", "starburst" to "Вифлеемская звезда", "book" to "Книга",
    )
    val LAYOUTS: List<Pair<String, String>> = listOf(
        "line" to "Линия", "circle" to "Окружность", "grid" to "Сетка", "spiral" to "Спираль", "scatter" to "Разброс", "fan" to "Веер",
    )
    val FONTS: List<Pair<String, String>> = listOf(
        "cormorant" to "Cormorant", "playfair" to "Playfair", "lora" to "Lora", "marck" to "Marck Script",
    )
    val DEFAULT_PALETTE: List<String> = listOf("#C9A45C", "#7E8FB8", "#5F6FA8", "#B88A5A", "#8C7BA6")

    private fun n(key: String, label: String, min: Double, max: Double, step: Double, def: Double) =
        ParamDef(key, label, ParamKind.NUM, min, max, step, def)
    private fun c(key: String, label: String, def: Double) = ParamDef(key, label, ParamKind.COLOR, 0.0, 5.0, 0.05, def)
    private fun s(key: String, label: String, options: List<Pair<String, String>>, def: String) =
        ParamDef(key, label, ParamKind.SELECT, defText = def, options = options)
    private fun hx(key: String, label: String, def: String) = ParamDef(key, label, ParamKind.HEX, defText = def)
    private fun tx(key: String, label: String, def: String) = ParamDef(key, label, ParamKind.TEXT, defText = def)

    val COMMON: List<ParamDef> = listOf(
        n("opacity", "Непрозрачность", 0.0, 1.0, 0.01, 1.0),
        n("avoid", "Не мешать тексту", 0.0, 1.0, 0.01, 0.0),
    )

    val layers: Map<String, LayerKind> = listOf(
        LayerKind("gradient", "Подложка (градиент)", Rendering.SOFT, listOf(
            s("kind", "Вид", listOf("linear" to "Линейный", "radial" to "Радиальный"), "linear"),
            hx("c1", "Цвет 1", "#0d0a1c"), hx("c2", "Цвет 2", "#1b1238"),
            n("angle", "Угол, °", 0.0, 360.0, 1.0, 90.0),
        )),
        LayerKind("field", "Краски (мягкие пятна)", Rendering.SOFT, listOf(
            n("count", "Число пятен", 1.0, 12.0, 1.0, 6.0), n("size", "Размер", 0.1, 1.0, 0.01, 0.45),
            n("speed", "Скорость", 0.0, 3.0, 0.05, 1.0), n("soft", "Яркость пятен", 0.1, 1.0, 0.01, 0.7),
            c("c", "Цвет", 0.0), n("spread", "Разброс цвета", 0.0, 3.0, 0.05, 1.4),
            s("move", "Движение", listOf("lissajous" to "Лиссажу", "orbit" to "Орбита", "drift" to "Дрейф"), "lissajous"),
        )),
        LayerKind("shape", "Образ / фигура (чёткая)", Rendering.CRISP, listOf(
            s("kind", "Форма", SHAPES, "cross"),
            n("x", "Положение x", 0.0, 1.0, 0.005, 0.5), n("y", "Положение y", 0.0, 1.0, 0.005, 0.5),
            n("size", "Размер", 0.02, 1.4, 0.005, 0.3), n("rot", "Поворот, °", -180.0, 180.0, 1.0, 0.0),
            n("spin", "Вращение, °/с", -180.0, 180.0, 1.0, 0.0), c("c", "Цвет", 0.0), n("cstep", "Цвет на копию", 0.0, 1.0, 0.01, 0.0),
            n("alpha", "Яркость", 0.0, 1.0, 0.01, 1.0), n("fill", "Заливка", 0.0, 1.0, 0.01, 1.0),
            n("stroke", "Обводка, px", 0.0, 20.0, 0.5, 0.0), n("glow", "Свечение, px", 0.0, 80.0, 1.0, 0.0),
            n("pn", "Углов / лучей", 3.0, 12.0, 1.0, 5.0), n("copies", "Число копий", 1.0, 64.0, 1.0, 1.0),
            s("layout", "Раскладка", LAYOUTS, "circle"), n("spread", "Размах раскладки", 0.0, 1.5, 0.01, 0.5),
            n("stepRot", "Поворот на копию, °", -90.0, 90.0, 0.5, 0.0), n("stepScale", "Размер на копию", 0.6, 1.4, 0.01, 1.0),
            n("fade", "Затухание по копиям", 0.0, 1.0, 0.01, 0.0), n("jitter", "Дрожь положения", 0.0, 1.0, 0.01, 0.0),
        )),
        LayerKind("emitter", "Излучатель (огоньки, искры)", Rendering.CRISP, listOf(
            n("count", "Число", 1.0, 200.0, 1.0, 40.0),
            s("kind", "Форма", listOf("dot" to "Огонёк", "star" to "Звезда", "cross" to "Крест", "ring" to "Кольцо"), "dot"),
            n("size", "Размер", 0.004, 0.08, 0.001, 0.02), n("speed", "Скорость", 0.0, 3.0, 0.05, 1.0),
            n("dir", "Направление, °", 0.0, 360.0, 1.0, 270.0), n("spread", "Разброс, °", 0.0, 180.0, 1.0, 40.0),
            c("c", "Цвет", 1.0), n("cspread", "Разброс цвета", 0.0, 3.0, 0.05, 1.0),
            n("twinkle", "Мерцание", 0.0, 1.0, 0.01, 0.0), n("alpha", "Яркость", 0.0, 1.0, 0.01, 0.7),
        )),
        LayerKind("rays", "Лучи", Rendering.CRISP, listOf(
            n("count", "Число", 3.0, 48.0, 1.0, 16.0), n("len", "Длина", 0.1, 1.4, 0.01, 0.9),
            n("width", "Толщина", 0.001, 0.05, 0.001, 0.004), n("inner", "Внутренний радиус", 0.0, 0.6, 0.01, 0.1),
            n("ox", "Центр x", 0.0, 1.0, 0.005, 0.5), n("oy", "Центр y", 0.0, 1.0, 0.005, 0.5),
            n("spin", "Вращение, °/с", -90.0, 90.0, 0.5, 10.0), c("c", "Цвет", 0.0), n("alpha", "Яркость", 0.0, 1.0, 0.01, 0.4),
        )),
        LayerKind("rings", "Кольца-волны", Rendering.CRISP, listOf(
            n("count", "Число", 1.0, 16.0, 1.0, 6.0), n("gap", "Шаг", 0.02, 0.3, 0.005, 0.09),
            n("width", "Толщина", 0.001, 0.03, 0.001, 0.004), n("speed", "Скорость", -2.0, 2.0, 0.05, 0.6),
            n("ox", "Центр x", 0.0, 1.0, 0.005, 0.5), n("oy", "Центр y", 0.0, 1.0, 0.005, 0.5),
            n("dash", "Штрих", 0.0, 1.0, 0.01, 0.0), c("c", "Цвет", 2.0), n("alpha", "Яркость", 0.0, 1.0, 0.01, 0.6),
        )),
        LayerKind("thread", "Нити и волны (линии)", Rendering.CRISP, listOf(
            s("kind", "Вид", listOf("wave" to "Волна", "lissajous" to "Лиссажу"), "wave"),
            n("lines", "Число нитей", 1.0, 12.0, 1.0, 3.0), n("amp", "Размах", 0.0, 0.45, 0.005, 0.12),
            n("freq", "Частота", 0.3, 8.0, 0.05, 1.5), n("speed", "Скорость", 0.0, 3.0, 0.05, 1.0),
            n("y", "Положение y", 0.0, 1.0, 0.005, 0.5), n("thick", "Толщина, px", 1.0, 14.0, 0.5, 3.0),
            c("c", "Цвет", 0.0), n("cspread", "Разброс цвета", 0.0, 2.0, 0.05, 0.4),
            n("glow", "Свечение, px", 0.0, 60.0, 1.0, 14.0), n("alpha", "Яркость", 0.0, 1.0, 0.01, 0.8),
        )),
        LayerKind("contour", "Контур: волны от границ образа", Rendering.SOFT, listOf(
            ParamDef("src", "Источник (слой-фигура)", ParamKind.LAYER),
            n("rings", "Кольца", 10.0, 120.0, 1.0, 46.0), n("speed", "Скорость", 0.0, 3.0, 0.05, 1.0),
            n("reach", "Затухание наружу", 0.3, 5.0, 0.05, 1.5), n("irid", "Перелив", 0.0, 1.0, 0.01, 0.45),
            c("c", "Цвет", 0.0), n("strength", "Сила", 0.0, 1.5, 0.01, 1.0),
        )),
        LayerKind("text", "Текст (всегда сверху)", Rendering.TEXT, listOf(
            s("source", "Источник текста", listOf("slide" to "Текст слайда", "fixed" to "Свой текст"), "slide"),
            tx("text", "Текст", "Слава в вышних Богу,\nи на земли мир,\nв человецех благоволение"),
            n("size", "Размер, % ширины", 2.0, 9.0, 0.1, 4.1), n("x", "Положение x", 0.0, 1.0, 0.005, 0.5),
            n("y", "Положение y", 0.0, 1.0, 0.005, 0.45), s("font", "Шрифт", FONTS, "cormorant"), n("shadow", "Тень", 0.0, 1.0, 0.01, 0.7),
        )),
    ).associateBy { it.type }

    val effects: Map<String, EffectKind> = listOf(
        EffectKind("blur", "Размытие", 1.5, false, listOf(n("r", "Радиус, px", 0.0, 40.0, 0.5, 6.0))),
        EffectKind("bloom", "Свечение (bloom)", 2.0, false, listOf(n("r", "Радиус, px", 1.0, 60.0, 1.0, 16.0), n("amt", "Сила", 0.0, 1.0, 0.01, 0.6))),
        EffectKind("color", "Цвет слоя", 0.8, false, listOf(
            n("hue", "Оттенок, °", -180.0, 180.0, 1.0, 0.0), n("sat", "Насыщенность, %", 0.0, 300.0, 1.0, 100.0), n("bri", "Яркость, %", 20.0, 200.0, 1.0, 100.0),
        )),
        EffectKind("warp", "Деформация (по пикселям)", 3.0, true, listOf(
            n("amt", "Сила", 0.0, 1.0, 0.01, 0.5), n("freq", "Частота", 0.5, 12.0, 0.1, 4.0), n("speed", "Скорость", 0.0, 3.0, 0.05, 1.0),
        )),
        EffectKind("kaleido", "Калейдоскоп", 0.5, false, listOf(
            n("n", "Сегменты", 2.0, 12.0, 1.0, 6.0), n("cx", "Центр x", 0.0, 1.0, 0.01, 0.5), n("cy", "Центр y", 0.0, 1.0, 0.01, 0.5),
        )),
        EffectKind("trails", "Следы", 2.5, true, listOf(
            n("decay", "Затухание", 0.0, 0.96, 0.01, 0.85), n("zoom", "Масштаб за кадр", 0.95, 1.05, 0.001, 1.01), n("rot", "Поворот за кадр, °", -3.0, 3.0, 0.05, 0.0),
        )),
    ).associateBy { it.type }

    /** Типы источников движения. `energy` и `beat` есть всегда. */
    val modTypes: Set<String> = setOf("lfo", "noise")
    val modShapes: Set<String> = setOf("sine", "tri", "saw")
    val builtinSources: Set<String> = setOf("energy", "beat")

    /** Описание каталога для нейросети и редактора (то же, что `catalog.layers/effects/limits` в docs/ai/SCENE_PROMPT.md). */
    fun toJson(): JsonElement = buildJsonObject {
        put("format", Scene.FORMAT)
        put("version", Scene.VERSION)
        put("blends", buildJsonArray { BLENDS.forEach { add(JsonPrimitive(it.first)) } })
        put("layers", buildJsonArray { layers.values.forEach { add(kindJson(it.type, it.name, it.rendering.name.lowercase(), it.params, null)) } })
        put("effects", buildJsonArray { effects.values.forEach { add(kindJson(it.type, it.name, if (it.pixel) "pixel" else "light", it.params, it.cost)) } })
        put("modulators", buildJsonArray { modTypes.forEach { add(JsonPrimitive(it)) } })
        put("sources", buildJsonArray { builtinSources.forEach { add(JsonPrimitive(it)) } })
        put("formula", buildJsonObject {
            put("variables", buildJsonArray { Expr.BUILTIN_VARS.sorted().forEach { add(JsonPrimitive(it)) } })
            put("functions", buildJsonArray { Expr.FUNCTIONS.sorted().forEach { add(JsonPrimitive(it)) } })
            put("maxNodes", Expr.MAX_NODES)
            put("maxLength", Expr.MAX_LENGTH)
        })
        put("limits", buildJsonObject {
            put("layers", SceneLimits.MAX_LAYERS); put("effectsPerLayer", SceneLimits.MAX_FX_PER_LAYER)
            put("pixelEffects", SceneLimits.MAX_PIXEL_FX); put("modulators", SceneLimits.MAX_MODS)
            put("particlesTotal", SceneLimits.MAX_PARTICLES_TOTAL); put("textLength", SceneLimits.MAX_TEXT)
        })
    }

    private fun kindJson(type: String, name: String, mode: String, params: List<ParamDef>, cost: Double?) = buildJsonObject {
        put("type", type); put("name", name); put("mode", mode)
        if (cost != null) put("cost", cost)
        put("params", JsonArray(params.map { d ->
            buildJsonObject {
                put("key", d.key); put("label", d.label); put("kind", d.kind.name.lowercase()); put("bindable", d.bindable)
                when (d.kind) {
                    ParamKind.NUM, ParamKind.COLOR -> { put("min", d.min); put("max", d.max); put("default", d.def) }
                    ParamKind.SELECT -> { put("options", buildJsonArray { d.options.forEach { add(JsonPrimitive(it.first)) } }); put("default", d.defText) }
                    else -> if (d.defText.isNotEmpty()) put("default", d.defText)
                }
            }
        }))
    }
}
