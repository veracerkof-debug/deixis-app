package app.deixis.core.effects

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Сцена фона (`*.deixis-scene.json`): слои, эффекты, источники движения, привязки. Это данные, а не код:
 * файл от человека и от нейросети читается одинаково и никогда не выполняется. Описание — docs/dev/SCENE.md.
 *
 * Объект в этом виде — результат проверки ([SceneValidator]): все параметры на месте, числа в пределах, ссылки целы.
 * Версионирование: [version] растёт только при несовместимых изменениях; новые необязательные поля версию не меняют.
 */
@Serializable
data class Scene(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val id: String = "",
    val title: String = "",
    val about: String = "",
    /** Зерно: вся «случайность» сцены (положение пятен, огоньков, разброс) зависит только от него. */
    val seed: Int = 7,
    val bpm: Int = 96,
    val speed: Double = 1.0,
    /** Мастер «тише» 0…1: гасит насыщенность, яркость эффектов, огоньки, скорость. */
    val quiet: Double = 0.35,
    /** Строгий режим: подложка, краски, один образ и текст. */
    val strict: Boolean = false,
    val palette: List<String> = SceneCatalog.DEFAULT_PALETTE,
    val mods: List<Modulator> = emptyList(),
    val layers: List<Layer>,
) {
    companion object {
        const val FORMAT = "deixis-scene"
        const val VERSION = 1
        const val FILE_SUFFIX = ".deixis-scene.json"
    }

    fun layer(id: String): Layer? = layers.firstOrNull { it.id == id }

    /** Слои, которые работают в строгом режиме: подложка, краски, текст и первая включённая фигура. */
    fun strictLayers(): List<Layer> {
        val firstShape = layers.firstOrNull { it.on && it.type == "shape" }?.id
        return layers.filter { it.type == "gradient" || it.type == "field" || it.type == "text" || (it.type == "shape" && it.id == firstShape) }
    }
}

/** Источник движения: плавная волна (`lfo`) или шум (`noise`). [id] вида `lfo1`, `noise2`. */
@Serializable
data class Modulator(
    val id: String,
    val type: String,
    /** Частота, Гц. */
    val rate: Double = 0.2,
    val shape: String = "sine",
    val phase: Double = 0.0,
)

@Serializable
data class Layer(
    val id: String,
    val type: String,
    val name: String,
    val on: Boolean = true,
    /** Приоритет 1…5: при нехватке времени отключаются слои с меньшим числом; 5 не отключается. */
    val prio: Int = 3,
    val blend: String = "screen",
    /** Все параметры слоя (числа и строки), в пределах каталога. */
    val p: Map<String, JsonPrimitive>,
    val bind: Map<String, Binding> = emptyMap(),
    val fx: List<Effect> = emptyList(),
) {
    fun num(key: String): Double = p[key]?.doubleOrNull ?: 0.0
    fun str(key: String): String = p[key]?.contentOrNull.orEmpty()
}

/** Привязка параметра: значение = база + источник × [amt] × диапазон параметра; либо формула [expr] вместо источника. */
@Serializable
data class Binding(val src: String = "", val amt: Double = 0.0, val expr: String = "")

@Serializable
data class Effect(
    val type: String,
    val off: Boolean = false,
    val p: Map<String, Double>,
    val bind: Map<String, Binding> = emptyMap(),
)
