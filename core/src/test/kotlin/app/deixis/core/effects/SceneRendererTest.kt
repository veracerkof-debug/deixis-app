package app.deixis.core.effects

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SceneRendererTest {
    private val W = 640
    private val H = 360

    private fun scene(layers: String, extra: String = "") =
        SceneValidator.validate("""{"seed":3,$extra"layers":[$layers]}""").also { assertTrue(it.ok, it.issues.toString()) }.scene!!

    private fun lum(p: Int) = (0.299 * ((p shr 16) and 255) + 0.587 * ((p shr 8) and 255) + 0.114 * (p and 255)) / 255.0

    /** Средняя яркость прямоугольника в долях кадра. */
    private fun mean(px: IntArray, x0: Double, y0: Double, x1: Double, y1: Double): Double {
        var s = 0.0; var n = 0
        for (y in (y0 * H).toInt() until (y1 * H).toInt()) for (x in (x0 * W).toInt() until (x1 * W).toInt()) { s += lum(px[y * W + x]); n++ }
        return s / n
    }

    private fun draw(s: Scene, steps: Int = 30, energy: Double = 0.75, text: TextBox? = null, setup: SceneRenderer.() -> Unit = {}): IntArray {
        val r = SceneRenderer(s, W, H, softWidth = 160)
        r.setup()
        r.jumpEnergy(energy)
        repeat(steps) { r.advance(1.0 / 30, energy) }
        val out = IntArray(W * H)
        r.render(out, text)
        return out
    }

    @Test
    fun everyBuiltInLookRendersADeterministicNonBlankFrame() {
        val dump = System.getProperty("deixis.sceneDump")?.let { File(it).apply { mkdirs() } }
        for (look in SceneLooks.all()) {
            val a = draw(look); val b = draw(look)
            assertTrue(a.contentEquals(b), "${look.id}: кадры из одних и тех же вызовов должны совпадать")
            val avg = mean(a, 0.0, 0.0, 1.0, 1.0)
            assertTrue(avg in 0.03..0.55, "${look.id}: средняя яркость $avg")
            // не однотонный: есть и тёмное, и светлое
            val lums = a.map { lum(it) }
            assertTrue(lums.max() - lums.min() > 0.02, "${look.id}: кадр совсем плоский")
            assertTrue(a.all { (it ushr 24) == 0xFF }, "${look.id}: кадр непрозрачный")
            dump?.let { d ->
                val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); img.setRGB(0, 0, W, H, a, 0, W)
                ImageIO.write(img, "png", File(d, "${look.id}.png"))
            }
        }
    }

    @Test
    fun differentTimeGivesDifferentFramesSoThePictureLives() {
        val look = SceneLooks.load("joy")
        val r = SceneRenderer(look, W, H, 160)
        r.jumpEnergy(0.8)
        val a = IntArray(W * H); val b = IntArray(W * H)
        repeat(10) { r.advance(1.0 / 30, 0.8) }; r.render(a)
        repeat(30) { r.advance(1.0 / 30, 0.8) }; r.render(b)
        var diff = 0
        for (i in a.indices) if (a[i] != b[i]) diff++
        assertTrue(diff > a.size / 4, "картинка должна двигаться: изменилось $diff пикселей")
    }

    @Test
    fun theImageAppearsWhereItIsPlacedAndOnlyThere() {
        val s = scene("""{"type":"gradient","p":{"c1":"#000000","c2":"#000000"}},
            {"type":"shape","id":"s","blend":"source-over","p":{"kind":"cross","x":0.75,"y":0.5,"size":0.5,"alpha":1,"c":2.5}}""")
        val px = draw(s)
        val on = mean(px, 0.71, 0.3, 0.79, 0.7)    // вертикаль креста
        val off = mean(px, 0.05, 0.05, 0.4, 0.95)  // левая часть пуста
        assertTrue(on > 0.12, "крест виден: $on")
        assertTrue(off < 0.005, "слева пусто: $off")
    }

    @Test
    fun avoidFadesALayerBehindTheText() {
        fun frame(avoid: Double): IntArray = draw(scene(
            """{"type":"gradient","p":{"c1":"#000000","c2":"#000000"}},
               {"type":"shape","id":"s","blend":"source-over","p":{"kind":"circle","x":0.34,"y":0.45,"size":0.8,"alpha":1,"c":2.5,"avoid":$avoid}}"""),
            text = TextBox(0.34, 0.45, 0.2, 0.12))
        val plain = frame(0.0); val dim = frame(1.0)
        val inText = mean(plain, 0.3, 0.4, 0.38, 0.5) to mean(dim, 0.3, 0.4, 0.38, 0.5)
        assertTrue(inText.second < inText.first * 0.35, "под текстом слой гасится: ${inText.first} → ${inText.second}")
        // у края зоны слой остаётся: гашение не плоское, а плавное
        val edgePlain = mean(plain, 0.5, 0.45, 0.55, 0.55); val edgeDim = mean(dim, 0.5, 0.45, 0.55, 0.55)
        assertTrue(edgeDim > inText.second, "край зоны гасится слабее центра")
        assertTrue(edgeDim <= edgePlain + 1e-6)
    }

    @Test
    fun bindingsToEnergyChangeThePicture() {
        val s = scene("""{"type":"gradient","p":{"c1":"#000000","c2":"#000000"}},
            {"type":"shape","id":"s","blend":"source-over","p":{"kind":"circle","size":0.1,"alpha":1,"c":2.5},"bind":{"size":{"src":"energy","amt":0.4}}}""")
        fun lit(e: Double) = draw(s, energy = e).count { lum(it) > 0.2 }
        val low = lit(0.0); val high = lit(1.0)
        assertTrue(high > low * 5, "с энергией круг растёт: $low → $high")
    }

    @Test
    fun formulasDrivePerCopyLookWithIndexAndCount() {
        // размер каждой копии свой: i и n доступны в формуле
        val flat = scene("""{"type":"gradient","p":{"c1":"#000000","c2":"#000000"}},
            {"type":"shape","blend":"source-over","p":{"kind":"circle","size":0.12,"copies":5,"layout":"line","spread":0.6,"alpha":1,"c":2.5}}""")
        val shaped = scene("""{"type":"gradient","p":{"c1":"#000000","c2":"#000000"}},
            {"type":"shape","blend":"source-over","p":{"kind":"circle","size":0.12,"copies":5,"layout":"line","spread":0.6,"alpha":1,"c":2.5},
             "bind":{"size":{"expr":"base*(0.3+1.4*i/(n-1))"}}}""")
        val a = draw(flat); val b = draw(shaped)
        // справа копии крупнее, слева мельче
        val leftA = mean(a, 0.2, 0.3, 0.4, 0.7); val leftB = mean(b, 0.2, 0.3, 0.4, 0.7)
        val rightA = mean(a, 0.6, 0.3, 0.8, 0.7); val rightB = mean(b, 0.6, 0.3, 0.8, 0.7)
        assertTrue(leftB < leftA && rightB > rightA, "слева меньше ($leftA→$leftB), справа больше ($rightA→$rightB)")
    }

    @Test
    fun quietDesaturatesAndStrictKeepsFewerLayers() {
        val look = SceneLooks.load("joy")
        fun sat(px: IntArray): Double {
            var s = 0.0
            for (p in px) { val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255; s += (maxOf(r, g, b) - minOf(r, g, b)) / 255.0 }
            return s / px.size
        }
        val loud = draw(look) { quiet = 0.0 }; val calm = draw(look) { quiet = 1.0 }
        assertTrue(sat(calm) < sat(loud) * 0.8, "«тише» снижает насыщенность: ${sat(loud)} → ${sat(calm)}")
        val full = SceneRenderer(look, W, H, 160).also { it.render(IntArray(W * H)) }.layersDrawn
        val strict = SceneRenderer(look, W, H, 160).also { it.strict = true; it.render(IntArray(W * H)) }.layersDrawn
        assertEquals(7, full) // подложка, краски, лучи, звезда, контур, нити, огоньки
        assertEquals(3, strict) // подложка, краски, образ
    }

    @Test
    fun droppedAndDisabledLayersAreSkipped() {
        val look = SceneLooks.load("joy")
        val r = SceneRenderer(look, W, H, 160)
        r.dropped = setOf("spark", "rays")
        r.render(IntArray(W * H))
        assertEquals(5, r.layersDrawn)
        r.decorEnabled = false; r.contourEnabled = false
        r.render(IntArray(W * H))
        assertEquals(4, r.layersDrawn) // без лучей и контура остаются подложка, краски, образ и нити (огоньки сняты бюджетом)
    }

    @Test
    fun aBrokenLayerSwitchesOffOnlyItself() {
        val good = scene("""{"type":"gradient","p":{"c1":"#101030","c2":"#101030"}},{"type":"shape","id":"a","p":{"kind":"circle","size":0.3,"alpha":1}}""")
        // слой неизвестного типа падает при вычислении, остальные рисуются
        val bad = good.copy(layers = good.layers + Layer("zz", "unknown-type", "Сломанный", p = emptyMap()))
        val r = SceneRenderer(bad, W, H, 160)
        val out = IntArray(W * H)
        r.render(out)
        assertTrue("zz" in r.failed.keys, r.failed.toString())
        assertEquals(2, r.layersDrawn)
        assertTrue(mean(out, 0.0, 0.0, 1.0, 1.0) > 0.02)
        r.render(out) // сломанный больше не рисуется и не пишет ошибку повторно
        assertEquals(1, r.failed.size)
    }

    @Test
    fun layerEffectsChangeThePicture() {
        val base = """{"type":"gradient","p":{"c1":"#000000","c2":"#000000"}},"""
        fun shape(fx: String) = """{"type":"shape","id":"s","blend":"screen","p":{"kind":"cross","size":0.4,"alpha":0.9,"c":0},"fx":[$fx]}"""
        val plain = draw(scene(base + shape("")))
        // bloom добавляет свет вокруг образа
        val bloom = draw(scene(base + shape("""{"type":"bloom","p":{"r":24,"amt":0.8}}""")))
        assertTrue(mean(bloom, 0.0, 0.0, 1.0, 1.0) > mean(plain, 0.0, 0.0, 1.0, 1.0) * 1.15, "bloom светлее")
        // blur сохраняет общий свет, но размывает край: резких перепадов меньше
        val blur = draw(scene(base + shape("""{"type":"blur","p":{"r":10}}""")))
        fun edges(px: IntArray): Int { var n = 0; for (y in 0 until H) for (x in 1 until W) if (abs(lum(px[y * W + x]) - lum(px[y * W + x - 1])) > 0.12) n++; return n }
        assertTrue(edges(blur) < edges(plain) / 2, "blur: перепадов ${edges(blur)} против ${edges(plain)}")
        // цвет: сдвиг оттенка меняет красный и синий
        val hue = draw(scene(base + shape("""{"type":"color","p":{"hue":180,"sat":100,"bri":100}}""")))
        assertNotEquals(plain.toList(), hue.toList())
        // деформация, калейдоскоп, следы выполняются и дают непустой кадр
        for (fx in listOf("""{"type":"warp","p":{"amt":0.6}}""", """{"type":"kaleido","p":{"n":2}}""", """{"type":"trails","p":{"decay":0.8}}""")) {
            val px = draw(scene(base + shape(fx)))
            assertTrue(mean(px, 0.0, 0.0, 1.0, 1.0) > 0.005, fx)
        }
    }

    @Test
    fun kaleidoscopeMakesAMirroredPicture() {
        // Круг лежит в первом клине (0…90° от центра): калейдоскоп размножает клин зеркально по четырём четвертям.
        val s = scene("""{"type":"gradient","p":{"c1":"#000000","c2":"#000000"}},
            {"type":"shape","blend":"source-over","p":{"kind":"circle","x":0.62,"y":0.6,"size":0.25,"alpha":1,"c":2.5},"fx":[{"type":"kaleido","p":{"n":4,"cx":0.5,"cy":0.5}}]}""")
        val px = draw(s)
        val corners = listOf(
            mean(px, 0.55, 0.5, 0.7, 0.7), mean(px, 0.3, 0.5, 0.45, 0.7), mean(px, 0.3, 0.3, 0.45, 0.5), mean(px, 0.55, 0.3, 0.7, 0.5),
        )
        corners.forEachIndexed { i, v -> assertTrue(v > 0.04, "четверть $i: $v") }
    }

    @Test
    fun sizeOfTheSoftBufferChangesCostNotTheLook() {
        val look = SceneLooks.load("evening")
        val small = SceneRenderer(look, W, H, 96).let { r -> r.jumpEnergy(0.7); repeat(30) { r.advance(1.0 / 30, 0.7) }; IntArray(W * H).also(r::render) }
        val big = SceneRenderer(look, W, H, 320).let { r -> r.jumpEnergy(0.7); repeat(30) { r.advance(1.0 / 30, 0.7) }; IntArray(W * H).also(r::render) }
        val d = abs(mean(small, 0.0, 0.0, 1.0, 1.0) - mean(big, 0.0, 0.0, 1.0, 1.0))
        assertTrue(d < 0.05, "средняя яркость почти не зависит от размера мягких слоёв: $d")
    }

    @Test
    fun frameTimeIsReasonableAndReported() {
        val look = SceneLooks.load("joy")
        val r = SceneRenderer(look, W, H, 192)
        val out = IntArray(W * H)
        r.jumpEnergy(0.9)
        repeat(5) { r.advance(1.0 / 30, 0.9); r.render(out) } // прогрев
        val t0 = System.nanoTime()
        val n = 20
        repeat(n) { r.advance(1.0 / 30, 0.9); r.render(out) }
        val ms = (System.nanoTime() - t0) / 1e6 / n
        println("SceneRenderer: ${W}x$H joy, ${"%.1f".format(ms)} мс на кадр")
        assertTrue(ms < 250, "кадр дольше разумного: $ms мс")
    }

    @Test
    fun smallerFrameAndOddAspectsWork() {
        val look = SceneLooks.load("glass")
        for ((w, h) in listOf(320 to 180, 480 to 360, 360 to 640, 97 to 53)) {
            val r = SceneRenderer(look, w, h, 96)
            val out = IntArray(w * h)
            r.jumpEnergy(0.7); repeat(10) { r.advance(1.0 / 30, 0.7) }
            r.render(out, TextBox(0.4, 0.5, 0.25, 0.15))
            assertTrue(out.any { lum(it) > 0.1 }, "${w}x$h: пусто")
        }
    }
}
