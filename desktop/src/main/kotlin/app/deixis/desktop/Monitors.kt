package app.deixis.desktop

import app.deixis.core.DeixisJson
import app.deixis.core.MonitorOutput
import app.deixis.core.MonitorRequest
import app.deixis.core.OutputLayout
import kotlinx.serialization.Serializable
import java.io.File

/** Монитор, как его видит система: постоянный идентификатор и положение на рабочем столе. */
data class ScreenInfo(val id: String, val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Проектор, который повторяет рабочий стол (Linux, X11: два выхода с одного угла), — не второй монитор:
 * зал на нём закрыл бы рабочий стол оператора. Мониторы с общим левым верхним углом считаются одним
 * (остаётся основной); настоящие соседние мониторы никогда не начинаются в одной точке.
 */
fun withoutMirrors(screens: List<ScreenInfo>, primaryId: String?): List<ScreenInfo> =
    screens.groupBy { it.x to it.y }.values.map { same -> same.firstOrNull { it.id == primaryId } ?: same.first() }
        .sortedBy { s -> screens.indexOf(s) }

/** Выбор оператора для монитора. Хранится только то, что оператор менял сам; остальное — умолчания. */
@Serializable
data class SavedMonitor(val enabled: Boolean, val layout: OutputLayout = OutputLayout.HALL, val dim: Int = app.deixis.core.OVERLAY_DIM_DEFAULT)

@Serializable
data class MonitorSettings(val monitors: Map<String, SavedMonitor> = emptyMap())

/** Настройки мониторов в JSON-файле. Испорченный файл даёт умолчания, а не ошибку на старте показа. */
class MonitorSettingsStore(private val file: File) {
    fun load(): MonitorSettings = try {
        if (file.isFile) DeixisJson.decodeFromString(MonitorSettings.serializer(), file.readText()) else MonitorSettings()
    } catch (e: Exception) {
        MonitorSettings()
    }

    @Synchronized
    fun save(settings: MonitorSettings) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(DeixisJson.encodeToString(MonitorSettings.serializer(), settings))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/**
 * Мониторы с раскладкой и признаком «показывать». Умолчания:
 * - один монитор — на нём зал во весь экран, как раньше;
 * - два и больше — монитор пульта ([primaryId]) свободен, на остальных зал.
 * [singleTakesHall] = false — проектор отключили посреди работы: оставшийся монитор пульта не занимается
 * залом сам (иначе выдернутый кабель накрыл бы пульт оператора видео со звуком).
 */
fun resolveMonitors(screens: List<ScreenInfo>, primaryId: String?, saved: MonitorSettings, singleTakesHall: Boolean = true): List<MonitorOutput> {
    val single = screens.size == 1 && singleTakesHall
    return screens.mapIndexed { i, s ->
        val primary = s.id == primaryId
        val choice = saved.monitors[s.id]
        MonitorOutput(
            id = s.id,
            name = "Монитор ${i + 1}",
            width = s.width,
            height = s.height,
            primary = primary,
            layout = choice?.layout ?: OutputLayout.HALL,
            enabled = choice?.enabled ?: (single || !primary),
            dim = choice?.dim ?: app.deixis.core.OVERLAY_DIM_DEFAULT,
        )
    }
}

/** Выбор оператора поверх сохранённых настроек. */
fun MonitorSettings.with(req: MonitorRequest): MonitorSettings =
    copy(monitors = monitors + (req.id to SavedMonitor(req.enabled, req.layout, (req.dim ?: monitors[req.id]?.dim ?: app.deixis.core.OVERLAY_DIM_DEFAULT).coerceIn(0, 100))))

/** Названия раскладок для меню. */
val OutputLayout.title: String
    get() = when (this) {
        OutputLayout.HALL -> "Зал"
        OutputLayout.STAGE -> "Сцена"
        OutputLayout.LOWER_THIRD -> "Титры"
        OutputLayout.CHROMA -> "Хромакей"
        OutputLayout.OVERLAY -> "Наложение"
    }
