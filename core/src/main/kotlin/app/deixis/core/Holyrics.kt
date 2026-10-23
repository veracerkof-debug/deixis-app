package app.deixis.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.security.MessageDigest

/*
 * Связь с Holyrics на компьютере через его официальный API Server
 * (https://github.com/holyrics/API-Server). Код Holyrics не используется: только описанный там протокол.
 * В Holyrics: Файл → Настройки → API Server → создать токен и выдать ему права.
 */

/** Holyrics присылает числа то числом, то строкой, и добавляет поля от версии к версии. */
val HolyricsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class HolyricsSlide(
    val text: String = "",
    @SerialName("slide_description") val description: String? = null,
    @SerialName("background_id") val backgroundId: String? = null,
)

@Serializable
data class HolyricsSong(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val author: String = "",
    val key: String = "",
    val slides: List<HolyricsSlide> = emptyList(),
    /** Порядок слайдов с 1 через запятую, например «1,2,3,2,2»; пусто — по порядку. */
    val order: String = "",
    val theme: String? = null,
    val archived: Boolean = false,
) {
    /** Слайды в порядке исполнения: припев повторяется столько раз, сколько указано в порядке. */
    fun orderedSlides(): List<HolyricsSlide> {
        val idx = order.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..slides.size }
        return if (idx.isEmpty()) slides else idx.map { slides[it - 1] }
    }
}

@Serializable
data class HolyricsText(
    val id: String,
    val title: String = "",
    val folder: String = "",
    val theme: String? = null,
    val slides: List<HolyricsSlide> = emptyList(),
)

@Serializable
data class HolyricsTheme(
    val id: String,
    val name: String = "",
    val background: Background = Background(),
    @SerialName("base_color") val baseColor: String? = null,
    val font: Font = Font(),
    val align: Align = Align(),
    val effect: Effect = Effect(),
    @SerialName("shape_fill") val shapeFill: Shape = Shape(),
    val settings: Settings = Settings(),
) {
    @Serializable
    data class Background(
        /** color, my_video, my_image, video, image, pattern, transparent, image_file, video_file. */
        val type: String = "color",
        /** Для color — цвет в hex, для остальных — ID или имя файла в библиотеке. */
        val id: String? = null,
        val opacity: Double = 100.0,
    )

    @Serializable
    data class Font(
        val name: String = "",
        val bold: Boolean = false,
        val italic: Boolean = false,
        /** Размер в процентах от высоты слайда, 0..50. */
        val size: Double = 10.0,
        val color: String = "FFFFFF",
        @SerialName("line_spacing") val lineSpacing: Double = 0.0,
        @SerialName("char_spacing") val charSpacing: Double = 0.0,
    )

    @Serializable
    data class Align(
        /** left, center, right, justify. */
        val horizontal: String = "center",
        /** top, middle, bottom. */
        val vertical: String = "middle",
        val margin: Margin = Margin(),
    )

    /** Поля в процентах от размера слайда. */
    @Serializable
    data class Margin(val top: Double = 3.0, val right: Double = 3.0, val bottom: Double = 3.0, val left: Double = 3.0)

    @Serializable
    data class Effect(
        @SerialName("outline_color") val outlineColor: String = "404040",
        @SerialName("outline_weight") val outlineWeight: Double = 0.0,
        @SerialName("shadow_color") val shadowColor: String = "404040",
        @SerialName("shadow_x_weight") val shadowX: Double = 0.0,
        @SerialName("shadow_y_weight") val shadowY: Double = 0.0,
        @SerialName("brightness_weight") val glow: Double = 0.0,
    )

    @Serializable
    data class Shape(
        val enabled: Boolean = false,
        val color: String = "000000",
        val margin: Margin = Margin(),
        val corner: Double = 0.0,
    )

    @Serializable
    data class Settings(val uppercase: Boolean = false)
}

/** Файл из вкладок Holyrics «Аудио», «Видео», «Изображения», «Файлы». */
@Serializable
data class HolyricsFile(
    val name: String,
    val isDir: Boolean = false,
    val length: Long? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Миниатюра 80×45 в base64. */
    val thumbnail: String? = null,
)

