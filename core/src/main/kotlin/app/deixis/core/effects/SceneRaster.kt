package app.deixis.core.effects

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Покрытие пикселей фигурой, 0…1, с сглаживанием краёв (4 подстроки на пиксель, точное покрытие по горизонтали).
 * Объединение фигур — максимум. Область, где что-то записано, хранится ([minX]…[maxY]): очистка и проход по покрытию
 * не трогают весь кадр.
 */
internal class Coverage(val w: Int, val h: Int) {
    val a = FloatArray(w * h)
    var minX = w; var minY = h; var maxX = -1; var maxY = -1
        private set
    private val row = FloatArray(w + 2)
    private var xs = DoubleArray(64)

    val empty: Boolean get() = maxX < minX

    fun clear() {
        if (empty) return
        for (y in minY..maxY) java.util.Arrays.fill(a, y * w + minX, y * w + maxX + 1, 0f)
        minX = w; minY = h; maxX = -1; maxY = -1
    }

    private fun touch(x0: Int, y0: Int, x1: Int, y1: Int) {
        if (x0 < minX) minX = x0
        if (y0 < minY) minY = y0
        if (x1 > maxX) maxX = x1
        if (y1 > maxY) maxY = y1
    }

    /** Закрасить многоугольник [pts] (x, y, x, y, …) в пикселях; перекрытие с прежним — максимум, [weight] — сила. */
    fun fillPoly(pts: DoubleArray, weight: Float = 1f) {
        val n = pts.size / 2
        if (n < 3) return
        var ymin = Double.MAX_VALUE; var ymax = -Double.MAX_VALUE; var xmin = Double.MAX_VALUE; var xmax = -Double.MAX_VALUE
        for (i in 0 until n) {
            val x = pts[i * 2]; val y = pts[i * 2 + 1]
            if (x < xmin) xmin = x; if (x > xmax) xmax = x; if (y < ymin) ymin = y; if (y > ymax) ymax = y
        }
        if (xmax < 0 || ymax < 0 || xmin >= w || ymin >= h || !xmin.isFinite() || !ymin.isFinite() || !xmax.isFinite() || !ymax.isFinite()) return
        val bx0 = max(0, floor(xmin).toInt()); val bx1 = min(w - 1, floor(xmax).toInt())
        val y0 = max(0, floor(ymin).toInt()); val y1 = min(h - 1, floor(ymax).toInt())
        if (bx1 < bx0 || y1 < y0) return
        if (xs.size < n) xs = DoubleArray(n * 2)
        val xsl = xs
        for (py in y0..y1) {
            java.util.Arrays.fill(row, bx0, bx1 + 2, 0f)
            for (s in 0 until SUB) {
                val y = py + (s + 0.5) / SUB
                var k = 0
                var j = n - 1
                for (i in 0 until n) {
                    val ay = pts[j * 2 + 1]; val by = pts[i * 2 + 1]
                    if ((ay <= y) != (by <= y)) {
                        val ax = pts[j * 2]; val bx = pts[i * 2]
                        xsl[k++] = ax + (y - ay) * (bx - ax) / (by - ay)
                    }
                    j = i
                }
                // сортировка вставками: пересечений мало
                for (i in 1 until k) { val v = xsl[i]; var m = i - 1; while (m >= 0 && xsl[m] > v) { xsl[m + 1] = xsl[m]; m-- }; xsl[m + 1] = v }
                var i = 0
                while (i + 1 < k) { span(xsl[i], xsl[i + 1], bx0, bx1); i += 2 }
            }
            val base = py * w
            var any = false
            for (x in bx0..bx1) {
                val v = min(1f, row[x]) * weight
                if (v > a[base + x]) { a[base + x] = v; any = true }
            }
            if (any) touch(bx0, py, bx1, py)
        }
    }

    private fun span(xa0: Double, xb0: Double, bx0: Int, bx1: Int) {
        val xa = max(xa0, bx0.toDouble()); val xb = min(xb0, (bx1 + 1).toDouble())
        if (xb <= xa) return
        val ia = xa.toInt(); val ib = xb.toInt()
        if (ia == ib) { row[ia] += ((xb - xa) * INV_SUB).toFloat(); return }
        row[ia] += ((ia + 1 - xa) * INV_SUB).toFloat()
        for (i in ia + 1 until ib) row[i] += INV_SUB.toFloat()
        if (ib <= bx1) row[ib] += ((xb - ib) * INV_SUB).toFloat()
    }

