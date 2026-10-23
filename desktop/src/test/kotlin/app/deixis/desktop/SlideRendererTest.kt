package app.deixis.desktop

import app.deixis.core.FileRef
import app.deixis.core.FileStore
import app.deixis.core.ImageContent
import app.deixis.core.Mode
import app.deixis.core.PdfContent
import app.deixis.core.TextContent
import app.deixis.core.TextStyle
import app.deixis.core.sha256Of
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SlideRendererTest {
    init { System.setProperty("java.awt.headless", "true") }

    private val store = FileStore(createTempDirectory("r").toFile())
    private val renderer = SlideRenderer(store)
    private val w = 1280
    private val h = 720

    private fun BufferedImage.lit(x0: Int, y0: Int, x1: Int, y1: Int): Int {
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) if (getRGB(x, y) and 0xFFFFFF != 0) n++
        return n
    }

    @Test
    fun longTextStaysInsideSafeMargins() {
        val text = "Очень длинная строка, которая не помещается на слайд целиком и должна перенестись по словам\nвторая строка"
        val img = renderer.render(w, h, TextContent(text, style = TextStyle(shadow = false)), Mode.NORMAL, null)
        val mx = (w * 0.05).toInt()
        val my = (h * 0.05).toInt()
        assertTrue(img.lit(mx, my, w - mx, h - my) > 1000, "текст нарисован")
        assertEquals(0, img.lit(0, 0, mx - 2, h), "левое поле пустое")
        assertEquals(0, img.lit(w - mx + 2, 0, w, h), "правое поле пустое")
        assertEquals(0, img.lit(0, 0, w, my - 2), "верхнее поле пустое")
    }

    @Test
    fun blackAndClearHideText() {
        val c = TextContent("Слово", style = TextStyle(background = 0xFF18233A))
        assertEquals(0, renderer.render(w, h, c, Mode.BLACK, null).lit(0, 0, w, h))
        val clear = renderer.render(w, h, c, Mode.CLEAR, null)
        assertEquals(Color(0x18, 0x23, 0x3A).rgb, clear.getRGB(w / 2, h / 2), "остаётся только фон")
    }

    @Test
    fun logoAndIdleScreensDraw() {
        assertTrue(renderer.render(w, h, null, Mode.LOGO, null).lit(0, 0, w, h) > 500)
        val idle = renderer.render(w, h, null, Mode.NORMAL, IdleInfo("ТВ в холле", "1234", "192.168.1.5:47820", "deixis://192.168.1.5:47820?pin=1234"))
        assertTrue(idle.lit(0, 0, w, h) > 2000)
    }

    @Test
    fun imageIsLetterboxed() {
        val src = BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().run { color = Color.RED; fillRect(0, 0, 400, 400); dispose() }
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(src, "png", it) }.toByteArray()
        val sha = sha256Of(bytes.inputStream())
        store.append(sha, bytes.size.toLong(), 0, bytes.inputStream())
        val img = renderer.render(w, h, ImageContent(FileRef(sha, "red.png", bytes.size.toLong(), "image/png")), Mode.NORMAL, null)
        assertEquals(Color.RED.rgb, img.getRGB(w / 2, h / 2))
        assertEquals(Color.BLACK.rgb, img.getRGB(10, h / 2), "поля по бокам чёрные")
    }

    @Test
    fun missingPdfPageFails() {
        val ref = FileRef("c".repeat(64), "нет.pdf", 1, "application/pdf")
        assertFailsWith<IllegalStateException> { renderer.render(w, h, PdfContent(ref, 0), Mode.NORMAL, null) }
    }

    @Test
    fun pdfPageKeepsItsColours() {
        // Слайд 4 презентации, переведённой LibreOffice: охристые, синие и красные блоки и цветное фото.
        val bytes = SlideRendererTest::class.java.getResourceAsStream("/colour.pdf")!!.readBytes()
        val sha = sha256Of(bytes.inputStream())
        store.append(sha, bytes.size.toLong(), 0, bytes.inputStream())
        val img = renderer.render(w, h, PdfContent(FileRef(sha, "colour.pdf", bytes.size.toLong(), "application/pdf"), 3), Mode.NORMAL, null)
        var coloured = 0
        for (y in 0 until h step 6) for (x in 0 until w step 6) {
            val c = Color(img.getRGB(x, y))
            if (maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue) > 40) coloured++
        }
        assertTrue(coloured > 200, "цвета страницы PDF сохранились: $coloured цветных точек")
    }
}
