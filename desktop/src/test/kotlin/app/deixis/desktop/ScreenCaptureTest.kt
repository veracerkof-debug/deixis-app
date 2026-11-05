package app.deixis.desktop

import app.deixis.core.MirrorSink
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Съёмка монитора: настоящий X-экран (Xvfb в CI), сжатие H.264 и обратное декодирование. */
class ScreenCaptureTest {
    @Test
    fun fitKeepsAspectAndEvenSides() {
        assertEquals(1920 to 1080, ScreenCapture.fit(1920, 1080, 1920))
        assertEquals(1280 to 720, ScreenCapture.fit(1920, 1080, 1280))
        assertEquals(1280 to 800, ScreenCapture.fit(2560, 1600, 1280))
        assertEquals(1366 to 768, ScreenCapture.fit(1366, 768, 1920))
        val (w, h) = ScreenCapture.fit(1365, 767, 1920)
        assertTrue(w % 2 == 0 && h % 2 == 0)
    }

    @Test
    fun capturesTheScreenIntoH264() {
        // Linux (Xvfb в CI) и Windows. На Маке съёмка требует разрешения «Запись экрана» — в CI его не дать.
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val canRun = (os.contains("linux") && System.getenv("DISPLAY") != null) || os.contains("win")
        if (!canRun || GraphicsEnvironment.isHeadless() || System.getProperty("deixis.gui") == null) return
        // Красное окно на весь экран: его цвет должен прийти через съёмку, сжатие и декодер.
        val frame = JFrame()
        val b = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        SwingUtilities.invokeAndWait {
            frame.isUndecorated = true
            frame.contentPane.background = Color(220, 30, 30)
            frame.bounds = b
            frame.isVisible = true
        }
        Thread.sleep(500)
        val configs = LinkedBlockingQueue<Pair<Int, Int>>()
        val frames = LinkedBlockingQueue<Pair<ByteArray, Boolean>>()
        val sink = object : MirrorSink {
            override fun config(annexB: ByteArray, width: Int, height: Int) { configs += width to height }
            override fun frame(annexB: ByteArray, key: Boolean, ptsUs: Long) { frames += annexB to key }
            override fun stopped() {}
        }
        val errors = LinkedBlockingQueue<String>()
        val cap = ScreenCapture(CaptureArea(b.x, b.y, b.width, b.height), sink, onError = { errors += it })
        try {
            val size = configs.poll(15, TimeUnit.SECONDS)
            assertNotNull(size, "нет SPS/PPS: ${errors.poll()}")
            val (first, key) = assertNotNull(frames.poll(5, TimeUnit.SECONDS))
            assertTrue(key, "первый кадр — ключевой")
            var red = false
            LowLatencyDecoder().use { dec ->
                var data = first
                repeat(10) {
                    dec.decode(data) { img ->
                        val c = Color(img.getRGB(img.width / 2, img.height / 2))
                        if (c.red > 180 && c.green < 80 && c.blue < 80) red = true
                    }
                    data = frames.poll(2, TimeUnit.SECONDS)?.first ?: return@repeat
                }
            }
            assertTrue(red, "в середине кадра — красное окно")
            cap.requestKey()
            var gotKey = false
            repeat(30) { if (frames.poll(1, TimeUnit.SECONDS)?.second == true) { gotKey = true; return@repeat } }
            assertTrue(gotKey, "по просьбе зрителя приходит ключевой кадр")
        } finally {
            cap.close()
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }
}
