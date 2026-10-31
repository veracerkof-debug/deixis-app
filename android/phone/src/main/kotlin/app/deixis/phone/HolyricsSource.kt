package app.deixis.phone

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import app.deixis.core.HolyricsClient
import app.deixis.core.HolyricsFile
import app.deixis.core.HolyricsLink
import app.deixis.core.HolyricsMedia
import app.deixis.core.HolyricsMediaRequest
import app.deixis.core.HolyricsPresentation
import app.deixis.core.HolyricsSong
import app.deixis.core.HolyricsText
import app.deixis.core.HolyricsTheme
import app.deixis.core.TextStyle
import app.deixis.core.backgroundImageName
import app.deixis.core.toTextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Состояние связи с Holyrics на компьютере. */
sealed interface HolyricsStatus {
    data object Off : HolyricsStatus
    data object Connecting : HolyricsStatus
    data class On(val link: HolyricsLink, val version: String) : HolyricsStatus
    data class Failed(val reason: String) : HolyricsStatus
}

/** Что сейчас идёт в Holyrics, для пульта: номер слайда и превью. */
/** Holyrics в сети: адрес и имя компьютера, если на нём же стоит Deixis для компьютера. */
data class HolyricsFound(val host: String, val name: String?)

data class HolyricsLive(val p: HolyricsPresentation, val previews: Map<Int, Bitmap>)

/**
 * Holyrics как источник и как экран. Песни, тексты и темы берутся через его API Server и показываются
 * на экранах Deixis в теме Holyrics; фото и видео — через Deixis на том же компьютере. Режим пульта
 * управляет показом в самом Holyrics.
 */
