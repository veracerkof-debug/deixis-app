package app.deixis.receiver

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import app.deixis.core.VideoAction
import app.deixis.core.VideoCommand
import app.deixis.core.VideoState
import androidx.compose.runtime.mutableStateOf
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/**
 * Видео через libVLC: играет почти любой формат, аппаратное декодирование включено,
 * а если устройство не справляется, VLC переходит на программное.
 */
class VlcPlayer(context: Context, private val onError: (String) -> Unit) {

    private val libVlc = LibVLC(context.applicationContext, arrayListOf("--no-sub-autodetect-file", "--audio-time-stretch"))
    private val player = MediaPlayer(libVlc)
    private val main = Handler(Looper.getMainLooper())

    private var name = ""
    private var loop = false
    private var startedAt = 0L
    private var userPaused = false
    private var stuckReported = false
    private var resumeAfterHide = false
    private var attached = false

    /**
     * Пропорции картинки с учётом поворота (вертикальное видео с телефона хранится повёрнутым).
     * Экран ставит под видео окно ровно этих пропорций, поэтому кадр целиком вписан, а не обрезан.
     */
    val aspect = mutableStateOf<Float?>(null)

    init {
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Vout -> main.post { trackAspect()?.let { aspect.value = it } }
                MediaPlayer.Event.EncounteredError -> main.post { onError("Не удалось воспроизвести «$name»") }
                MediaPlayer.Event.EndReached -> if (loop) main.post { player.stop(); player.play() }
            }
        }
    }

    fun attach(layout: VLCVideoLayout) {
        if (attached) player.detachViews()
        player.attachViews(layout, null, false, false)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
        attached = true
    }

    fun detach() {
        if (attached) player.detachViews()
        attached = false
    }

    fun play(file: File, name: String, loop: Boolean, startMs: Long) {
        this.name = name
        this.loop = loop
        startedAt = System.currentTimeMillis()
        userPaused = false
        stuckReported = false
        resumeAfterHide = false
        aspect.value = fileAspect(file)
        val media = Media(libVlc, file.absolutePath)
        media.setHWDecoderEnabled(true, false)
        if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
        player.setMedia(media)
        media.release()
        player.play()
    }

    fun apply(cmd: VideoCommand) {
        when (cmd.action) {
            VideoAction.PLAY -> { userPaused = false; player.play() }
            VideoAction.PAUSE -> { userPaused = true; player.pause() }
            VideoAction.SEEK -> cmd.positionMs?.let { player.setTime(it.coerceAtLeast(0)) }
            VideoAction.VOLUME -> cmd.volume?.let { player.setVolume(it.coerceIn(0, 200)) }
            VideoAction.LOOP -> cmd.loop?.let { loop = it }
        }
    }

    /** На «Чёрный» и «Логотип» фильм встаёт на паузу и продолжается, когда картинку вернули. */
    fun hide() {
        resumeAfterHide = player.isPlaying
        if (resumeAfterHide) player.pause()
    }

    fun unhide() {
        if (resumeAfterHide) player.play()
        resumeAfterHide = false
    }

    fun stop() {
        startedAt = 0L
        resumeAfterHide = false
        player.stop()
    }

    fun state(): VideoState {
        checkStuck()
        return VideoState(
            playing = player.isPlaying,
            positionMs = player.time.coerceAtLeast(0),
            durationMs = player.length.coerceAtLeast(0),
            volume = player.volume.coerceAtLeast(0),
            loop = loop,
        )
    }

    /** Файл без воспроизводимых дорожек не начинает играть и сам об ошибке не сообщает. */
    private fun checkStuck() {
        if (stuckReported || userPaused || resumeAfterHide || startedAt == 0L || player.isPlaying) return
        if (System.currentTimeMillis() - startedAt > 5_000 && player.time <= 0 && player.length <= 0) {
            stuckReported = true
            onError("Не удалось воспроизвести «$name»: нет поддерживаемых дорожек")
        }
    }

    private fun fileAspect(file: File): Float? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: return@runCatching null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: return@runCatching null
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w <= 0f || h <= 0f) null else if (rot % 180 != 0) h / w else w / h
        } finally {
            runCatching { r.release() }
        }
    }.getOrNull()

    /** Пропорции из дорожки VLC: если файл не прочитался заранее (редкий контейнер). */
    private fun trackAspect(): Float? = runCatching {
        val t = player.currentVideoTrack ?: return@runCatching null
        if (t.width <= 0 || t.height <= 0) return@runCatching null
        val sar = if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toFloat() / t.sarDen else 1f
        val w = t.width * sar
        val h = t.height.toFloat()
        val rotated = t.orientation == IMedia.VideoTrack.Orientation.RightTop || t.orientation == IMedia.VideoTrack.Orientation.LeftBottom ||
            t.orientation == IMedia.VideoTrack.Orientation.LeftTop || t.orientation == IMedia.VideoTrack.Orientation.RightBottom
        if (rotated) h / w else w / h
    }.getOrNull()

    fun release() {
        detach()
        player.release()
        libVlc.release()
    }
}
