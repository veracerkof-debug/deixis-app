package app.deixis.desktop

import app.deixis.core.FileRef
import app.deixis.core.FileStore
import app.deixis.core.ImageContent
import app.deixis.core.MonitorRequest
import app.deixis.core.Mode
import app.deixis.core.OutputLayout
import app.deixis.core.PdfContent
import app.deixis.core.ScriptureBlock
import app.deixis.core.ScriptureContent
import app.deixis.core.TextContent
import app.deixis.core.TextStyle
import app.deixis.core.VideoContent
import app.deixis.core.VideoState
import app.deixis.core.sha256Of
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonitorsTest {
    private val two = listOf(ScreenInfo("\\Display0", 0, 0, 1920, 1080), ScreenInfo("\\Display1", 1920, 0, 1280, 720))

    @Test
    fun oneMonitorShowsTheHallAsBefore() {
        val m = resolveMonitors(two.take(1), "\\Display0", MonitorSettings()).single()
        assertTrue(m.primary)
        assertTrue(m.enabled)
        assertEquals(OutputLayout.HALL, m.layout)
    }

    @Test
    fun withTwoMonitorsTheOperatorMonitorStaysFree() {
        val list = resolveMonitors(two, "\\Display0", MonitorSettings())
        assertEquals(listOf(false, true), list.map { it.enabled })
        assertEquals(listOf(true, false), list.map { it.primary })
        assertEquals(listOf("Монитор 1", "Монитор 2"), list.map { it.name })
        assertEquals(1280, list[1].width)
    }

    @Test
    fun choiceIsSavedAndSurvivesRestart() {
        val file = File(createTempDirectory("monitors").toFile(), "monitors.json")
        val store = MonitorSettingsStore(file)
        val s = store.load()
            .with(MonitorRequest("\\Display0", true, OutputLayout.STAGE))
            .with(MonitorRequest("\\Display1", true, OutputLayout.CHROMA))
        store.save(s)
        val list = resolveMonitors(two, "\\Display0", MonitorSettingsStore(file).load())
        assertEquals(listOf(OutputLayout.STAGE, OutputLayout.CHROMA), list.map { it.layout })
        assertEquals(listOf(true, true), list.map { it.enabled })
        // Монитор, который оператор не трогал, получает умолчание.
        val three = two + ScreenInfo("\\Display2", 3200, 0, 1920, 1080)
        assertTrue(resolveMonitors(three, "\\Display0", MonitorSettingsStore(file).load())[2].enabled)
    }

    @Test
    fun brokenSettingsFileGivesDefaults() {
        val file = File(createTempDirectory("monitors").toFile(), "monitors.json").apply { writeText("{не json") }
        assertEquals(MonitorSettings(), MonitorSettingsStore(file).load())
    }

    @Test
    fun operatorWindowPrefersEdgeThenChromeOnWindows() {
        val env = mapOf("ProgramFiles(x86)" to "C:\\Program Files (x86)", "ProgramFiles" to "C:\\Program Files")
        val list = OperatorLauncher.candidates("Windows 11", env) { null }
        assertTrue(list.first().endsWith("msedge.exe"))
        assertTrue(list.indexOfFirst { it.endsWith("chrome.exe") } > list.indexOfLast { it.endsWith("msedge.exe") })
        val linux = OperatorLauncher.candidates("Linux", emptyMap()) { if (it == "chromium") "/usr/bin/chromium" else null }
        assertEquals(listOf("/usr/bin/chromium"), linux)
        val args = OperatorLauncher.arguments("chrome", "http://127.0.0.1:47820/remote#t=abc", File("/data/operator-browser"))
        assertTrue("--app=http://127.0.0.1:47820/remote#t=abc" in args)
        assertTrue("--window-size=1400,900" in args)
        assertTrue(args.any { it.startsWith("--user-data-dir=") && it.endsWith("operator-browser") })
    }
}

class OutputFramesTest {
    init { System.setProperty("java.awt.headless", "true") }

    private val store = FileStore(createTempDirectory("frames").toFile())
    private val frames = OutputFrames(store)
    private val w = 1280
    private val h = 720
    /** `-Ddeixis.outputsPng=папка` сохраняет кадры раскладок для просмотра глазами. */
    private val pngDir = System.getProperty("deixis.outputsPng")?.let(::File)

    private fun save(name: String, img: BufferedImage) {
        val dir = pngDir ?: return
        dir.mkdirs()
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    private fun BufferedImage.count(x0: Int, y0: Int, x1: Int, y1: Int, test: (Int) -> Boolean): Int {
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) if (test(getRGB(x, y) and 0xFFFFFF)) n++
        return n
    }

    private val verse = ScriptureContent(
        listOf(ScriptureBlock("Ибо так возлюбил Бог мир, что отдал Сына Своего Единородного, дабы всякий верующий в Него, не погиб, но имел жизнь вечную.")),
        ref = "Иоанна 3:16",
        style = TextStyle(background = 0xFF18233A, caps = false),
    )

