package app.deixis.core.airplay

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Двоичный список свойств Apple (bplist00): в нём iPhone и Mac передают запросы AirPlay.
 * Написан по открытому описанию формата; читает словари, массивы, строки, данные, числа и логические значения.
 * Значения: Map<String, Any?>, List<Any?>, String, ByteArray, Long, Double, Boolean.
 */
object Bplist {
    private val MAGIC = "bplist00".toByteArray()

    /** Разбирает список; null — не bplist или он повреждён. Глубина и размер ограничены: данные приходят из сети. */
    fun parse(bytes: ByteArray): Any? = runCatching { Reader(bytes).root() }.getOrNull()

    fun write(root: Any?): ByteArray = Writer().write(root)

    private class Reader(val b: ByteArray) {
        val offsetSize: Int
        val refSize: Int
        val count: Int
        val top: Int
        val tableAt: Int

        init {
            require(b.size >= MAGIC.size + 32 && b.copyOfRange(0, MAGIC.size).contentEquals(MAGIC))
            val t = ByteBuffer.wrap(b, b.size - 32, 32)
            t.position(t.position() + 6)
            offsetSize = t.get().toInt()
            refSize = t.get().toInt()
            val c = t.getLong(); val tp = t.getLong(); val ta = t.getLong()
            require(offsetSize in 1..8 && refSize in 1..8 && c in 1..MAX_OBJECTS && tp in 0 until c && ta in 8 until b.size)
            count = c.toInt(); top = tp.toInt(); tableAt = ta.toInt()
            require(tableAt.toLong() + count.toLong() * offsetSize <= b.size - 32)
        }

        fun root(): Any? = obj(top, 0)

        fun uint(at: Int, size: Int): Long {
            require(at >= 0 && at + size <= b.size)
            var v = 0L
            for (i in 0 until size) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return v
        }

        fun offset(ref: Int): Int {
            require(ref in 0 until count)
            val o = uint(tableAt + ref * offsetSize, offsetSize)
            require(o in 8 until tableAt)
            return o.toInt()
        }

        /** Длина из младших 4 бит маркера или из следующего за ним целого. Возвращает длину и начало содержимого. */
        fun length(at: Int): Pair<Int, Int> {
            val low = b[at].toInt() and 0x0F
            if (low != 0x0F) return low to at + 1
            val m = b[at + 1].toInt() and 0xFF
            require(m shr 4 == 1)
            val size = 1 shl (m and 0x0F)
            val n = uint(at + 2, size)
            require(n in 0..b.size)
            return n.toInt() to at + 2 + size
        }

        fun obj(ref: Int, depth: Int): Any? {
            require(depth < MAX_DEPTH)
            val at = offset(ref)
            val marker = b[at].toInt() and 0xFF
            return when (marker shr 4) {
                0x0 -> when (marker) { 0x08 -> false; 0x09 -> true; else -> null }
                0x1 -> {
                    val size = 1 shl (marker and 0x0F)
                    require(size <= 8)
                    val v = uint(at + 1, size)
                    // Восьмибайтное целое знаковое, меньшие — беззнаковые.
                    v
                }
                0x2 -> {
                    val size = 1 shl (marker and 0x0F)
                    when (size) {
                        4 -> java.lang.Float.intBitsToFloat(uint(at + 1, 4).toInt()).toDouble()
                        8 -> java.lang.Double.longBitsToDouble(uint(at + 1, 8))
                        else -> error("real")
                    }
                }
                0x3 -> java.lang.Double.longBitsToDouble(uint(at + 1, 8))
                0x4 -> { val (n, s) = length(at); require(s + n <= b.size); b.copyOfRange(s, s + n) }
                0x5 -> { val (n, s) = length(at); require(s + n <= b.size); String(b, s, n, Charsets.US_ASCII) }
                0x6 -> { val (n, s) = length(at); require(s + 2 * n <= b.size); String(b, s, 2 * n, Charsets.UTF_16BE) }
                0x8 -> uint(at + 1, (marker and 0x0F) + 1)
                0xA -> {
                    val (n, s) = length(at)
                    require(n <= MAX_OBJECTS)
                    List(n) { obj(uint(s + it * refSize, refSize).toInt(), depth + 1) }
                }
                0xD -> {
                    val (n, s) = length(at)
                    require(n <= MAX_OBJECTS)
                    val out = LinkedHashMap<String, Any?>()
                    for (i in 0 until n) {
                        val k = obj(uint(s + i * refSize, refSize).toInt(), depth + 1) as? String ?: continue
                        out[k] = obj(uint(s + (n + i) * refSize, refSize).toInt(), depth + 1)
                    }
                    out
                }
                else -> null
            }
        }
    }

