package app.deixis.core.airplay

/*
 * Разбор потока зеркала AirPlay (порт данных из SETUP, поток типа 110).
 *
 * Происхождение: переписано на Kotlin по lib/raop_rtp_mirror.c проекта UxPlay
 * (https://github.com/FDH2/UxPlay, GPL-3.0), который восходит к RPiPlay (dsafa22, 2019, LGPL-2.1+)
 * и shairplay (Juho Vähä-Herttua, LGPL-2.1+). Подробности — в NOTICE.
 */

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Что приходит из потока зеркала, уже в виде Annex-B. */
sealed interface MirrorEvent {
    /** SPS и PPS (Annex-B, со стартовыми кодами) и размер картинки. */
    class Config(val annexB: ByteArray, val width: Int, val height: Int) : MirrorEvent
    /** Кадр H.264 в Annex-B; [ptsUs] — время отправителя в микросекундах. */
    class Frame(val annexB: ByteArray, val key: Boolean, val ptsUs: Long) : MirrorEvent
    /** Отправитель уснул (погас экран iPhone): кадров не будет, пока он не проснётся. */
    data object Pause : MirrorEvent
}

/**
 * Читает пакеты: 128 байт заголовка (длина — первые 4 байта, little-endian; тип — байт 4),
 * затем содержимое. Тип 0 — зашифрованный кадр, где NAL-блоки разделены длинами вместо стартовых кодов;
 * тип 1 — открытые SPS и PPS в виде avcC; 2 и 5 — служебные, пропускаются.
 */
class MirrorReader(private val cipher: MirrorCipher) {

    /** Читает до конца потока или ошибки. Повреждённый пакет — конец соединения: iPhone переподключится. */
    fun run(input: InputStream, sink: (MirrorEvent) -> Unit) {
        val data = DataInputStream(input)
        val header = ByteArray(HEADER)
        var paused = false
        while (true) {
            try { data.readFully(header) } catch (_: EOFException) { return }
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val size = h.getInt(0)
            if (size < 0 || size > MAX_PAYLOAD) return
            val payload = ByteArray(size).also { data.readFully(it) }
            when (header[4].toInt() and 0xFF) {
                0 -> {
                    val plain = cipher.decrypt(payload)
                    val annexB = lengthsToStartCodes(plain) ?: continue
                    sink(MirrorEvent.Frame(annexB, hasIdr(annexB), ntpToUs(h.getLong(8))))
                }
                1 -> {
                    val option = header[6].toInt() and 0xFF
                    if (option == 0x56 || option == 0x5E) { if (!paused) { paused = true; sink(MirrorEvent.Pause) }; continue }
                    paused = false
                    if (option == 0x1E) return // H.265 не заявлен в возможностях; такой поток не показать
                    val config = avcConfig(payload) ?: continue
                    val w = h.getFloat(56).toInt().takeIf { it > 0 } ?: h.getFloat(40).toInt()
                    val ht = h.getFloat(60).toInt().takeIf { it > 0 } ?: h.getFloat(44).toInt()
                    sink(MirrorEvent.Config(config, w, ht))
                }
                else -> Unit
            }
        }
    }

    companion object {
        const val HEADER = 128
        /** Кадр больше 16 МБ не бывает даже в 4K; больше — испорченный поток. */
        const val MAX_PAYLOAD = 16 shl 20

        private val START = byteArrayOf(0, 0, 0, 1)

        /** Длины NAL-блоков (4 байта, big-endian) заменяются на стартовые коды. null — длины не сходятся. */
        fun lengthsToStartCodes(data: ByteArray): ByteArray? {
            val out = data.copyOf()
            var at = 0
            while (at < out.size) {
                if (at + 4 > out.size) return null
                val n = ByteBuffer.wrap(out, at, 4).int
                if (n < 1 || n > out.size - at - 4) return null
                START.copyInto(out, at)
                if (out[at + 4].toInt() and 0x80 != 0) return null // запретный бит NAL
                at += 4 + n
            }
            return out
        }

        fun hasIdr(annexB: ByteArray): Boolean = app.deixis.core.splitNalUnits(annexB).any { app.deixis.core.nalType(it) == 5 }

        /** avcC (как в MP4): SPS и PPS со стартовыми кодами. */
        fun avcConfig(p: ByteArray): ByteArray? {
            if (p.size < 8) return null
            val b = ByteBuffer.wrap(p)
            val spsSize = b.getShort(6).toInt() and 0xFFFF
            if (8 + spsSize + 3 > p.size) return null
            val ppsSize = b.getShort(8 + spsSize + 1).toInt() and 0xFFFF
            val ppsAt = 8 + spsSize + 3
            if (ppsAt + ppsSize > p.size || spsSize == 0 || ppsSize == 0) return null
            return START + p.copyOfRange(8, 8 + spsSize) + START + p.copyOfRange(ppsAt, ppsAt + ppsSize)
        }

        /** Время в формате NTP (32 бита секунд и 32 бита долей) — в микросекунды. */
        fun ntpToUs(ntp: Long): Long {
            val sec = ntp ushr 32
            val frac = ntp and 0xFFFFFFFFL
            return sec * 1_000_000 + (frac * 1_000_000 ushr 32)
        }
    }
}
