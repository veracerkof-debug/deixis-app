package app.deixis.core.effects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QualityGovernorTest {
    private var now = 0L
    private fun tick(g: QualityGovernor, ms: Double, fps: Double = 30.0): QualityGovernor.Decision? {
        now += 500
        return g.tick(ms, fps, 30, now)
    }

    @Test
    fun overloadDropsOneTierAfterTwoTicks() {
        val g = QualityGovernor()
        assertEquals("Обычный", g.tier.name)
        assertNull(tick(g, 24.0))          // одна плохая отметка — ещё не повод
        val d = tick(g, 24.0)
        assertNotNull(d)
        assertEquals(1, d.index)
        assertEquals("Лёгкий", g.tier.name)
        assertTrue("нагрузка" in d.reason)
    }

    @Test
    fun lowFpsCountsAsOverloadEvenWithCheapFrames() {
        val g = QualityGovernor()
        tick(g, 5.0, fps = 18.0)
        assertNotNull(tick(g, 5.0, fps = 18.0))
    }

    @Test
    fun singleSpikeDoesNotChangeAnything() {
        val g = QualityGovernor()
        assertNull(tick(g, 28.0)); assertNull(tick(g, 8.0)); assertNull(tick(g, 28.0)); assertNull(tick(g, 8.0))
        assertEquals(2, g.index)
    }

    @Test
    fun fallsToTheBottomButNotBelow() {
        val g = QualityGovernor()
        repeat(40) { tick(g, 30.0, fps = 12.0) }
        assertEquals(0, g.index)
        assertEquals("Минимум", g.tier.name)
    }

    @Test
    fun recoversSlowlyAndNeverReturnsToTheFailedTierForAMinute() {
        val g = QualityGovernor()
        tick(g, 24.0); tick(g, 24.0)           // упали на «Лёгкий», «Обычный» запрещён на минуту
        assertEquals(1, g.index)
        // устройство снова свободно: быстрые кадры
        var up: QualityGovernor.Decision? = null
        var seconds = 0
        while (up == null && seconds < 200) { up = tick(g, 0.9); seconds++ }
        assertNotNull(up)
        assertEquals(2, g.index)
        assertTrue(seconds * 0.5 >= 60.0 - 0.001, "раньше чем через минуту подъём запрещён: ${seconds * 0.5} с")
    }

    @Test
    fun startupProbeRisesQuicklyOnAPowerfulDevice() {
        val g = QualityGovernor()
        val d1 = tick(g, 0.5); val d2 = tick(g, 0.5)
        assertNull(d1)
        assertNotNull(d2) // в первые 7 секунд хватает двух отметок
        assertEquals("Хороший", g.tier.name)
    }

    @Test
    fun projectionStopsARiseThatWouldNotFit() {
        val g = QualityGovernor()
        // 5 мс на 30 к/с — запас есть (15 %), но прогноз 320×180 = 5 × 2,78 = 13,9 мс > 36 % от 33 мс = 12 мс
        repeat(40) { assertNull(tick(g, 5.0)) }
        assertEquals(2, g.index)
    }

    @Test
    fun noRiseAtTheTopAndResetStartsOver() {
        val g = QualityGovernor(startIndex = 4)
        repeat(30) { assertNull(tick(g, 0.2)) }
        assertEquals(4, g.index)
        g.reset(QualityTiers.SAFE_INDEX)
        assertEquals("Лёгкий", g.tier.name)
    }

    @Test
    fun tiersAreOrderedByCostAndKeepTheEssentials() {
        val t = QualityTiers.ALL
        assertEquals(t.sortedBy { it.width }, t)
        assertTrue(t.zipWithNext().all { (a, b) -> b.particles >= a.particles && b.crispAdj >= a.crispAdj })
        assertTrue(QualityTiers.SAFE_INDEX < QualityTiers.DEFAULT_INDEX)
        assertTrue(t.first().crispAdj < 0 && t.last().crispAdj > 0) // слабый — мягче, сильный — резче
    }
}
