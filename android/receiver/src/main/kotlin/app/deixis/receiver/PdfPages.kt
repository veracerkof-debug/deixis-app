package app.deixis.receiver

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.Executors

/**
 * Страницы PDF на Android-экране: документ остаётся открытым, готовые страницы лежат в памяти, следующая рисуется
 * заранее в фоне — листание вперёд без паузы. Раньше каждая страница открывала файл заново и рисовалась, только
 * когда её показали. PdfRenderer держит одну открытую страницу, поэтому рисование — по очереди.
 */
object PdfPages {
    private class Doc(val path: String, val stamp: Long, val fd: ParcelFileDescriptor, val renderer: PdfRenderer)

    private var doc: Doc? = null
    private val lock = Any()
    private val pages = LinkedHashMap<String, Bitmap>(8, 0.75f, true)
    private var bytes = 0L
    private val pending = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-ahead").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    private fun key(file: File, page: Int, w: Int, h: Int) = "${file.path}:${file.lastModified()}:$page:${w}x$h"

    /** Страница уже готова (без рисования). */
    fun cached(file: File, page: Int, w: Int, h: Int): Bitmap? = synchronized(pages) { pages[key(file, page, w, h)] }

    /** Страница, вписанная в [w]×[h]; следующая готовится заранее. */
    fun page(file: File, page: Int, w: Int, h: Int): Bitmap {
        val bmp = render(file, page, w, h)
        ahead(file, page + 1, w, h)
        return bmp
    }

    /** Нарисовать страницу заранее, в фоне (если её ещё нет). */
    fun ahead(file: File, page: Int, w: Int, h: Int) {
        val k = key(file, page, w, h)
        if (cached(file, page, w, h) != null) return
        synchronized(pending) { if (!pending.add(k)) return }
        worker.execute {
            try {
                if (page < count(file)) render(file, page, w, h)
            } catch (_: Throwable) {
                // Заранее — не обязательно: страница нарисуется, когда её покажут.
            } finally {
                synchronized(pending) { pending.remove(k) }
            }
        }
    }

    fun count(file: File): Int = synchronized(lock) { open(file).renderer.pageCount }

    private fun render(file: File, page: Int, w: Int, h: Int): Bitmap {
        cached(file, page, w, h)?.let { return it }
        val bmp = synchronized(lock) {
            cached(file, page, w, h)?.let { return it }
            val r = open(file).renderer
            require(page in 0 until r.pageCount) { "В «${file.name}» нет страницы ${page + 1}" }
            r.openPage(page).use { p ->
                val scale = minOf(w.toFloat() / p.width, h.toFloat() / p.height)
                val out = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                out.eraseColor(android.graphics.Color.WHITE)
                p.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                out
            }
        }
        put(key(file, page, w, h), bmp)
        return bmp
    }

    private fun put(k: String, bmp: Bitmap) = synchronized(pages) {
        pages.put(k, bmp)?.let { bytes -= it.byteCount }
        bytes += bmp.byteCount
        val it = pages.entries.iterator()
        while (bytes > MAX_BYTES && pages.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.value === bmp) continue
            bytes -= e.value.byteCount
            it.remove()
        }
    }

    /** Открытый документ этого файла (другой файл или изменённый — закрывается прежний). Под [lock]. */
    private fun open(file: File): Doc {
        val stamp = file.lastModified()
        doc?.let { if (it.path == file.path && it.stamp == stamp) return it }
        doc?.let { runCatching { it.renderer.close() }; runCatching { it.fd.close() } }
        doc = null
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val d = try { Doc(file.path, stamp, fd, PdfRenderer(fd)) } catch (e: Throwable) { fd.close(); throw e }
        doc = d
        return d
    }

    /** ~4 страницы Full HD: на ТВ-приставке памяти немного. */
    private const val MAX_BYTES = 40L * 1024 * 1024
}
