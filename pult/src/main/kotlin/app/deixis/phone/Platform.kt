package app.deixis.phone

import androidx.compose.ui.graphics.ImageBitmap
import app.deixis.core.SqlSource
import java.io.File
import java.io.InputStream

/**
 * Что пульт берёт у системы. Пульт один — на телефоне и на компьютере (код в pult/); здесь всё, что у них
 * разное: где лежат данные, как открыть выбранный файл, как перевести презентацию, что умеет устройство.
 * Телефон — AndroidPlatform (android/phone), компьютер — DesktopPlatform (desktop).
 *
 * Файлы оператора — по адресу-строке: content://… на телефоне, file:///… на компьютере. Сами файлы не копируются.
 */
interface Platform {
    /** Папка данных пульта (библиотека, профили, копии, модули Библии). */
    val filesDir: File
    /** Папка временных файлов. */
    val cacheDir: File
    /** Как экраны видят это устройство: «Redmi 9T», «Ноутбук Алексея». */
    val deviceName: String
    /** Телефон или компьютер: какие разделы и кнопки пульта есть. */
    val features: Features

    /** Маленькое хранилище настроек ключ–значение ([name] — его имя). */
    fun prefs(name: String): Prefs

    // ——— Файлы оператора ———

    /** Открыть файл по адресу; null — файла нет или доступ к нему пропал. */
    fun open(uri: String): InputStream?
    /** Имя, размер (0 — неизвестен) и тип файла. */
    fun meta(uri: String): FileMeta
    /** Сохранить доступ к файлу после перезапуска (на телефоне — разрешение системы). */
    fun keep(uri: String)
    /** Адрес файла в папке пульта. */
    fun fileUri(f: File): String
    /** Сколько страниц в PDF; 0 — не PDF или не открылся. */
    fun pdfPages(uri: String): Int
    /** Длительность видео; 0 — неизвестна. */
    fun videoDurationMs(uri: String): Long
    /** Фото HEIC/HEIF в JPEG [out] для экранов, которые его не открывают; false — не вышло. */
    fun heicToJpeg(uri: String, out: File): Boolean

    /** Перевести презентацию или документ Word в PDF здесь же; false — так это устройство не умеет. */
    fun canConvertHere(ext: String): Boolean
    /** Перевод в PDF [out]; возвращает предупреждение для оператора (подменённые шрифты и т. п.) или null. */
    fun convertToPdf(src: File, ext: String, out: File): String?

    /** Модуль Библии (SQLite) только для чтения. */
    fun sql(file: File): SqlSource

    // ——— Демо-набор ———

    /** Имена файлов демо-набора (фото, видео, PDF, презентация). */
    fun demoFiles(): List<String>
    fun openDemo(name: String): InputStream
    /** Размер файла демо-набора; -1 — неизвестен (тогда копируется всегда). */
    fun demoSize(name: String): Long

    // ——— Папка копий вне приложения ———

    fun folderFiles(folder: String): List<Pair<String, String>>
    fun folderWrite(folder: String, name: String, bytes: ByteArray)
    fun folderDelete(uri: String)
    fun keepFolder(folder: String)

    // ——— Картинки ———

    fun decodeImage(bytes: ByteArray): ImageBitmap?
    /** Миниатюра фото, видео или страницы PDF ([index]) шириной не больше [width]. */
    fun thumbnail(uri: String, kind: ThumbKind, index: Int, width: Int): ImageBitmap?
    // ——— Связь ———

    /** Держать связь с экранами, пока пульт в фоне (на телефоне — служба с уведомлением). */
    fun keepAlive()
    /** Поиск экранов в сети (на телефоне — разрешение Wi-Fi на мультикаст). */
    fun multicast(on: Boolean)
    /** Остановить трансляцию экрана этого устройства, если она идёт. */
    fun stopScreenShare()
    /** Адрес этого устройства в сети для ссылок зрителям; null — сети нет. */
    val lanAddress: String?
    /** Пульт сейчас на виду (а не свёрнут): от этого зависит, как часто спрашивать Holyrics. */
    val visible: Boolean
    /** Вывод на телевизоры DLNA; null — устройство так не умеет. */
    val dlna: DlnaOut?
}

/** Имя, размер и тип файла оператора. */
data class FileMeta(val name: String, val size: Long, val mime: String?)

/** Что есть у пульта на этом устройстве. */
data class Features(
    /** Компьютер: пульт в окне рядом с залом, а не в кармане. */
    val desktop: Boolean,
    /** Транслировать экран этого устройства (телефон). */
    val screenShare: Boolean,
    /** Стать экраном самому («Стать экраном» на телефоне). */
    val becomeScreen: Boolean,
    /** Кабель HDMI и Миракаст. */
    val cable: Boolean,
    /** Трансляция экрана со звуком приложений (Android 10+). */
    val shareSound: Boolean = false,
)

/** Хранилище ключ–значение: на телефоне SharedPreferences, на компьютере файл. */
interface Prefs {
    fun getString(key: String, def: String?): String?
    fun getInt(key: String, def: Int): Int
    fun getLong(key: String, def: Long): Long
    fun getBoolean(key: String, def: Boolean): Boolean
    fun getStringSet(key: String, def: Set<String>?): Set<String>?
    fun contains(key: String): Boolean
    /** Все строки хранилища (ключ → значение). */
    fun all(): Map<String, Any?>
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String?)
        fun putInt(key: String, value: Int)
        fun putLong(key: String, value: Long)
        fun putBoolean(key: String, value: Boolean)
        fun putStringSet(key: String, value: Set<String>)
        fun remove(key: String)
        fun clear()
    }
}

