package app.deixis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayMotionTest {
    private val lt = LowerThird("Анна Иванова", "Ведущая")

    @Test
    fun appearsAndLeavesSmoothly() {
        val m = OverlayMotion(400)
        m.update(Overlays(lowerThird = lt), 1_000)
        assertEquals(0f, m.frame(1_000).lowerThird!!.presence, 0.001f)
        val mid = m.frame(1_200)
        assertTrue(mid.lowerThird!!.presence in 0.5f..0.99f, "на полпути: ${mid.lowerThird.presence}")
        assertTrue(mid.moving)
        assertEquals(1f, m.frame(1_400).lowerThird!!.presence, 0.001f)
        assertTrue(!m.frame(1_500).moving, "встала на место — кадры не нужны")
        // Уход: плашка ещё видна, пока уезжает, и пропадает совсем.
        m.update(Overlays(), 2_000)
        assertEquals(lt, m.frame(2_100).lowerThird?.value, "уходящая плашка ещё рисуется")
        assertNull(m.frame(2_400).lowerThird)
    }

    @Test
    fun newNameEntersAgainAndBlackIsInstant() {
        val m = OverlayMotion(400)
        m.update(Overlays(lowerThird = lt), 0)
        m.frame(1_000)
        val next = lt.copy(title = "Пётр Смирнов")
        m.update(Overlays(lowerThird = next), 1_000)
        val f = m.frame(1_000).lowerThird!!
        assertEquals(next, f.value)
        assertEquals(0f, f.presence, 0.001f)
        m.update(Overlays(lowerThird = next, clock = ClockOverlay()), 2_000)
        // «Чёрный»: всё убрано сразу, без ухода.
        m.update(Overlays(), 2_100, instant = true)
        assertTrue(m.frame(2_100).isEmpty)
        // Вернулись из «Чёрного» — слой снова плавно появляется.
        m.update(Overlays(lowerThird = next), 3_000)
        assertEquals(0f, m.frame(3_000).lowerThird!!.presence, 0.001f)
    }

    @Test
    fun interruptedLeavingComesBackFromWhereItWas() {
        val m = OverlayMotion(400)
        m.update(Overlays(ticker = Ticker("Сбор в 18:00")), 0)
        m.update(Overlays(), 1_000)
        val leaving = m.frame(1_200).ticker!!.presence
        m.update(Overlays(ticker = Ticker("Сбор в 18:00")), 1_200)
        val back = m.frame(1_200).ticker!!.presence
        assertEquals(leaving, back, 0.01f, "без скачка")
    }
}

class OverlaySamplesTest {
    @Test
    fun everySampleHasItsPictureAndSurvivesTheWire() {
        for (s in OverlaySamples.all()) {
            for (f in s.overlays.files) {
                val b = OverlaySamples.bytes(f.name)
                kotlin.test.assertEquals(f.size, b.size.toLong(), f.name)
                kotlin.test.assertEquals(sha256Of(b.inputStream()), f.sha256)
            }
            val back = decodeMsg(SetOverlays(s.overlays).encode()) as SetOverlays
            kotlin.test.assertEquals(s.overlays, back.overlays, s.name)
        }
        kotlin.test.assertTrue(OverlaySamples.all().size >= 5)
    }
}
