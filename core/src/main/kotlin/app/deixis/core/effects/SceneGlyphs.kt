package app.deixis.core.effects

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Контур образа в единицах «радиус = 1» (центр в нуле, ось y вниз). Заливка — объединение [pts] всех контуров;
 * [carve] > 0 — вырез: уменьшает заливку под собой (внутренняя тень пламени); [strokeW] > 0 — линия вдоль контура
 * толщиной в тех же единицах (нимб ангела), а не заливка.
 */
class GlyphPoly(val pts: DoubleArray, val carve: Double = 0.0, val strokeW: Double = 0.0, val closed: Boolean = true)

/**
 * Векторные образы сцены: крест, голубь, ангел, пламя, звезда, книга и простые фигуры. Те же контуры, что в прототипе
 * (`prototype/effects-scene.html`), поэтому картинка совпадает. Бизье переводятся в ломаные один раз при загрузке.
 */
object SceneGlyphs {
    private const val CURVE_STEPS = 12

    private class PB {
        val pts = ArrayList<Double>()
        private var cx = 0.0
        private var cy = 0.0
        fun m(x: Double, y: Double): PB { pts += x; pts += y; cx = x; cy = y; return this }
        fun l(x: Double, y: Double): PB { pts += x; pts += y; cx = x; cy = y; return this }
        fun c(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double): PB {
            val x0 = cx; val y0 = cy
            for (i in 1..CURVE_STEPS) {
                val t = i.toDouble() / CURVE_STEPS; val u = 1 - t
                pts += u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x
                pts += u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y
            }
            cx = x; cy = y
            return this
        }
        fun arc(ox: Double, oy: Double, rx: Double, ry: Double, a0: Double, a1: Double, steps: Int = 28): PB {
            for (i in 0..steps) { val a = a0 + (a1 - a0) * i / steps; pts += ox + rx * cos(a); pts += oy + ry * sin(a) }
            cx = pts[pts.size - 2]; cy = pts[pts.size - 1]
            return this
        }
        fun build(carve: Double = 0.0, strokeW: Double = 0.0, closed: Boolean = true) = GlyphPoly(pts.toDoubleArray(), carve, strokeW, closed)
    }

