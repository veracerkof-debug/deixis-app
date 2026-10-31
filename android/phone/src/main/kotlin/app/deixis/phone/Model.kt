package app.deixis.phone

import app.deixis.core.Overlays
import app.deixis.core.LogoStyle
import android.content.Context
import app.deixis.core.DeixisJson
import app.deixis.core.FileRef
import app.deixis.core.ScriptureContent
import app.deixis.core.ScriptureOptions
import app.deixis.core.TextStyle
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Вид элемента библиотеки: от него зависят значок, раздел и то, как элемент показывается. */
@Serializable
enum class Kind(val title: String, val plural: String) {
    PRESENTATION("Презентация", "Презентации"),
    VIDEO("Видео", "Видео"),
    PHOTO("Фото", "Фото"),
    FILE("Файл", "Файлы"),
    SONG("Песня", "Песни"),
    TEXT("Текст", "Тексты"),
    BIBLE("Библия", "Библия"),
    COUNTDOWN("Отсчёт", "Отсчёты"),
}

/**
 * Обратный отсчёт в библиотеке: длительность или «до времени», подпись, формат, доли секунды, что после нуля,
 * шрифт, размер, цвет, положение. Фон и тень — от темы оформления, как у текста.
 */
@Serializable
data class CountdownSpec(
    val durationMs: Long = 5 * 60_000L,
    /** «18:30» — до этого времени (ближайшего: сегодня или завтра); null — длительность. */
    val until: String? = null,
    val label: String? = "До начала",
    val labelPosition: app.deixis.core.LabelPosition = app.deixis.core.LabelPosition.ABOVE,
    val format: app.deixis.core.CountdownFormat = app.deixis.core.CountdownFormat.AUTO,
    val decimals: Int = 0,
    val afterZero: app.deixis.core.AfterZero = app.deixis.core.AfterZero.HOLD,
    val font: String = "sans",
    val sizePercent: Float = 34f,
    /** Цвет цифр; null — цвет темы. */
    val color: Long? = null,
    val valign: app.deixis.core.VAlign = app.deixis.core.VAlign.MIDDLE,
) {
    /** Сколько отсчитывать, начиная с [now]. */
    fun startMs(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Long {
        val t = until?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() } ?: return durationMs
        var at = now.toLocalDate().atTime(t)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return java.time.Duration.between(now, at).toMillis()
    }

    val title: String
        get() = until?.let { "До $it" } ?: app.deixis.core.formatCountdown(durationMs, app.deixis.core.CountdownFormat.AUTO)
}

/** Место Писания, сохранённое в библиотеке: книга, глава, стихи и переводы. */
@Serializable
data class BiblePick(
    val book: Int,
    val chapter: Int,
    val from: Int,
    val to: Int,
    val modules: List<String>,
    val options: ScriptureOptions = ScriptureOptions(),
)

/**
 * Элемент библиотеки. Файлы не копируются: хранится ссылка на файл телефона с постоянным доступом.
 * Песни и тексты хранят слайды сами.
 */
@Serializable
data class LibItem(
    val id: String,
    val kind: Kind,
    val title: String,
    val slides: List<String> = emptyList(),
    val labels: List<String?> = emptyList(),
    val uri: String? = null,
    val mime: String? = null,
    val size: Long = 0,
    /** Страницы PDF (у презентаций и документов — после перевода на компьютере). */
    val pages: Int = 0,
    val durationMs: Long = 0,
    val bible: BiblePick? = null,
    /** Оформление, пришедшее с элементом (например тема Holyrics). */
    val style: TextStyle? = null,
    val created: Long = System.currentTimeMillis(),
    /** Видео: как ложится на экран и чем заполнены поля. */
    val videoFit: app.deixis.core.VideoFit = app.deixis.core.VideoFit.FIT,
    val videoBackdrop: app.deixis.core.VideoBackdrop = app.deixis.core.VideoBackdrop.BLACK,
    /** Обратный отсчёт. */
    val countdown: CountdownSpec? = null,
    /** Из демо-набора первого запуска: убирается одной кнопкой в настройках. */
    val demo: Boolean = false,
)

/** Сетлист: план показа на дату — пара, лекция, служение. */
@Serializable
data class Setlist(val id: String, val title: String, val date: String, val items: List<String> = emptyList()) {
    val day: LocalDate get() = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
    val past: Boolean get() = day.isBefore(LocalDate.now())
    val dateText: String get() = day.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")))
}

/** Настройки приложения из шторки «Настройки». */
@Serializable
data class AppSettings(
    val theme: String = "light",
    val alwaysPreview: Boolean = false,
    /**
     * Тёмная тема пульта, пока идёт показ. По умолчанию выключено: тема не меняется сама при подключении.
     * Прежнее поле `darkLive` (включено по умолчанию) больше не читается — у всех тема остаётся своей.
     */
    val darkDuringShow: Boolean = false,
    val clicker: Boolean = true,
    val volumeKeys: Boolean = false,
    val caps: Boolean = false,
    val church: Boolean = true,
    /** Вид заставки «Логотип»: ΔΕΙΞΙΣ, большая Δ, δεῖξις или Deixis. */
    val logo: LogoStyle = LogoStyle.WORDMARK,
    /** Своя картинка на «Логотипе» вместо знака (логотип церкви, школы); файл — в localFiles. */
    val logoImage: app.deixis.core.FileRef? = null,
    /** Панель тем на главном: спрятана в значок, узкая полоса или на всё место под окнами. */
    val themePanel: ThemePanel = ThemePanel.STRIP,
    /** Режим работы: своя база Deixis или пульт Holyrics (его база на компьютере). Выбирается в «Устройствах». */
    val workMode: WorkMode = WorkMode.DEIXIS,
)

