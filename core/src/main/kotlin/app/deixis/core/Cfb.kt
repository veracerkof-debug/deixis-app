package app.deixis.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/*
 * Чтение составного файла Microsoft (Compound File Binary, OLE2, [MS-CFB]): в нём лежат старые
 * PPT, DOC, XLS. Своя реализация: заголовок, FAT и DIFAT, малая FAT и малый поток, каталог.
 * Все цепочки проверяются на границы и петли: испорченный файл не вешает чтение и не съедает память.
 */
internal class Cfb private constructor(private val raf: RandomAccessFile) : Closeable {

    class Entry(val name: String, val type: Int, val left: Int, val right: Int, val child: Int, val start: Int, val size: Long)

    private val fileLen = raf.length()
    private val sectorShift: Int
    private val sectorSize: Int
    private val miniSize: Int
    private val miniCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<Entry>
    private var miniChain: IntArray? = null

    init {
        if (fileLen < 512) throw PptxException(NOT_PPT)
        val h = ByteArray(512)
        raf.seek(0); raf.readFully(h)
        if (le64(h, 0) != SIGNATURE) throw PptxException(NOT_PPT)
        sectorShift = le16(h, 0x1E)
        if (sectorShift != 9 && sectorShift != 12) throw PptxException(BROKEN)
        sectorSize = 1 shl sectorShift
        val miniShift = le16(h, 0x20)
        if (miniShift !in 4..sectorShift) throw PptxException(BROKEN)
        miniSize = 1 shl miniShift
        miniCutoff = le32(h, 0x38).toLong() and 0xFFFFFFFFL
        val nFat = le32(h, 0x2C)
        val firstDir = le32(h, 0x30)
        val firstMiniFat = le32(h, 0x3C)
        val nMiniFat = le32(h, 0x40)
        val firstDifat = le32(h, 0x44)
        val nDifat = le32(h, 0x48)
        val maxSectors = ((fileLen + sectorSize - 1) / sectorSize).toInt()
        if (nFat < 0 || nFat > maxSectors) throw PptxException(BROKEN)

        // Номера секторов FAT: первые 109 в заголовке, остальные — в цепочке секторов DIFAT.
        val fatSectors = ArrayList<Int>(nFat)
        for (i in 0 until 109) { if (fatSectors.size >= nFat) break; fatSectors += le32(h, 0x4C + i * 4) }
        var d = firstDifat
        var guard = 0
        val per = sectorSize / 4 - 1
        val buf = ByteArray(sectorSize)
        while (fatSectors.size < nFat && d >= 0 && guard++ <= nDifat.coerceIn(0, maxSectors)) {
            if (!readSector(d, buf)) break
            for (i in 0 until per) { if (fatSectors.size >= nFat) break; fatSectors += le32(buf, i * 4) }
            d = le32(buf, per * 4)
        }
        val perSector = sectorSize / 4
        // FAT не длиннее числа секторов в файле: ложный заголовок не раздувает память.
        fat = IntArray(minOf(fatSectors.size.toLong() * perSector, maxSectors.toLong() + perSector).toInt()) { FREE }
        for ((k, s) in fatSectors.withIndex()) {
            if (k * perSector >= fat.size) break
            if (!readSector(s, buf)) continue
            for (i in 0 until perSector) { val j = k * perSector + i; if (j < fat.size) fat[j] = le32(buf, i * 4) }
        }

        entries = run {
            val dir = readChain(chain(firstDir, fat), Long.MAX_VALUE, MAX_DIR)
            (0 until dir.size / 128).map { i ->
                val o = i * 128
                val nameLen = le16(dir, o + 64).coerceIn(0, 64)
                val name = String(dir, o, (nameLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
                val size = if (sectorShift == 9) le32(dir, o + 120).toLong() and 0xFFFFFFFFL else le64(dir, o + 120)
                Entry(name, dir[o + 66].toInt() and 0xFF, le32(dir, o + 68), le32(dir, o + 72), le32(dir, o + 76), le32(dir, o + 116), size)
            }
        }
        if (entries.isEmpty() || entries[0].type != 5) throw PptxException(BROKEN)
        miniFat = run {
            if (nMiniFat <= 0 || firstMiniFat < 0) return@run IntArray(0)
            val bytes = readChain(chain(firstMiniFat, fat), nMiniFat.toLong() * sectorSize, MAX_DIR)
            IntArray(bytes.size / 4) { le32(bytes, it * 4) }
        }
    }

    /** Потоки корня по имени (без учёта регистра, как в спецификации). */
    private val rootStreams: Map<String, Entry> by lazy {
        val out = mutableMapOf<String, Entry>()
        val seen = HashSet<Int>()
        val stack = ArrayDeque<Int>()
        entries[0].child.takeIf { it in entries.indices }?.let { stack += it }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (!seen.add(i) || i !in entries.indices) continue
            val e = entries[i]
            out.putIfAbsent(e.name.uppercase(), e)
            if (e.left in entries.indices) stack += e.left
            if (e.right in entries.indices) stack += e.right
        }
        out
    }

    fun has(name: String) = rootStreams.containsKey(name.uppercase())

    fun size(name: String): Long = rootStreams[name.uppercase()]?.takeIf { it.type == 2 }?.size ?: -1

    /** Весь поток или null; больше [limit] байт — ошибка. */
    fun stream(name: String, limit: Long = MAX_STREAM): ByteArray? {
        val e = rootStreams[name.uppercase()]?.takeIf { it.type == 2 } ?: return null
        if (e.size > limit) throw PptxException(TOO_BIG)
        return read(e, 0, e.size)
    }

    /** Часть потока: [len] байт с [offset]. */
    fun read(name: String, offset: Long, len: Int): ByteArray? {
        val e = rootStreams[name.uppercase()]?.takeIf { it.type == 2 } ?: return null
        if (offset < 0 || len < 0 || offset >= e.size) return null
        return read(e, offset, minOf(len.toLong(), e.size - offset))
    }

    private fun read(e: Entry, offset: Long, len: Long): ByteArray {
        if (e.size < miniCutoff) {
            val mini = miniChain ?: chain(entries[0].start, fat).also { miniChain = it }
            val chain = chain(e.start, miniFat)
            // Размер из каталога не больше настоящей цепочки: ложный размер не раздувает память.
            val out = ByteArray(minOf(len, chain.size.toLong() * miniSize - offset).coerceAtLeast(0).toInt())
            var done = 0
            while (done < out.size) {
                val pos = offset + done
                val k = (pos / miniSize).toInt()
                if (k >= chain.size) break
                val inSector = (pos % miniSize).toInt()
                val n = minOf(miniSize - inSector, out.size - done)
                // Малый сектор лежит в малом потоке, а тот — в обычных секторах.
                val mpos = chain[k].toLong() * miniSize + inSector
                val big = (mpos / sectorSize).toInt()
                if (big >= mini.size) break
                if (!readAt(sectorOffset(mini[big]) + mpos % sectorSize, out, done, n)) break
                done += n
            }
            return if (done == out.size) out else out.copyOf(done)
        }
        val chain = chain(e.start, fat)
        val out = ByteArray(minOf(len, chain.size.toLong() * sectorSize - offset).coerceAtLeast(0).toInt())
        var done = 0
        while (done < out.size) {
            val pos = offset + done
            val k = (pos / sectorSize).toInt()
            if (k >= chain.size) break
            val inSector = (pos % sectorSize).toInt()
            val n = minOf(sectorSize - inSector, out.size - done)
            if (!readAt(sectorOffset(chain[k]) + inSector, out, done, n)) break
            done += n
        }
        return if (done == out.size) out else out.copyOf(done)
    }

    /** Цепочка секторов; петля или выход за таблицу обрывают её. */
    private fun chain(start: Int, table: IntArray): IntArray {
        val out = ArrayList<Int>()
        var s = start
        val seen = HashSet<Int>()
        while (s >= 0 && s < table.size && seen.add(s)) { out += s; s = table[s] }
        // Последний сектор может не попасть в таблицу, если её урезали: берём его как есть.
        if (s >= 0 && s !in seen && out.isEmpty()) out += s
        return out.toIntArray()
    }

    private fun readChain(chain: IntArray, limit: Long, cap: Long): ByteArray {
        val n = minOf(chain.size.toLong() * sectorSize, limit, cap).toInt()
        val out = ByteArray(n)
        var done = 0
        for (s in chain) {
            if (done >= n) break
            val k = minOf(sectorSize, n - done)
            if (!readAt(sectorOffset(s), out, done, k)) break
            done += k
        }
        return if (done == n) out else out.copyOf(done)
    }

    private fun sectorOffset(s: Int) = (s.toLong() + 1) shl sectorShift

    private fun readSector(s: Int, buf: ByteArray) = s >= 0 && readAt(sectorOffset(s), buf, 0, sectorSize)

    private fun readAt(pos: Long, buf: ByteArray, off: Int, n: Int): Boolean {
        if (pos < 0 || pos + n > fileLen) {
            // Хвост файла бывает обрезан: читаем что есть, остальное — нули.
            if (pos >= fileLen || pos < 0) return false
            raf.seek(pos); raf.readFully(buf, off, (fileLen - pos).toInt())
            return true
        }
        raf.seek(pos); raf.readFully(buf, off, n)
        return true
    }

    override fun close() = raf.close()

    companion object {
        private const val SIGNATURE = -0x1ee54e5e1fee3030L // D0 CF 11 E0 A1 B1 1A E1
        private const val FREE = -1
        private const val MAX_STREAM = 512L shl 20
        private const val MAX_DIR = 64L shl 20
        const val NOT_PPT = "Это не презентация PowerPoint (PPT)"
        const val BROKEN = "Файл PPT повреждён: его структура не читается"
        const val TOO_BIG = "Презентация PPT слишком большая для встроенного перевода"

        fun open(file: File): Cfb {
            val raf = RandomAccessFile(file, "r")
            return try { Cfb(raf) } catch (e: Throwable) { raf.close(); throw e }
        }

        /** Составной файл ли это (по первым байтам). */
        fun isCompound(file: File): Boolean = runCatching {
            RandomAccessFile(file, "r").use { r -> if (r.length() < 8) false else { val b = ByteArray(8); r.readFully(b); le64(b, 0) == SIGNATURE } }
        }.getOrDefault(false)

        fun le16(b: ByteArray, o: Int): Int = if (o < 0 || o + 2 > b.size) 0 else (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun le32(b: ByteArray, o: Int): Int = if (o < 0 || o + 4 > b.size) 0 else
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
        fun le64(b: ByteArray, o: Int): Long = (le32(b, o).toLong() and 0xFFFFFFFFL) or (le32(b, o + 4).toLong() shl 32)
    }
}