    private fun poly(vararg xy: Double): GlyphPoly = GlyphPoly(xy)
    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) = poly(x0, y0, x1, y0, x1, y1, x0, y1)
    private fun circle(x: Double, y: Double, r: Double, n: Int = 32) = PB().arc(x, y, r, r, 0.0, 2 * PI, n).build()

    private fun wing(s: Double): GlyphPoly = PB()
        .m(s * .1, -.38).c(s * .45, -.95, s * .95, -.95, s * 1.05, -.5).c(s * 1.1, -.25, s * .9, -.05, s * .78, .02)
        .l(s * .82, .12).l(s * .62, .1).l(s * .66, .22).l(s * .46, .18).l(s * .48, .3).l(s * .28, .2).l(s * .2, .0).build()

    private val cross = listOf(rect(-.1, -1.0, .1, 1.05), rect(-.48, -.54, .48, -.35))

    private val dove = listOf(
        circle(-.5, -.2, .13, 20),
        poly(-.61, -.26, -.84, -.15, -.61, -.11),
        PB().m(-.42, -.1).c(-.1, -.05, .3, .0, .62, .2).l(1.0, .46).l(.9, .5).l(1.02, .6).l(.88, .62).l(.96, .74).l(.55, .56)
            .c(.3, .5, .1, .5, -.15, .46).c(-.45, .4, -.62, .2, -.6, -.06).build(),
        poly(.12, .02, .3, -.3, .7, -.52, 1.0, -.46, .88, -.36, .98, -.28, .8, -.24, .86, -.14, .68, -.1, .72, .0, .5, .1),
        PB().m(-.22, .02).c(-.3, -.45, .08, -.85, .56, -1.0).l(.42, -.86).l(.54, -.74).l(.34, -.7).l(.46, -.58).l(.26, -.52)
            .l(.38, -.4).l(.18, -.34).l(.28, -.22).l(.08, -.16).l(.14, -.02).l(.3, .1).build(),
    )

    private val angel = listOf(
        wing(1.0), wing(-1.0),
        PB().arc(0.0, -.78, .2, .065, 0.0, 2 * PI, 36).build(strokeW = .045),
        circle(0.0, -.6, .13, 24),
        PB().m(-.1, -.46).l(.1, -.46).c(.2, -.4, .24, -.3, .3, -.12).l(.52, .1).l(.4, .16).l(.22, -.02)
            .c(.26, .3, .36, .6, .5, .95).l(-.5, .95).c(-.36, .6, -.26, .3, -.22, -.02).l(-.4, .16).l(-.52, .1).l(-.3, -.12)
            .c(-.24, -.3, -.2, -.4, -.1, -.46).build(),
    )

    private val flame = listOf(
        PB().m(.02, -1.05).c(.12, -.72, .52, -.5, .52, -.02).c(.52, .4, .28, .7, .0, .7).c(-.3, .7, -.52, .42, -.52, -.02)
            .c(-.52, -.3, -.38, -.42, -.32, -.62).c(-.18, -.52, -.1, -.72, .02, -1.05).build(),
        PB().m(.02, -.42).c(.08, -.24, .3, -.12, .3, .12).c(.3, .38, .16, .55, .0, .55).c(-.17, .55, -.3, .38, -.3, .12)
            .c(-.3, -.05, -.18, -.1, -.14, -.25).c(-.07, -.2, -.04, -.3, .02, -.42).build(carve = 0.5),
    )

    private val starburst: List<GlyphPoly> = run {
        val p = ArrayList<Double>()
        for (i in 0 until 16) {
            val a = -PI / 2 + i * PI / 8
            val outer = i % 2 == 0
            val k = i / 2
            val r = if (outer) (if (k % 2 == 0) (if (k % 4 == 0) 1.05 else .78) else .46) else .13
            p += cos(a) * r; p += sin(a) * r
        }
        listOf(GlyphPoly(p.toDoubleArray()))
    }

    private val book = listOf(
        PB().m(.0, .32).c(-.3, .14, -.7, .12, -.95, .26).l(-.95, -.5).c(-.7, -.62, -.3, -.6, .0, -.34).c(.3, -.6, .7, -.62, .95, -.5)
            .l(.95, .26).c(.7, .12, .3, .14, .0, .32).build(),
        PB().m(-1.0, .36).c(-.7, .22, -.3, .22, .0, .46).c(.3, .22, .7, .22, 1.0, .36).l(1.0, .44).c(.7, .3, .3, .3, .0, .54)
            .c(-.3, .3, -.7, .3, -1.0, .44).build(),
    )

    private val heart = listOf(PB().m(.0, .9).c(-1.25, .1, -.9, -.85, .0, -.35).c(.9, -.85, 1.25, .1, .0, .9).build())
    private val drop = listOf(
        PB().m(.0, -1.0).c(.2, -.5, .8, -.1, .8, .4).c(.8, .8, .45, 1.0, .0, 1.0).c(-.45, 1.0, -.8, .8, -.8, .4).c(-.8, -.1, -.2, -.5, .0, -1.0).build(),
    )
    private val moon = listOf(PB().arc(0.0, 0.0, 1.0, 1.0, .6, 2 * PI - .6, 40).arc(.45, 0.0, .82, .82, 2 * PI - .95, .95, 32).build())
    private val rectGlyph = listOf(rect(-1.0, -.7, 1.0, .7))
    private val circleGlyph = listOf(circle(0.0, 0.0, 1.0, 48))
    /** Кольцо рисуется линией по окружности; толщину задаёт слой, здесь контур окружности. */
    private val ring = listOf(PB().arc(0.0, 0.0, 1.0, 1.0, 0.0, 2 * PI, 48).build(strokeW = -1.0))

    /** Наборы контуров образа [kind]; [n] — число углов многоугольника и звезды. null — неизвестная форма. */
    fun of(kind: String, n: Int = 5): List<GlyphPoly>? = when (kind) {
        "cross" -> cross; "dove" -> dove; "angel" -> angel; "flame" -> flame; "starburst" -> starburst; "book" -> book
        "heart" -> heart; "drop" -> drop; "moon" -> moon; "rect" -> rectGlyph; "circle" -> circleGlyph; "ring" -> ring
        "polygon" -> listOf(regular(n, false)); "star" -> listOf(regular(n, true))
        else -> null
    }

    /** Правильный многоугольник или звезда с лучами (внутренний радиус 0,45), вершина смотрит вверх. */
    private fun regular(n: Int, star: Boolean): GlyphPoly {
        val m = if (star) n * 2 else n
        val p = DoubleArray(m * 2)
        for (i in 0 until m) {
            val a = -PI / 2 + i * 2 * PI / m
            val r = if (star && i % 2 == 1) .45 else 1.0
            p[i * 2] = cos(a) * r; p[i * 2 + 1] = sin(a) * r
        }
        return GlyphPoly(p)
    }

    /** Образы-символы с готовым внутренним вырезом или линией: у них нет «обводки по контуру» как у фигур. */
    val SYMBOLS: Set<String> = setOf("cross", "dove", "angel", "flame", "starburst", "book")
}
