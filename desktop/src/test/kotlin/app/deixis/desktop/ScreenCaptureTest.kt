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

    /** Окно программы (Linux, X11): снимается именно оно, даже если рядом другое. */
    @Test
    fun capturesOneWindow() {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (!os.contains("linux") || System.getenv("DISPLAY") == null || GraphicsEnvironment.isHeadless() || System.getProperty("deixis.gui") == null) return
        if (AppWindows.run("xwininfo", "-version") == null && AppWindows.run("xwininfo", "-root") == null) return
        val frame = JFrame("Проектор проверки")
        SwingUtilities.invokeAndWait {
            frame.contentPane.background = Color(30, 40, 220)
            frame.setBounds(100, 100, 640, 360)
            frame.isVisible = true
        }
        Thread.sleep(700)
        val info = AppWindows.run("xwininfo", "-name", "Проектор проверки")
        val id = Regex("Window id: (0x[0-9a-fA-F]+)").find(info.orEmpty())?.groupValues?.get(1)?.removePrefix("0x")?.toLong(16)
        if (id == null) { SwingUtilities.invokeAndWait { frame.dispose() }; return }
        val sizes = LinkedBlockingQueue<Pair<Int, Int>>()
        val frames = LinkedBlockingQueue<ByteArray>()
        val sink = object : MirrorSink {
            override fun config(annexB: ByteArray, width: Int, height: Int) { sizes += width to height }
            override fun frame(annexB: ByteArray, key: Boolean, ptsUs: Long) { frames += annexB }
            override fun stopped() {}
        }
        val errors = LinkedBlockingQueue<String>()
        val cap = ScreenCapture(CaptureArea(0, 0, 640, 360, windowId = id), sink, onError = { errors += it })
        try {
            val size = assertNotNull(sizes.poll(15, TimeUnit.SECONDS), "нет кадров окна: ${errors.poll()}")
            println("окно 0x${java.lang.Long.toHexString(id)}: кадр $size")
            assertEquals(640 to 360, size, "кадр размером с окно, а не с экран")
            var blue = false
            LowLatencyDecoder().use { dec ->
                repeat(8) {
                    val d = frames.poll(2, TimeUnit.SECONDS) ?: return@repeat
                    dec.decode(d) { img -> val c = Color(img.getRGB(img.width / 2, img.height / 2)); if (c.blue > 180 && c.red < 80) blue = true }
                }
            }
            assertTrue(blue, "в кадре — синее окно")
        } finally {
            cap.close()
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
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

/** Виртуальный экран Мака (CGVirtualDisplay): появляется в системе как монитор и убирается. Только на Маке в CI. */
class MacVirtualScreenTest {
    @Test
    fun appearsAndGoes() {
        val mac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        if (!mac || System.getProperty("deixis.gui") == null) return
        assertTrue(MacVirtualScreen.supported, "CGVirtualDisplay есть в системе")
        val id = MacVirtualScreen.create().getOrElse { throw AssertionError("дисплей не создан: ${it.message}", it) }
        try {
            var seen = false
            repeat(50) {
                if (!seen) {
                    seen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { it.iDstring.endsWith(" $id") || it.iDstring == id.toString() }
                    if (!seen) Thread.sleep(100)
                }
            }
            println("виртуальный дисплей $id: Java видит — $seen; экраны " + GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.joinToString { it.iDstring })
            assertTrue(seen, "Java видит новый монитор")
            assertTrue(MacVirtualScreen.captureIndex(id) >= 0)
        } finally {
            MacVirtualScreen.remove()
        }
    }
}
