package app.deixis.core

/** Прямоугольник кадра на экране в пикселях экрана; может выходить за экран (заполнить, исходный размер). */
data class VideoRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Размер кадра для показа: с пропорциями пикселя (SAR, у DVD и HDV пиксели не квадратные) и поворотом
 * (вертикальное видео с телефона хранится повёрнутым на 90°).
 */
fun displaySize(width: Int, height: Int, sarNum: Int = 1, sarDen: Int = 1, rotated: Boolean = false): Pair<Float, Float> {
    val sar = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen else 1f
    val w = width * sar
    val h = height.toFloat()
    return if (rotated) h to w else w to h
}

/**
 * Где лежит кадр [videoW]×[videoH] (уже с SAR и поворотом) на экране [screenW]×[screenH] в режиме [fit].
 * Одна формула для ТВ, телефона, компьютера и экрана в браузере — картинка везде одинакова.
 */
fun videoRect(screenW: Float, screenH: Float, videoW: Float, videoH: Float, fit: VideoFit): VideoRect {
    if (videoW <= 0f || videoH <= 0f || fit == VideoFit.STRETCH) return VideoRect(0f, 0f, screenW, screenH)
    val (w, h) = when (fit) {
        VideoFit.FIT -> minOf(screenW / videoW, screenH / videoH).let { videoW * it to videoH * it }
        VideoFit.FILL -> maxOf(screenW / videoW, screenH / videoH).let { videoW * it to videoH * it }
        else -> videoW to videoH
    }
    return VideoRect((screenW - w) / 2f, (screenH - h) / 2f, w, h)
}
