package app.deixis.desktop

import app.deixis.core.FileStore
import app.deixis.core.Mode
import app.deixis.core.SceneBackground
import app.deixis.core.TextContent
import app.deixis.core.TextStyle
import app.deixis.core.effects.SceneLooks
import app.deixis.core.effects.SceneText
import java.awt.image.BufferedImage
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Живой фон на компьютере: слайд над сценой прозрачный, текст уходит от образа, фон считается сам и меняется во времени. */
class SceneBackdropTest {
    init { System.setProperty("java.awt.headless", "true") }

    private val renderer = SlideRenderer(FileStore(createTempDirectory("scene").toFile()))
    private val w = 1280
    private val h = 720

    private fun alphaAt(img: BufferedImage, x: Int, y: Int) = (img.getRGB(x, y) ushr 24) and 0xFF

    private fun textContent(look: String?) = TextContent(
        "Слава в вышних Богу, и на земли мир, в человецех благоволение",
        style = TextStyle(backgroundScene = look?.let { SceneBackground(it) }),
    )

    @Test
    fun slideOverASceneIsTransparentSoTheWindowCanShowTheBackdrop() {
        val img = renderer.render(w, h, textContent("evening"), Mode.NORMAL, null)
        assertEquals(0, alphaAt(img, 5, 5), "фон слайда прозрачный: под ним кадр сцены")
        var lit = 0
        for (y in 0 until h) for (x in 0 until w) if (alphaAt(img, x, y) > 200) lit++
        assertTrue(lit > 3000, "текст нарисован поверх: $lit")
        // без живого фона слайд непрозрачный, как раньше
        assertEquals(255, alphaAt(renderer.render(w, h, textContent(null), Mode.NORMAL, null), 5, 5))
        // чёрный экран не показывает сцену
        assertEquals(255, alphaAt(renderer.render(w, h, textContent("evening"), Mode.BLACK, null), 5, 5))
    }

    @Test
    fun textMovesAwayFromTheImage() {
        val scene = SceneLooks.load("evening") // крест справа
        assertNotNull(SceneText.margins(scene))
        val box = renderer.textBox(w, h, textContent("evening"))
        assertNotNull(box)
        assertTrue(box.cx < 0.5, "текст левее центра: ${box.cx}")
        assertTrue(box.cx + box.halfW < 0.68, "текст не заходит на образ (крест начинается с 0,67): ${box.cx + box.halfW}")
        val plain = renderer.textBox(w, h, textContent(null))
        assertNotNull(plain)
        assertTrue(plain.cx > 0.45 && plain.cx < 0.55, "без сцены текст по центру: ${plain.cx}")
        // образ слева — текст справа
        assertNull(SceneText.margins(SceneLooks.load("dawn")), "образ по центру — поля не нужны")
    }

    @Test
    fun backdropProducesLivingFramesAndAdaptsToTheTextBox() {
        var repaints = 0
        val b = SceneBackdrop { repaints++ }
        try {
            val box = renderer.textBox(w, h, textContent("joy"))
            b.show(SceneBackground("joy", energy = 0.8f), w, h, box)
            val first = waitFrame(b)
            assertNotNull(first, "кадр должен появиться")
            assertEquals(first.width * 9, first.height * 16, "пропорции окна сохранены")
            val snap1 = first.getRGB(first.width / 2, first.height / 2) to first.getRGB(first.width * 3 / 4, first.height / 3)
            // Ждём следующий кадр, а не фиксированное время: холодная JVM считает первые кадры дольше.
            val second = waitChange(b, first) ?: error("новый кадр не пришёл за 8 с; журнал: ${app.deixis.diag.Log.recent(6).joinToString(" | ")}")
            var diff = 0
            for (y in 0 until second.height step 2) for (x in 0 until second.width step 2) {
                if (second.getRGB(x, y) != first.getRGB(x, y)) diff++
            }
            // Через 3 кадра картинка заметно другая: движение и пульс
            val later = run { Thread.sleep(400); b.frame!! }
            var diff2 = 0
            for (y in 0 until later.height step 2) for (x in 0 until later.width step 2) if (later.getRGB(x, y) != first.getRGB(x, y)) diff2++
            assertTrue(diff + diff2 > 100, "кадры меняются во времени: $diff, $diff2")
            assertTrue(repaints > 5, "окно получает уведомления о кадрах: $repaints")
            assertTrue(b.fps in 5.0..40.0 || b.fps == 0.0, "частота кадров правдоподобна: ${b.fps}")
            assertTrue(b.tierName.isNotEmpty())
            assertNotNull(snap1)
        } finally { b.release() }
    }

    @Test
    fun unknownLookAndTinyWindowsDoNothingHarmful() {
        val b = SceneBackdrop { }
        try {
            b.show(SceneBackground("no-such-look"), w, h, null)
            Thread.sleep(150)
            assertNull(b.frame, "нет такой сцены — кадров нет, вывод не трогаем")
            b.show(SceneBackground("evening"), 4, 4, null) // слишком мал — игнорируется
            Thread.sleep(100)
            assertNull(b.frame)
        } finally { b.release() }
        // после освобождения вызовы безопасны
        b.show(SceneBackground("evening"), w, h, null)
        b.stop()
        b.release()
    }

    @Test
    fun stopKeepsTheLastFrameAndStopsWork() {
        val b = SceneBackdrop { }
        try {
            b.show(SceneBackground("plain"), w, h, null)
            assertNotNull(waitFrame(b))
            b.stop()
            Thread.sleep(120)
            val keep = b.frame
            Thread.sleep(150)
            assertTrue(keep === b.frame, "после остановки кадр не меняется")
        } finally { b.release() }
    }

    private fun waitChange(b: SceneBackdrop, old: BufferedImage): BufferedImage? {
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end) { b.frame?.takeIf { it !== old }?.let { return it }; Thread.sleep(20) }
        return null
    }

    private fun waitFrame(b: SceneBackdrop): BufferedImage? {
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end) { b.frame?.let { return it }; Thread.sleep(20) }
        return null
    }
}