    private fun image(): ImageContent {
        val src = BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().run {
                color = Color(0x2B4C7E); fillRect(0, 0, 1600, 900)
                color = Color(0xBC9361); fillRect(120, 120, 900, 120)
                color = Color.WHITE; fillRect(120, 320, 1300, 40); fillRect(120, 400, 1100, 40); fillRect(120, 480, 1200, 40)
                dispose()
            }
        }
        val bytes = java.io.ByteArrayOutputStream().also { ImageIO.write(src, "png", it) }.toByteArray()
        val sha = sha256Of(bytes.inputStream())
        store.append(sha, bytes.size.toLong(), 0, bytes.inputStream())
        return ImageContent(FileRef(sha, "slide.png", bytes.size.toLong(), "image/png"))
    }

    @Test
    fun captionsTakeOnlyTextAndSitLow() {
        assertNull(captionContent(image(), Mode.NORMAL, chroma = false), "фото в титры не попадает")
        assertNull(captionContent(verse, Mode.BLACK, chroma = true), "«Чёрный» убирает титры")
        assertNull(captionContent(verse, Mode.CLEAR, chroma = true))

        val lower = frames.render(OutputLayout.LOWER_THIRD, w, h, FrameState(verse, Mode.NORMAL))
        save("lower-third", lower)
        assertEquals(0, lower.count(0, 0, w, (h * 0.6).toInt()) { it != 0 }, "верх экрана пустой и чёрный: фон оформления не берётся")
        assertTrue(lower.count(0, (h * 0.64).toInt(), w, h) { it == 0xFFFFFF } > 2000, "текст внизу")

        val chroma = frames.render(OutputLayout.CHROMA, w, h, FrameState(verse, Mode.NORMAL))
        save("chroma", chroma)
        assertEquals(0x00FF00, chroma.getRGB(10, 10) and 0xFFFFFF, "чистый зелёный")
        assertEquals(0, chroma.count(0, 0, w, (h * 0.6).toInt()) { it != 0x00FF00 })
        assertTrue(chroma.count(0, (h * 0.64).toInt(), w, h) { it == 0xFFFFFF } > 2000)

        val song = TextContent("Свят, свят, свят Господь\nВся земля полна славы Его", style = TextStyle(caps = true))
        save("chroma-song", frames.render(OutputLayout.CHROMA, w, h, FrameState(song, Mode.NORMAL)))
        val photo = frames.render(OutputLayout.CHROMA, w, h, FrameState(image(), Mode.NORMAL))
        assertEquals(w * h, photo.count(0, 0, w, h) { it == 0x00FF00 }, "фото на хромакее — только зелёный")
        assertEquals(0, frames.render(OutputLayout.LOWER_THIRD, w, h, FrameState(verse, Mode.LOGO)).count(0, 0, w, h) { it != 0 })
    }

    @Test
    fun stageShowsCurrentNextClockAndNeverGoesBlank() {
        val start = System.currentTimeMillis() - 754_000
        val s = FrameState(image(), Mode.NORMAL, next = verse, startedAt = start, now = System.currentTimeMillis())
        val stage = frames.render(OutputLayout.STAGE, w, h, s)
        save("stage", stage)
        assertTrue(stage.count(0, (h * 0.1).toInt(), (w * 0.6).toInt(), h) { it == 0x2B4C7E } > 50_000, "текущий слайд крупно слева")
        assertTrue(stage.count((w * 0.64).toInt(), 0, w, h) { it == 0xFFFFFF } > 1000, "справа следующий слайд и часы")

        // «Чёрный» в зале: у ведущего слайд остаётся, сверху — метка.
        val black = frames.render(OutputLayout.STAGE, w, h, s.copy(mode = Mode.BLACK))
        save("stage-black", black)
        assertTrue(black.count(0, (h * 0.1).toInt(), (w * 0.6).toInt(), h) { it == 0x2B4C7E } > 50_000, "слайд не гаснет")
        assertTrue(black.count(0, 0, (w * 0.62).toInt(), (h * 0.1).toInt()) { it == 0xB96E65 } > 300, "метка «В зале чёрный экран»")

        val text = frames.render(OutputLayout.STAGE, w, h, FrameState(verse, Mode.NORMAL, next = TextContent("Аминь"), startedAt = start))
        save("stage-scripture", text)
        assertTrue(text.count(0, 0, (w * 0.6).toInt(), h) { it == 0xFFFFFF } > 5000, "Писание читается крупно")
        assertEquals(0, text.count(0, (h * 0.1).toInt(), (w * 0.6).toInt(), h) { it == 0x18233A }, "без фона оформления зала")

        val film = VideoContent(FileRef("a".repeat(64), "Вступление.mp4", 1, "video/mp4"))
        save("stage-video", frames.render(OutputLayout.STAGE, w, h, FrameState(film, Mode.NORMAL, video = VideoState(true, 83_000, 245_000), startedAt = start)))
        save("stage-idle", frames.render(OutputLayout.STAGE, w, h, FrameState(null, Mode.NORMAL)))
    }

    @Test
    fun hallIsTheReceiverPicture() {
        val img = frames.render(OutputLayout.HALL, w, h, FrameState(PdfContent(FileRef("b".repeat(64), "x.pdf", 1, "application/pdf"), 0), Mode.BLACK))
        assertEquals(0, img.count(0, 0, w, h) { it != 0 })
    }

    @Test
    fun elapsedTime() {
        assertEquals("0:00:00", OutputFrames.elapsed(null, 5_000))
        assertEquals("1:02:03", OutputFrames.elapsed(0, 3_723_000))
        assertEquals("4:05", OutputFrames.duration(245_000))
    }
}
