package app.deixis.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountdownTest {
    private fun c(ms: Long, after: AfterZero = AfterZero.HOLD, format: CountdownFormat = CountdownFormat.AUTO, decimals: Int = 0) =
        CountdownContent("t", ms, afterZero = after, format = format, decimals = decimals)

    @Test
    fun formatsLikeAScoreboard() {
        assertEquals("30:00", formatCountdown(30 * 60_000L, CountdownFormat.AUTO))
        // Секунды вверх: «0:01» видно до самого нуля.
        assertEquals("0:01", formatCountdown(1, CountdownFormat.AUTO))
        assertEquals("1:00:00", formatCountdown(3_600_000, CountdownFormat.AUTO))
        assertEquals("0:05:00", formatCountdown(300_000, CountdownFormat.HMS))
        assertEquals("75:00", formatCountdown(4_500_000, CountdownFormat.MS))
        assertEquals("90", formatCountdown(90_000, CountdownFormat.S))
        // Доли секунды — настоящие, от часов.
        assertEquals("0:04.250", formatCountdown(4_250, CountdownFormat.AUTO, decimals = 3))
        assertEquals("0:04.3", formatCountdown(4_250, CountdownFormat.AUTO, decimals = 1))
    }

    @Test
    fun afterZero() {
        val now = 1_000_000L
        val base = c(5_000).anchored(now)
        assertEquals("0:05", countdownFace(base, now).text)
        assertEquals("0:00", countdownFace(base, now + 9_000).text)
        val over = c(5_000, AfterZero.OVERTIME).anchored(now)
        val f = countdownFace(over, now + 47_000)
        assertEquals("+0:42", f.text)
        assertTrue(f.overtime)
        assertTrue(countdownFace(c(5_000, AfterZero.HIDE).anchored(now), now + 6_000).hidden)
        // Пауза: время не уходит.
        val paused = c(5_000).copy(running = false).anchored(now)
        assertEquals("0:05", countdownFace(paused, now + 60_000).text)
    }

    @Test
    fun screensFollowTheirOwnClocksNotTheSenders() {
        // Часы ТВ отстают на 7 минут: отсчёт всё равно идёт от получения, а не от часов пульта.
        val core = ReceiverCore(ReceiverInfo("tv", "ТВ", "test", "0"), FileStore(File(createTempDirectory("cd").toFile(), "f")), Pairing(MemoryTokenStore()))
        core.apply(Show(c(60_000)))
        val held = core.state.value.content as CountdownContent
        val left = held.remainingAt(System.currentTimeMillis())
        assertTrue(left in 59_000..60_000, "осталось $left")
        assertEquals(held, core.state.value.timer, "«Сцена» видит тот же отсчёт")
        // В зале уже песня — отсчёт остаётся в «Сцене».
        core.apply(Show(TextContent("Песня")))
        assertEquals("t", core.state.value.timer?.id)
        // Пауза с пульта меняет его, зал не трогает.
        core.apply(SetTimer(c(42_000).copy(running = false)))
        assertEquals(42_000, core.state.value.timer!!.remainingAt(System.currentTimeMillis() + 10_000))
        assertTrue(core.state.value.content is TextContent)
        // Дальше — сколько осталось на этот миг.
        assertEquals(42_000, core.state.value.timer!!.forWire(System.currentTimeMillis()).remainingMs)
        core.apply(SetTimer(null))
        assertEquals(null, core.state.value.timer)
    }

    @Test
    fun digitsDoNotJitterAndFitEveryFormat() {
        // Пропорциональный шрифт: «1» узкая, «0» широкая. Ячейки одинаковы — строка не дрожит.
        val measure = { s: String, size: Float -> s.sumOf { ch -> (if (ch == '1') 0.3 else if (ch == ':') 0.25 else 0.6) * size.toDouble() }.toFloat() }
        val asc = { s: Float -> s * 0.8f }
        val desc = { s: Float -> s * 0.2f }
        for ((w, h) in listOf(1920f to 1080f, 1024f to 768f, 1080f to 1920f, 600f to 1800f, 5120f to 1440f)) {
            val cd = c(600_000).copy(label = "До начала служения")
            val a = layoutCountdown(cd, "11:11", w, h, measure, asc, desc)
            val b = layoutCountdown(cd, "10:00", w, h, measure, asc, desc)
            assertEquals(a.size, b.size, "${w}x$h: размер не скачет")
            // Вторая цифра: «1» в «11:11» и «0» в «10:00» — в одной ячейке, узкая «1» по её центру.
            assertEquals(b.cells[1].second + (0.6f - 0.3f) * a.size / 2f, a.cells[1].second, 0.5f, "${w}x$h: цифра в своей ячейке")
            assertEquals(a.cells.first().second, b.cells.first().second, 0.5f, "${w}x$h: строка стоит на месте")
            val area = safeArea(w, h)
            val right = a.cells.last().second + measure(a.cells.last().first, a.size)
            assertTrue(a.cells.first().second >= area.left - 1 && right <= area.right + 1, "${w}x$h: в полях")
            assertTrue(a.labelSize * measure("До начала служения", 1f) <= area.width + 1, "${w}x$h: подпись помещается")
        }
    }
}
