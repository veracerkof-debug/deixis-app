package app.deixis.desktop

import app.deixis.core.FileStore
import app.deixis.core.Mode
import app.deixis.core.MonitorRequest
import app.deixis.core.OutputLayout
import app.deixis.core.TextContent
import app.deixis.core.VideoContent
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Наложение»: прозрачный кадр со слоем своей плотности; текст — титрами внизу; «Чёрный» — монитор как есть. */
class OverlayLayoutTest {
    private val frames = OutputFrames(FileStore(File(createTempDirectory("overlay").toFile(), "files")))
    private val w = 640
    private val h = 360

    private fun alpha(img: java.awt.image.BufferedImage, x: Int, y: Int) = img.getRGB(x, y) ushr 24

    @Test
    fun layerHasItsDensityAndTextSitsAtTheBottom() {
        val img = frames.render(OutputLayout.OVERLAY, w, h, FrameState(TextContent("Титры поверх"), Mode.NORMAL), dim = 50)
        assertTrue(img.colorModel.hasAlpha(), "кадр с прозрачностью")
        assertEquals(overlayAlpha(50), alpha(img, 2, 2), "слой 50 % в углу")
        val bottom = (h * 3 / 4 until h).any { y -> (0 until w).any { x -> img.getRGB(x, y) and 0xFFFFFF == 0xFFFFFF && alpha(img, x, y) == 255 } }
        val top = (0 until h / 4).any { y -> (0 until w).any { x -> alpha(img, x, y) > overlayAlpha(50) } }
        assertTrue(bottom, "белый текст внизу")
        assertTrue(!top, "сверху только слой")
    }

    @Test
    fun zeroDensityIsFullyTransparentAroundText() {
        val img = frames.render(OutputLayout.OVERLAY, w, h, FrameState(TextContent("Только текст"), Mode.NORMAL), dim = 0)
        assertEquals(0, alpha(img, 2, 2))
    }

    @Test
    fun blackAndVideoLeaveTheMonitorAsIs() {
        val black = frames.render(OutputLayout.OVERLAY, w, h, FrameState(TextContent("Слайд"), Mode.BLACK), dim = 60)
        assertEquals(0, alpha(black, w / 2, h - 10), "«Чёрный» убирает и слой, и текст")
        val clear = frames.render(OutputLayout.OVERLAY, w, h, FrameState(TextContent("Слайд"), Mode.CLEAR), dim = 60)
        assertEquals(overlayAlpha(60), alpha(clear, w / 2, h / 2), "«Очистить» оставляет только слой")
        val video = frames.render(OutputLayout.OVERLAY, w, h, FrameState(VideoContent(app.deixis.core.FileRef("a".repeat(64), "v.mp4", 1, "video/mp4")), Mode.NORMAL), dim = 20)
        assertEquals(overlayAlpha(20), alpha(video, w / 2, h / 2), "видео в наложение не идёт")
    }

    @Test
    fun densityIsSavedPerMonitorAndClamped() {
        val s = MonitorSettings().with(MonitorRequest("m1", true, OutputLayout.OVERLAY, 140))
        assertEquals(100, s.monitors.getValue("m1").dim)
        val kept = s.with(MonitorRequest("m1", true, OutputLayout.OVERLAY))
        assertEquals(100, kept.monitors.getValue("m1").dim, "без плотности в запросе — прежняя")
        val m = resolveMonitors(listOf(ScreenInfo("m1", 0, 0, 1920, 1080)), "m1", kept).single()
        assertEquals(OutputLayout.OVERLAY, m.layout)
        assertEquals(100, m.dim)
    }
}
