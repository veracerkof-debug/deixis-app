package app.deixis.core.effects

import app.deixis.core.SceneBackground
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MicMeterTest {
    private fun tone(amp: Double, n: Int = 800) = ShortArray(n) { (sin(it * 2 * PI * 440 / 16000) * amp * 32767).toInt().toShort() }

    @Test
    fun loudSoundRaisesTheLevelQuicklyAndSilenceLowersItSlowly() {
        val m = MicMeter()
        repeat(60) { m.feed(tone(0.01)) }
        val quiet = m.level
        repeat(6) { m.feed(tone(0.5)) }
        val loud = m.level
        assertTrue(loud > quiet + 0.3, "громкий звук виден сразу: $quiet → $loud")
        repeat(2) { m.feed(tone(0.01)) }
        assertTrue(m.level > 0.2, "после удара уровень спадает не мгновенно: ${m.level}")
        repeat(80) { m.feed(tone(0.01)) }
        assertTrue(m.level < loud * 0.5, "а в тишине всё же спадает: ${m.level}")
    }

    @Test
    fun levelStaysInRangeAndEmptyBlocksChangeNothing() {
        val m = MicMeter()
        repeat(50) { assertTrue(m.feed(tone(1.0)) in 0.0..1.0) }
        val before = m.level
        assertEquals(before, m.feed(ShortArray(0), 0))
    }

    @Test
    fun micGainAddsToTheBackgroundEnergy() {
        val b = SceneBackground("evening", energy = 0.4f, mic = 0.5f)
        assertEquals(0.4, b.energyAt(0.0), 1e-6)
        assertEquals(0.4 + 0.5 * 0.8, b.energyWithMic(0.0, 0.8), 1e-6)
        assertEquals(1.0, b.copy(mic = 1f).energyWithMic(0.0, 5.0), 1e-9, "не выше 1")
        assertEquals(0.4, SceneBackground("x", energy = 0.4f).energyWithMic(0.0, 1.0), 1e-6, "без реакции микрофон не влияет")
    }
}
