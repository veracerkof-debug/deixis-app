package app.deixis.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Разбор xrandr для виртуального монитора на Linux: какой выход свободен и где он встанет. */
class XrandrTest {
    private val laptop = """
        Screen 0: minimum 320 x 200, current 1920 x 1080, maximum 16384 x 16384
        eDP-1 connected primary 1920x1080+0+0 (normal left inverted right x axis y axis) 344mm x 193mm
           1920x1080     60.02*+
        HDMI-1 connected 1920x1080+0+0 (normal left inverted right x axis y axis) 0mm x 0mm
           1920x1080     60.00*+
        DP-1 disconnected (normal left inverted right x axis y axis)
        HDMI-2 disconnected (normal left inverted right x axis y axis)
    """.trimIndent()

    @Test
    fun readsScreenAndOutputs() {
        assertEquals(1920 to 1080, Xrandr.screen(laptop))
        val outs = Xrandr.outputs(laptop)
        assertEquals(listOf("eDP-1", "HDMI-1", "DP-1", "HDMI-2"), outs.map { it.name })
        val edp = outs.first()
        assertEquals(true, edp.primary)
        assertEquals(1920, edp.width)
        // Проектор дублирует рабочий стол: тот же угол 0,0.
        assertEquals(0, outs[1].x)
        assertNull(outs[2].width)
    }

    @Test
    fun freeOutputSkipsConnectedAndLaptopPanel() {
        assertEquals("DP-1", Xrandr.freeOutput(Xrandr.outputs(laptop))?.name)
        val intel = laptop + "\nVIRTUAL1 disconnected (normal left inverted right x axis y axis)"
        assertEquals("VIRTUAL1", Xrandr.freeOutput(Xrandr.outputs(intel))?.name, "VIRTUAL — первым")
        val noFree = "Screen 0: minimum 8 x 8, current 1366 x 768, maximum 32767 x 32767\neDP-1 connected primary 1366x768+0+0\neDP-2 disconnected"
        assertNull(Xrandr.freeOutput(Xrandr.outputs(noFree)))
    }
}
