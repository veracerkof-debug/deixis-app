package app.deixis.phone

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.deixis.core.Content
import app.deixis.core.Discovery
import app.deixis.core.Dlna
import app.deixis.core.DlnaController
import app.deixis.core.DlnaFileServer
import app.deixis.core.DlnaRenderer
import app.deixis.core.FileRef
import app.deixis.core.ImageContent
import app.deixis.core.Mode
import app.deixis.core.PdfContent
import app.deixis.core.ReceiverState
import app.deixis.core.ScreenContent
import app.deixis.core.UploadSource
import app.deixis.core.VideoAction
import app.deixis.core.VideoContent
import app.deixis.core.VideoState
import app.deixis.receiver.renderPdfPage
import app.deixis.receiver.renderSlideBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Обычный телевизор с DLNA как экран — запасной путь «на всякий случай». Телевизор сам скачивает файл
 * с телефона; слайды, страницы PDF, «Чёрный» и «Логотип» уходят картинками. Смена слайда занимает
 * столько, сколько телевизору нужно, чтобы открыть новую картинку. Демонстрация экрана здесь не работает.
 */
class DlnaOutput(private val app: Application) {
    val found = MutableStateFlow<List<DlnaRenderer>>(emptyList())

    /** Режим и ход видео на телевизоре — для пульта, как у приёмника Deixis. */
    val state = MutableStateFlow<ReceiverState?>(null)

    var tv: DlnaController? = null
        private set
    private var server: DlnaFileServer? = null
    private val sources = mutableMapOf<String, UploadSource>()
    private var shown: Content? = null
    private var mode = Mode.NORMAL
    private var poll: Job? = null

    /** Поиск телевизоров раз в 20 секунд, пока открыт пульт. */
    fun search(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            runCatching { Dlna.discover() }.onSuccess { found.value = it }
            delay(20_000)
        }
    }

    suspend fun open(r: DlnaRenderer, scope: CoroutineScope) = withContext(Dispatchers.IO) {
        close()
        server = DlnaFileServer().also { it.start() }
        tv = DlnaController(r)
        state.value = ReceiverState()
        poll = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1_000)
                val t = tv ?: continue
                if (shown !is VideoContent || mode != Mode.NORMAL) continue
                runCatching { t.state() }.onSuccess { s ->
                    state.value = state.value?.copy(video = VideoState(playing = s.playing, positionMs = s.positionMs, durationMs = s.durationMs, volume = 100, loop = false))
                }
            }
        }
    }

    fun close() {
        poll?.cancel()
        poll = null
        tv?.let { t -> CoroutineScope(Dispatchers.IO).launch { runCatching { t.stop() }; t.close() } }
        tv = null
        server?.stop()
        server = null
        sources.clear()
        shown = null
        mode = Mode.NORMAL
        state.value = null
    }

    /** Файл телефона для показа: телевизору он отдаётся напрямую, без копии на приёмник. */
    fun register(source: UploadSource): FileRef {
        val id = "dlna-" + UUID.randomUUID().toString().replace("-", "")
        sources[id] = source
        return FileRef(id, source.name, source.size, source.mime)
    }

    suspend fun show(content: Content) {
        shown = content
        mode = Mode.NORMAL
        state.value = ReceiverState(content = content, mode = mode)
        render()
    }

    /** «Чёрный», «Логотип», «Очистить»: повторное нажатие возвращает показ. */
    suspend fun toggle(m: Mode) {
        mode = if (mode == m) Mode.NORMAL else m
        state.value = state.value?.copy(mode = mode)
        render()
    }

    suspend fun video(action: VideoAction, deltaMs: Long) {
        val t = tv ?: return
        when (action) {
            VideoAction.PLAY -> t.resume()
            VideoAction.PAUSE -> t.pause()
            VideoAction.SEEK -> t.seek(((state.value?.video?.positionMs ?: 0) + deltaMs).coerceAtLeast(0))
            else -> Unit
        }
    }

    private suspend fun render() = withContext(Dispatchers.IO) {
        val t = tv ?: throw IOException("Телевизор не выбран")
        val s = server ?: throw IOException("Телевизор не выбран")
        val address = Discovery.localAddress()?.hostAddress ?: throw IOException("Телефон не в сети Wi-Fi")
        val c = shown
        when {
            mode != Mode.NORMAL || c == null -> picture(t, s, address, renderSlideBitmap(app, c, if (c == null) Mode.BLACK else mode))
            c is ImageContent || c is VideoContent -> {
                val ref = if (c is ImageContent) c.file else (c as VideoContent).file
                val src = sources[ref.sha256] ?: throw IOException("Файл «${ref.name}» не найден на телефоне")
                t.play(s.share(src, address), ref.name.substringBeforeLast('.'), src.mime)
            }
            c is PdfContent -> picture(t, s, address, pdfPage(c))
            c is ScreenContent -> throw IOException("Демонстрация экрана на DLNA-телевизор не идёт: выберите экран Deixis")
            else -> picture(t, s, address, renderSlideBitmap(app, c, Mode.NORMAL))
        }
        s.forgetAllBut(2)
    }

    private suspend fun picture(t: DlnaController, s: DlnaFileServer, address: String, bmp: Bitmap) {
        val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it); bmp.recycle() }.toByteArray()
        val src = object : UploadSource {
            override val name = "slide.jpg"
            override val size = jpeg.size.toLong()
            override val mime = "image/jpeg"
            override fun open(): InputStream = jpeg.inputStream()
        }
        t.play(s.share(src, address), "Deixis", "image/jpeg")
    }

    /** Страница PDF на чёрном кадре 1920×1080. PdfRenderer нужен файл, поэтому PDF один раз копируется в кэш. */
    private fun pdfPage(c: PdfContent): Bitmap {
        val src = sources[c.file.sha256] ?: throw IOException("Файл «${c.file.name}» не найден на телефоне")
        val file = File(app.cacheDir, "dlna-${c.file.sha256}.pdf")
        if (!file.isFile) src.open().use { input -> file.outputStream().use { input.copyTo(it) } }
        val page = renderPdfPage(file, c.page, 1920, 1080)
        val out = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.BLACK)
        val scale = minOf(1920f / page.width, 1080f / page.height)
        val w = page.width * scale
        val h = page.height * scale
        canvas.drawBitmap(page, null, RectF((1920 - w) / 2, (1080 - h) / 2, (1920 + w) / 2, (1080 + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        page.recycle()
        return out
    }
}
