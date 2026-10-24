package app.deixis.core

import java.io.File
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlaysTest {
    private fun core() = ReceiverCore(
        ReceiverInfo("tv", "ТВ", "test", "0"),
        FileStore(File(createTempDirectory("overlays").toFile(), "files")),
        Pairing(MemoryTokenStore()),
    )

    @Test
    fun layerSurvivesSlideChangesAndHidesOnBlack() {
        val core = core()
        val o = Overlays(lowerThird = LowerThird("Анна Иванова", "Тема: весна"), clock = ClockOverlay())
        assertNull(core.apply(SetOverlays(o)))
        core.apply(Show(TextContent("Первый")))
        core.apply(Show(TextContent("Второй")))
        assertEquals(o, core.state.value.overlays, "смена слайда слой не трогает")
        assertTrue(overlaysVisible(Mode.NORMAL) && overlaysVisible(Mode.CLEAR))
        assertTrue(!overlaysVisible(Mode.BLACK) && !overlaysVisible(Mode.LOGO))
        core.apply(SetOverlays(Overlays()))
        assertTrue(core.state.value.overlays.isEmpty)
    }

    @Test
    fun logoNeedsItsFileFirst() {
        val core = core()
        val bytes = ByteArray(1000) { it.toByte() }
        val sha = sha256Of(bytes.inputStream())
        val ref = FileRef(sha, "logo.png", bytes.size.toLong(), "image/png")
        val err = core.apply(SetOverlays(Overlays(logo = LogoOverlay(ref))))
        assertEquals("missing_file", err?.code)
        assertTrue(core.state.value.overlays.isEmpty, "без файла слой не меняется")
        core.files.append(sha, bytes.size.toLong(), 0, bytes.inputStream())
        assertNull(core.apply(SetOverlays(Overlays(logo = LogoOverlay(ref)))))
        assertNotNull(core.state.value.overlays.logo)
    }

    @Test
    fun oldReceiverStateWithoutLayerStillReads() {
        val old = """{"type":"status","state":{"content":null,"mode":"NORMAL","video":null,"error":null,"viewers":0}}"""
        val msg = decodeMsg(old) as Status
        assertTrue(msg.state.overlays.isEmpty)
        val round = decodeMsg(SetOverlays(Overlays(ticker = Ticker("Сбор в 18:00"))).encode()) as SetOverlays
        assertEquals("Сбор в 18:00", round.overlays.ticker?.text)
    }

    @Test
    fun geometryStaysInsideTheScreenAtEveryShape() {
        for ((w, h) in listOf(1920f to 1080f, 1024f to 768f, 1080f to 1920f, 3840f to 1080f)) {
            val m = overlayMargin(w, h)
            for (c in Corner.entries) {
                val b = logoBox(w, h, 400f, 200f, LogoOverlay(FileRef("x", "l.png", 1, "image/png"), corner = c), aboveTicker = true)
                assertTrue(b.left >= m - 0.5f && b.right <= w - m + 0.5f && b.top >= m - 0.5f, "$w×$h $c")
                assertTrue(b.bottom <= h - tickerHeight(h) - m + 0.5f, "логотип над бегущей строкой: $w×$h $c")
                assertEquals(2f, b.width / b.height, 0.01f, "пропорции картинки")
            }
            val lt = lowerThirdBox(w, h, textWidth = w * 3, hasSubtitle = true, aboveTicker = false)
            assertTrue(lt.right <= w - m + 0.5f, "длинное имя не выходит за поле: $w×$h")
            assertEquals(h - m, lt.bottom, 0.5f)
            val t = tickerBox(w, h)
            assertEquals(h, t.bottom, 0.01f)
        }
    }

    @Test
    fun tickerEntersFromTheRightAndLoops() {
        val w = 1000f
        val text = 500f
        // 10 % ширины в секунду: за секунду — 100 точек влево.
        val a = tickerX(w, text, 10f, 0)
        val b = tickerX(w, text, 10f, 1000)
        assertEquals(1000f, a, 0.5f)
        assertEquals(900f, b, 0.5f)
        // Путь — экран плюс текст (1500 точек = 15 с), дальше по кругу.
        assertEquals(a, tickerX(w, text, 10f, 15_000), 0.5f)
        // Время с 1970 года не ломает плавность: соседние миллисекунды — соседние точки.
        val now = 1_790_000_000_123L
        val d = tickerX(w, text, 10f, now) - tickerX(w, text, 10f, now + 10)
        assertTrue(d in 0.5f..1.5f || d < -1000f, "шаг $d")
    }

    @Test
    fun clockReadsLocalTime() {
        val t = 1_790_000_000_000L // 2026-09-21 14:13:20 UTC
        assertEquals("14:13", clockText(t, seconds = false, zone = ZoneOffset.UTC))
        assertEquals("14:13:20", clockText(t, seconds = true, zone = ZoneOffset.UTC))
        assertEquals("19:13", clockText(t, seconds = false, zone = ZoneOffset.ofHours(5)))
        assertEquals("0:13", clockText(t, seconds = false, zone = ZoneOffset.ofHours(10)))
    }
}
