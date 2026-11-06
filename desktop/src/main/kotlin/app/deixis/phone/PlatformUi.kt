package app.deixis.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import app.deixis.core.Content
import app.deixis.core.FileStore
import app.deixis.core.LogoStyle
import app.deixis.core.ScriptureContent
import app.deixis.core.TextContent
import app.deixis.desktop.SlideRenderer
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import app.deixis.core.TextStyle as SlideStyle

// Пульт на компьютере: то, что экраны пульта (pult/) берут у этой системы.

/** Окно, к которому привязаны окна выбора файлов (окно пульта). */
@Volatile var pultFrame: Frame? = null

/** Выбор файлов в системном окне; несколько — с Ctrl/Shift. */
@Composable
fun rememberFilePicker(multiple: Boolean = false, onPick: (List<String>) -> Unit): FilePicker {
    val pick = rememberUpdatedState(onPick)
    return remember(multiple) {
        FilePicker { mimes ->
            SwingUtilities.invokeLater {
                val d = FileDialog(pultFrame, L.chooseFiles, FileDialog.LOAD)
                d.isMultipleMode = multiple
                val exts = extensionsFor(mimes)
                if (exts != null) d.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in exts }
                d.isVisible = true
                val files = d.files.orEmpty().filter { it.isFile }
                if (files.isNotEmpty()) pick.value(files.map { it.toURI().toString() })
            }
        }
    }
}

/** Выбор папки (резервные копии вне программы: флешка, облачная папка). */
@Composable
fun rememberFolderPicker(onPick: (String) -> Unit): FolderPicker {
    val pick = rememberUpdatedState(onPick)
    return remember {
        FolderPicker {
            SwingUtilities.invokeLater {
                val ch = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = L.chooseFolder }
                if (ch.showOpenDialog(pultFrame) == JFileChooser.APPROVE_OPTION) ch.selectedFile?.let { pick.value(it.toURI().toString()) }
            }
        }
    }
}

/** Esc закрывает шторку и полноэкранный вид, как «Назад» на телефоне. */
object DesktopBack {
    private val handlers = ArrayList<Pair<() -> Boolean, () -> Unit>>()

    @Synchronized fun add(h: Pair<() -> Boolean, () -> Unit>) { handlers += h }
    @Synchronized fun remove(h: Pair<() -> Boolean, () -> Unit>) { handlers -= h }

    /** Esc нажат: срабатывает последний включённый; true — было что закрыть. */
    @Synchronized fun back(): Boolean {
        val h = handlers.lastOrNull { it.first() } ?: return false
        h.second()
        return true
    }
}

@Composable
fun BackHandlerOn(enabled: Boolean, onBack: () -> Unit) {
    val on = rememberUpdatedState(enabled)
    val act = rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        val h: Pair<() -> Boolean, () -> Unit> = { on.value } to { act.value() }
        DesktopBack.add(h)
        onDispose { DesktopBack.remove(h) }
    }
}

/** Действия «Добавить» на компьютере: файлы — системным окном; трансляция экрана — плиткой «Экран компьютера». */
@Composable
fun rememberActs(c: Controller, ui: Ui): Acts {
    val files = remember { arrayOfNulls<((LibItem) -> Unit)?>(1) }
    val presentation = remember { booleanArrayOf(false) }
    val picker = rememberFilePicker(multiple = true) { uris -> c.importFiles(uris, presentation[0]) { files[0]?.invoke(it) } }
    val bible = rememberFilePicker { uris -> uris.firstOrNull()?.let(c::importBible) }
    return remember(c, ui) {
        Acts(
            pick = { mimes, pres, cb -> files[0] = cb; presentation[0] = pres; picker.launch(mimes) },
            share = { ui.toast(L.desktopShareHint) },
            addBible = { bible.launch(arrayOf("*/*")) },
            holyrics = { ui.holyrics = true },
        )
    }
}

/** Расширения файлов для фильтра окна выбора; null — любые. */
private fun extensionsFor(mimes: Array<String>): Set<String>? {
    if (mimes.any { it == "*/*" }) return null
    val out = HashSet<String>()
    for (m in mimes) when {
        m.startsWith("image/") -> out += setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "tif", "tiff", "heic", "heif")
        m.startsWith("video/") -> out += setOf("mp4", "mov", "m4v", "mkv", "webm", "avi", "wmv", "mpg", "mpeg", "ts")
        m.startsWith("audio/") -> out += setOf("mp3", "m4a", "aac", "wav", "flac", "ogg")
        m == "application/pdf" -> out += "pdf"
        m.contains("presentation") || m.contains("powerpoint") -> out += setOf("pptx", "ppt", "odp", "key")
        m.contains("word") || m.contains("document") -> out += setOf("docx", "doc", "odt", "rtf")
        m.startsWith("text/") -> out += setOf("txt", "cho", "chordpro", "chopro", "xml")
        m.contains("json") -> out += "json"
        else -> return null
    }
    return out
}

// ——— Слайды превью — тем же движком, что зал этого компьютера ———

/** Знак ΔΕΙΞΙΣ — шрифт GFS Didot, как в зале. */
val Didot: FontFamily by lazy {
    val bytes = SlideRenderer::class.java.getResourceAsStream("/web/GFSDidot-Regular.ttf")!!.use { it.readBytes() }
    FontFamily(Font("GFSDidot", bytes))
}

private val previewRenderer by lazy { SlideRenderer(FileStore(File(System.getProperty("java.io.tmpdir"), "deixis-preview"))) }

/** Картинка слайда размером с место под неё; перерисовывается, только когда меняется слайд или размер. */
@Composable
private fun Rendered(key: Any, draw: (w: Int, h: Int) -> java.awt.image.BufferedImage) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current
        val w = with(d) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
        val h = with(d) { maxHeight.toPx() }.toInt().coerceAtLeast(1)
        val img = remember(key, w, h) { synchronized(previewRenderer) { runCatching { draw(w, h).toComposeImageBitmap() }.getOrNull() } }
        if (img != null) Image(img, null, Modifier.fillMaxSize())
    }
}

@Composable
fun LogoFull(style: LogoStyle = LogoStyle.WORDMARK, @Suppress("UNUSED_PARAMETER") color: Color = Color.White) =
    Rendered(style) { w, h -> previewRenderer.renderPreview(w, h, null, clear = false, transparent = true, logo = style) }

@Composable
fun TextSlideCanvas(
    text: String, ref: String?, style: SlideStyle, clear: Boolean = false,
    @Suppress("UNUSED_PARAMETER") overscan: Boolean = false, drawBackground: Boolean = true,
) {
    val content: Content = TextContent(text, ref, style.copy(backgroundImage = null, backgroundVideo = null))
    Rendered(Triple(content, clear, drawBackground)) { w, h -> previewRenderer.renderPreview(w, h, content, clear, transparent = !drawBackground) }
}

@Composable
fun ScriptureSlideCanvas(
    c: ScriptureContent, clear: Boolean = false,
    @Suppress("UNUSED_PARAMETER") overscan: Boolean = false, drawBackground: Boolean = true,
) {
    val content = c.copy(style = c.style.copy(backgroundImage = null, backgroundVideo = null))
    Rendered(Triple(content, clear, drawBackground)) { w, h -> previewRenderer.renderPreview(w, h, content, clear, transparent = !drawBackground) }
}
