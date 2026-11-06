package app.deixis.phone

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.deixis.core.DocConverter
import app.deixis.core.SqlSource
import app.deixis.core.mimeOf
import app.deixis.desktop.JdbcSql
import app.deixis.desktop.VideoProbe
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.Properties
import javax.imageio.ImageIO

/**
 * Пульт на компьютере: файлы оператора — обычные файлы (file:///…), перевод PPTX и DOCX тем же переводчиком,
 * что у приёмника этого компьютера (PowerPoint, LibreOffice, Keynote или встроенный), PDF — PDFBox, видео — FFmpeg.
 */
class DesktopPlatform(
    /** Папка данных Deixis; пульт хранит своё в её подпапке pult. */
    dataDir: File,
    private val converter: DocConverter,
    private val lan: () -> String?,
    private val shown: () -> Boolean,
) : Platform {
    override val filesDir: File = File(dataDir, "pult").apply { mkdirs() }
    override val cacheDir: File = File(dataDir, "pult-cache").apply { mkdirs() }
    override val deviceName: String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Deixis"
    override val features = Features(desktop = true, screenShare = false, becomeScreen = false, cable = false)

    private val prefsCache = HashMap<String, FilePrefs>()

    @Synchronized
    override fun prefs(name: String): Prefs = prefsCache.getOrPut(name) { FilePrefs(File(File(filesDir, "prefs").apply { mkdirs() }, "$name.properties")) }

    private fun file(uri: String): File? = runCatching { if (uri.startsWith("file:")) File(URI(uri)) else File(uri) }.getOrNull()

    override fun open(uri: String): InputStream? = file(uri)?.takeIf { it.isFile }?.inputStream()

    override fun meta(uri: String): FileMeta {
        val f = file(uri) ?: return FileMeta("", 0, null)
        val mime = runCatching { java.nio.file.Files.probeContentType(f.toPath()) }.getOrNull() ?: mimeOf(f.name)
        return FileMeta(f.name, f.length(), mime)
    }

    override fun keep(uri: String) = Unit
    override fun fileUri(f: File): String = f.toURI().toString()

    override fun pdfPages(uri: String): Int = file(uri)?.let { f -> Loader.loadPDF(f).use { it.numberOfPages } } ?: 0

    override fun videoDurationMs(uri: String): Long = file(uri)?.let(VideoProbe::durationMs) ?: 0L

    // HEIC читают не все экраны; на компьютере Java его не открывает — такие фото лучше сохранить в JPEG.
    override fun heicToJpeg(uri: String, out: File): Boolean = false

    override fun canConvertHere(ext: String): Boolean = ext in CONVERTIBLE

    override fun convertToPdf(src: File, ext: String, out: File): String? {
        val work = File(cacheDir, "convert-${System.nanoTime()}")
        try {
            val r = converter.toPdf(src, work)
            r.pdf.copyTo(out, overwrite = true)
            return r.warning
        } finally {
            work.deleteRecursively()
        }
    }

    override fun sql(file: File): SqlSource = JdbcSql(file)

    override fun demoFiles(): List<String> =
        javaClass.getResourceAsStream("/demo/index.txt")?.use { it.readBytes().decodeToString().lines().filter { l -> l.isNotBlank() } }.orEmpty()

    override fun openDemo(name: String): InputStream = javaClass.getResourceAsStream("/demo/$name") ?: error(name)

    override fun demoSize(name: String): Long = -1L

    override fun folderFiles(folder: String): List<Pair<String, String>> =
        file(folder)?.listFiles().orEmpty().filter { it.isFile }.map { fileUri(it) to it.name }

    override fun folderWrite(folder: String, name: String, bytes: ByteArray) {
        val dir = file(folder) ?: return
        File(dir, name).writeBytes(bytes)
    }

    override fun folderDelete(uri: String) { file(uri)?.delete() }
    override fun keepFolder(folder: String) = Unit

    override fun decodeImage(bytes: ByteArray): ImageBitmap? =
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

    override fun thumbnail(uri: String, kind: ThumbKind, index: Int, width: Int): ImageBitmap? {
        val f = file(uri)?.takeIf { it.isFile } ?: return null
        val img: BufferedImage = when (kind) {
            ThumbKind.PAGE -> Loader.loadPDF(f).use { doc ->
                if (index !in 0 until doc.numberOfPages) return null
                val scale = width / doc.getPage(index).cropBox.width
                PDFRenderer(doc).renderImage(index, scale, ImageType.RGB)
            }
            ThumbKind.PHOTO -> scaled(ImageIO.read(f) ?: return null, width)
            ThumbKind.VIDEO -> VideoProbe.frame(f, VIDEO_FRAME_MS, width) ?: return null
        }
        return img.toComposeImageBitmap()
    }

    private fun scaled(src: BufferedImage, width: Int): BufferedImage {
        if (src.width <= width) return src
        val h = (width.toLong() * src.height / src.width).toInt().coerceAtLeast(1)
        val out = BufferedImage(width, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, width, h, null)
        g.dispose()
        return out
    }

    // Компьютер не засыпает с открытым окном и сеть не теряет: ни службы, ни разрешений не нужно.
    override fun keepAlive() = Unit
    override fun multicast(on: Boolean) = Unit
    override fun stopScreenShare() = Unit
    override val lanAddress: String? get() = lan()
    override val visible: Boolean get() = shown()
    override val dlna: DlnaOut? = null

    private companion object {
        val CONVERTIBLE = setOf("pptx", "ppt", "docx", "doc", "odp", "odt", "key", "rtf")
        const val VIDEO_FRAME_MS = 1_000L
    }
}

/** Настройки пульта на компьютере: файл .properties, запись сразу. */
private class FilePrefs(private val file: File) : Prefs {
    private val p = Properties().apply { runCatching { file.inputStream().use { load(it) } } }

    @Synchronized override fun getString(key: String, def: String?): String? = p.getProperty(key) ?: def
    @Synchronized override fun getInt(key: String, def: Int) = p.getProperty(key)?.toIntOrNull() ?: def
    @Synchronized override fun getLong(key: String, def: Long) = p.getProperty(key)?.toLongOrNull() ?: def
    @Synchronized override fun getBoolean(key: String, def: Boolean) = p.getProperty(key)?.toBooleanStrictOrNull() ?: def
    @Synchronized override fun getStringSet(key: String, def: Set<String>?): Set<String>? =
        p.getProperty(SET + key)?.let { v -> if (v.isEmpty()) emptySet() else v.split(SEP).toSet() } ?: def
    @Synchronized override fun contains(key: String) = p.containsKey(key) || p.containsKey(SET + key)
    @Synchronized override fun all(): Map<String, Any?> = p.stringPropertyNames().filter { !it.startsWith(SET) }.associateWith { p.getProperty(it) }

    @Synchronized override fun edit(block: Prefs.Editor.() -> Unit) {
        object : Prefs.Editor {
            override fun putString(key: String, value: String?) { if (value == null) p.remove(key) else p.setProperty(key, value) }
            override fun putInt(key: String, value: Int) { p.setProperty(key, value.toString()) }
            override fun putLong(key: String, value: Long) { p.setProperty(key, value.toString()) }
            override fun putBoolean(key: String, value: Boolean) { p.setProperty(key, value.toString()) }
            override fun putStringSet(key: String, value: Set<String>) { p.setProperty(SET + key, value.joinToString(SEP)) }
            override fun remove(key: String) { p.remove(key); p.remove(SET + key) }
            override fun clear() { p.clear() }
        }.block()
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { p.store(it, null) }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    private companion object {
        const val SET = "set."
        const val SEP = "\u001F"
    }
}