class HolyricsSource(private val app: Application, private val controller: Controller) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("holyrics", Context.MODE_PRIVATE)
    private var client: HolyricsClient? = null

    val status = MutableStateFlow<HolyricsStatus>(HolyricsStatus.Off)
    val playlist = MutableStateFlow<List<HolyricsSong>>(emptyList())
    val songs = MutableStateFlow<List<HolyricsSong>>(emptyList())
    val texts = MutableStateFlow<List<HolyricsText>>(emptyList())
    val themes = MutableStateFlow<List<HolyricsTheme>>(emptyList())
    val images = MutableStateFlow<List<HolyricsFile>>(emptyList())
    val videos = MutableStateFlow<List<HolyricsFile>>(emptyList())

    /** Тема для показа на экранах Deixis; null — тема самой песни или текста. */
    val themeId = MutableStateFlow(prefs.getString("theme", null))

    /** Пульт Holyrics: «Назад» и «Дальше» листают показ в Holyrics. */
    val remote = MutableStateFlow(false)
    val live = MutableStateFlow<HolyricsLive?>(null)
    private var poll: Job? = null

    init {
        val host = prefs.getString("host", null)
        val token = prefs.getString("token", null)
        if (host != null && token != null) connect(HolyricsLink(host, prefs.getInt("port", HolyricsClient.DEFAULT_PORT), token), quiet = true)
    }

    fun connect(link: HolyricsLink, quiet: Boolean = false) = scope.launch {
        status.value = HolyricsStatus.Connecting
        client?.close()
        val c = HolyricsClient(link.host, link.port, link.token)
        try {
            val v = withContext(Dispatchers.IO) { c.version() }
            client = c
            prefs.edit().putString("host", link.host).putInt("port", link.port).putString("token", link.token).apply()
            status.value = HolyricsStatus.On(link, v.version)
            refresh()
        } catch (e: Exception) {
            c.close()
            status.value = HolyricsStatus.Failed(e.message ?: "Нет связи с Holyrics")
            if (!quiet) controller.messages.emit("Holyrics: ${e.message ?: "нет связи"}")
        }
    }

    fun disconnect() {
        setRemote(false)
        client?.close()
        client = null
        prefs.edit().clear().apply()
        status.value = HolyricsStatus.Off
    }

    /** Ход переноса в библиотеку Deixis: «Песни: 120 из 640»; null — не идёт. */
    val importing = MutableStateFlow<String?>(null)

    /**
     * «Перенести в Deixis»: песни (со слайдами в порядке исполнения и подписями частей), тексты, темы и
     * плейлист Holyrics становятся своими и остаются без Holyrics. Holyrics отдаёт списки без слайдов,
     * поэтому каждая песня и текст берутся по одной; не взялся — пропускается, остальное переносится.
     */
    fun importAll() {
        if (importing.value != null) return
        io { c ->
            importing.value = "Подготовка…"
            try {
                val songList = c.songs()
                val songs = songList.mapIndexedNotNull { i, s ->
                    importing.value = "Песни: ${i + 1} из ${songList.size}"
                    val full = runCatching { c.song(s.id) }.getOrNull() ?: return@mapIndexedNotNull null
                    val slides = full.orderedSlides()
                    LibItem(HolyricsImport.songId(s.id), Kind.SONG, full.title.ifBlank { s.title }, slides.map { it.text }, slides.map { it.description?.takeIf { d -> d.isNotBlank() } })
                }
                val textList = c.texts()
                val texts = textList.mapIndexedNotNull { i, t ->
                    importing.value = "Тексты: ${i + 1} из ${textList.size}"
                    val full = runCatching { c.text(t.id) }.getOrNull() ?: return@mapIndexedNotNull null
                    LibItem(HolyricsImport.textId(t.id), Kind.TEXT, full.title.ifBlank { t.title }, full.slides.map { it.text })
                }
                importing.value = "Темы и плейлист…"
                val themes = runCatching { c.themes() }.getOrDefault(emptyList()).map { t ->
                    val name = t.name.ifBlank { "Holyrics ${t.id}" }
                    Theme(HolyricsImport.themeId(t.id), name, t.toTextStyle().copy(name = name))
                }
                val playlist = runCatching { c.songPlaylist() }.getOrNull()?.map { HolyricsImport.songId(it.id) }
                withContext(Dispatchers.Main) { controller.importHolyrics(HolyricsHaul(songs + texts, themes, playlist)) }
                controller.messages.emit(
                    "Из Holyrics в Deixis: песен ${songs.size}, текстов ${texts.size}, тем ${themes.size}" +
                        if (playlist != null) ", плейлист — сетлист «Плейлист Holyrics»" else "",
                )
            } finally {
                importing.value = null
            }
        }
    }

    /** Найденные в сети Holyrics; null — ещё не искали. */
    val found = MutableStateFlow<List<HolyricsFound>?>(null)
    val searching = MutableStateFlow(false)

    /** «Найти»: опрос своей сети Wi-Fi на порту API Server Holyrics — только по нажатию. */
    fun find() = scope.launch {
        val local = app.deixis.core.Discovery.localAddress() as? java.net.Inet4Address
        if (local == null) { controller.messages.emit("Телефон не в сети Wi-Fi: Holyrics ищется в той же сети"); return@launch }
        searching.value = true
        try {
            val hosts = app.deixis.core.HolyricsDiscovery.scan(app.deixis.core.HolyricsDiscovery.neighbours(local))
            found.value = hosts.map { HolyricsFound(it, controller.screenName(it)) }
        } finally {
            searching.value = false
        }
    }

    /** Ссылка для второго телефона (его камера откроет её в Deixis). */
    fun link(): HolyricsLink? = (status.value as? HolyricsStatus.On)?.link

    /** Перечитывает библиотеку Holyrics. */
    fun refresh() = io { c ->
        playlist.value = c.songPlaylist()
        songs.value = c.songs().sortedBy { it.title.lowercase() }
        texts.value = c.texts().sortedBy { it.folder.lowercase() + "/" + it.title.lowercase() }
        themes.value = c.themes()
        images.value = runCatching { c.files(HolyricsMedia.IMAGE) }.getOrDefault(emptyList())
        videos.value = runCatching { c.files(HolyricsMedia.VIDEO) }.getOrDefault(emptyList())
    }

    fun selectTheme(id: String?) {
        themeId.value = id
        prefs.edit().putString("theme", id).apply()
    }

    /** Песня на экран Deixis: слайды в порядке исполнения, тема Holyrics. */
    fun showSong(id: String) = io { c ->
        val song = c.song(id) ?: return@io controller.messages.emit("Песня не найдена в Holyrics")
        val slides = song.orderedSlides()
        val style = styleFor(song.theme)
        withContext(Dispatchers.Main) { controller.showSlides(song.title, slides.map { it.text }, style, slides.map { it.description }) }
    }

    fun showText(id: String) = io { c ->
        val text = c.text(id) ?: return@io controller.messages.emit("Текст не найден в Holyrics")
        val style = styleFor(text.theme)
        withContext(Dispatchers.Main) { controller.showSlides(text.title, text.slides.map { it.text }, style) }
    }

    /** Фото или видео из Holyrics на экран Deixis; если экран так не умеет — показывает его в самом Holyrics. */
    fun showMedia(kind: HolyricsMedia, file: HolyricsFile) = io { c ->
        val link = link() ?: return@io
        val ref = runCatching { controller.importFromHolyrics(HolyricsMediaRequest(link.port, link.token, kind, file.name)) }
            .onFailure { controller.messages.emit(it.message ?: "Не удалось взять файл из Holyrics") }
            .getOrNull()
        if (ref != null) {
            withContext(Dispatchers.Main) { if (kind == HolyricsMedia.VIDEO) controller.showVideo(ref) else controller.showImage(ref) }
        } else {
            if (kind == HolyricsMedia.VIDEO) c.playVideo(file.name) else c.showImage(file.name)
            controller.messages.emit("Показано в Holyrics. На экран Deixis фото и видео Holyrics попадают через Deixis для компьютера рядом с Holyrics")
        }
    }

    /** Показ в самом Holyrics. */
    fun showInHolyrics(song: HolyricsSong? = null, text: HolyricsText? = null) = io { c ->
        song?.let { c.showSong(it.id) }
        text?.let { c.showText(it.id) }
    }

    /**
     * Оформление для экрана Deixis: выбранная тема, иначе тема элемента. Картинка фона темы
     * берётся через Deixis на компьютере с Holyrics; без него остаётся цвет темы.
     */
    private suspend fun styleFor(itemTheme: String?): TextStyle? {
        val theme = themes.value.firstOrNull { it.id == (themeId.value ?: itemTheme) } ?: return null
        val link = link()
        val bg = theme.backgroundImageName?.let { name ->
            if (link == null) null
            else runCatching { controller.importFromHolyrics(HolyricsMediaRequest(link.port, link.token, HolyricsMedia.IMAGE, name)) }.getOrNull()
        }
        return theme.toTextStyle(bg)
    }

    /**
     * Тема Holyrics в зал из панели тем на главном: сначала цвет и шрифт сразу, потом — с картинкой фона,
     * если её удалось взять через Deixis на компьютере с Holyrics.
     */
    fun applyTheme(theme: HolyricsTheme) {
        controller.applyTheme(theme.toTextStyle().copy(name = theme.name))
        val name = theme.backgroundImageName ?: return
        val link = link() ?: return
        scope.launch(Dispatchers.IO) {
            val bg = runCatching { controller.importFromHolyrics(HolyricsMediaRequest(link.port, link.token, HolyricsMedia.IMAGE, name)) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) { controller.applyTheme(theme.toTextStyle(bg).copy(name = theme.name)) }
        }
    }

    fun setRemote(on: Boolean) {
        remote.value = on
        poll?.cancel()
        poll = null
        live.value = null
        if (!on) return
        // API Holyrics не сообщает об изменениях сам: опрос пару раз в секунду, превью — только при смене показа.
        poll = scope.launch(Dispatchers.IO) {
            var previews = emptyMap<Int, Bitmap>()
            var shownId: String? = null
            while (isActive) {
                val c = client
                if (c != null) {
                    runCatching {
                        val cheap = c.current()
                        if (cheap != null && cheap.id != shownId) {
                            previews = c.current(PREVIEW)?.slides.orEmpty().mapNotNull { s -> s.preview?.let { s.number to decode(it) } }
                                .mapNotNull { (n, b) -> b?.let { n to it } }.toMap()
                            shownId = cheap.id
                        }
                        live.value = cheap?.let { HolyricsLive(it, previews) }
                    }
                }
                delay(500)
            }
        }
    }

    fun next() = io { it.next() }
    fun previous() = io { it.previous() }
    fun goTo(index: Int) = io { it.goTo(index) }

    private fun decode(b64: String): Bitmap? = runCatching {
        val bytes = Base64.decode(b64.substringAfter("base64,"), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun io(block: suspend (HolyricsClient) -> Unit) = scope.launch {
        val c = client ?: return@launch controller.messages.emit("Сначала подключите Holyrics")
        try {
            withContext(Dispatchers.IO) { block(c) }
        } catch (e: Exception) {
            controller.messages.emit("Holyrics: ${e.message ?: "ошибка"}")
        }
    }

    private companion object {
        const val PREVIEW = "320x180"
    }
}
