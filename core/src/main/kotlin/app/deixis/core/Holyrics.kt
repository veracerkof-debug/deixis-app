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
import kotlinx.serialization.json.booleanOrNull
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
    /** Песня, если показ — песня: по нему пульт отмечает её в плейлисте. */
    @SerialName("song_id") val songId: String? = null,
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

/** Сохранённый плейлист Holyrics: имя и что в нём. */
@Serializable
data class HolyricsSavedPlaylist(val id: String = "", val name: String = "", val items: List<Item> = emptyList()) {
    @Serializable
    data class Item(val id: String = "", val type: String = "", val name: String = "")
}

@Serializable
data class HolyricsTokenInfo(val version: String = "", val permissions: String = "")

/** Тема или фон Holyrics: тип `theme`, `my_image`, `my_video`, `image`, `video`. */
@Serializable
data class HolyricsBackground(val id: String = "", val type: String = "", val name: String = "", val tags: List<String> = emptyList()) {
    val isTheme get() = type == "theme"
    val isVideo get() = type.endsWith("video")
}

@Serializable
data class HolyricsAnnouncement(val id: String = "", val name: String = "", val text: String = "", val archived: Boolean = false)

@Serializable
data class HolyricsFavorite(val id: String = "", val name: String = "")

@Serializable
internal data class HolyricsNamed(val name: String = "")

@Serializable
internal data class HolyricsThumb(val id: String = "", val type: String = "", val image: String? = null)

/** Клавиши экрана Holyrics: F8 — обои, F9 — пустой экран, F10 — чёрный экран. */
enum class HolyricsKey { F8, F9, F10 }

/** Вкладка файлов Holyrics. */
enum class HolyricsMedia(val listAction: String, val getAction: String) {
    IMAGE("GetImages", "GetImage"),
    VIDEO("GetVideos", "GetVideo"),
    AUDIO("GetAudios", "GetAudio"),
    FILE("GetFiles", "GetFile"),
}

/**
 * Ошибка Holyrics. [missing] — действия, на которые у токена нет права (Holyrics отвечает «unauthorized»);
 * [request] — `pending`, если Holyrics показал оператору уведомление с просьбой дать права, `denied` — отказано.
 */
class HolyricsException(
    message: String,
    val key: String? = null,
    val missing: List<String> = emptyList(),
    val request: String? = null,
) : IOException(message) {
    val unauthorized: Boolean get() = missing.isNotEmpty() || key.equals("unauthorized", true) || message.orEmpty().contains("unauthorized", true)
}

/** Права токена на действия пульта: чего не хватает и попросил ли Holyrics оператора их дать. */
data class HolyricsPermissions(val missing: List<String>, val request: String?) {
    val ok: Boolean get() = missing.isEmpty()
}

/**
 * Подключение к Holyrics. По умолчанию — вход по хешу: токен не уходит в сеть открытым текстом,
 * каждый запрос подписывается своим номером. [useHash] = false — токен в адресе, как в простом примере API.
 */
