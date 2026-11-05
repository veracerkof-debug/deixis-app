package app.deixis.desktop

import app.deixis.core.MirrorSink
import app.deixis.core.joinAnnexB
import app.deixis.core.nalType
import app.deixis.core.splitNalUnits
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_LOW_DELAY
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avdevice.avdevice_register_all
import org.bytedeco.ffmpeg.global.avformat.av_find_input_format
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET
import org.bytedeco.ffmpeg.global.avutil.AV_PICTURE_TYPE_I
import org.bytedeco.ffmpeg.global.avutil.AV_PICTURE_TYPE_NONE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.PointerPointer
import java.io.Closeable

/**
 * Что снимать: монитор целиком (область в координатах системы) или окно программы —
 * [windowId] на Linux (X11), [windowTitle] на Windows. Размер окна берётся из самого окна.
 */
data class CaptureArea(
    val x: Int, val y: Int, val width: Int, val height: Int, val screenIndex: Int = 0,
    val windowId: Long? = null, val windowTitle: String? = null,
)

/**
 * Съёмка монитора компьютера и сжатие в H.264 — тем же FFmpeg, что декодирует трансляцию (сборка bytedeco под LGPL).
 * Снимает x11grab на Linux, gdigrab на Windows, avfoundation на Маке. Кодировщик — аппаратный, где он есть
 * (VideoToolbox на Маке, Media Foundation на Windows), иначе OpenH264 (BSD). Кадры Annex-B уходят в [sink]
 * — в общий поток экрана: зал, экраны в сети, зрители по QR.
 *
 * Слабому компьютеру (два ядра) — 720p, остальным — до 1080p; 30 кадров в секунду, ключевой кадр раз в секунду
 * и по просьбе нового зрителя ([requestKey]).
 */
