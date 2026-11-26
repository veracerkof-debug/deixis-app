package app.deixis.core.effects

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionTest {
    @Test
    fun tempoPriority() {
        assertEquals(Tempo.Resolved(100, TempoSource.TAP), Tempo.resolve(tap = 100, slide = 92, song = 76, mode = 60))
        assertEquals(Tempo.Resolved(92, TempoSource.SLIDE), Tempo.resolve(0, 92, 76, 60))
        assertEquals(Tempo.Resolved(76, TempoSource.SONG), Tempo.resolve(0, 0, 76, 60))
        assertEquals(Tempo.Resolved(60, TempoSource.MODE), Tempo.resolve(0, 0, 0, 60))
    }

    @Test
    fun beatClockPhaseAccumulatesAndTempoChangesSmoothly() {
        val c = BeatClock(startBpm = 120.0)
        repeat(50) { c.advance(0.01, 120.0) } // полсекунды при 120 = одна доля
        assertTrue(minOf(c.phase, 1 - c.phase) < 0.02, c.phase.toString()) // фаза замкнулась на целой доле
        // смена темпа не прыгает: за 0,1 с темп сдвинулся лишь частично
        c.advance(0.1, 60.0)
        assertTrue(c.bpm in 60.0..120.0 && c.bpm > 90.0, c.bpm.toString())
        repeat(100) { c.advance(0.05, 60.0) }
        assertEquals(60.0, c.bpm, 0.5)
    }

    @Test
    fun beatEnvelopeIsSmoothAndInRange() {
        val c = BeatClock(96.0)
        var min = 1.0; var max = 0.0; var maxStep = 0.0; var prev = c.envelope()
        repeat(2000) {
            c.advance(1.0 / 60, 96.0)
            val e = c.envelope()
            min = minOf(min, e); max = maxOf(max, e); maxStep = maxOf(maxStep, abs(e - prev)); prev = e
        }
        assertTrue(min >= 0.0 && max <= 1.0)
        assertTrue(max > 0.95 && min < 0.05, "импульс доходит до краёв: $min…$max")
        assertTrue(maxStep < 0.15, "без резких скачков (не мигание): $maxStep")
        assertEquals(0.0, BeatClock(0.0).envelope(0.0), 0.0)
    }

    @Test
    fun energyApproachesTargetWithoutOvershoot() {
        var e = 0.1
        val seen = ArrayList<Double>()
        repeat(300) { e = Energy.approach(e, 0.9, 1.0 / 30, 1.2); seen += e }
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a }, "растёт монотонно")
        assertTrue(seen.all { it <= 0.9 })
        assertEquals(0.9, e, 0.001)
        // «Поклонение» (постоянная больше) догоняет медленнее
        assertTrue(Energy.approach(0.1, 0.9, 1.0, 2.8) < Energy.approach(0.1, 0.9, 1.0, 1.2))
        assertEquals(0.5, Energy.ramp(0.0, 1.0, 0.5), 1e-9)
        assertEquals(0.2, Energy.ramp(0.2, 0.9, -3.0), 1e-9)
        assertEquals(0.9, Energy.ramp(0.2, 0.9, 7.0), 1e-9)
    }

    @Test
    fun modulatorsStayInUnitRangeAndRepeat() {
        for (shape in listOf("sine", "tri", "saw")) {
            val m = Modulator("lfo1", "lfo", rate = 0.5, shape = shape)
            for (i in 0..400) assertTrue(Modulation.value(m, i * 0.05) in 0.0..1.0, shape)
            assertEquals(Modulation.value(m, 0.3), Modulation.value(m, 0.3 + 2.0), 1e-9) // период 2 с
        }
        assertEquals(0.5, Modulation.value(Modulator("lfo1", "lfo", 1.0, "sine"), 0.0), 1e-9)
        val n = Modulator("noise1", "noise", 0.4)
        assertEquals(Modulation.value(n, 3.3), Modulation.value(n, 3.3), 0.0)
        for (i in 0..200) assertTrue(Modulation.value(n, i * 0.1) in 0.0..1.0)
    }

    @Test
    fun bindingsResolveSourceFormulaAndRange() {
        val size = SceneCatalog.layers.getValue("shape").params.first { it.key == "size" } // 0,02…1,4
        val vars = mapOf("energy" to 0.5, "beat" to 1.0, "t" to 0.0)
        // база + источник × сила × диапазон
        val v = Bindings.resolve(size, 0.3, Binding("beat", 0.03), vars)
        assertEquals(0.3 + 1.0 * 0.03 * (1.4 - 0.02), v, 1e-9)
        // формула вместо источника, номер копии и число копий
        val f = Bindings.resolve(size, 0.3, Binding(expr = "base*(1-0.4*i/n)"), vars, i = 5, n = 10)
        assertEquals(0.3 * (1 - 0.4 * 5 / 10), f, 1e-9)
        // результат не выходит за пределы параметра
        assertEquals(1.4, Bindings.resolve(size, 0.3, Binding(expr = "base*100"), vars), 1e-9)
        // неизвестный источник и отсутствие привязки оставляют базу
        assertEquals(0.3, Bindings.resolve(size, 0.3, Binding("zzz", 1.0), vars), 1e-9)
        assertEquals(0.3, Bindings.resolve(size, 0.3, null, vars), 1e-9)
        // нечисло в формуле возвращает базу, а не ломает кадр
        assertEquals(0.3, Bindings.resolve(size, 0.3, Binding(expr = "pow(0-1,0.5)"), vars), 1e-9)
        // цвет палитры не обрезается: он зациклен
        val color = SceneCatalog.layers.getValue("shape").params.first { it.key == "c" }
        assertEquals(7.2, Bindings.resolve(color, 0.0, Binding(expr = "7.2"), vars), 1e-9)
    }

    @Test
    fun environmentHasAllSources() {
        val scene = SceneValidator.validate("""{"mods":[{"id":"lfo1","type":"lfo","rate":1},{"id":"noise1","type":"noise"}],"layers":[{"type":"text"}]}""").scene!!
        val env = Modulation.env(scene, t = 0.0, energy = 0.4, beat = 0.7)
        assertEquals(setOf("t", "energy", "beat", "pi", "lfo1", "noise1"), env.keys)
        assertEquals(0.4, env.getValue("energy"), 0.0)
        assertEquals(0.5, env.getValue("lfo1"), 1e-9)
    }
}
