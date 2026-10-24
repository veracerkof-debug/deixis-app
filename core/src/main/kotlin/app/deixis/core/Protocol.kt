package app.deixis.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Версия протокола. Растёт при несовместимых изменениях; новые поля добавляются со значениями по умолчанию. */
const val PROTOCOL_VERSION = 1

/** Тип службы в mDNS, под которым приёмник объявляет себя в сети. */
const val SERVICE_TYPE = "_deixis._tcp.local."

/** Сообщение по управляющему каналу (WebSocket, JSON). */
@Serializable
sealed interface Msg

// Источник → приёмник

/** Первое сообщение уже сопряжённого источника. */
@Serializable @SerialName("hello")
data class Hello(val version: Int, val sender: String, val token: String) : Msg

/** Первое сообщение нового источника: PIN с экрана приёмника. */
@Serializable @SerialName("pair")
data class PairRequest(val version: Int, val sender: String, val pin: String) : Msg

@Serializable @SerialName("show")
data class Show(
    val content: Content,
    /** Следующий слайд — для вида «Сцена» (суфлёр); файлы для него могут быть ещё не переданы. */
    val next: Content? = null,
) : Msg

/**
 * Обратный отсчёт без смены зала: пауза, ±1 мин, «заново». Если этот же отсчёт сейчас в зале, он
 * меняется и там; иначе только в «Сцене». null — убрать отсчёт из «Сцены».
 */
@Serializable @SerialName("timer")
data class SetTimer(val timer: CountdownContent?) : Msg

/** Слой поверх показа целиком: что прислано, то и видно; пустой — слой убран. */
@Serializable @SerialName("overlays")
data class SetOverlays(val overlays: Overlays) : Msg

@Serializable @SerialName("mode")
data class SetMode(val mode: Mode) : Msg

@Serializable @SerialName("video")
data class VideoCommand(
    val action: VideoAction,
    val positionMs: Long? = null,
    val volume: Int? = null,
    val loop: Boolean? = null,
) : Msg

@Serializable @SerialName("ping")
data class Ping(val t: Long) : Msg

// Приёмник → источник

@Serializable @SerialName("welcome")
data class Welcome(
    val version: Int,
    val receiver: ReceiverInfo,
    val state: ReceiverState,
    /** Ключ страницы для зрителей: http://приёмник/view?k=ключ. */
    val viewKey: String? = null,
) : Msg

@Serializable @SerialName("paired")
data class Paired(val token: String) : Msg

@Serializable @SerialName("denied")
data class Denied(val reason: DenyReason) : Msg

@Serializable @SerialName("status")
data class Status(val state: ReceiverState) : Msg

@Serializable @SerialName("pong")
data class Pong(val t: Long) : Msg

@Serializable @SerialName("error")
data class ErrorMsg(val code: String, val message: String) : Msg

@Serializable
enum class DenyReason { WRONG_PIN, TOO_MANY_ATTEMPTS, UNKNOWN_TOKEN, INCOMPATIBLE_VERSION }

/** Что показано на экране. */
@Serializable
sealed interface Content

@Serializable @SerialName("text")
data class TextContent(val text: String, val ref: String? = null, val style: TextStyle = TextStyle()) : Content

@Serializable @SerialName("image")
data class ImageContent(val file: FileRef) : Content

@Serializable @SerialName("video")
data class VideoContent(
    val file: FileRef,
    val loop: Boolean = false,
    val startMs: Long = 0,
    /** Как кадр ложится на экран; по умолчанию вписан целиком, без обрезки и растяжения. */
    val fit: VideoFit = VideoFit.FIT,
    /** Чем заполнены поля вокруг вписанного кадра. */
    val backdrop: VideoBackdrop = VideoBackdrop.BLACK,
    /** Цвет полей для [VideoBackdrop.THEME] (ARGB) — фон темы. */
    val backdropColor: Long = 0xFF000000,
) : Content

/** Как видео ложится на экран. */
@Serializable
enum class VideoFit {
    /** Вписать: кадр целиком, поля по бокам или сверху и снизу. */
    FIT,
    /** Заполнить: экран целиком, лишнее по краям обрезается. */
    FILL,
    /** Растянуть: экран целиком, пропорции не сохраняются. */
    STRETCH,
    /** Исходный размер: пиксель в пиксель по центру. */
    ORIGINAL,
}

/** Поля вокруг вписанного кадра. */
@Serializable
enum class VideoBackdrop { BLACK, BLUR, THEME }

@Serializable @SerialName("pdf")
data class PdfContent(val file: FileRef, val page: Int) : Content

/** Файл определяется содержимым: одинаковый файл не передаётся дважды. */
@Serializable
data class FileRef(val sha256: String, val name: String, val size: Long, val mime: String)