/**
 * DEIXIS — своя библиотека, сетлисты и экраны Deixis. HOLYRICS — пульт Holyrics: главный экран, библиотека
 * и сетлисты — его плейлист, база и сохранённые плейлисты на компьютере; показ идёт в самом Holyrics.
 */
enum class WorkMode { DEIXIS, HOLYRICS }

enum class ThemePanel { HIDDEN, STRIP, FULL }

/** Перевод презентации или документа на компьютере: PDF лежит на том приёмнике, который его сделал. */
@Serializable
data class Conversion(val output: String, val pdf: FileRef, val pages: Int)

@Serializable
data class StoreData(
    val items: List<LibItem> = emptyList(),
    val setlists: List<Setlist> = emptyList(),
    val active: String? = null,
    val recent: List<String> = emptyList(),
    /** Оформление: «all» — всегда, «set:<id>», «item:<id>», «slide:<id>:<n>». */
    val styles: Map<String, TextStyle> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    /** Хэш файла элемента (считается один раз) и переводы презентаций. */
    val hashes: Map<String, FileRef> = emptyMap(),
    val conversions: Map<String, Conversion> = emptyMap(),
    /** Свои темы оформления (встроенные — в коде). */
    val themes: List<Theme> = emptyList(),
    /** Файлы телефона по хэшу: фоновые картинки тем. */
    val localFiles: Map<String, String> = emptyMap(),
    /** Демо-материалы добавлены (один раз, при первом запуске). */
    val demoSeeded: Boolean = false,
    /** Слой поверх показа: что включено сейчас. Логотип — файл телефона из [localFiles]. */
    val overlays: Overlays = Overlays(),
    /** Заготовки слоя, чтобы не набирать заново: имена для плашки и тексты бегущей строки. */
    val overlayDrafts: OverlayDrafts = OverlayDrafts(),
)

/** Последние плашки и строки: чаще всего ведущий показывает одни и те же имена. */
@Serializable
data class OverlayDrafts(
    val lowerThirds: List<app.deixis.core.LowerThird> = emptyList(),
    val tickers: List<String> = emptyList(),
    /** Логотип, выбранный однажды: включается одним касанием. */
    val logo: app.deixis.core.LogoOverlay? = null,
)

/** Тема оформления слайдов с текстом: фон, цвет, размер, тень, плашка, положение. */
@Serializable
data class Theme(val id: String, val name: String, val style: TextStyle, val builtIn: Boolean = false)

/** Хранилище библиотеки и сетлистов: один JSON в памяти приложения, запись после каждого изменения. */
class Store(private val context: Context) {
    private val file = File(context.filesDir, "library.json")

    /** Основной файл не прочитался, и библиотека восстановлена из резервной копии. */
    var recovered = false
        private set

    var data: StoreData = load()
        private set

    /**
     * Файл не читается (испорчен или записан непонятной версией) — его нельзя молча заменить пустой библиотекой:
     * он откладывается рядом, а библиотека берётся из самой свежей резервной копии.
     */
    private fun load(): StoreData {
        if (!file.isFile) return firstRun()
        val saved = runCatching { DeixisJson.decodeFromString(StoreData.serializer(), file.readText()) }.getOrNull()
        if (saved != null) return saved
        runCatching { file.copyTo(File(file.parentFile, "library.broken-${System.currentTimeMillis()}.json"), overwrite = true) }
        val backup = runCatching { Backups(context).newest() }.getOrNull()
        recovered = backup != null
        return backup ?: firstRun()
    }

    /** Заменить всю библиотеку (восстановление из копии). */
    fun replace(d: StoreData): StoreData = update { d }

    /** Первый запуск: пустой сетлист на сегодня, чтобы на главном было куда добавлять. */
    private fun firstRun(): StoreData {
        val set = Setlist(newId(), "Сегодня", LocalDate.now().toString())
        return StoreData(setlists = listOf(set), active = set.id)
    }

    fun update(change: (StoreData) -> StoreData): StoreData {
        data = change(data)
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(DeixisJson.encodeToString(StoreData.serializer(), data))
            tmp.renameTo(file)
        }
        return data
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

/** Один слайд элемента для сетки слайдов, предпросмотра и показа. */
sealed interface Slide {
    data class Text(val text: String, val label: String? = null) : Slide
    data class Scripture(val content: ScriptureContent) : Slide
    data class Page(val index: Int) : Slide
    data object Picture : Slide
    data object Movie : Slide
    /** Презентация или документ ещё не переведены: их переводит компьютер при первом показе. */
    data object Pending : Slide
    data class Screen(val width: Int, val height: Int) : Slide
    data class Countdown(val spec: CountdownSpec) : Slide
}

/** Что показывается или ждёт в предпросмотре: элемент библиотеки или разовый (текст, Holyrics, экран). */
data class Source(
    val itemId: String?,
    val title: String,
    val kind: Kind?,
    val slides: List<Slide>,
    val style: TextStyle? = null,
    /** Место Писания, если показ идёт из вкладки «Библия»: по нему она отмечает стих в зале. */
    val bible: BiblePick? = null,
)

/** [startMs] — с какого места начать видео (из предпросмотра видео). */
data class Cue(val source: Source, val index: Int, val startMs: Long = 0) {
    val slide: Slide? get() = source.slides.getOrNull(index)
    fun same(other: Cue?) = other != null && source.itemId != null && source.itemId == other.source.itemId && index == other.index
}
