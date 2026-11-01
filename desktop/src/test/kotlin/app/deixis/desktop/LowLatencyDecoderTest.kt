package app.deixis.desktop

import app.deixis.core.annexBSamples
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LowLatencyDecoderTest {
    /** Запись H.264 как от телефона: Baseline, без B-кадров, ключевой кадр раз в секунду. Нет ffmpeg — проверка пропускается. */
    private fun record(color: String, frames: Int, size: String = "640x360", src: String = "color=c=$color:s=$size:r=30"): ByteArray? {
        val out = File(Files.createTempDirectory("lld").toFile(), "s.h264")
        val ok = runCatching {
            ProcessBuilder(
                "ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", src,
                "-frames:v", "$frames", "-c:v", "libx264", "-profile:v", "baseline", "-bf", "0", "-g", "30",
                "-tune", "zerolatency", "-pix_fmt", "yuv420p", "-f", "h264", out.path,
            ).inheritIO().start().waitFor() == 0
        }.getOrDefault(false)
        return if (ok && out.isFile) out.readBytes() else null
    }

    @Test
    fun everyFrameComesOutAtOnceWithTheRightPicture() {
        if (!LowLatencyDecoder.available) { println("FFmpeg недоступен — пропуск"); return }
        val stream = record("red", 45) ?: run { println("нет ffmpeg с libx264 — пропуск"); return }
        val samples = annexBSamples(stream)
        assertEquals(45, samples.size)
        LowLatencyDecoder().use { d ->
            var lagged = 0
            var last: java.awt.image.BufferedImage? = null
            for (s in samples) {
                var got = 0
                d.decode((s.config ?: ByteArray(0)) + s.data) { got++; last = it }
                // Главное свойство: кадр выходит сразу, декодер не копит кадры у себя.
                if (got != 1) lagged++
            }
            assertEquals(0, lagged, "кадров, вышедших не сразу")
            val img = last!!
            assertEquals(640 to 360, img.width to img.height)
            val rgb = img.getRGB(320, 180)
            val r = rgb shr 16 and 0xFF; val g = rgb shr 8 and 0xFF; val b = rgb and 0xFF
            assertTrue(r > 200 && g < 60 && b < 60, "красный кадр, а пришло $r,$g,$b")
        }
    }

    /** Full HD с движением (как экран телефона): время от кадра до готовой картинки. Цель — единицы миллисекунд. */
    @Test
    fun fullHdFrameIsReadyInMilliseconds() {
        if (!LowLatencyDecoder.available) return
        val stream = record("", 90, src = "testsrc2=s=1920x1080:r=30") ?: return
        val samples = annexBSamples(stream)
        LowLatencyDecoder().use { d ->
            val times = samples.map { s ->
                val t0 = System.nanoTime()
                var got = 0
                d.decode((s.config ?: ByteArray(0)) + s.data) { got++ }
                assertEquals(1, got)
                (System.nanoTime() - t0) / 1_000_000.0
            }
            val warm = times.drop(10).sorted()
            val median = warm[warm.size / 2]
            val p95 = warm[(warm.size * 95) / 100]
            println("Full HD: медиана %.1f мс, 95%% — %.1f мс, худший %.1f мс".format(median, p95, warm.last()))
            assertTrue(median < 33.0, "кадр Full HD дольше кадра потока: $median мс")
        }
    }

    @Test
    fun ffmpegBuildIsLgpl() {
        if (!LowLatencyDecoder.available) return
        val cfg = LowLatencyDecoder.configuration()
        println("FFmpeg: $cfg")
        assertFalse(cfg.contains("--enable-gpl"), "сборка FFmpeg с GPL-частями")
        assertFalse(cfg.contains("--enable-nonfree"), "сборка FFmpeg с несвободными частями")
    }
}
