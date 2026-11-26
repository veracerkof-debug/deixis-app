package app.deixis.core.effects

/**
 * Малые кадры сцены для интерфейса: превью в студии и образцы стилей. Кадр отдаётся BMP-файлом: его читают и Android,
 * и компьютер, а пульту не нужен ни OpenGL, ни своя работа с пикселями.
 */
object SceneFrames {
    /** Несжатый BMP 24 бита из пикселей `0xAARRGGBB` ([w]×[h], строки сверху вниз). */
    fun bmp(pixels: IntArray, w: Int, h: Int): ByteArray {
        val row = (w * 3 + 3) and 3.inv()
        val size = 54 + row * h
        val out = ByteArray(size)
        fun put32(at: Int, v: Int) { for (i in 0..3) out[at + i] = (v ushr (8 * i)).toByte() }
        out[0] = 'B'.code.toByte(); out[1] = 'M'.code.toByte()
        put32(2, size); put32(10, 54); put32(14, 40); put32(18, w); put32(22, h)
        out[26] = 1; out[28] = 24; put32(34, row * h)
        for (y in 0 until h) {
            var o = 54 + (h - 1 - y) * row
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                out[o++] = p.toByte(); out[o++] = (p ushr 8).toByte(); out[o++] = (p ushr 16).toByte()
            }
        }
        return out
    }

    /** Один кадр сцены на [seconds]-й секунде при энергии [energy]: образец для сетки стилей. Не меняет сцену. */
    fun still(scene: Scene, w: Int, h: Int, energy: Double = 0.6, seconds: Double = 3.0, quiet: Double? = null): IntArray {
        val r = SceneRenderer(scene, w, h, (w / 2).coerceAtLeast(48))
        quiet?.let { r.quiet = it }
        r.jumpEnergy(energy)
        val dt = 1.0 / 15
        var t = 0.0
        while (t < seconds) { r.advance(dt, energy); t += dt }
        return IntArray(w * h).also { r.render(it, null) }
    }
}
