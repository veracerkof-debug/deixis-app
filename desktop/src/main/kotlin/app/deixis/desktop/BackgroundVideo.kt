package app.deixis.desktop

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.nio.ByteBuffer

/**
 * Видеофон слайдов: VLC декодирует кадры в память, окно рисует их под текстом (поверх встроенного окна VLC
 * рисовать нельзя). Без звука, по кругу; тот же файл не начинается заново, когда меняется слайд.
 * Кадры — не больше [MAX_W] по ширине: фону хватает FullHD, а 4K так не грузит процессор.
 */
class BackgroundVideo(private val onFrame: () -> Unit) {
    val available: Boolean = VideoPlayer.vlcFound
    private val factory: MediaPlayerFactory? = if (available) MediaPlayerFactory("--no-osd", "--no-snapshot-preview", "--no-video-title-show", "--deinterlace=-1") else null
    private val player = factory?.mediaPlayers()?.newEmbeddedMediaPlayer()
    @Volatile private var image: BufferedImage? = null
    @Volatile var playingSha: String? = null
        private set

    /** Последний кадр; null — ещё нет. */
    val frame: BufferedImage? get() = image

    init {
        player?.videoSurface()?.set(factory!!.videoSurfaces().newVideoSurface(object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                val scale = if (sourceWidth > MAX_W) MAX_W.toFloat() / sourceWidth else 1f
                val w = (sourceWidth * scale).toInt().coerceAtLeast(1)
                val h = (sourceHeight * scale).toInt().coerceAtLeast(1)
                image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                return RV32BufferFormat(w, h)
            }
            override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit
            override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
        }, object : RenderCallback {
            override fun lock(mediaPlayer: MediaPlayer) = Unit
            override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat, displayWidth: Int, displayHeight: Int) {
                val img = image ?: return
                val data = (img.raster.dataBuffer as DataBufferInt).data
                val ib = nativeBuffers[0].asIntBuffer()
                ib.get(data, 0, minOf(data.size, ib.remaining()))
                onFrame()
            }
            override fun unlock(mediaPlayer: MediaPlayer) = Unit
        }, true))
    }

    /** Играть файл по кругу; тот же файл — продолжить, не начиная заново. */
    fun play(file: File, sha: String) {
        val mp = player ?: return
        if (playingSha == sha && mp.status().isPlaying) return
        playingSha = sha
        mp.controls().repeat = true
        mp.media().play(file.absolutePath, ":no-audio", ":input-repeat=65535")
    }

    /** Где сейчас видеофон, мс (для проверки, что смена слайда его не перезапускает). */
    fun timeMs(): Long = runCatching { player?.status()?.time() ?: 0L }.getOrDefault(0L)

    fun stop() {
        playingSha = null
        image = null
        runCatching { player?.controls()?.stop() }
    }

    fun release() {
        stop()
        runCatching { player?.release() }
        runCatching { factory?.release() }
    }

    private companion object {
        const val MAX_W = 1920
    }
}
