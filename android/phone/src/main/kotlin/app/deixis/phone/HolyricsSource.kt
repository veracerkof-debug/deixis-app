package app.deixis.phone

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import app.deixis.core.Discovery
import app.deixis.core.HolyricsDiscovery
import app.deixis.core.HolyricsAnnouncement
import app.deixis.core.HolyricsBible
import app.deixis.core.HolyricsBibleVersion
import app.deixis.core.HolyricsBackground
import app.deixis.core.HolyricsClient
import app.deixis.core.HolyricsFavorite
import app.deixis.core.HolyricsPermissions
import app.deixis.core.HolyricsFile
import app.deixis.core.HolyricsLink
import app.deixis.core.HolyricsSavedPlaylist
import app.deixis.core.HolyricsKey
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
        // Вкладка «Библия» в режиме пульта Holyrics показывает стихи в Holyrics, «Чёрный» и «Очистить» — его F10 и F9.
        controller.holyricsRemote = object : Controller.HolyricsRemote {
            override fun showBible(p: BiblePick) { this@HolyricsSource.showBible(p) }
            override fun toggleKey(key: HolyricsKey) { this@HolyricsSource.toggleKey(key) }
        }
        val host = prefs.getString("host", null)
        val token = prefs.getString("token", null)
        if (host != null && token != null) connect(HolyricsLink(host, prefs.getInt("port", HolyricsClient.DEFAULT_PORT), token), quiet = true)
    }

    fun connect(link: HolyricsLink, quiet: Boolean = false) = scope.launch {
        status.value = HolyricsStatus.Connecting
        client?.close()
        val c = HolyricsClient(link.host, link.port, link.token)
        try {
            val v = withContext(Dispatchers.IO) { c.hello() }
            client = c
            val clean = link.copy(token = link.token.trim())
            prefs.edit().putString("host", clean.host).putInt("port", clean.port).putString("token", clean.token).apply()
            status.value = HolyricsStatus.On(clean, v)
            askRights(c, quiet)
            refresh()
        } catch (e: Exception) {
            c.close()
            status.value = HolyricsStatus.Failed(e.message ?: "Нет связи с Holyrics")
            if (!quiet) controller.messages.emit("Holyrics: ${e.message ?: "нет связи"}")
        }
    }

    /** Права токена, которых не хватает пульту; пусто — всё разрешено. */
    val missing = MutableStateFlow<HolyricsPermissions?>(null)

    /**
     * Holyrics даёт токену права по каждому действию. Чего не хватает — просим одним разом: Holyrics (2.25+)
     * показывает на компьютере уведомление «разрешить». Без этих прав подключение остаётся, просто часть кнопок молчит.
     */
    private suspend fun askRights(c: HolyricsClient, quiet: Boolean) {
        val p = withContext(Dispatchers.IO) { runCatching { c.checkPermissions(HolyricsClient.REMOTE) }.getOrNull() } ?: return
        missing.value = p.takeIf { !it.ok }
        if (p.ok || quiet) return
        controller.messages.emit(
            if (p.request == "pending") "Holyrics просит подтвердить права для Deixis: нажмите «Разрешить» в уведомлении на компьютере"
            else "У токена Holyrics не хватает прав (${p.missing.size}). Добавьте их в Holyrics: Файл → Настройки → API Server → Управление правами",
        )
    }

    /** Ещё раз спросить права — после того как их дали в Holyrics. */
    fun recheckRights() = io { c -> askRights(c, quiet = false); refresh() }

    fun disconnect() {
        setRemote(false)
        client?.close()
        client = null
        prefs.edit().clear().apply()
        missing.value = null
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
        val local = Discovery.localAddress() as? java.net.Inet4Address
        if (local == null) { controller.messages.emit("Телефон не в сети Wi-Fi: Holyrics ищется в той же сети"); return@launch }
        searching.value = true
        try {
            val hosts = HolyricsDiscovery.scan(HolyricsDiscovery.neighbours(local))
            found.value = hosts.map { HolyricsFound(it, controller.screenName(it)) }
        } finally {
            searching.value = false
        }
    }

    /** Ссылка для второго телефона (его камера откроет её в Deixis). */
    fun link(): HolyricsLink? = (status.value as? HolyricsStatus.On)?.link

    /** Перечитывает библиотеку Holyrics. */
    fun refresh() = io { c ->
        // Каждый список — отдельно: если на что-то у токена нет права, остальное всё равно приходит.
        runCatching { c.songPlaylist() }.onSuccess { playlist.value = it }
        runCatching { c.songs() }.onSuccess { l -> songs.value = l.sortedBy { it.title.lowercase() } }
        runCatching { c.texts() }.onSuccess { l -> texts.value = l.sortedBy { it.folder.lowercase() + "/" + it.title.lowercase() } }
        runCatching { c.themes() }.onSuccess { themes.value = it }
        runCatching { c.bibleVersions() }.onSuccess { bibleVersions.value = it }
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

    /** Фото или видео из базы Holyrics — в самом Holyrics (режим пульта Holyrics). */
    fun showMediaInHolyrics(kind: HolyricsMedia, file: HolyricsFile) = io { c -> if (kind == HolyricsMedia.VIDEO) c.playVideo(file.name) else c.showImage(file.name) }

    /** Режим работы «Пульт Holyrics» или «Deixis» (выбор хранится в настройках приложения). */
    fun useAsRemote(on: Boolean) = controller.setSettings { it.copy(workMode = if (on) WorkMode.HOLYRICS else WorkMode.DEIXIS) }

    /** Показ в самом Holyrics. */
    fun showInHolyrics(song: HolyricsSong? = null, text: HolyricsText? = null) = io { c ->
        song?.let { c.showSong(it.id) }
        text?.let { c.showText(it.id) }
        mixInto(c)
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
            var tick = 0
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
                        // Клавиши экрана меняют и в самом Holyrics: сверка раз в пару секунд.
                        if (tick++ % KEYS_EVERY == 0) keys.value = HolyricsKey.entries.filter { k -> runCatching { c.screenKey(k) }.getOrDefault(false) }.toSet()
                    }
                }
                delay(500)
            }
        }
    }

    // ——— Режим «Пульт Holyrics»: плейлист, сохранённые плейлисты, F8/F9/F10 ———

    /** Включённые клавиши экрана Holyrics (F8 — обои, F9 — пустой, F10 — чёрный). */
    val keys = MutableStateFlow<Set<HolyricsKey>>(emptySet())
    val saved = MutableStateFlow<List<HolyricsSavedPlaylist>>(emptyList())

    /** Слайды песни для раскрытой строки плейлиста: берутся по одной песне и запоминаются. */
    private val songSlides = mutableMapOf<String, HolyricsSong>()
    suspend fun song(id: String): HolyricsSong? = songSlides[id] ?: client?.let { c ->
        withContext(Dispatchers.IO) { runCatching { c.song(id) }.getOrNull() }?.also { songSlides[id] = it }
    }

    /** Песня (и сразу с нужного слайда) в самом Holyrics — с выбранной темой, если она выбрана. */
    fun showSongAt(id: String, index: Int) = io { c -> c.showSong(id, index); mixInto(c) }

    // ——— Данные Holyrics, которые смешиваются с темами ———

    /** Темы и фоны Holyrics (лента тем на главном) и их миниатюры. */
    val backgrounds = MutableStateFlow<List<HolyricsBackground>>(emptyList())
    val thumbs = MutableStateFlow<Map<String, Bitmap>>(emptyMap())

    /**
     * Тема или фон Holyrics, который держится для всех показов с пульта: песня, текст, быстрый текст,
     * отсчёт, автопрезентация идут с ним. null — у каждого своя тема, как задано в Holyrics.
     */
    val mix = MutableStateFlow(prefs.getString("mix", null))

    val announcements = MutableStateFlow<List<HolyricsAnnouncement>>(emptyList())
    val automatic = MutableStateFlow<List<String>>(emptyList())
    val favorites = MutableStateFlow<List<HolyricsFavorite>>(emptyList())
    val audios = MutableStateFlow<List<HolyricsFile>>(emptyList())
    val documents = MutableStateFlow<List<HolyricsFile>>(emptyList())

    /** Темы, фоны, объявления, автопрезентации, избранное, аудио и файлы Holyrics — для режима пульта. */
    fun loadExtras() = io { c ->
        val bg = runCatching { c.backgrounds() }.getOrDefault(emptyList())
        backgrounds.value = bg
        announcements.value = runCatching { c.announcements() }.getOrDefault(emptyList()).filter { !it.archived }
        automatic.value = runCatching { c.automaticPresentations() }.getOrDefault(emptyList())
        favorites.value = runCatching { c.favorites() }.getOrDefault(emptyList())
        audios.value = runCatching { c.files(HolyricsMedia.AUDIO, thumbnails = false) }.getOrDefault(emptyList())
        documents.value = runCatching { c.files(HolyricsMedia.FILE, thumbnails = false) }.getOrDefault(emptyList())
        // Миниатюры — пачками, чтобы лента наполнялась сразу, а не после всех.
        for ((type, list) in bg.groupBy { if (it.isTheme) "theme" else "background" }) {
            for (chunk in list.map { it.id }.chunked(THUMB_CHUNK)) {
                val got = runCatching { c.thumbnails(type, chunk) }.getOrDefault(emptyMap())
                    .mapNotNull { (id, bytes) -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { id to it } }
                thumbs.value = thumbs.value + got
            }
        }
    }

    /** Касание темы в ленте: она сразу ложится на то, что идёт, и держится дальше; повторное касание — отпустить. */
    fun pickMix(bg: HolyricsBackground) {
        val next = if (mix.value == bg.id) null else bg.id
        mix.value = next
        prefs.edit().putString("mix", next).apply()
        if (next != null) io { c -> c.setBackground(next) }
    }

    /** Выбранная тема — тому, что только что пошло в Holyrics (Holyrics открывает показ со своей темой). */
    private suspend fun mixInto(c: HolyricsClient) {
        val id = mix.value ?: return
        delay(MIX_DELAY_MS)
        runCatching { c.setBackground(id) }
    }

    fun quickText(text: String) = io { c -> c.quickText(text, mix.value) }
    fun countdown(time: String, exact: Boolean, before: String, after: String) = io { c -> c.countdown(time, exact, before, after, mix.value) }
    fun showAnnouncement(id: String?, seconds: Int) = io { c -> c.showAnnouncement(id, seconds); mixInto(c) }
    fun playAutomatic(file: String) = io { c -> c.playAutomaticPresentation(file, mix.value) }
    fun runFavorite(f: HolyricsFavorite) = io { c -> c.runFavorite(f.id) }
    fun playAudio(f: HolyricsFile) = io { c -> c.playAudio(f.name) }
    fun openFile(f: HolyricsFile) = io { c -> c.executeFile(f.name); controller.messages.emit("«${f.name}» открыт в Holyrics") }
    fun alert(text: String, show: Boolean) = io { c -> c.alert(text, show) }
    fun stageCountdown(minutes: Int, text: String) = io { c -> c.stageCountdown(minutes, 0, text) }
    fun stopStageCountdown() = io { c -> c.stopStageCountdown() }

    fun addToPlaylist(song: HolyricsSong) = io { c ->
        c.addToPlaylist(song.id)
        playlist.value = runCatching { c.songPlaylist() }.getOrDefault(playlist.value)
        controller.messages.emit("«${song.title}» — в плейлисте Holyrics")
    }

    fun removeFromPlaylist(index: Int) = io { c ->
        c.removeFromPlaylist(index)
        playlist.value = runCatching { c.songPlaylist() }.getOrDefault(playlist.value)
    }

    fun loadSaved() = io { c -> saved.value = c.savedPlaylists() }

    /** Сохранённый плейлист в Holyrics: заменить открытый или дописать в конец. */
    fun loadPlaylist(name: String, merge: Boolean) = io { c ->
        c.loadSavedPlaylist(name, merge)
        playlist.value = runCatching { c.songPlaylist() }.getOrDefault(playlist.value)
        controller.messages.emit(if (merge) "«$name» добавлен в плейлист Holyrics" else "В Holyrics открыт плейлист «$name»")
    }

    /** F8/F9/F10: повторное нажатие выключает, как в Holyrics. */
    fun toggleKey(key: HolyricsKey) = io { c ->
        val on = key !in keys.value
        c.setScreenKey(key, on)
        keys.value = if (on) keys.value + key else keys.value - key
    }

    // ——— Библия: модули Deixis ↔ переводы Holyrics ———

    /** Переводы Библии в Holyrics. */
    val bibleVersions = MutableStateFlow<List<HolyricsBibleVersion>>(emptyList())
    /** Свой выбор «модуль Deixis → перевод Holyrics» поверх подбора; меняется в «Переводы в Holyrics». */
    val bibleMap = MutableStateFlow(readBibleMap())
    private var syncedTabs: List<String>? = null

    private fun readBibleMap(): Map<String, String> =
        prefs.all.filterKeys { it.startsWith(BIBLE_KEY) }.mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(BIBLE_KEY) to it } }.toMap()

    fun loadBibleVersions() = io { c -> bibleVersions.value = c.bibleVersions() }

    /** Перевод Holyrics для модуля Deixis: свой выбор, иначе подобранный по сокращению, названию, языку. */
    fun versionFor(file: String): HolyricsBibleVersion? {
        val all = bibleVersions.value
        bibleMap.value[file]?.let { id -> return all.firstOrNull { it.id == id } }
        val m = controller.bibleModules.value.firstOrNull { it.fileName == file } ?: return null
        return HolyricsBible.match(m.title, m.abbreviation, m.language, all)
    }

    /** Свой выбор перевода Holyrics для модуля; null — снова подбирать самому. */
    fun mapBible(file: String, versionId: String?) {
        prefs.edit().apply { if (versionId == null) remove(BIBLE_KEY + file) else putString(BIBLE_KEY + file, versionId) }.apply()
        bibleMap.value = readBibleMap()
        syncedTabs = null
    }

    /**
     * Стихи из «Библии» Deixis — в Holyrics: тем же переводом (модуль → перевод Holyrics), а если выбрано два
     * перевода — они же ставятся во вкладки Библии Holyrics и показываются рядом.
     */
    fun showBible(p: BiblePick) = io { c ->
        if (bibleVersions.value.isEmpty()) bibleVersions.value = runCatching { c.bibleVersions() }.getOrDefault(emptyList())
        val ids = HolyricsBible.verseIds(p.book, p.chapter, p.from, p.to)
        if (ids.isEmpty()) return@io controller.messages.emit("Этой книги нет в Библии Holyrics")
        val tabs = p.modules.mapNotNull { versionFor(it)?.id }.distinct()
        if (tabs.isNotEmpty() && tabs != syncedTabs) {
            runCatching { c.setBibleTabs(tabs) }.onSuccess { syncedTabs = tabs }
        }
        c.showVerses(ids, tabs.firstOrNull())
        mixInto(c)
    }

    /** Переводы из вкладок Библии Holyrics — в «Библию» Deixis (модули, сопоставленные с ними). */
    fun takeBibleTabs() = io { c ->
        if (bibleVersions.value.isEmpty()) bibleVersions.value = c.bibleVersions()
        val tabs = c.bibleTabs().filter { it.isNotBlank() }
        val files = tabs.mapNotNull { v -> controller.bibleModules.value.firstOrNull { versionFor(it.fileName)?.id == v }?.fileName }
        if (files.isEmpty()) return@io controller.messages.emit("Для переводов Holyrics (${tabs.joinToString()}) в Deixis нет модулей: добавьте их в «Переводы Библии»")
        withContext(Dispatchers.Main) { controller.desk.choose(files) }
        syncedTabs = tabs
        controller.messages.emit("Переводы как в Holyrics: ${tabs.joinToString()}")
    }

    /** Переводы, выбранные в Deixis, — во вкладки Библии Holyrics. */
    fun pushBibleTabs(files: List<String>) = io { c ->
        if (bibleVersions.value.isEmpty()) bibleVersions.value = c.bibleVersions()
        val tabs = files.mapNotNull { versionFor(it)?.id }.distinct()
        if (tabs.isEmpty()) return@io controller.messages.emit("Для этих модулей в Holyrics нет переводов")
        c.setBibleTabs(tabs)
        syncedTabs = tabs
        controller.messages.emit("В Holyrics: ${tabs.joinToString()}")
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
        /** Клавиши F8–F10 опрашиваются через столько циклов (по полсекунды). */
        const val KEYS_EVERY = 4
        const val THUMB_CHUNK = 12
        const val MIX_DELAY_MS = 250L
        const val BIBLE_KEY = "bible:"
    }
}
