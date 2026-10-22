package app.deixis.phone

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import app.deixis.core.Connection
import app.deixis.core.Content
import app.deixis.core.DenyReason
import app.deixis.core.Discovery
import app.deixis.core.FileRef
import app.deixis.core.ImageContent
import app.deixis.core.MediaKind
import app.deixis.core.Mode
import app.deixis.core.PairingCode
import app.deixis.core.PairingException
import app.deixis.core.PdfContent
import app.deixis.core.ReceiverServer
import app.deixis.core.ScriptureContent
import app.deixis.core.ScriptureOptions
import app.deixis.core.buildScriptureSlides
import app.deixis.core.parseBibleRef
import app.deixis.core.ReceiverState
import app.deixis.core.SenderClient
import app.deixis.core.TextContent
import app.deixis.core.UploadSource
import app.deixis.core.VideoAction
import app.deixis.core.VideoCommand
import app.deixis.core.VideoContent
import app.deixis.core.mediaKindOf
import app.deixis.core.mimeOf
import app.deixis.core.splitIntoSlides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Экран, на который можно показать. */
data class Target(val id: String, val name: String, val host: String, val port: Int, val kind: Kind) {
    enum class Kind { NETWORK, CABLE, MANUAL }
}

/** Что сейчас показывается с телефона. */
sealed interface Item {
    val title: String
    data class Text(val slides: List<String>, val index: Int) : Item { override val title = "Текст" }
    data class Pdf(val ref: FileRef, val pages: Int, val index: Int) : Item { override val title = ref.name }
    data class Image(val ref: FileRef) : Item { override val title = ref.name }
    data class Video(val ref: FileRef) : Item { override val title = ref.name }
    data class Scripture(val slides: List<ScriptureContent>, val index: Int) : Item {
        override val title = slides[index].ref ?: slides[index].blocks.firstOrNull()?.ref ?: "Писание"
    }
}