@Serializable
data class HolyricsPresentation(
    val id: String = "",
    /** song, verse, text, audio, image, announcement, automatic_presentation, quick_presentation. */
    val type: String = "",
    val name: String = "",
    @SerialName("slide_number") val slideNumber: Int = 0,
    @SerialName("total_slides") val totalSlides: Int = 0,
    /** default, wallpaper, blank, black, final_slide. */
    @SerialName("slide_type") val slideType: String = "default",
    val slides: List<Slide>? = null,
) {
    @Serializable
    data class Slide(
        val number: Int = 0,
        val text: String = "",
        @SerialName("theme_id") val themeId: String? = null,
        @SerialName("slide_description") val description: String? = null,
        /** Превью слайда в base64 (до 640×360). */
        val preview: String? = null,
    )
}

@Serializable
data class HolyricsVersion(
    val version: String = "",
    val platform: String = "",
    /** Папка данных Holyrics на компьютере. */
    val baseDir: String? = null,
)

/** Вкладка файлов Holyrics. */
enum class HolyricsMedia(val listAction: String, val getAction: String) {
    IMAGE("GetImages", "GetImage"),
    VIDEO("GetVideos", "GetVideo"),
    AUDIO("GetAudios", "GetAudio"),
    FILE("GetFiles", "GetFile"),
}

class HolyricsException(message: String) : IOException(message)

/**
 * Подключение к Holyrics. По умолчанию — вход по хешу: токен не уходит в сеть открытым текстом,
 * каждый запрос подписывается своим номером. [useHash] = false — токен в адресе, как в простом примере API.
 */
