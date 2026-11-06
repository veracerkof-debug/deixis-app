package app.deixis.phone

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.deixis.core.Docx
import app.deixis.core.SqlSource
import app.deixis.receiver.AndroidSql
import app.deixis.receiver.PptxPdf
import java.io.File
import java.io.InputStream

/** Пульт на телефоне: файлы через систему документов Android, перевод презентаций своим движком, DLNA. */
class AndroidPlatform(private val app: Application) : Platform {
    override val filesDir: File get() = app.filesDir
    override val cacheDir: File get() = app.cacheDir
    override val deviceName: String = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
    override val features = Features(
        desktop = false, screenShare = true, becomeScreen = true, cable = true,
        shareSound = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
    )

    override fun prefs(name: String): Prefs = AndroidPrefs(app.getSharedPreferences(name, Context.MODE_PRIVATE))

    override fun open(uri: String): InputStream? = runCatching { app.contentResolver.openInputStream(Uri.parse(uri)) }.getOrNull()

    override fun meta(uri: String): FileMeta {
        val u = Uri.parse(uri)
        var name = u.lastPathSegment ?: ""
        var size = 0L
        runCatching {
            app.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) size = c.getLong(1) }
            }
        }
        if (size == 0L && u.scheme == "file") size = File(u.path ?: "").length()
        return FileMeta(name, size, runCatching { app.contentResolver.getType(u) }.getOrNull())
    }

    override fun keep(uri: String) {
        runCatching { app.contentResolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    override fun fileUri(f: File): String = Uri.fromFile(f).toString()

    override fun pdfPages(uri: String): Int =
        app.contentResolver.openFileDescriptor(Uri.parse(uri), "r")!!.use { fd -> PdfRenderer(fd).use { it.pageCount } }

    override fun videoDurationMs(uri: String): Long = MediaMetadataRetriever().run {
        try {
            setDataSource(app, Uri.parse(uri))
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            release()
        }
    }

    override fun heicToJpeg(uri: String, out: File): Boolean {
        val u = Uri.parse(uri)
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Снимок 48 Мп целиком — около 190 МБ памяти: уменьшается до 4K по длинной стороне при чтении.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, u)) { d, info, _ ->
                val long = maxOf(info.size.width, info.size.height)
                if (long > PHOTO_MAX_SIDE) {
                    val k = PHOTO_MAX_SIDE.toFloat() / long
                    d.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
                }
            }
        } else {
            app.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) } ?: return false
        }
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        return true
    }

    // PPTX и DOCX переводятся в PDF прямо на телефоне: превью страниц и показ на любом экране без компьютера.
    override fun canConvertHere(ext: String): Boolean = ext in PptxPdf.FORMATS || ext in Docx.FORMATS

    override fun convertToPdf(src: File, ext: String, out: File): String? = PptxPdf.warning(PptxPdf.convert(app, src, out))

    override fun sql(file: File): SqlSource = AndroidSql(file)

    override fun demoFiles(): List<String> = app.assets.list("demo").orEmpty().toList()
    override fun openDemo(name: String): InputStream = app.assets.open("demo/$name")
    // Размер в APK известен только у несжатых файлов (фото, видео); сжатые копируются всегда.
    override fun demoSize(name: String): Long = runCatching { app.assets.openFd("demo/$name").use { it.length } }.getOrDefault(-1L)

    override fun folderFiles(folder: String): List<Pair<String, String>> {
        val tree = Uri.parse(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<Pair<String, String>>()
        app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out += DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString() to name
            }
        }
        return out
    }

    override fun folderWrite(folder: String, name: String, bytes: ByteArray) {
        val tree = Uri.parse(folder)
        val cr = app.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(cr, parent, "application/json", name) ?: return
        cr.openOutputStream(doc, "w")?.use { it.write(bytes) }
    }

    override fun folderDelete(uri: String) {
        DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(uri))
    }

    override fun keepFolder(folder: String) {
        app.contentResolver.takePersistableUriPermission(Uri.parse(folder), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    override fun decodeImage(bytes: ByteArray): ImageBitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

    override fun thumbnail(uri: String, kind: ThumbKind, index: Int, width: Int): ImageBitmap? {
        val u = Uri.parse(uri)
        return when (kind) {
            ThumbKind.PAGE -> page(u, index, width)
            ThumbKind.PHOTO -> photo(u, width)
            ThumbKind.VIDEO -> video(u, width)
        }?.asImageBitmap()
    }

    private fun page(uri: Uri, index: Int, width: Int): Bitmap? {
        val fd = app.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return fd.use {
            PdfRenderer(it).use { r ->
                if (index !in 0 until r.pageCount) null
                else r.openPage(index).use { pg ->
                    val h = (width.toFloat() * pg.height / pg.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(android.graphics.Color.WHITE)
                        pg.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    private fun photo(uri: Uri, width: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { return app.contentResolver.loadThumbnail(uri, Size(width, width), null) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { d, info, _ ->
                    val scale = (info.size.width / width).coerceAtLeast(1)
                    d.setTargetSize(info.size.width / scale, info.size.height / scale)
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        return app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    private fun video(uri: Uri, width: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(app, uri)
            val frame = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val h = (width.toFloat() * frame.height / frame.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(frame, width, h, true).also { if (it !== frame) frame.recycle() }
        } finally {
            runCatching { r.release() }
        }
    }

    override fun keepAlive() = ShowService.start(app)

    private var multicastLock: WifiManager.MulticastLock? = null

    override fun multicast(on: Boolean) {
        if (on) {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("deixis")?.apply { setReferenceCounted(false); acquire() }
        } else runCatching { multicastLock?.release() }
    }

    override fun stopScreenShare() = ScreenShareService.stop(app)

    override val visible: Boolean get() = (app as? PhoneApp)?.remoteVisible != false
    // Ссылка зрителям строится по адресу экрана; своего экрана с адресом 127.0.0.1 у телефона нет.
    override val lanAddress: String? = null

    override val dlna: DlnaOut by lazy { DlnaOutput(app) }

    private companion object {
        /** Фото для экрана — не больше 4K по длинной стороне: больше зал не покажет. */
        const val PHOTO_MAX_SIDE = 3840
        const val JPEG_QUALITY = 92
    }
}

/** Настройки ключ–значение телефона. */
private class AndroidPrefs(private val p: SharedPreferences) : Prefs {
    override fun getString(key: String, def: String?) = p.getString(key, def)
    override fun getInt(key: String, def: Int) = p.getInt(key, def)
    override fun getLong(key: String, def: Long) = p.getLong(key, def)
    override fun getBoolean(key: String, def: Boolean) = p.getBoolean(key, def)
    override fun getStringSet(key: String, def: Set<String>?): Set<String>? = p.getStringSet(key, def)
    override fun contains(key: String) = p.contains(key)
    override fun all(): Map<String, Any?> = p.all
    override fun edit(block: Prefs.Editor.() -> Unit) {
        val e = p.edit()
        object : Prefs.Editor {
            override fun putString(key: String, value: String?) { e.putString(key, value) }
            override fun putInt(key: String, value: Int) { e.putInt(key, value) }
            override fun putLong(key: String, value: Long) { e.putLong(key, value) }
            override fun putBoolean(key: String, value: Boolean) { e.putBoolean(key, value) }
            override fun putStringSet(key: String, value: Set<String>) { e.putStringSet(key, value) }
            override fun remove(key: String) { e.remove(key) }
            override fun clear() { e.clear() }
        }.block()
        e.apply()
    }
}
