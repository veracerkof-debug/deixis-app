package app.deixis.desktop

import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.ffmpeg.global.avutil.AV_TIME_BASE
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File

/**
 * Длительность и кадр видеофайла через FFmpeg (та же сборка bytedeco под LGPL, что у трансляции):
 * пульту на компьютере — для миниатюр и предпросмотра, без проигрывателя.
 */
object VideoProbe {
    init { av_log_set_level(AV_LOG_QUIET) }

    /** Длительность, мс; 0 — не открылся. */
    fun durationMs(file: File): Long = open(file) { ctx -> (ctx.duration() * 1000 / AV_TIME_BASE).coerceAtLeast(0) } ?: 0L

    /** Кадр около [atMs] шириной [width]; null — не вышло. */
    fun frame(file: File, atMs: Long, width: Int): BufferedImage? = open(file) { ctx ->
        val stream = (0 until ctx.nb_streams()).firstOrNull { ctx.streams(it).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO } ?: return@open null
        val par = ctx.streams(stream).codecpar()
        val codec = avcodec_find_decoder(par.codec_id()) ?: return@open null
        val dec = avcodec_alloc_context3(codec)
        val pkt: AVPacket = av_packet_alloc()
        val frame = av_frame_alloc()
        try {
            if (avcodec_parameters_to_context(dec, par) < 0 || avcodec_open2(dec, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) return@open null
            val dur = ctx.duration().coerceAtLeast(0)
            val target = (atMs * AV_TIME_BASE / 1000).coerceAtMost(if (dur > 0) dur / 2 else Long.MAX_VALUE)
            if (target > 0) av_seek_frame(ctx, -1, target, AVSEEK_FLAG_BACKWARD)
            var reads = 0
            while (reads++ < MAX_PACKETS && av_read_frame(ctx, pkt) >= 0) {
                try {
                    if (pkt.stream_index() != stream) continue
                    if (avcodec_send_packet(dec, pkt) < 0) continue
                    if (avcodec_receive_frame(dec, frame) == 0) return@open toImage(frame, width)
                } finally {
                    av_packet_unref(pkt)
                }
            }
            null
        } finally {
            av_frame_free(frame)
            av_packet_free(pkt)
            avcodec_free_context(dec)
        }
    }

    private fun toImage(frame: org.bytedeco.ffmpeg.avutil.AVFrame, width: Int): BufferedImage? {
        val w0 = frame.width(); val h0 = frame.height()
        if (w0 <= 0 || h0 <= 0) return null
        val w = (minOf(width, w0).coerceAtLeast(2)) and 0x7FFFFFFE
        val h = (w.toLong() * h0 / w0).toInt().coerceAtLeast(2)
        val sws = sws_getContext(w0, h0, frame.format(), w, h, AV_PIX_FMT_BGR24, SWS_BILINEAR, null, null, null as DoublePointer?) ?: return null
        return try {
            val img = BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR)
            val bytes = (img.raster.dataBuffer as DataBufferByte).data
            // Четыре плоскости и запас в конце: swscale читает все указатели и пишет блоками.
            val out = BytePointer(bytes.size.toLong() + PAD)
            val planes = PointerPointer<BytePointer>(4L).put(0L, out)
            val strides = IntPointer(4L).put(0L, w * 3)
            try {
                if (sws_scale(sws, frame.data(), frame.linesize(), 0, h0, planes, strides) <= 0) return null
                out.get(bytes)
            } finally {
                out.close(); planes.close(); strides.close()
            }
            img
        } finally {
            sws_freeContext(sws)
        }
    }

    private fun <T> open(file: File, block: (AVFormatContext) -> T): T? {
        val ctx = AVFormatContext(null)
        if (avformat_open_input(ctx, file.absolutePath, null, null) < 0) return null
        return try {
            if (avformat_find_stream_info(ctx, null as PointerPointer<*>?) < 0) null else block(ctx)
        } catch (_: Throwable) {
            null
        } finally {
            avformat_close_input(ctx)
        }
    }

    private const val MAX_PACKETS = 600
    private const val PAD = 64L
}
