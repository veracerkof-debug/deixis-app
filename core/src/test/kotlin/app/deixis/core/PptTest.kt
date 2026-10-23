package app.deixis.core

import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PptTest {
    private fun sample(name: String) = File("../desktop/src/test/resources/$name").takeIf { it.isFile } ?: File("desktop/src/test/resources/$name")

    private fun render(deck: Deck, out: File?, scale: Float = 1.5f): List<BufferedImage> = deck.slides.map { s ->
        val img = BufferedImage((deck.width * scale).toInt(), (deck.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.composite = AlphaComposite.SrcOver
        g.scale(scale.toDouble(), scale.toDouble())
        DeckRender.paint(deck, s, AwtPainter(g))
        g.dispose()
        out?.let { d -> d.mkdirs(); ImageIO.write(img, "png", File(d, "slide-${s.number}.png")) }
        img
    }

    /** Тексты слайдов: абзацы без пробелов по краям, пустые пропускаются. */
    private fun texts(deck: Deck): List<List<String>> = deck.slides.map { s ->
        fun of(items: List<DeckItem>): List<String> = items.flatMap { item ->
            when (item) {
                is DeckItem.Shape -> item.text?.paras.orEmpty().map { p -> p.runs.joinToString("") { it.text } }
                is DeckItem.Table -> item.cells.flatten().flatMap { c -> c.text?.paras.orEmpty().map { p -> p.runs.joinToString("") { it.text } } }
                else -> emptyList()
            }
        }
        of(s.items).map { it.replace('\n', ' ').replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
    }

    @Test
    fun readsLectureDeck() {
        val ppt = Ppt.read(sample("lecture16x9.ppt"))
        val pptx = Pptx.read(sample("lecture16x9.pptx"))
        assertEquals(pptx.slides.size, ppt.slides.size, "число слайдов (скрытый пропущен)")
        assertEquals(pptx.width, ppt.width, 1f)
        assertEquals(pptx.height, ppt.height, 1f)
        val a = texts(ppt)
        val b = texts(pptx)
        for (i in b.indices) assertEquals(b[i].sorted(), a[i].sorted(), "тексты слайда ${i + 1}")
        assertTrue(a.flatten().none { it.contains("СКРЫТЫЙ") })

        val pictures = ppt.slides.flatMap { it.items }.filterIsInstance<DeckItem.Picture>()
        assertTrue(pictures.isNotEmpty(), "картинка найдена")
        val bytes = ppt.image(pictures.first().image)
        assertTrue(bytes != null && ImageIO.read(bytes.inputStream()) != null, "картинка читается")

        val out = System.getProperty("deixis.render")?.let { File(it, "lecture-ppt") }
        val images = render(ppt, out)
        val img = images.first()
        val colors = (0 until img.width step 7).flatMap { x -> (0 until img.height step 7).map { y -> img.getRGB(x, y) } }.toSet()
        assertTrue(colors.size > 3)
    }

    /** Ручная проверка любого файла: -Ddeixis.renderFile=файл.ppt -Ddeixis.render=папка. */
    @Test
    fun rendersGivenFile() {
        val f = System.getProperty("deixis.renderFile")?.let { File(it) }?.takeIf { it.extension.lowercase() in setOf("ppt", "pps", "pot") } ?: return
        val deck = Ppt.read(f)
        render(deck, File(System.getProperty("deixis.render") ?: "build/render"))
        println("Слайдов: ${deck.slides.size}, ${deck.width}×${deck.height}")
        println("Предупреждения: " + deck.warnings.joinToString("; "))
    }

    /** Отладка: дерево записей, -Ddeixis.pptDump=файл.ppt. */
    @Test
    fun dumpsRecords() {
        val f = System.getProperty("deixis.pptDump")?.let { File(it) } ?: return
        val doc = Cfb.open(f).use { it.stream("PowerPoint Document")!! }
        val sb = StringBuilder()
        fun walk(from: Int, to: Int, depth: Int) {
            var p = from
            while (p + 8 <= to) {
                val vi = Cfb.le16(doc, p); val type = Cfb.le16(doc, p + 2); val len = Cfb.le32(doc, p + 4)
                val end = minOf(to, p + 8 + maxOf(len, 0))
                sb.append("  ".repeat(depth)).append("@$p ${"%04X".format(type)} inst=${vi ushr 4} len=$len")
                if (vi and 0xF != 0xF) {
                    val n = minOf(len, if (type == 0xF00B || type == 0xF122) 1200 else 48).coerceAtLeast(0)
                    sb.append("  ").append((0 until n).joinToString(" ") { "%02x".format(doc[p + 8 + it]) })
                    if (type == 0x0FA0) sb.append("  «").append(String(doc, p + 8, len, Charsets.UTF_16LE).replace('\r', '¶').take(80)).append("»")
                    if (type == 0x0FA8) sb.append("  «").append(String(doc, p + 8, len, Charsets.ISO_8859_1).replace('\r', '¶').take(80)).append("»")
                }
                sb.append('\n')
                if (vi and 0xF == 0xF) walk(p + 8, end, depth + 1)
                p = end
            }
        }
        walk(0, doc.size, 0)
        File(System.getProperty("deixis.render") ?: "build", f.nameWithoutExtension + ".dump.txt").writeText(sb.toString())
    }

    @Test
    fun rejectsEncrypted() {
        val f = File.createTempFile("enc", ".ppt").apply { writeBytes(PptTest::class.java.getResourceAsStream("/encrypted.ppt")!!.readBytes()) }
        val e = assertFailsWith<PptxException> { Ppt.read(f) }
        assertTrue(e.message!!.contains("паролем"), e.message)
        f.delete()
    }

    @Test
    fun rejectsGarbage() {
        val notCfb = File.createTempFile("not", ".ppt").apply { writeBytes(ByteArray(2048) { (it * 7).toByte() }) }
        assertFailsWith<PptxException> { Ppt.read(notCfb) }
        // Настоящий заголовок, испорченное содержимое: понятная ошибка, без зависания.
        val good = sample("lecture16x9.ppt").readBytes()
        val rnd = java.util.Random(1)
        repeat(30) { n ->
            val bad = good.copyOf()
            for (k in 0 until 200) bad[512 + rnd.nextInt(bad.size - 512)] = rnd.nextInt().toByte()
            if (n % 3 == 0) for (k in 0x2C until 0x50) bad[k] = rnd.nextInt().toByte()
            val f = File.createTempFile("bad", ".ppt").apply { writeBytes(bad) }
            val r = runCatching { Ppt.read(f) }
            r.exceptionOrNull()?.let { assertTrue(it is PptxException, "ошибка $it") }
            f.delete()
        }
        val cut = File.createTempFile("cut", ".ppt").apply { writeBytes(good.copyOf(good.size / 3)) }
        runCatching { Ppt.read(cut) }.exceptionOrNull()?.let { assertTrue(it is PptxException, "ошибка $it") }
    }
}
