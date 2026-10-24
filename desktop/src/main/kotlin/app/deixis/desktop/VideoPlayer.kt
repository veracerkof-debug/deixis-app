package app.deixis.desktop

import app.deixis.core.VideoAction
import app.deixis.core.VideoCommand
import app.deixis.core.VideoState
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import uk.co.caprica.vlcj.player.component.InputEvents
import uk.co.caprica.vlcj.player.component.callback.FilledCallbackImagePainter
import uk.co.caprica.vlcj.player.component.callback.ScaledCallbackImagePainter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.media.VideoOrientation
import java.awt.Component
import java.io.File
import javax.swing.SwingUtilities

/**
 * Видео через libVLC: играет почти любой формат и использует аппаратное декодирование, где оно есть.
 * Без установленного VLC [available] = false, и приёмник сообщает пульту, что видео здесь недоступно.
 */
class VideoPlayer(private val onError: (String) -> Unit, private val onFinished: () -> Unit) {

    val available: Boolean = vlcFound

    /** Параметры libVLC для диагностики на месте, например `DEIXIS_VLC_ARGS="--vout=xcb_x11 -vv"`. */
    private val vlcArgs = DEFAULT_ARGS + System.getenv("DEIXIS_VLC_ARGS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()

    /**
     * Windows и Linux: VLC рисует прямо в окно. На macOS встроить окно VLC в окно Java нельзя (зависает),
     * поэтому кадры идут через память и рисуются самим окном, с сохранением пропорций.
     * Ввод VLC отключён: мышь и клавиши получает окно вывода, а не проигрыватель.
     */
    private val component: javax.swing.JComponent? = when {
        !available -> null
        isMac -> CallbackMediaPlayerComponent(MediaPlayerFactory(vlcArgs), null, InputEvents.NONE, true, ScaledCallbackImagePainter())
        else -> EmbeddedMediaPlayerComponent(MediaPlayerFactory(vlcArgs), null, null, InputEvents.DISABLE_NATIVE, null)
    }
    val view: Component? get() = component

    /** Поверхность, по которой щёлкает оператор: сам кадр видео. */
    val surface: Component?
        get() = when (val c = component) {
            is EmbeddedMediaPlayerComponent -> c.videoSurfaceComponent()
            is CallbackMediaPlayerComponent -> c.videoSurfaceComponent()
            else -> null
        }

    private val player: EmbeddedMediaPlayer?
        get() = when (val c = component) {
            is EmbeddedMediaPlayerComponent -> c.mediaPlayer()
            is CallbackMediaPlayerComponent -> c.mediaPlayer()
            else -> null
        }

    /** Кадр сменил размер (новый файл, поворот): окно перекладывает кадр. Вызывается в потоке окон. */
    var onVideoSize: (() -> Unit)? = null

    /** Размер кадра для показа (с пропорциями пикселя и поворотом); null — ещё неизвестен. */
    fun displaySize(): Pair<Float, Float>? = runCatching {
        val t = player?.media()?.info()?.videoTracks()?.firstOrNull() ?: return@runCatching null
        if (t.width() <= 0 || t.height() <= 0) return@runCatching null
        val o = t.orientation()
        val rotated = o == VideoOrientation.LEFT_TOP || o == VideoOrientation.LEFT_BOTTOM ||
            o == VideoOrientation.RIGHT_TOP || o == VideoOrientation.RIGHT_BOTTOM
        app.deixis.core.displaySize(t.width(), t.height(), t.sampleAspectRatio(), t.sampleAspectRatioBase(), rotated)
    }.getOrNull()

    /** Растянуть кадр на окно [size] (ширина, высота) без сохранения пропорций; null — обычные пропорции. */
    fun stretchTo(size: Pair<Int, Int>?) {
        when (val c = component) {
            // На Маке кадр рисует само окно: растянуть — другой способ рисования, а не пропорции VLC.
            is CallbackMediaPlayerComponent -> c.setImagePainter(if (size != null) FilledCallbackImagePainter() else ScaledCallbackImagePainter())
            else -> runCatching { player?.video()?.setAspectRatio(size?.let { "${it.first}:${it.second}" }) }
        }
    }

    /** Уменьшенный снимок текущего кадра — для размытых полей. */
    fun snapshot(width: Int): java.awt.image.BufferedImage? = runCatching { player?.snapshots()?.get(width, 0) }.getOrNull()

    private var currentName = ""
    private var resumeAfterHide = false

    init {
        player?.events()?.addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun error(mediaPlayer: MediaPlayer) {
                SwingUtilities.invokeLater { onError("Не удалось воспроизвести «$currentName»") }
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                SwingUtilities.invokeLater(onFinished)
            }

            override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) {
                SwingUtilities.invokeLater { onVideoSize?.invoke() }
            }

            override fun elementaryStreamSelected(mediaPlayer: MediaPlayer, type: uk.co.caprica.vlcj.media.TrackType, id: Int) {
                if (type == uk.co.caprica.vlcj.media.TrackType.VIDEO) SwingUtilities.invokeLater { onVideoSize?.invoke() }
            }
        })
    }

    /** [audio] = false — второй монитор зала: звук идёт только из одного проигрывателя. */
    fun play(file: File, name: String, loop: Boolean, startMs: Long, audio: Boolean = true) {
        val mp = player ?: return
        currentName = name
        voutChecked = false
        stuckReported = false
        userPaused = false
        startedAt = System.currentTimeMillis()
        mp.controls().repeat = loop
        val options = listOfNotNull(
            if (startMs > 0) ":start-time=${startMs / 1000.0}" else null,
            if (audio) null else ":no-audio",
        ).toTypedArray()
        if (!mp.media().play(file.absolutePath, *options)) onError("Не удалось открыть «$name»")
    }

    /** Демонстрация экрана: поток с приёмника на этом же компьютере, с минимальной буферизацией. */
    fun playStream(url: String) {
        val mp = player ?: return
        currentName = "трансляция экрана"
        voutChecked = false
        stuckReported = false
        userPaused = false
        startedAt = 0L // у потока нет длины: проверка «застрял» не применяется
        mp.controls().repeat = false
        // Опоздавшие кадры не выбрасываются: выброшенный ключевой кадр дал бы серую кашу до следующего.
        mp.media().play(
            url, ":network-caching=$STREAM_CACHING_MS", ":live-caching=$STREAM_CACHING_MS", ":no-audio", ":demux=mp4",
            ":clock-jitter=0", ":avcodec-hurry-up=0", ":avcodec-skip-frame=0", ":no-drop-late-frames", ":no-skip-frames",
        )
    }

    fun apply(cmd: VideoCommand) {
        val mp = player ?: return
        when (cmd.action) {
            VideoAction.PLAY -> { userPaused = false; mp.controls().play() }
            VideoAction.PAUSE -> { userPaused = true; mp.controls().setPause(true) }
            VideoAction.SEEK -> cmd.positionMs?.let { mp.controls().setTime(it.coerceAtLeast(0)) }
            VideoAction.VOLUME -> cmd.volume?.let { mp.audio().setVolume(it.coerceIn(0, 200)) }
            VideoAction.LOOP -> cmd.loop?.let { mp.controls().repeat = it }
        }
    }

    /** На «Чёрный» и «Логотип» фильм встаёт на паузу и продолжается, когда картинку вернули. */
    fun hide() {
        val mp = player ?: return
        resumeAfterHide = mp.status().isPlaying
        if (resumeAfterHide) mp.controls().setPause(true)
    }

    fun unhide() {
        if (resumeAfterHide) player?.controls()?.play()
        resumeAfterHide = false
    }

    fun stop() {
        startedAt = 0L
        player?.controls()?.stop()
        resumeAfterHide = false
    }

    private var voutChecked = false
    private var startedAt = 0L
    private var userPaused = false
    private var stuckReported = false

    /**
     * Звук и время могут идти, даже когда VLC не смог открыть окно видео (нет модуля вывода, сбой драйвера).
     * Тогда зал видит чёрный экран, а пульт должен узнать об этом, а не считать, что всё в порядке.
     */
    private fun checkVideoOutput(mp: MediaPlayer) {
        if (voutChecked || mp.status().time() < 1_500) return
        voutChecked = true
        if (mp.video().trackCount() > 0 && mp.status().videoOutputs() == 0) {
            onError("VLC не смог вывести изображение «$currentName»")
        }
    }

    /** Файл без воспроизводимых дорожек не начинает играть и не сообщает об ошибке сам. */
    private fun checkStuck(mp: MediaPlayer) {
        if (stuckReported || userPaused || startedAt == 0L || resumeAfterHide) return
        if (System.currentTimeMillis() - startedAt > 5_000 && mp.status().time() <= 0 && mp.status().length() <= 0) {
            stuckReported = true
            onError("Не удалось воспроизвести «$currentName»: нет поддерживаемых дорожек")
        }
    }

    fun state(): VideoState? {
        val mp = player ?: return null
        if (mp.status().isPlaying) checkVideoOutput(mp) else checkStuck(mp)
        return VideoState(
            playing = mp.status().isPlaying,
            positionMs = mp.status().time().coerceAtLeast(0),
            durationMs = mp.status().length().coerceAtLeast(0),
            volume = mp.audio().volume().coerceAtLeast(0),
            loop = mp.controls().repeat,
        )
    }

    companion object {
        /** Запас на неровный Wi-Fi. Меньше — заметнее рывки, больше — заметнее отставание от телефона. */
        const val STREAM_CACHING_MS = 150

        private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

        /**
         * Чересстрочное видео (ТВ-записи, старые камеры) — без «гребёнки»: VLC сам решает, нужен ли фильтр.
         * Никаких надписей VLC поверх видео в зале: ни громкости, ни названия файла, ни превью снимка
         * (снимки кадра делаются для размытых полей).
         */
        private val DEFAULT_ARGS = listOf("--deinterlace=-1", "--deinterlace-mode=yadif", "--no-osd", "--no-snapshot-preview", "--no-video-title-show")

        /** Поиск VLC один раз на всю программу: окна вывода пересоздаются, а поиск небыстрый. */
        val vlcFound: Boolean by lazy {
            try {
                NativeDiscovery().discover()
            } catch (e: Throwable) {
                false
            }
        }
    }

    fun release() {
        when (val c = component) {
            is EmbeddedMediaPlayerComponent -> c.release()
            is CallbackMediaPlayerComponent -> c.release()
        }
    }
}
