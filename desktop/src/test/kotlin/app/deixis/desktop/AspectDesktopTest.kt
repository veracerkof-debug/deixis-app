package app.deixis.desktop

import app.deixis.core.FileStore
import app.deixis.core.Mode
import app.deixis.core.OutputLayout
import app.deixis.core.SAFE_MARGIN
import app.deixis.core.ScriptureBlock
import app.deixis.core.ScriptureContent
import app.deixis.core.TextContent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Кадры на любом формате экрана: 16:9, 4:3, 1:1, 9:16, 32:9 и узкая колонна 1:3. Ничего не касается краёв
 * (значит, не обрезано), и в кадре что-то нарисовано. `-Ddeixis.outputsPng=папка` сохраняет кадры для просмотра.
 */
class AspectDesktopTest {
    private val formats = listOf("16x9" to (1280 to 720), "4x3" to (1024 to 768), "1x1" to (900 to 900),
        "9x16" to (720 to 1280), "32x9" to (2560 to 720), "1x3" to (400 to 1200))
    private val frames = OutputFrames(FileStore(File(createTempDirectory("aspect").toFile(), "files")))
    private val pngDir = System.getProperty("deixis.outputsPng")?.let { File(it, "aspect") }

    private val scripture = ScriptureContent(
        blocks = listOf(
            ScriptureBlock("16 Ибо так возлюбил Бог мир, что отдал Сына Своего Единородного, дабы всякий верующий в Него не погиб, но имел жизнь вечную.", "Синодальный"),
            ScriptureBlock("16 For God so loved the world, that he gave his only begotten Son, that whosoever believeth in him should not perish.", "King James"),
        ),
        ref = "Евангелие от Иоанна 3:16", sideBySide = true,
    )
    private val song = TextContent("Великий Бог, когда на мир смотрю я,\nНа всё, что Ты создал Своим перстом", "Великий Бог · Куплет 1")
    private val idle = IdleInfo("Проектор в большом зале", "4821", "192.168.1.20:7300", "deixis://192.168.1.20:7300?pin=4821")

    private fun lit(rgb: Int) = ((rgb shr 16 and 0xFF) + (rgb shr 8 and 0xFF) + (rgb and 0xFF)) > 60

    /** Полоса у края шириной в половину безопасного поля — чёрная: ничего не вылезло за поля. */
    private fun assertInside(name: String, img: BufferedImage, edgeFraction: Float = SAFE_MARGIN * 0.5f) {
        val e = (minOf(img.width, img.height) * edgeFraction).toInt().coerceAtLeast(1)
        var any = false
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val edge = x < e || y < e || x >= img.width - e || y >= img.height - e
            val on = lit(img.getRGB(x, y))
            if (on) any = true
            if (edge && on) throw AssertionError("$name: рисунок у края экрана в ($x, $y) — обрезано")
        }
        assertTrue(any, "$name: кадр пустой")
    }

    private fun save(name: String, img: BufferedImage) {
        val dir = pngDir ?: return
        dir.mkdirs()
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    @Test
    fun hallFramesFitEveryFormat() {
        for ((f, wh) in formats) {
            val (w, h) = wh
            fun check(kind: String, s: FrameState, layout: OutputLayout = OutputLayout.HALL) {
                val img = frames.render(layout, w, h, s)
                save("$f-$kind", img)
                assertInside("$f $kind", img)
            }
            check("song", FrameState(song, Mode.NORMAL))
            check("scripture", FrameState(scripture, Mode.NORMAL))
            check("logo", FrameState(song, Mode.LOGO))
            check("idle", FrameState(null, Mode.NORMAL, idle = idle))
            check("lower-third", FrameState(scripture, Mode.NORMAL), OutputLayout.LOWER_THIRD)
        }
    }

    @Test
    fun stageFitsEveryFormat() {
        for ((f, wh) in formats) {
            val (w, h) = wh
            val img = frames.render(OutputLayout.STAGE, w, h, FrameState(scripture, Mode.NORMAL, next = song, startedAt = 0, now = 3_723_000))
            save("$f-stage", img)
            // У «Сцены» окна идут почти до полей; край экрана — чёрный.
            assertInside("$f stage", img, edgeFraction = 0.02f)
        }
    }
}
