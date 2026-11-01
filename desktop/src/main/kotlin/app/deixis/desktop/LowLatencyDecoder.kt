package app.deixis.desktop

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG2_FAST
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_LOW_DELAY
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avcodec.av_new_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_configuration
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET
import org.bytedeco.ffmpeg.global.swscale.SWS_FAST_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte

/**
 * Декодер трансляции экрана с малой задержкой — по принципу scrcpy: кадр H.264 (Annex-B) идёт прямо в декодер
 * FFmpeg и сразу выходит картинкой, без буфера плеера и без ожидания следующих кадров.
 *
 * Задержку дают три вещи, и все три убраны: запас плеера (здесь его нет — кадр рисуется, как только готов),
 * многопоточный разбор по кадрам (он держит у себя столько кадров, сколько потоков; здесь потоки делят один кадр —
 * FF_THREAD_SLICE) и переупорядочивание кадров (флаг LOW_DELAY: телефон шлёт Baseline без B-кадров).
 *
 * Не потокобезопасен: работает в одном потоке приёма трансляции.
 */
class LowLatencyDecoder : AutoCloseable {
    private val ctx: AVCodecContext
    private val packet: AVPacket = av_packet_alloc()
    private val frame: AVFrame = av_frame_alloc()
    private var sws: SwsContext? = null
    private var rgb: BytePointer? = null
    private var rgbSize = 0
    private var images = arrayOfNulls<BufferedImage>(2)
    private var next = 0
    private var planes: PointerPointer<BytePointer>? = null
    private var strides: IntPointer? = null

    private fun dstPlanes(out: BytePointer) = (planes ?: PointerPointer<BytePointer>(4L).also { planes = it }).put(0L, out)
    private fun dstStrides(stride: Int) = (strides ?: IntPointer(4L).also { strides = it }).put(0L, stride)

    init {
        av_log_set_level(AV_LOG_QUIET)
        val codec = avcodec_find_decoder(AV_CODEC_ID_H264)
        if (codec == null) { av_frame_free(frame); av_packet_free(packet); throw IllegalStateException("Нет декодера H.264") }
        ctx = avcodec_alloc_context3(codec)
        ctx.flags(ctx.flags() or AV_CODEC_FLAG_LOW_DELAY)
        ctx.flags2(ctx.flags2() or AV_CODEC_FLAG2_FAST)
        ctx.thread_type(FF_THREAD_SLICE)
        ctx.thread_count(0)
        if (avcodec_open2(ctx, codec, null as AVDictionary?) < 0) {
            avcodec_free_context(ctx)
            av_frame_free(frame)
            av_packet_free(packet)
            throw IllegalStateException("Декодер H.264 не открылся")
        }
    }

    /**
     * Кадр потока (Annex-B; перед ключевым — SPS и PPS). Готовые картинки — в [onImage], обычно ровно одна
     * на каждый кадр: декодер ничего у себя не держит. Две картинки чередуются, поэтому та, что сейчас на экране,
     * не перезаписывается, пока рисуется следующая.
     */
    fun decode(data: ByteArray, onImage: (BufferedImage) -> Unit) {
        if (av_new_packet(packet, data.size) < 0) return
        packet.data().put(data, 0, data.size)
        val sent = avcodec_send_packet(ctx, packet)
        av_packet_unref(packet)
        if (sent < 0) return
        while (avcodec_receive_frame(ctx, frame) == 0) {
            toImage()?.let(onImage)
        }
    }

    private fun toImage(): BufferedImage? {
        val w = frame.width()
        val h = frame.height()
        if (w <= 0 || h <= 0) return null
        sws = sws_getCachedContext(sws, w, h, frame.format(), w, h, AV_PIX_FMT_BGR24, SWS_FAST_BILINEAR, null, null, null as DoublePointer?)
        val s = sws ?: return null
        val size = w * h * 3
        if (rgb == null || rgbSize != size) {
            rgb?.close()
            rgb = BytePointer(size.toLong())
            rgbSize = size
            images = arrayOfNulls(2)
        }
        val out = rgb!!
        // FFmpeg ждёт по четыре указателя и шага строки (плоскости), даже если картинка в одной плоскости.
        if (sws_scale(s, frame.data(), frame.linesize(), 0, h, dstPlanes(out), dstStrides(w * 3)) <= 0) return null
        val img = images[next]?.takeIf { it.width == w && it.height == h }
            ?: BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR).also { images[next] = it }
        next = 1 - next
        out.position(0).get((img.raster.dataBuffer as DataBufferByte).data, 0, size)
        return img
    }

    override fun close() {
        sws?.let { sws_freeContext(it) }
        sws = null
        rgb?.close()
        planes?.close()
        strides?.close()
        av_frame_free(frame)
        av_packet_free(packet)
        avcodec_free_context(ctx)
    }

    companion object {
        /** FF_THREAD_SLICE из libavcodec/avcodec.h: потоки делят один кадр, а не держат по кадру каждый. */
        private const val FF_THREAD_SLICE = 2

        /** Есть ли на этом компьютере библиотеки FFmpeg (в установщике — да); нет — трансляцию показывает VLC. */
        val available: Boolean by lazy {
            runCatching { LowLatencyDecoder().close(); true }.getOrDefault(false)
        }

        /** Как собран FFmpeg — для проверки лицензии сборки (без --enable-gpl / --enable-nonfree). */
        fun configuration(): String = runCatching { avcodec_configuration().string }.getOrDefault("")
    }
}
