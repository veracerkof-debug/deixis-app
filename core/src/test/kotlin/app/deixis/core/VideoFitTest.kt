package app.deixis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoFitTest {
    private fun r(l: Float, t: Float, w: Float, h: Float) = VideoRect(l, t, w, h)

    @Test
    fun fitKeepsTheWholeFrame() {
        // Вертикальное 1080×1920 на экране 1920×1080 — поля по бокам, ничего не обрезано.
        assertEquals(r(656.25f, 0f, 607.5f, 1080f), videoRect(1920f, 1080f, 1080f, 1920f, VideoFit.FIT))
        // 16:9 на проекторе 4:3 — поля сверху и снизу.
        assertEquals(r(0f, 96f, 1024f, 576f), videoRect(1024f, 768f, 1920f, 1080f, VideoFit.FIT))
        // 4K на экране FullHD — уменьшено целиком.
        assertEquals(r(0f, 0f, 1920f, 1080f), videoRect(1920f, 1080f, 3840f, 2160f, VideoFit.FIT))
    }

    @Test
    fun fillCoversStretchFillsOriginalIsPixelForPixel() {
        assertEquals(r(-128f, 0f, 1280f, 720f).let { it }, videoRect(1024f, 720f, 1280f, 720f, VideoFit.FILL))
        assertEquals(r(0f, 0f, 1024f, 768f), videoRect(1024f, 768f, 1920f, 1080f, VideoFit.STRETCH))
        assertEquals(r(640f, 360f, 640f, 360f), videoRect(1920f, 1080f, 640f, 360f, VideoFit.ORIGINAL))
        assertEquals(r(-960f, -540f, 3840f, 2160f), videoRect(1920f, 1080f, 3840f, 2160f, VideoFit.ORIGINAL))
    }

    @Test
    fun sarAndRotation() {
        // DVD PAL 720×576 с пикселями 16:11 — на экране 16:9 (≈1047×576).
        val (w, h) = displaySize(720, 576, 16, 11)
        assertEquals(1047.27f, w, 0.1f)
        assertEquals(576f, h)
        // Видео с телефона 1920×1080, записанное с поворотом 90°, — вертикальное.
        assertEquals(1080f to 1920f, displaySize(1920, 1080, rotated = true))
        // Старые файлы без новых полей открываются: вписать, чёрные поля.
        val old = DeixisJson.decodeFromString(Content.serializer(), """{"type":"video","file":{"sha256":"a","name":"v.mp4","size":1,"mime":"video/mp4"}}""") as VideoContent
        assertEquals(VideoFit.FIT, old.fit)
        assertEquals(VideoBackdrop.BLACK, old.backdrop)
    }
}