class HolyricsClient(
    val host: String,
    val port: Int = DEFAULT_PORT,
    private val token: String,
    private val useHash: Boolean = true,
    private val http: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 4_000
            requestTimeoutMillis = 15_000
        }
    },
) : AutoCloseable {
    private val lock = Mutex()
    private var sid: String? = null
    private var nonce: String? = null
    private var rid = 0L

    private val base get() = "http://$host:$port/api"

    /** Выполняет действие API и возвращает поле `data` (или null). Ошибку Holyrics превращает в [HolyricsException]. */
    suspend fun call(action: String, body: JsonObject = JsonObject(emptyMap())): JsonElement? = lock.withLock {
        val data = body.toString()
        if (!useHash) return@withLock parse(http.post("$base/$action?token=${token.encodeURLParameter()}") { json(data) }.bodyAsText())
        repeat(2) { attempt ->
            if (sid == null) login()
            rid++
            val dtoken = sha256Hex("$nonce:$rid:$token:$data")
            val text = http.post("$base/$action?sid=$sid&rid=$rid&dtoken=$dtoken") { json(data) }.bodyAsText()
            try {
                return@withLock parse(text)
            } catch (e: HolyricsException) {
                // Сессия могла истечь (перезапуск Holyrics): входим заново один раз.
                if (attempt == 0 && isSessionError(e.message)) { sid = null; return@repeat }
                throw e
            }
        }
        throw HolyricsException("Holyrics не принимает вход")
    }

    private suspend fun login() {
        val first = parse(http.post("$base/Auth") { json("{}") }.bodyAsText())?.jsonObject ?: throw HolyricsException("Holyrics не выдал сеанс")
        val s = first["sid"]?.jsonPrimitive?.contentOrNull ?: throw HolyricsException("Holyrics не выдал сеанс")
        val n = first["nonce"]?.jsonPrimitive?.contentOrNull ?: throw HolyricsException("Holyrics не выдал сеанс")
        val dtoken = sha256Hex("$n:0:$token:auth")
        parse(http.post("$base/Auth?sid=$s&rid=0&dtoken=$dtoken") { json("{}") }.bodyAsText())
        sid = s
        nonce = n
        rid = 0
    }

    private fun io.ktor.client.request.HttpRequestBuilder.json(data: String) {
        contentType(ContentType.Application.Json)
        setBody(data)
    }

    private fun parse(text: String): JsonElement? {
        val obj = runCatching { HolyricsJson.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw HolyricsException("Holyrics ответил непонятно")
        if (obj["status"]?.jsonPrimitive?.contentOrNull != "ok") {
            throw HolyricsException(obj["error"]?.jsonPrimitive?.contentOrNull ?: "Holyrics вернул ошибку")
        }
        return obj["data"]?.takeIf { it !is JsonNull }
    }

    private fun isSessionError(msg: String?) = msg != null && listOf("session", "sid", "token", "nonce", "auth", "expired").any { msg.contains(it, ignoreCase = true) }

    private suspend fun <T> get(action: String, serializer: KSerializer<T>, body: JsonObject = JsonObject(emptyMap())): T? =
        call(action, body)?.let { HolyricsJson.decodeFromJsonElement(serializer, it) }

    private fun args(vararg pairs: Pair<String, Any?>) = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            null -> Unit
            is String -> put(k, v)
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            else -> put(k, v.toString())
        }
    }

    suspend fun version(): HolyricsVersion = get("GetVersion", HolyricsVersion.serializer()) ?: HolyricsVersion()

    /** Список песен без текстов (быстро); текст — через [song]. */
    suspend fun songs(): List<HolyricsSong> =
        get("GetSongs", ListSerializer(HolyricsSong.serializer()), args("fields" to "id,title,artist,author,key,theme,archived")).orEmpty().filter { !it.archived }

    suspend fun song(id: String): HolyricsSong? = get("GetSong", HolyricsSong.serializer(), args("id" to id))

    /** Плейлист песен текущего служения. */
    suspend fun songPlaylist(): List<HolyricsSong> = get("GetSongPlaylist", ListSerializer(HolyricsSong.serializer())).orEmpty()

    suspend fun texts(): List<HolyricsText> =
        get("GetTexts", ListSerializer(HolyricsText.serializer()), args("fields" to "id,title,folder,theme")).orEmpty()

    suspend fun text(id: String): HolyricsText? = get("GetText", HolyricsText.serializer(), args("id" to id))

    suspend fun themes(): List<HolyricsTheme> = get("GetThemes", ListSerializer(HolyricsTheme.serializer())).orEmpty()

    suspend fun files(kind: HolyricsMedia, folder: String? = null, thumbnails: Boolean = true): List<HolyricsFile> =
        get(kind.listAction, ListSerializer(HolyricsFile.serializer()), args("folder" to folder, "include_metadata" to true, "include_thumbnail" to thumbnails)).orEmpty()

    /** Что сейчас идёт в Holyrics; null — ничего. [previewSize] вроде «320x180» добавляет картинки слайдов. */
    suspend fun current(previewSize: String? = null): HolyricsPresentation? =
        get(
            "GetCurrentPresentation", HolyricsPresentation.serializer(),
            args("include_slides" to true, "include_slide_preview" to (previewSize != null), "slide_preview_size" to previewSize),
        )

    suspend fun next() { call("ActionNext") }
    suspend fun previous() { call("ActionPrevious") }
    suspend fun goTo(index: Int) { call("ActionGoToIndex", args("index" to index)) }
    suspend fun closePresentation() { call("CloseCurrentPresentation") }
    suspend fun showSong(id: String, index: Int = 0) { call("ShowSong", args("id" to id, "initial_index" to index)) }
    suspend fun showText(id: String, index: Int = 0) { call("ShowText", args("id" to id, "initial_index" to index)) }
    suspend fun showImage(file: String) { call("ShowImage", args("file" to file)) }
    suspend fun playVideo(file: String) { call("PlayVideo", args("file" to file)) }

    override fun close() = http.close()

    companion object {
        const val DEFAULT_PORT = 8091
    }
}

internal fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * Ссылка для подключения к Holyrics, которую телефон показывает QR-кодом второму телефону:
 * `deixis-holyrics://192.168.1.10:8091?token=…`. Токен в QR — только для своих: им можно управлять Holyrics.
 */
data class HolyricsLink(val host: String, val port: Int, val token: String) {
    fun toUri() = "$SCHEME://$host:$port?token=${token.encodeURLParameter()}"

    companion object {
        const val SCHEME = "deixis-holyrics"

        fun parse(s: String): HolyricsLink? {
            val t = s.trim()
            if (!t.startsWith("$SCHEME://")) return null
            val rest = t.removePrefix("$SCHEME://")
            val hostPort = rest.substringBefore('?')
            val token = rest.substringAfter("token=", "").substringBefore('&').let { java.net.URLDecoder.decode(it, "UTF-8") }
            val host = hostPort.substringBefore(':').ifEmpty { return null }
            val port = hostPort.substringAfter(':', "").toIntOrNull() ?: HolyricsClient.DEFAULT_PORT
            if (token.isEmpty()) return null
            return HolyricsLink(host, port, token)
        }
    }
}