class ScreenCapture(
    private val area: CaptureArea,
    private val sink: MirrorSink,
    private val fps: Int = FPS,
    private val maxSide: Int = defaultMaxSide(),
    private val onError: (String) -> Unit = {},
) : Closeable {
    @Volatile private var running = true
    @Volatile private var keyWanted = true
    private val thread = Thread(::run, "deixis-capture").apply { isDaemon = true; start() }

    /** Новый подписчик (зритель, экран в сети) ждёт ключевой кадр: следующий кадр будет ключевым. */
    fun requestKey() { keyWanted = true }

    val alive: Boolean get() = thread.isAlive

    override fun close() {
        running = false
        thread.join(3_000)
    }

    private fun run() {
        var input: AVFormatContext? = null
        var dec: AVCodecContext? = null
        var enc: AVCodecContext? = null
        var sws: SwsContext? = null
        val pkt: AVPacket = av_packet_alloc()
        val out: AVPacket = av_packet_alloc()
        val raw: AVFrame = av_frame_alloc()
        val yuv: AVFrame = av_frame_alloc()
        try {
            av_log_set_level(AV_LOG_QUIET)
            avdevice_register_all()
            input = openInput() ?: return onError("Не удалось начать съёмку экрана")
            val st = input.streams(0)
            val decoder = avcodec_find_decoder(st.codecpar().codec_id()) ?: return onError("Нет декодера для съёмки экрана")
            dec = avcodec_alloc_context3(decoder)
            avcodec_parameters_to_context(dec, st.codecpar())
            if (avcodec_open2(dec, decoder, null as AVDictionary?) < 0) return onError("Съёмка экрана не открылась")

            val (w, h) = fit(dec.width(), dec.height(), maxSide)
            val (encoder, _) = openEncoder(w, h) ?: return onError("Нет кодировщика H.264")
            enc = encoder
            yuv.format(enc.pix_fmt()); yuv.width(w); yuv.height(h)
            if (av_frame_get_buffer(yuv, 0) < 0) return onError("Нет памяти для кадра")
            var config: ByteArray? = null
            var pts = 0L
            val started = System.nanoTime()

            while (running) {
                if (av_read_frame(input, pkt) < 0) break
                val sent = avcodec_send_packet(dec, pkt)
                av_packet_unref(pkt)
                if (sent < 0) continue
                while (running && avcodec_receive_frame(dec, raw) == 0) {
                    sws = sws_getCachedContext(sws, raw.width(), raw.height(), raw.format(), w, h, enc.pix_fmt(), SWS_BILINEAR, null, null, null as DoublePointer?)
                    av_frame_make_writable(yuv)
                    sws_scale(sws, raw.data(), raw.linesize(), 0, raw.height(), yuv.data(), yuv.linesize())
                    yuv.pts(pts++)
                    yuv.pict_type(if (keyWanted) AV_PICTURE_TYPE_I else AV_PICTURE_TYPE_NONE)
                    keyWanted = false
                    if (avcodec_send_frame(enc, yuv) < 0) continue
                    while (avcodec_receive_packet(enc, out) == 0) {
                        val data = ByteArray(out.size()).also { out.data().get(it) }
                        val ptsUs = (System.nanoTime() - started) / 1_000
                        av_packet_unref(out)
                        val nals = splitNalUnits(data)
                        val ps = nals.filter { nalType(it) == 7 || nalType(it) == 8 }
                        if (ps.size >= 2) {
                            val cfg = joinAnnexB(ps)
                            if (!cfg.contentEquals(config)) { config = cfg; sink.config(cfg, w, h) }
                        } else if (config == null) {
                            extradataConfig(enc)?.let { config = it; sink.config(it, w, h) }
                        }
                        if (config != null) sink.frame(data, nals.any { nalType(it) == 5 }, ptsUs)
                    }
                }
            }
        } catch (e: Throwable) {
            onError("Съёмка экрана прервалась: ${e.message}")
        } finally {
            runCatching { sink.stopped() }
            sws?.let { sws_freeContext(it) }
            av_frame_free(raw); av_frame_free(yuv)
            av_packet_free(pkt); av_packet_free(out)
            dec?.let { avcodec_free_context(it) }
            enc?.let { avcodec_free_context(it) }
            input?.let { avformat_close_input(it) }
        }
    }

    /** Устройство съёмки своей системы с нужной областью и частотой. */
    private fun openInput(): AVFormatContext? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val opts = AVDictionary(null)
        av_dict_set(opts, "framerate", fps.toString(), 0)
        val (format, url) = when {
            os.contains("win") && area.windowTitle != null -> {
                av_dict_set(opts, "draw_mouse", "1", 0)
                "gdigrab" to "title=${area.windowTitle}"
            }
            os.contains("win") -> {
                av_dict_set(opts, "offset_x", area.x.toString(), 0)
                av_dict_set(opts, "offset_y", area.y.toString(), 0)
                av_dict_set(opts, "video_size", "${area.width}x${area.height}", 0)
                av_dict_set(opts, "draw_mouse", "1", 0)
                "gdigrab" to "desktop"
            }
            os.contains("mac") -> {
                av_dict_set(opts, "capture_cursor", "1", 0)
                av_dict_set(opts, "pixel_format", "nv12", 0)
                "avfoundation" to "Capture screen ${area.screenIndex}:none"
            }
            area.windowId != null -> {
                av_dict_set(opts, "window_id", "0x" + java.lang.Long.toHexString(area.windowId), 0)
                av_dict_set(opts, "draw_mouse", "1", 0)
                "x11grab" to (System.getenv("DISPLAY") ?: ":0")
            }
            else -> {
                av_dict_set(opts, "video_size", "${area.width}x${area.height}", 0)
                av_dict_set(opts, "draw_mouse", "1", 0)
                "x11grab" to "${System.getenv("DISPLAY") ?: ":0"}+${area.x},${area.y}"
            }
        }
        val fmt = av_find_input_format(format) ?: return null.also { av_dict_free(opts) }
        val ctx = AVFormatContext(null)
        val ok = avformat_open_input(ctx, url, fmt, opts) >= 0
        av_dict_free(opts)
        if (!ok) return null
        if (avformat_find_stream_info(ctx, null as PointerPointer<*>?) < 0 || ctx.nb_streams() < 1) { avformat_close_input(ctx); return null }
        return ctx
    }

    /** Первый кодировщик, который открылся: аппаратный своей системы, затем OpenH264. */
    private fun openEncoder(w: Int, h: Int): Pair<AVCodecContext, String>? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val names = when {
            os.contains("mac") -> listOf("h264_videotoolbox", "libopenh264")
            os.contains("win") -> listOf("h264_mf", "libopenh264")
            else -> listOf("libopenh264")
        }
        for (name in names) {
            val codec: AVCodec = avcodec_find_encoder_by_name(name) ?: continue
            val c = avcodec_alloc_context3(codec)
            c.width(w); c.height(h)
            c.time_base(AVRational().num(1).den(fps))
            c.framerate(AVRational().num(fps).den(1))
            c.pix_fmt(if (name == "h264_mf") AV_PIX_FMT_NV12 else AV_PIX_FMT_YUV420P)
            c.gop_size(fps)
            c.max_b_frames(0)
            c.bit_rate(bitrate(w, h))
            c.flags(c.flags() or AV_CODEC_FLAG_LOW_DELAY)
            val o = AVDictionary(null)
            when (name) {
                // Baseline у OpenH264 и так по умолчанию; пропуск кадров держит заданный поток на слабом процессоре.
                "libopenh264" -> av_dict_set(o, "allow_skip_frames", "1", 0)
                "h264_videotoolbox" -> { av_dict_set(o, "realtime", "1", 0); av_dict_set(o, "profile", "baseline", 0) }
                "h264_mf" -> { av_dict_set(o, "scenario", "display_remoting", 0); av_dict_set(o, "rate_control", "cbr", 0) }
            }
            val ok = avcodec_open2(c, codec, o) >= 0
            av_dict_free(o)
            if (ok) return c to name
            avcodec_free_context(c)
        }
        return null
    }

    /** SPS и PPS из заголовка кодировщика, если в первом кадре их нет (Annex-B — со стартовым кодом). */
    private fun extradataConfig(c: AVCodecContext): ByteArray? {
        val n = c.extradata_size()
        if (n <= 4) return null
        val b = ByteArray(n).also { c.extradata().get(it) }
        if (!(b[0].toInt() == 0 && b[1].toInt() == 0 && (b[2].toInt() == 1 || (b[2].toInt() == 0 && b[3].toInt() == 1)))) return null
        return b
    }

    companion object {
        const val FPS = 30

        /** Два ядра и меньше (Celeron и подобные) — 720p, иначе 1080p: сжатие идёт на процессоре. */
        fun defaultMaxSide(): Int = if (Runtime.getRuntime().availableProcessors() <= 2) 1280 else 1920

        /** Размер под кодировщик: длинная сторона не больше [maxSide], стороны чётные, пропорции те же. */
        fun fit(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
            val k = minOf(1.0, maxSide.toDouble() / maxOf(w, h))
            fun even(v: Double) = (v.toInt() / 2 * 2).coerceAtLeast(2)
            return even(w * k) to even(h * k)
        }

        /** Около 0,1 бита на пиксель кадра: 1080p — 6 Мбит/с, 720p — 2,8 Мбит/с; хватает для текста слайдов. */
        fun bitrate(w: Int, h: Int): Long = (w.toLong() * h * FPS / 10).coerceIn(1_000_000L, 8_000_000L)
    }
}