/** Телевизоры DLNA (вывод картинкой без приложения Deixis на экране). */
interface DlnaOut {
    val found: kotlinx.coroutines.flow.StateFlow<List<app.deixis.core.DlnaRenderer>>
    val state: kotlinx.coroutines.flow.StateFlow<app.deixis.core.ReceiverState?>
    var logo: app.deixis.core.LogoStyle
    var logoPicture: String?
    fun search(scope: kotlinx.coroutines.CoroutineScope): kotlinx.coroutines.Job
    suspend fun open(r: app.deixis.core.DlnaRenderer, scope: kotlinx.coroutines.CoroutineScope)
    fun close()
    fun register(source: app.deixis.core.UploadSource): app.deixis.core.FileRef
    suspend fun show(content: app.deixis.core.Content)
    suspend fun toggle(m: app.deixis.core.Mode)
    suspend fun video(action: app.deixis.core.VideoAction, deltaMs: Long)
}

/** Устройство без DLNA: телевизоров не ищет и ничего не показывает. */
object NoDlna : DlnaOut {
    override val found = kotlinx.coroutines.flow.MutableStateFlow<List<app.deixis.core.DlnaRenderer>>(emptyList())
    override val state = kotlinx.coroutines.flow.MutableStateFlow<app.deixis.core.ReceiverState?>(null)
    override var logo = app.deixis.core.LogoStyle.WORDMARK
    override var logoPicture: String? = null
    override fun search(scope: kotlinx.coroutines.CoroutineScope): kotlinx.coroutines.Job = kotlinx.coroutines.Job()
    override suspend fun open(r: app.deixis.core.DlnaRenderer, scope: kotlinx.coroutines.CoroutineScope) = error("DLNA")
    override fun close() = Unit
    override fun register(source: app.deixis.core.UploadSource): app.deixis.core.FileRef = error("DLNA")
    override suspend fun show(content: app.deixis.core.Content) = Unit
    override suspend fun toggle(m: app.deixis.core.Mode) = Unit
    override suspend fun video(action: app.deixis.core.VideoAction, deltaMs: Long) = Unit
}

/**
 * Пульт на этом устройстве для экранов интерфейса: платформа и службы приложения (Holyrics, обновления),
 * плюс то, что зовёт систему (настройки трансляции, «Стать экраном»).
 */
interface PultHost {
    val platform: Platform
    val holyrics: HolyricsSource
    /** Версия программы для строки внизу настроек. */
    val version: String

    /** Обновления этой программы. */
    val updates: app.deixis.core.UpdateHost
    /** «Что нового» один раз после обновления. */
    fun takeWhatsNew(): List<app.deixis.core.ChangelogEntry>
    /** Обновления идут через магазин (Google Play): тогда пульт их не предлагает. */
    val updatesFromStore: Boolean
    /** Система разрешает этой программе ставить обновления. */
    val canInstallUpdates: Boolean
    /** Версия, которую уже пробовали поставить сами (в режиме «Автоматически» — один раз). */
    var autoInstallTried: String?

    /** «Сообщить о проблеме», «Есть идея» в настройках. */
    @androidx.compose.runtime.Composable fun FeedbackSection()

    /** Системные настройки трансляции (Миракаст). */
    fun openCastSettings()
    /** Стать экраном самому (телефон показывает зал). */
    fun becomeScreen()
}

val LocalHost = androidx.compose.runtime.staticCompositionLocalOf<PultHost> { error("PultHost") }

/** Выбор файлов оператором: на телефоне — системное окно документов, на компьютере — окно выбора файлов. */
class FilePicker(private val open: (Array<String>) -> Unit) {
    /** [mimes] — какие файлы предлагать: типы MIME, например только картинки или только видео. */
    fun launch(mimes: Array<String>) = open(mimes)
}

/** Выбор папки (копии вне приложения). */
class FolderPicker(private val open: () -> Unit) {
    fun launch(@Suppress("UNUSED_PARAMETER") start: String?) = open()
}

/*
 * Каждая платформа (android/phone и desktop) определяет ещё в пакете app.deixis.phone:
 *   @Composable fun rememberFilePicker(multiple: Boolean = false, onPick: (List<String>) -> Unit): FilePicker
 *   @Composable fun rememberFolderPicker(onPick: (String) -> Unit): FolderPicker
 *   @Composable fun BackHandlerOn(enabled: Boolean, onBack: () -> Unit)
 *   @Composable fun rememberActs(c: Controller, ui: Ui): Acts
 *   @Composable fun VideoPreview(…)  — видео в «Предпросмотре»
 */
/*
 * Слайды в превью рисует тот же движок, что зал этого устройства (на телефоне — экран Android,
 * на компьютере — окно зала), поэтому превью совпадает с залом. Каждая платформа определяет:
 *   val Didot: FontFamily                                          — шрифт знака ΔΕΙΞΙΣ
 *   @Composable fun LogoFull(style: LogoStyle, color: Color = Color.White)
 *   @Composable fun TextSlideCanvas(text: String, ref: String?, style: SlideStyle, clear: Boolean = false,
 *       overscan: Boolean = false, drawBackground: Boolean = true)
 *   @Composable fun ScriptureSlideCanvas(c: ScriptureContent, clear: Boolean = false, overscan: Boolean = false,
 *       drawBackground: Boolean = true)
 */
