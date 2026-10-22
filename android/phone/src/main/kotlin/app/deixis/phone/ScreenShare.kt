package app.deixis.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import app.deixis.core.ScreenStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Демонстрация экрана телефона. Android требует для захвата экрана видимую службу с уведомлением:
 * пока оно есть, человек знает, что экран транслируется, и может остановить трансляцию из шторки.
 */
class ScreenShareService : Service() {
    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val controller = (application as PhoneApp).controller
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = intent?.let {
            if (Build.VERSION.SDK_INT >= 33) it.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") it.getParcelableExtra(EXTRA_DATA)
        }
        // Уведомление — до получения проекции: так требует Android 14.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        val stream = controller.openScreenStream()
        if (data == null || stream == null) {
            controller.screenStopped("Нет связи с экраном")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data) ?: error("Нет разрешения на захват экрана")
            projection = p
            capture = ScreenCapture(this, p, stream) { w, h -> controller.showScreen(w, h) }.also { it.start() }
        } catch (e: Exception) {
            stream.close()
            controller.screenStopped(e.message ?: "Не удалось начать трансляцию экрана")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        capture?.stop()
        capture = null
        runCatching { projection?.stop() }
        projection = null
        (application as PhoneApp).controller.screenStopped(null)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Трансляция экрана", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle("Экран транслируется")
            .setContentText("Всё, что на экране телефона, видно в зале")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Остановить", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "screen"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "app.deixis.phone.STOP_SCREEN"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, ScreenShareService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenShareService::class.java))
        }
    }
}

/**
 * Захват экрана в H.264: виртуальный дисплей рисует прямо во входную поверхность кодировщика.
 * Поворот телефона пересоздаёт кодировщик с новым размером; виртуальный дисплей остаётся тем же
 * (на Android 14 его можно создать только один раз за разрешение).
 */
private class ScreenCapture(
    private val context: Context,
    private val projection: MediaProjection,
    private val stream: ScreenStream,
    private val onSize: (Int, Int) -> Unit,
) {
    private val thread = HandlerThread("deixis-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private var display: VirtualDisplay? = null
    private var encoder: Encoder? = null
    private var size = 0 to 0

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && targetSize().first != size.first) restart()
        }
    }

    fun start() {
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = ScreenShareService.stop(context)
        }, handler)
        val (w, h) = targetSize()
        val enc = Encoder(w, h, stream)
        encoder = enc
        size = w to h
        display = projection.createVirtualDisplay(
            "Deixis", w, h, density(), DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, enc.surface, null, handler,
        )
        onSize(w, h)
        displays.registerDisplayListener(listener, handler)
    }

    private fun restart() {
        val (w, h) = targetSize()
        val old = encoder
        val enc = Encoder(w, h, stream)
        display?.resize(w, h, density())
        display?.surface = enc.surface
        encoder = enc
        size = w to h
        old?.stop()
        onSize(w, h)
    }

    fun stop() {
        runCatching { displays.unregisterDisplayListener(listener) }
        display?.release()
        display = null
        encoder?.stop()
        encoder = null
        stream.close()
        thread.quitSafely()
    }

    private fun density(): Int = context.resources.displayMetrics.densityDpi

    /** Размер экрана, уменьшенный до 1920×1080 по длинной и короткой стороне, кратно 16 для кодировщиков. */
    private fun targetSize(): Pair<Int, Int> {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        displays.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        val long = max(m.widthPixels, m.heightPixels).toDouble()
        val short = min(m.widthPixels, m.heightPixels).toDouble()
        val scale = min(1.0, min(MAX_LONG / long, MAX_SHORT / short))
        fun even(v: Double) = max(16, (v * scale / 16).roundToInt() * 16)
        return even(m.widthPixels.toDouble()) to even(m.heightPixels.toDouble())
    }

    private companion object {
        const val MAX_LONG = 1920.0
        const val MAX_SHORT = 1080.0
    }
}

/** Кодировщик H.264 с выходом в [ScreenStream]. Готовые кадры уходят сразу, без накопления. */
private class Encoder(w: Int, h: Int, private val stream: ScreenStream) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val surface: Surface
    @Volatile private var running = true
    @Volatile private var lastKeyRequest = 0L
    private val drain: Thread

    init {
        configure(w, h)
        surface = codec.createInputSurface()
        codec.start()
        stream.onKeyRequest = { requestKey() }
        drain = Thread({ drainLoop() }, "deixis-encoder").apply { start() }
    }

    private fun configure(w: Int, h: Int) {
        fun format(withProfile: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // Около 0,1 бита на точку при 30 кадрах: 1080×1920 — около 6 Мбит/с, по Wi-Fi это с запасом.
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 3).coerceIn(1_500_000, 8_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Неподвижный экран всё равно даёт кадр раз в 0,1 с: зрители видят, что связь жива.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 30f)
            if (withProfile) {
                // Baseline без B-кадров: кадр уходит сразу, его понимает любой декодер, включая браузеры.
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            }
        }
        try {
            codec.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            codec.reset()
            codec.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    /** Ключевой кадр раньше срока (новый зритель, обрыв связи); не чаще трёх раз в секунду. */
    private fun requestKey() {
        val now = System.currentTimeMillis()
        if (now - lastKeyRequest < 300 || !running) return
        lastKeyRequest = now
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try { codec.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            if (index < 0) continue
            try {
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(index)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size).also { buf.get(it) }
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    stream.send(key && !config, config, info.presentationTimeUs, bytes)
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                break
            }
        }
    }

    fun stop() {
        running = false
        drain.join(500)
        runCatching { codec.stop() }
        runCatching { codec.release() }
        surface.release()
    }
}
