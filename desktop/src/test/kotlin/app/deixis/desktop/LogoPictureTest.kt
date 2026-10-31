package app.deixis.desktop

import app.deixis.core.FileRef
import app.deixis.core.FileStore
import app.deixis.core.Mode
import app.deixis.core.TextContent
import app.deixis.core.sha256Of
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** Своя картинка на «Логотипе» вписана на чёрном; «Уйти с экранов» в кадре — чёрный (само окно прячется). */
class LogoPictureTest {
    @Test
    fun logoPictureReplacesTheMarkAndAwayIsBlack() {
        val files = FileStore(File(createTempDirectory("logo-pic").toFile(), "files"))
        val png = ByteArrayOutputStream().also { out ->
            val img = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
            img.createGraphics().run { color = Color(200, 30, 30); fillRect(0, 0, 40, 20); dispose() }
            ImageIO.write(img, "png", out)
        }.toByteArray()
        val sha = sha256Of(png.inputStream())
        files.append(sha, png.size.toLong(), 0, png.inputStream())
        val ref = FileRef(sha, "церковь.png", png.size.toLong(), "image/png")
        val r = SlideRenderer(files)

        val logo = r.render(320, 180, TextContent("Слайд"), Mode.LOGO, null, logoImage = ref)
        assertEquals(Color(200, 30, 30).rgb, logo.getRGB(160, 90), "картинка в середине кадра")
        assertEquals(Color.BLACK.rgb, logo.getRGB(160, 2), "поля сверху и снизу — чёрные")

        val away = r.render(320, 180, TextContent("Слайд"), Mode.AWAY, null)
        assertEquals(Color.BLACK.rgb, away.getRGB(160, 90))
    }
}