/** Цвета в ARGB. Размер текста — доля высоты экрана в процентах. */
@Serializable
data class TextStyle(
    val background: Long = 0xFF000000,
    val color: Long = 0xFFFFFFFF,
    /** Предел размера текста: доля меньшей стороны экрана. Обычно текст упирается в поля раньше. */
    val sizePercent: Float = 20f,
    val shadow: Boolean = true,
    val outline: Boolean = false,
    /** Всё заглавными буквами. */
    val caps: Boolean = false,
    val align: HAlign = HAlign.CENTER,
    val valign: VAlign = VAlign.MIDDLE,
    /** Поля оформления в процентах (слева и справа — от ширины, сверху и снизу — от высоты); не меньше безопасных. */
    val margins: Margins? = null,
    val lineHeight: Float = SLIDE_LINE_HEIGHT,
    val outlineColor: Long = 0xE6000000,
    /** Толщина обводки, доля кегля. */
    val outlineWidth: Float = 0.06f,
    val shadowColor: Long = 0xB4000000,
    /** Плашка под текстом (ARGB); null — без плашки. */
    val box: Long? = null,
    /** Картинка фона; приёмник должен её иметь, как и любой файл показа. */
    val backgroundImage: FileRef? = null,
    /**
     * Видеофон (как анимированный фон Holyrics): играет по кругу без звука и не начинается заново,
     * пока у следующих слайдов тот же фон; текст меняется поверх. Сильнее картинки фона.
     */
    val backgroundVideo: FileRef? = null,
    /** Название оформления для пульта, например тема Holyrics. */
    val name: String? = null,
)

@Serializable
enum class HAlign { LEFT, CENTER, RIGHT }

@Serializable
enum class VAlign { TOP, MIDDLE, BOTTOM }

@Serializable
data class Margins(val left: Float, val top: Float, val right: Float, val bottom: Float)

@Serializable
enum class Mode { NORMAL, BLACK, LOGO, CLEAR }

@Serializable
enum class VideoAction { PLAY, PAUSE, SEEK, VOLUME, LOOP }

@Serializable
data class ReceiverInfo(
    val id: String,
    val name: String,
    val platform: String,
    val appVersion: String,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    /** Приёмник берёт фото и видео из Holyrics на своём компьютере. */
    val holyrics: Boolean = false,
    /** Приёмник сам переводит презентации в PDF (PPTX и PPT — всегда, ODP и KEY — с LibreOffice). */
    val convert: Boolean = false,
    /** Приёмник переводит документы и таблицы в PDF (LibreOffice на компьютере). */
    val documents: Boolean = false,
)

@Serializable
data class VideoState(
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val volume: Int = 100,
    val loop: Boolean = false,
)

/** Состояние приёмника. Источник правды — приёмник: после переподключения пульт берёт его отсюда. */
@Serializable
data class ReceiverState(
    val content: Content? = null,
    val mode: Mode = Mode.NORMAL,
    val video: VideoState? = null,
    val error: String? = null,
    /** Сколько зрителей смотрит показ в браузере. */
    val viewers: Int = 0,
    /** Следующий слайд, если пульт его прислал: для вида «Сцена». */
    val next: Content? = null,
    /** Идущий обратный отсчёт — в «Сцене» он виден и тогда, когда в зале уже другое. */
    val timer: CountdownContent? = null,
    /** Поверх показа: логотип, плашка, бегущая строка, часы. Смена слайда его не трогает. */
    val overlays: Overlays = Overlays(),
)

/** Состояние файла на приёмнике: сколько байт уже есть и проверен ли он целиком. */
@Serializable
data class FileStatus(val sha256: String, val bytes: Long, val complete: Boolean)

val DeixisJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun Msg.encode(): String = DeixisJson.encodeToString(Msg.serializer(), this)

/** Неизвестное сообщение от более новой версии возвращает null: его пропускают, а не рвут связь. */
fun decodeMsg(text: String): Msg? = try {
    DeixisJson.decodeFromString(Msg.serializer(), text)
} catch (e: SerializationException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

/** Все файлы, нужные слайду: сам файл и картинка фона из оформления. */
val Content.files: List<FileRef>
    get() = listOfNotNull(file, style?.backgroundImage, style?.backgroundVideo)

/** Оформление текста слайда, если оно у слайда есть. */
val Content.style: TextStyle?
    get() = when (this) {
        is TextContent -> style
        is ScriptureContent -> style
        is CountdownContent -> style
        else -> null
    }

val Content.file: FileRef?
    get() = when (this) {
        is ImageContent -> file
        is VideoContent -> file
        is PdfContent -> file
        is TextContent -> null
        is ScriptureContent -> null
        is ScreenContent -> null
        is CountdownContent -> null
    }
