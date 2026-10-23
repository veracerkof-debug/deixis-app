package app.deixis.desktop

import app.deixis.core.DeixisJson
import app.deixis.core.MonitorOutput
import app.deixis.core.MonitorRequest
import app.deixis.core.OutputLayout
import kotlinx.serialization.Serializable
import java.io.File

/** Монитор, как его видит система: постоянный идентификатор и положение на рабочем столе. */
data class ScreenInfo(val id: String, val x: Int, val y: Int, val width: Int, val height: Int)

/** Выбор оператора для монитора. Хранится только то, что оператор менял сам; остальное — умолчания. */
@Serializable
data class SavedMonitor(val enabled: Boolean, val layout: OutputLayout = OutputLayout.HALL)

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
 */
fun resolveMonitors(screens: List<ScreenInfo>, primaryId: String?, saved: MonitorSettings): List<MonitorOutput> {
    val single = screens.size == 1
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
        )
    }
}

/** Выбор оператора поверх сохранённых настроек. */
fun MonitorSettings.with(req: MonitorRequest): MonitorSettings =
    copy(monitors = monitors + (req.id to SavedMonitor(req.enabled, req.layout)))

/** Названия раскладок для меню. */
val OutputLayout.title: String
    get() = when (this) {
        OutputLayout.HALL -> "Зал"
        OutputLayout.STAGE -> "Сцена"
        OutputLayout.LOWER_THIRD -> "Титры"
        OutputLayout.CHROMA -> "Хромакей"
    }
