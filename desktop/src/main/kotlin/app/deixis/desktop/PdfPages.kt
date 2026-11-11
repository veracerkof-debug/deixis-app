package app.deixis.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Страницы PDF для всех окон вывода и зрителей. Раньше каждая страница открывала документ заново и рисовалась,
 * только когда её показали: тяжёлая презентация листалась с паузой. Теперь документ остаётся открытым, готовые
 * страницы лежат в памяти (общей для всех мониторов), а следующая рисуется заранее в фоне — листание вперёд мгновенное.
 * Документ, к которому долго не обращались, закрывается (на Windows открытый файл нельзя удалить).
 */
object PdfPages {
    private class Doc(val doc: PDDocument, val renderer: PDFRenderer, val stamp: Long) {
        @Volatile var usedAt = System.currentTimeMillis()
    }

    private val docs = LinkedHashMap<String, Doc>(4, 0.75f, true)
    private val pages = object : LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {}
    private var bytes = 0L
    private val pending = HashSet<String>()
    private val ahead = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pdf-ahead").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    init {
        ahead.scheduleWithFixedDelay({ closeIdle() }, IDLE_MS, IDLE_MS, TimeUnit.MILLISECONDS)
    }

    /** Сколько страниц в документе. */
    fun count(file: File): Int = doc(file).let { d -> synchronized(d) { d.doc.numberOfPages } }

    /** Страница [page], вписанная в [w]×[h] (или по ширине [w], если [h] = 0); следующая готовится заранее. */
    fun page(file: File, page: Int, w: Int, h: Int, name: String = file.name): BufferedImage {
        val img = render(file, page, w, h, name)
        prefetch(file, page + 1, w, h, name)
        return img
    }

    private fun key(file: File, page: Int, w: Int, h: Int) = "${file.path}:${file.lastModified()}:$page:${w}x$h"

    private fun cached(k: String): BufferedImage? = synchronized(pages) { pages[k] }

    private fun render(file: File, page: Int, w: Int, h: Int, name: String): BufferedImage {
        val k = key(file, page, w, h)
        cached(k)?.let { return it }
        val d = doc(file)
        // Документ PDFBox рисуется одним потоком: заранее начатая страница дорисуется, и её возьмут из памяти.
        val img = synchronized(d) {
            cached(k)?.let { return it }
            require(page in 0 until d.doc.numberOfPages) { "В «$name» нет страницы ${page + 1}" }
            val box = d.doc.getPage(page).cropBox
            val scale = if (h > 0) minOf(w / box.width, h / box.height) else w / box.width
            d.renderer.renderImage(page, scale, ImageType.RGB)
        }
        put(k, img)
        return img
    }

    private fun prefetch(file: File, page: Int, w: Int, h: Int, name: String) {
        val k = key(file, page, w, h)
        if (cached(k) != null) return
        synchronized(pending) { if (!pending.add(k)) return }
        ahead.execute {
            try {
                val d = doc(file)
                if (page < synchronized(d) { d.doc.numberOfPages }) render(file, page, w, h, name)
            } catch (_: Throwable) {
                // Заранее — не обязательно: не вышло — страница нарисуется, когда её покажут.
            } finally {
                synchronized(pending) { pending.remove(k) }
            }
        }
    }

    private fun put(k: String, img: BufferedImage) = synchronized(pages) {
        pages.put(k, img)?.let { bytes -= size(it) }
        bytes += size(img)
        val it = pages.entries.iterator()
        while (bytes > MAX_BYTES && pages.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.value === img) continue
            bytes -= size(e.value)
            it.remove()
        }
    }

    private fun size(img: BufferedImage) = img.width.toLong() * img.height * BYTES_PER_PIXEL

    private fun doc(file: File): Doc {
        val stamp = file.lastModified()
        synchronized(docs) {
            docs[file.path]?.let { d -> if (d.stamp == stamp) { d.usedAt = System.currentTimeMillis(); return d } else close(file.path) }
            val doc = Loader.loadPDF(file)
            val d = Doc(doc, PDFRenderer(doc), stamp)
            docs[file.path] = d
            while (docs.size > MAX_DOCS) close(docs.keys.first())
            return d
        }
    }

    private fun close(path: String) {
        val d = docs.remove(path) ?: return
        // Закрывается, когда его никто не рисует.
        ahead.execute { synchronized(d) { runCatching { d.doc.close() } } }
    }

    private fun closeIdle() {
        val now = System.currentTimeMillis()
        synchronized(docs) { docs.filterValues { now - it.usedAt > IDLE_MS }.keys.toList().forEach(::close) }
    }

    /** Для проверки: страница уже в памяти. */
    internal fun isReady(file: File, page: Int, w: Int, h: Int) = cached(key(file, page, w, h)) != null

    private const val MAX_DOCS = 3
    /** ~6 страниц Full HD или 2 страницы 4K: больше памяти на это не уходит. */
    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val BYTES_PER_PIXEL = 4
    private const val IDLE_MS = 60_000L
}