    private class Writer {
        val objects = ArrayList<ByteArray>()

        fun write(root: Any?): ByteArray {
            add(root)
            val out = ByteArrayOutputStream()
            out.write(MAGIC)
            val offsets = IntArray(objects.size)
            objects.forEachIndexed { i, o -> offsets[i] = out.size(); out.write(o) }
            val tableAt = out.size()
            val offsetSize = pow2(bytesFor(tableAt.toLong()))
            offsets.forEach { putUint(out, it.toLong(), offsetSize) }
            val trailer = ByteBuffer.allocate(32)
            trailer.position(6)
            trailer.put(offsetSize.toByte()).put(REF_SIZE.toByte())
            trailer.putLong(objects.size.toLong()).putLong(0).putLong(tableAt.toLong())
            out.write(trailer.array())
            return out.toByteArray()
        }

        /** Добавляет объект и возвращает его номер. Номера двухбайтные: ответам AirPlay их хватает с запасом. */
        fun add(v: Any?): Int {
            val idx = objects.size
            check(idx < 0xFFFF) { "bplist: слишком много объектов" }
            objects.add(ByteArray(0))
            val o = ByteArrayOutputStream()
            when (v) {
                null -> o.write(0x00)
                is Boolean -> o.write(if (v) 0x09 else 0x08)
                is Int, is Long, is Short -> {
                    val n = (v as Number).toLong()
                    if (n < 0) { o.write(0x13); putUint(o, n, 8) } else {
                        val size = when { n <= 0xFF -> 1; n <= 0xFFFF -> 2; n <= 0xFFFFFFFFL -> 4; else -> 8 }
                        o.write(0x10 or Integer.numberOfTrailingZeros(size)); putUint(o, n, size)
                    }
                }
                is Double, is Float -> { o.write(0x23); putUint(o, java.lang.Double.doubleToLongBits((v as Number).toDouble()), 8) }
                is ByteArray -> { marker(o, 0x40, v.size); o.write(v) }
                is String -> {
                    if (v.all { it.code < 0x80 }) { marker(o, 0x50, v.length); o.write(v.toByteArray(Charsets.US_ASCII)) }
                    else { marker(o, 0x60, v.length); o.write(v.toByteArray(Charsets.UTF_16BE)) }
                }
                is List<*> -> {
                    val refs = v.map { add(it) }
                    marker(o, 0xA0, refs.size); refs.forEach { putUint(o, it.toLong(), REF_SIZE) }
                }
                is Map<*, *> -> {
                    val keys = v.keys.map { add(it.toString()) }
                    val values = v.values.map { add(it) }
                    marker(o, 0xD0, keys.size)
                    keys.forEach { putUint(o, it.toLong(), REF_SIZE) }
                    values.forEach { putUint(o, it.toLong(), REF_SIZE) }
                }
                else -> error("bplist: ${v::class.simpleName}")
            }
            objects[idx] = o.toByteArray()
            return idx
        }

        fun marker(o: ByteArrayOutputStream, kind: Int, n: Int) {
            if (n < 0x0F) o.write(kind or n) else {
                o.write(kind or 0x0F)
                val size = pow2(bytesFor(n.toLong()))
                o.write(0x10 or Integer.numberOfTrailingZeros(size)); putUint(o, n.toLong(), size)
            }
        }

        /** Размеры целых в bplist — 1, 2, 4 или 8 байт. */
        fun pow2(n: Int): Int = when { n <= 1 -> 1; n == 2 -> 2; n <= 4 -> 4; else -> 8 }

        fun bytesFor(n: Long): Int = when { n <= 0xFF -> 1; n <= 0xFFFF -> 2; n <= 0xFFFFFF -> 3; n <= 0xFFFFFFFFL -> 4; else -> 8 }

        fun putUint(o: ByteArrayOutputStream, v: Long, size: Int) {
            for (i in size - 1 downTo 0) o.write(((v ushr (8 * i)) and 0xFF).toInt())
        }

        companion object { const val REF_SIZE = 2 }
    }

    private const val MAX_OBJECTS = 100_000L
    private const val MAX_DEPTH = 32
}