    /** Отрезок толщиной [width] (скруглённые концы не рисуются, концы чуть удлинены, чтобы стыки не рвались). */
    fun segment(x0: Double, y0: Double, x1: Double, y1: Double, width: Double, weight: Float = 1f) {
        val dx = x1 - x0; val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len < 1e-6) return
        val ux = dx / len; val uy = dy / len
        val hw = max(width, 1.0) / 2
        val e = hw * 0.5
        val nx = -uy * hw; val ny = ux * hw
        fillPoly(doubleArrayOf(x0 - ux * e + nx, y0 - uy * e + ny, x1 + ux * e + nx, y1 + uy * e + ny, x1 + ux * e - nx, y1 + uy * e - ny, x0 - ux * e - nx, y0 - uy * e - ny), weight)
    }

    /** Линия вдоль ломаной [pts]; [closed] — замкнуть. */
    fun polyline(pts: DoubleArray, closed: Boolean, width: Double, weight: Float = 1f) {
        val n = pts.size / 2
        for (i in 0 until (if (closed) n else n - 1)) {
            val j = (i + 1) % n
            segment(pts[i * 2], pts[i * 2 + 1], pts[j * 2], pts[j * 2 + 1], width, weight)
        }
    }

    companion object {
        const val SUB = 4
        const val INV_SUB = 1.0 / SUB
    }
}

/**
 * Слой с прозрачностью: цвет в предумноженном виде (r, g, b, a), 0…1, кадровый размер. Область записи отмечается, поэтому
 * очистка и наложение на кадр идут только по ней.
 */
internal class Surface(val w: Int, val h: Int) {
    val px = FloatArray(w * h * 4)
    var x0 = w; var y0 = h; var x1 = -1; var y1 = -1

    val empty: Boolean get() = x1 < x0

    fun clear() {
        if (empty) return
        for (y in y0..y1) java.util.Arrays.fill(px, (y * w + x0) * 4, (y * w + x1 + 1) * 4, 0f)
        x0 = w; y0 = h; x1 = -1; y1 = -1
    }

    fun touch(ax0: Int, ay0: Int, ax1: Int, ay1: Int) {
        if (ax0 < x0) x0 = ax0
        if (ay0 < y0) y0 = ay0
        if (ax1 > x1) x1 = ax1
        if (ay1 > y1) y1 = ay1
    }

    /** Закрасить по покрытию [c] цветом (r, g, b) с силой [alpha]. */
    fun paint(c: Coverage, r: Float, g: Float, b: Float, alpha: Float) {
        if (c.empty || alpha <= 0f) return
        for (y in c.minY..c.maxY) {
            var i = y * w + c.minX
            for (x in c.minX..c.maxX) {
                val cv = c.a[i]
                if (cv > 0.002f) {
                    val s = cv * alpha
                    val o = i * 4
                    val k = 1f - s
                    px[o] = r * s + px[o] * k; px[o + 1] = g * s + px[o + 1] * k; px[o + 2] = b * s + px[o + 2] * k; px[o + 3] = s + px[o + 3] * k
                }
                i++
            }
        }
        touch(c.minX, c.minY, c.maxX, c.maxY)
    }

    /** Закрасить один пиксель поверх (предумноженно). */
    fun over(x: Int, y: Int, r: Float, g: Float, b: Float, s: Float) {
        if (x < 0 || y < 0 || x >= w || y >= h || s <= 0f) return
        val o = (y * w + x) * 4
        val k = 1f - s
        px[o] = r * s + px[o] * k; px[o + 1] = g * s + px[o + 1] * k; px[o + 2] = b * s + px[o + 2] * k; px[o + 3] = s + px[o + 3] * k
        if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
    }

    fun touchAll() { x0 = 0; y0 = 0; x1 = w - 1; y1 = h - 1 }

    fun copyFrom(o: Surface) {
        System.arraycopy(o.px, 0, px, 0, px.size)
        x0 = o.x0; y0 = o.y0; x1 = o.x1; y1 = o.y1
    }
}