/** Логика пульта без экрана: поиск, сопряжение, отправка, управление. */
@OptIn(ExperimentalCoroutinesApi::class)
class Controller(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("tokens", Context.MODE_PRIVATE)
    private val deviceName = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    private val found = MutableStateFlow<List<Target>>(emptyList())
    private val cable = MutableStateFlow<Target?>(null)
    private val manual = MutableStateFlow<List<Target>>(emptyList())

    /** Все экраны: кабель первым, затем найденные в сети и введённые вручную. */
    val targets: StateFlow<List<Target>> = combine(cable, found, manual) { c, f, m -> listOfNotNull(c) + f + m.filter { x -> f.none { it.host == x.host && it.port == x.port } } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val active = MutableStateFlow<Target?>(null)
    private val client = MutableStateFlow<SenderClient?>(null)
    val connection: StateFlow<Connection> = client.flatMapLatest { it?.connection ?: flowOf(Connection.Idle) }
        .stateIn(scope, SharingStarted.Eagerly, Connection.Idle)
    val receiverState: StateFlow<ReceiverState?> = client.flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Ссылка для зрителей по имени в локальной сети, например http://deixis-ab12.local:47820/view?k=… */
    val viewLocalUrl: StateFlow<String?> = client.flatMapLatest { c ->
        c?.viewKey?.map { key -> if (key != null && active.value?.kind != Target.Kind.CABLE) c.viewLocalUrl() else null } ?: flowOf(null)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Ссылка для зрителей в браузере; для кабеля HDMI её нет: тот приёмник виден только этому телефону. */
    val viewUrl: StateFlow<String?> = client.flatMapLatest { c ->
        c?.viewKey?.map { key -> if (key != null && active.value?.kind != Target.Kind.CABLE) c.viewUrl() else null } ?: flowOf(null)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val item = MutableStateFlow<Item?>(null)
    val progress = MutableStateFlow<String?>(null)
    val needPin = MutableStateFlow<Target?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val bible = BibleLibrary(app)
    /** Модули Библии на телефоне; обновляется после импорта и удаления. */
    val bibleModules = MutableStateFlow(runCatching { bible.modules() }.getOrDefault(emptyList()))

    private var browseJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var clientJobs: List<Job> = emptyList()

    fun startDiscovery() {
        if (browseJob != null) return
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("deixis")?.apply { setReferenceCounted(false); acquire() }
        browseJob = scope.launch(Dispatchers.IO) {
            runCatching {
                Discovery.browse().collect { list -> found.value = list.map { Target(it.id, it.name, it.host, it.port, Target.Kind.NETWORK) } }
            }
        }
    }

    fun stopDiscovery() {
        browseJob?.cancel()
        browseJob = null
        runCatching { multicastLock?.release() }
    }

    fun select(t: Target) {
        if (active.value == t && connection.value is Connection.Connected) return
        val token = prefs.getString(key(t), null)
        if (token == null) needPin.value = t else open(t, token)
    }

    fun pair(t: Target, pin: String) = scope.launch {
        val c = SenderClient(t.host, t.port, deviceName, scope)
        try {
            val token = c.pair(pin.trim())
            prefs.edit().putString(key(t), token).apply()
            needPin.value = null
            open(t, token)
        } catch (e: PairingException) {
            messages.emit(
                when (e.reason) {
                    DenyReason.WRONG_PIN -> "Неверный PIN"
                    DenyReason.TOO_MANY_ATTEMPTS -> "Слишком много попыток. На экране новый PIN"
                    DenyReason.INCOMPATIBLE_VERSION -> "Версии Deixis не совпадают. Обновите приложения"
                    DenyReason.UNKNOWN_TOKEN -> "Приёмник не узнал телефон"
                },
            )
        } catch (e: Exception) {
            messages.emit("Нет связи с «${t.name}»")
        } finally {
            c.close()
        }
    }

    /** Адрес вида 192.168.1.20[:порт] или ссылка из QR-кода приёмника. */
    fun addManual(input: String) {
        val code = PairingCode.parse(input.trim())
        if (code != null) {
            val t = Target(code.id.ifEmpty { "${code.host}:${code.port}" }, code.name.ifEmpty { code.host }, code.host, code.port, Target.Kind.MANUAL)
            manual.value = manual.value.filter { it.id != t.id } + t
            pair(t, code.pin)
            return
        }
        val host = input.trim().substringBefore(':')
        if (host.isEmpty()) return
        val port = input.substringAfter(':', "").toIntOrNull() ?: ReceiverServer.DEFAULT_PORT
        val t = Target("$host:$port", host, host, port, Target.Kind.MANUAL)
        manual.value = manual.value.filter { it.id != t.id } + t
        select(t)
    }

    /** Кабель HDMI: приёмник в этом же телефоне, сопряжение не нужно. */
    fun setCable(t: Target?, token: String?) {
        if (t != null && token != null) {
            prefs.edit().putString(key(t), token).apply()
            cable.value = t
            if (active.value == null) open(t, token)
        } else {
            if (active.value?.kind == Target.Kind.CABLE) disconnect()
            cable.value = null
        }
    }

    fun cableToken(): String? = prefs.getString("cable", null)

    private fun open(t: Target, token: String) {
        disconnect()
        val c = SenderClient(t.host, t.port, deviceName, scope)
        c.connect(token)
        client.value = c
        active.value = t
        clientJobs = listOf(
            scope.launch { c.errors.collect { messages.emit(it.message) } },
            scope.launch {
                c.connection.collect {
                    if (it is Connection.Denied && it.reason == DenyReason.UNKNOWN_TOKEN) {
                        // Приёмник забыл телефон (сброс, переустановка): спрашиваем PIN заново.
                        prefs.edit().remove(key(t)).apply()
                        needPin.value = t
                    }
                }
            },
            scope.launch {
                var last: String? = null
                c.state.collect { s -> s?.error?.takeIf { it != last }?.let { messages.emit(it) }; last = s?.error }
            },
        )
    }

    fun disconnect() {
        clientJobs.forEach { it.cancel() }
        clientJobs = emptyList()
        client.value?.close()
        client.value = null
        active.value = null
    }

    fun showText(text: String) {
        val slides = splitIntoSlides(text)
        if (slides.isEmpty()) return
        show(Item.Text(slides, 0))
    }

    fun showUri(uri: Uri) = scope.launch {
        val c = client.value
        if (c == null || connection.value !is Connection.Connected) {
            messages.emit("Сначала выберите экран")
            return@launch
        }
        try {
            val meta = withContext(Dispatchers.IO) { query(uri) }
            when (mediaKindOf(meta.name) ?: kindFromMime(app.contentResolver.getType(uri))) {
                MediaKind.PRESENTATION -> messages.emit("PPTX, PPT и ODP пока нужно сохранить как PDF")
                MediaKind.TEXT -> showText(withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } })
                MediaKind.IMAGE -> {
                    val source = if (meta.name.lowercase().let { it.endsWith(".heic") || it.endsWith(".heif") }) heicToJpeg(uri, meta.name) else uriSource(uri, meta)
                    show(Item.Image(upload(c, source)))
                }
                MediaKind.VIDEO -> show(Item.Video(upload(c, uriSource(uri, meta))))
                MediaKind.PDF -> {
                    val pages = withContext(Dispatchers.IO) {
                        app.contentResolver.openFileDescriptor(uri, "r")!!.use { fd -> PdfRenderer(fd).use { it.pageCount } }
                    }
                    show(Item.Pdf(upload(c, uriSource(uri, meta)), pages, 0))
                }
                null -> messages.emit("Формат «${meta.name}» не поддерживается")
            }
        } catch (e: Exception) {
            messages.emit(e.message ?: "Не удалось открыть файл")
        } finally {
            progress.value = null
        }
    }

    fun importBible(uri: Uri) = scope.launch {
        try {
            val m = withContext(Dispatchers.IO) { bible.import(uri) }
            bibleModules.value = withContext(Dispatchers.IO) { bible.modules() }
            if (bible.selected.isEmpty()) bible.selected = listOf(m.fileName)
            messages.emit("Добавлен перевод «${m.title}»")
        } catch (e: Exception) {
            messages.emit(e.message ?: "Не удалось добавить модуль")
        }
    }

    fun removeBible(fileName: String) {
        bible.remove(fileName)
        bible.selected = bible.selected - fileName
        bibleModules.value = bible.modules()
    }

    /** Показывает место Писания на выбранных переводах с текущими настройками. */
    fun showScripture(refText: String, selected: List<String>, options: ScriptureOptions) = scope.launch {
        bible.options = options
        bible.selected = selected
        bible.lastRef = refText
        val modules = selected.mapNotNull { f -> bibleModules.value.firstOrNull { it.fileName == f } }
        if (modules.isEmpty()) return@launch messages.emit("Выберите перевод")
        val slides = withContext(Dispatchers.IO) {
            val ref = parseBibleRef(refText, modules.first().books) ?: return@withContext null
            buildScriptureSlides(ref, modules, options)
        }
        when {
            slides == null -> messages.emit("Не понял место «$refText». Пример: Ин 3:16-18")
            slides.isEmpty() -> messages.emit("В переводе нет такого места")
            else -> show(Item.Scripture(slides, 0))
        }
    }

    fun step(delta: Int) {
        when (val it = item.value) {
            is Item.Scripture -> it.copy(index = (it.index + delta).coerceIn(0, it.slides.lastIndex)).takeIf { n -> n != it }?.let(::show)
            is Item.Text -> it.copy(index = (it.index + delta).coerceIn(0, it.slides.lastIndex)).takeIf { n -> n != it }?.let(::show)
            is Item.Pdf -> it.copy(index = (it.index + delta).coerceIn(0, it.pages - 1)).takeIf { n -> n != it }?.let(::show)
            else -> Unit
        }
    }

    fun mode(m: Mode) {
        val current = receiverState.value?.mode
        client.value?.mode(if (current == m) Mode.NORMAL else m)
    }

    fun video(action: VideoAction, deltaMs: Long = 0) {
        val v = receiverState.value?.video
        val cmd = when (action) {
            VideoAction.SEEK -> VideoCommand(action, positionMs = ((v?.positionMs ?: 0) + deltaMs).coerceAtLeast(0))
            VideoAction.LOOP -> VideoCommand(action, loop = !(v?.loop ?: false))
            else -> VideoCommand(action)
        }
        client.value?.video(cmd)
    }

    private fun show(it: Item) {
        val content: Content = when (it) {
            is Item.Text -> TextContent(it.slides[it.index])
            is Item.Pdf -> PdfContent(it.ref, it.index)
            is Item.Image -> ImageContent(it.ref)
            is Item.Video -> VideoContent(it.ref)
            is Item.Scripture -> it.slides[it.index]
        }
        if (client.value?.show(content) == true) item.value = it
        else scope.launch { messages.emit("Нет связи с экраном") }
    }

    private suspend fun upload(c: SenderClient, source: UploadSource): FileRef {
        progress.value = "«${source.name}»: подготовка"
        return c.upload(source) { sent, total ->
            val p = if (total == 0L) 100 else (sent * 100 / total).toInt()
            progress.value = "«${source.name}»: $p%"
        }
    }

    private data class Meta(val name: String, val size: Long?)

    private fun query(uri: Uri): Meta {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: uri.lastPathSegment ?: "file"
                val size = if (c.isNull(1)) null else c.getLong(1)
                return Meta(name, size)
            }
        }
        return Meta(uri.lastPathSegment ?: "file", null)
    }

    /** Если система не знает размер файла, он копируется во временный: без размера нельзя докачивать. */
    private suspend fun uriSource(uri: Uri, meta: Meta): UploadSource = withContext(Dispatchers.IO) {
        val known = meta.size
        if (known != null && known >= 0) {
            object : UploadSource {
                override val name = meta.name
                override val size: Long = known
                override val mime = mimeOf(meta.name)
                override fun open(): InputStream = app.contentResolver.openInputStream(uri) ?: error("Файл недоступен")
            }
        } else {
            val tmp = File(app.cacheDir, "upload-" + meta.name.replace('/', '_'))
            app.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            fileSource(tmp, meta.name)
        }
    }

    /** HEIC с iPhone и новых Android приставки читают не всегда, поэтому телефон отправляет JPEG. */
    private suspend fun heicToJpeg(uri: Uri, name: String): UploadSource = withContext(Dispatchers.IO) {
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri))
        } else {
            app.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) } ?: error("Не удалось прочитать «$name»")
        }
        val jpegName = name.substringBeforeLast('.') + ".jpg"
        val tmp = File(app.cacheDir, "upload-$jpegName")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        fileSource(tmp, jpegName)
    }

    private fun fileSource(file: File, name: String) = object : UploadSource {
        override val name = name
        override val size = file.length()
        override val mime = mimeOf(name)
        override fun open(): InputStream = file.inputStream()
    }

    private fun kindFromMime(mime: String?): MediaKind? = when {
        mime == null -> null
        mime.startsWith("image/") -> MediaKind.IMAGE
        mime.startsWith("video/") || mime.startsWith("audio/") -> MediaKind.VIDEO
        mime == "application/pdf" -> MediaKind.PDF
        mime.startsWith("text/") -> MediaKind.TEXT
        else -> null
    }

    private fun key(t: Target) = if (t.kind == Target.Kind.CABLE) "cable" else t.id
}