class HolyricsClient(
    val host: String,
    val port: Int = DEFAULT_PORT,
    token: String,
    private val useHash: Boolean = true,
    private val http: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 4_000
            requestTimeoutMillis = 15_000
        }
    },
) : AutoCloseable {
    /** Токен без пробелов и переносов по краям: при вставке из Holyrics они часто прилипают. */
    private val token = token.trim()
    private val lock = Mutex()
    private var sid: String? = null
    private var nonce: String? = null
    private var rid = 0L

    private val base get() = "http://$host:$port/api"

    /** Выполняет действие API и возвращает поле `data` (или null). Ошибку Holyrics превращает в [HolyricsException]. */
    suspend fun call(action: String, body: JsonObject = JsonObject(emptyMap())): JsonElement? = lock.withLock {
        val data = body.toString()
        if (!useHash) return@withLock parse(http.post("$base/$action?token=${token.encodeURLParameter()}") { json(data) }.bodyAsText(), action)
        repeat(2) { attempt ->
            if (sid == null) login()
            rid++
            val dtoken = sha256Hex("$nonce:$rid:$token:$data")
            val text = http.post("$base/$action?sid=$sid&rid=$rid&dtoken=$dtoken") { json(data) }.bodyAsText()
            try {
                return@withLock parse(text, action)
            } catch (e: HolyricsException) {
                // Сессия могла истечь (перезапуск Holyrics): входим заново один раз.
                if (attempt == 0 && !e.unauthorized && isSessionError(e.message)) { sid = null; return@repeat }
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

    private fun parse(text: String, action: String? = null): JsonElement? {
        val obj = runCatching { HolyricsJson.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw HolyricsException("Holyrics ответил непонятно")
        if (obj["status"]?.jsonPrimitive?.contentOrNull != "ok") throw errorOf(obj["error"], action)
        return obj["data"]?.takeIf { it !is JsonNull }
    }

    /** Ошибка Holyrics бывает строкой («invalid token») или объектом (код, ключ, недостающие права). */
    private fun errorOf(err: JsonElement?, action: String?): HolyricsException {
        val o = err as? JsonObject
        val text = (err as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            ?: o?.get("message")?.jsonPrimitive?.contentOrNull ?: o?.get("key")?.jsonPrimitive?.contentOrNull
        val key = o?.get("key")?.jsonPrimitive?.contentOrNull ?: text
        val listed = o?.get("unauthorized_actions")?.jsonPrimitive?.contentOrNull
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val request = o?.get("request_status")?.jsonPrimitive?.contentOrNull
        val unauthorized = listed.isNotEmpty() || key.equals("unauthorized", true) || text.orEmpty().contains("unauthorized", true)
        if (!unauthorized) return HolyricsException(text ?: "Holyrics вернул ошибку", key)
        val missing = listed.ifEmpty { listOfNotNull(action) }
        val what = if (missing.isEmpty()) "это действие" else missing.joinToString(", ")
        return HolyricsException(
            "токену не разрешено: $what. В Holyrics: Файл → Настройки → API Server → Управление правами",
            "unauthorized", missing, request,
        )
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
            is JsonElement -> put(k, v)
            is List<*> -> put(k, kotlinx.serialization.json.JsonArray(v.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) }))
            else -> put(k, v.toString())
        }
    }

    suspend fun version(): HolyricsVersion = get("GetVersion", HolyricsVersion.serializer()) ?: HolyricsVersion()

    /** Версия Holyrics и права токена (v2.25+). */
    suspend fun tokenInfo(): HolyricsTokenInfo = get("GetTokenInfo", HolyricsTokenInfo.serializer()) ?: HolyricsTokenInfo()

    /**
     * Проверка входа: токен верный, если Holyrics его принял. Версия — из сведений о токене или «GetVersion»;
     * если ни на то, ни на другое у токена нет права, вход всё равно состоялся — версия пустая.
     */
    suspend fun hello(): String {
        for (ask in listOf<suspend () -> String>({ tokenInfo().version }, { version().version })) {
            try { return ask() } catch (e: HolyricsException) { if (!e.unauthorized) throw e }
        }
        return ""
    }

    /**
     * Есть ли у токена права на [actions]. Чего не хватает, Holyrics (v2.25+) сам предлагает оператору разрешить
     * уведомлением на компьютере (`request` = `pending`).
     */
    suspend fun checkPermissions(actions: Collection<String>): HolyricsPermissions = try {
        call("CheckPermissions", args("actions" to actions.joinToString(",")))
        HolyricsPermissions(emptyList(), null)
    } catch (e: HolyricsException) {
        if (!e.unauthorized) throw e
        // Если Holyrics не назвал, каких именно прав нет, — вслепую не перечисляем всё: пусть оператор проверит сам.
        HolyricsPermissions(e.missing.filter { it != "CheckPermissions" }.ifEmpty { listOf("CheckPermissions") }, e.request)
    }

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

    // ——— Плейлист и экран Holyrics: всё это доступно без подписки Holyrics Plan ———

    /** Песня в конец плейлиста Holyrics (того, что открыт в Holyrics сейчас). */
    suspend fun addToPlaylist(songId: String) { call("AddLyricsToPlaylist", args("id" to songId)) }

    /** Убрать из плейлиста Holyrics по месту в списке (с нуля). */
    suspend fun removeFromPlaylist(index: Int) { call("RemoveFromLyricsPlaylist", args("index" to index)) }

    /** Сохранённые в Holyrics плейлисты. */
    suspend fun savedPlaylists(): List<HolyricsSavedPlaylist> = get("GetSavedPlaylists", ListSerializer(HolyricsSavedPlaylist.serializer())).orEmpty()

    /** Сохранённый плейлист в Holyrics: заменить открытый или [merge] — дописать в конец. */
    suspend fun loadSavedPlaylist(name: String, merge: Boolean = false) { call("LoadSavedPlaylist", args("name" to name, "merge" to merge)) }

    /** F8 — обои, F9 — пустой экран, F10 — чёрный экран Holyrics: включено ли. */
    suspend fun screenKey(key: HolyricsKey): Boolean = call("Get${key.name}")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull } ?: false

    suspend fun setScreenKey(key: HolyricsKey, on: Boolean) { call("Set${key.name}", args("enable" to on)) }

    suspend fun next() { call("ActionNext") }
    suspend fun previous() { call("ActionPrevious") }
    suspend fun goTo(index: Int) { call("ActionGoToIndex", args("index" to index)) }
    suspend fun closePresentation() { call("CloseCurrentPresentation") }
    suspend fun showSong(id: String, index: Int = 0) { call("ShowSong", args("id" to id, "initial_index" to index)) }
    suspend fun showText(id: String, index: Int = 0) { call("ShowText", args("id" to id, "initial_index" to index)) }
    suspend fun showImage(file: String) { call("ShowImage", args("file" to file)) }
    suspend fun playVideo(file: String) { call("PlayVideo", args("file" to file)) }
    suspend fun playAudio(file: String) { call("PlayAudio", args("file" to file)) }
    /** Файл из вкладки «Файлы» (презентации, документы): Holyrics открывает его сам. */
    suspend fun executeFile(file: String) { call("ExecuteFile", args("file" to file)) }

    /** Темы и фоны Holyrics одним списком: `theme`, `my_image`, `my_video`, `image`, `video`. */
    suspend fun backgrounds(): List<HolyricsBackground> = get("GetBackgrounds", ListSerializer(HolyricsBackground.serializer())).orEmpty()

    /** Тема или фон — тому, что идёт сейчас. Так песня, текст или медиа смешиваются с любой темой. */
    suspend fun setBackground(id: String) { call("SetCurrentBackground", args("id" to id)) }

    /** Миниатюры тем и фонов: id → картинка (PNG/JPEG). */
    suspend fun thumbnails(type: String, ids: List<String>): Map<String, ByteArray> {
        if (ids.isEmpty()) return emptyMap()
        val list = get("GetThumbnail", ListSerializer(HolyricsThumb.serializer()), args("ids" to ids, "type" to type)).orEmpty()
        return list.mapNotNull { t -> t.image?.let { img -> runCatching { java.util.Base64.getMimeDecoder().decode(img.substringAfter("base64,")) }.getOrNull()?.let { t.id to it } } }.toMap()
    }

    /** Быстрый текст на экран; [themeId] — с этой темой, иначе с темой Holyrics по умолчанию. */
    suspend fun quickText(text: String, themeId: String? = null) {
        call("ShowQuickPresentation", args("text" to text, "theme" to themeId?.let { theme(it) }))
    }

    /** Обратный отсчёт на экране: [time] — «MM:SS» на сколько или «HH:MM» до какого времени ([exact]). */
    suspend fun countdown(time: String, exact: Boolean, before: String = "", after: String = "", themeId: String? = null) {
        call("ShowCountdown", args(
            "time" to time, "exact_time" to exact, "text_before" to before.ifBlank { null }, "text_after" to after.ifBlank { null },
            "zero_fill" to true, "theme" to themeId?.let { theme(it) },
        ))
    }

    suspend fun announcements(): List<HolyricsAnnouncement> = get("GetAnnouncements", ListSerializer(HolyricsAnnouncement.serializer())).orEmpty()

    /** Объявление; [id] = null — все по кругу, раз в [seconds] секунд. */
    suspend fun showAnnouncement(id: String?, seconds: Int = 10) {
        call("ShowAnnouncement", args(
            "id" to (id ?: "all"),
            "automatic" to if (id == null) buildJsonObject { put("seconds", seconds); put("repeat", true) } else null,
        ))
    }

    /** Автоматические презентации (файлы .ap). */
    suspend fun automaticPresentations(): List<String> =
        get("GetAPs", ListSerializer(HolyricsNamed.serializer())).orEmpty().map { it.name }.filter { it.isNotBlank() }

    suspend fun playAutomaticPresentation(file: String, themeId: String? = null) {
        call("PlayAP", args("file" to file, "theme" to themeId?.let { theme(it) }))
    }

    /** Кнопки панели «Избранное» Holyrics. */
    suspend fun favorites(): List<HolyricsFavorite> = get("GetFavorites", ListSerializer(HolyricsFavorite.serializer())).orEmpty()
    suspend fun runFavorite(id: String) { call("FavoriteAction", args("id" to id)) }

    /** Тревога — строка поверх всего на экране Holyrics; пустая или [show] = false — убрать. */
    suspend fun alert(text: String, show: Boolean) { call("SetAlert", args("text" to text, "show" to show)) }

    /** Обратный отсчёт на панели для сцены (зал его не видит). */
    suspend fun stageCountdown(minutes: Int, seconds: Int = 0, text: String = "") {
        call("StartCountdownCP", args("minutes" to minutes, "seconds" to seconds, "yellow_starts_at" to 60, "text" to text.ifBlank { null }))
    }
    suspend fun stopStageCountdown() { call("StopCountdownCP") }

    private fun theme(id: String) = buildJsonObject { put("id", id) }

    companion object {
        const val DEFAULT_PORT = 8091

        /** Всё, что делает пульт Holyrics: права на это просятся у Holyrics одним уведомлением. */
        val REMOTE = listOf(
            "GetVersion", "GetSongs", "GetSong", "GetSongPlaylist", "GetTexts", "GetText", "GetThemes",
            "GetImages", "GetVideos", "GetAudios", "GetFiles", "GetCurrentPresentation",
            "ShowSong", "ShowText", "ShowImage", "PlayVideo", "PlayAudio", "ExecuteFile",
            "ActionNext", "ActionPrevious", "ActionGoToIndex", "CloseCurrentPresentation",
            "AddLyricsToPlaylist", "RemoveFromLyricsPlaylist", "GetSavedPlaylists", "LoadSavedPlaylist",
            "GetF8", "GetF9", "GetF10", "SetF8", "SetF9", "SetF10",
            "GetBackgrounds", "GetThumbnail", "SetCurrentBackground", "ShowQuickPresentation", "ShowCountdown",
            "GetAnnouncements", "ShowAnnouncement", "GetAPs", "PlayAP", "GetFavorites", "FavoriteAction",
            "SetAlert", "StartCountdownCP", "StopCountdownCP",
        )
    }

    override fun close() = http.close()
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

/**
 * Цвет Holyrics в ARGB: «F5F5F5» — непрозрачный, «RRGGBBAA» — с прозрачностью (так записаны цвета плашек).
 * Непонятный цвет даёт [fallback].
 */
fun holyricsColor(hex: String?, fallback: Long): Long {
    val h = hex?.trim()?.removePrefix("#") ?: return fallback
    return when (h.length) {
        6 -> h.toLongOrNull(16)?.let { 0xFF000000 or it } ?: fallback
        8 -> h.toLongOrNull(16)?.let { rgba -> ((rgba and 0xFF) shl 24) or (rgba ushr 8) } ?: fallback
        else -> fallback
    }
}

/** Смесь двух цветов ARGB: [t] = 1 — только [a]. */
internal fun mix(a: Long, b: Long, t: Double): Long {
    fun ch(c: Long, s: Int) = (c ushr s) and 0xFF
    fun m(s: Int) = (ch(a, s) * t + ch(b, s) * (1 - t)).toLong().coerceIn(0, 255) shl s
    return 0xFF000000 or m(16) or m(8) or m(0)
}

/** Тип фона темы — картинка из библиотеки Holyrics (её можно взять файлом через Deixis на том же компьютере). */
val HolyricsTheme.backgroundImageName: String?
    get() = background.id?.takeIf { background.type == "image_file" }

/**
 * Тема Holyrics в оформлении слайда Deixis: цвета, поля, выравнивание, обводка, тень, плашка, заглавные.
 * Шрифт темы не переносится: чужие шрифты в приложение не встраиваются, слайд рисуется своим шрифтом.
 * [backgroundImage] — уже переданная на приёмник картинка фона, если она есть.
 */
fun HolyricsTheme.toTextStyle(backgroundImage: FileRef? = null): TextStyle {
    val base = holyricsColor(baseColor, 0xFF000000)
    val bg = if (background.type == "color") mix(holyricsColor(background.id, 0xFF000000), base, background.opacity.coerceIn(0.0, 100.0) / 100) else 0xFF000000
    val m = align.margin
    return TextStyle(
        background = bg,
        color = holyricsColor(font.color, 0xFFFFFFFF),
        // Размер темы — доля высоты слайда; у нас это предел, а меньше текст станет, только если не влезает.
        sizePercent = font.size.toFloat().takeIf { it > 0 }?.coerceIn(3f, 50f) ?: 20f,
        shadow = effect.shadowX != 0.0 || effect.shadowY != 0.0 || effect.glow > 0,
        shadowColor = holyricsColor(effect.shadowColor, 0xB4000000),
        outline = effect.outlineWeight > 0,
        outlineColor = holyricsColor(effect.outlineColor, 0xE6000000),
        outlineWidth = (effect.outlineWeight / 100 * 0.15).toFloat().coerceIn(0.01f, 0.15f),
        caps = settings.uppercase,
        align = when (align.horizontal) { "left" -> HAlign.LEFT; "right" -> HAlign.RIGHT; else -> HAlign.CENTER },
        valign = when (align.vertical) { "top" -> VAlign.TOP; "bottom" -> VAlign.BOTTOM; else -> VAlign.MIDDLE },
        margins = Margins(m.left.toFloat(), m.top.toFloat(), m.right.toFloat(), m.bottom.toFloat()),
        lineHeight = (1.0 + font.lineSpacing).toFloat().coerceIn(0.9f, 2.2f),
        box = if (shapeFill.enabled) holyricsColor(shapeFill.color, 0x80000000) else null,
        backgroundImage = backgroundImage,
        name = name,
    )
}
