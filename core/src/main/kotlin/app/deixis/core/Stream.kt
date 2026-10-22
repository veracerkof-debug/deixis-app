package app.deixis.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * Демонстрация экрана: живая картинка H.264 с телефона вместо файла.
 * Так показывается то, что нельзя передать файлом: экран целиком или файл больше [FILE_LIMIT_BYTES].
 */
@Serializable @SerialName("screen")
data class ScreenContent(val width: Int, val height: Int) : Content

/** Файлы до 1 ГБ передаются на приёмник и играются там; больше — показываются демонстрацией экрана. */
const val FILE_LIMIT_BYTES = 1L shl 30

/**
 * Кадр потока. На проводе: 1 байт флагов (1 — ключевой, 2 — параметры SPS/PPS), 8 байт номера,
 * 8 байт времени показа в микросекундах, затем данные Annex-B.
 */
class StreamFrame(val key: Boolean, val config: Boolean, val seq: Long, val ptsUs: Long, val data: ByteArray) {

    fun encode(): ByteArray = ByteBuffer.allocate(HEADER + data.size).apply {
        put(((if (key) 1 else 0) or (if (config) 2 else 0)).toByte())
        putLong(seq)
        putLong(ptsUs)
        put(data)
    }.array()

    companion object {
        const val HEADER = 17

        fun decode(bytes: ByteArray): StreamFrame? {
            if (bytes.size < HEADER) return null
            val b = ByteBuffer.wrap(bytes)
            val flags = b.get().toInt()
            return StreamFrame(flags and 1 != 0, flags and 2 != 0, b.getLong(), b.getLong(), bytes.copyOfRange(HEADER, bytes.size))
        }
    }
}

/** Кадр для подписчика: [config] — SPS/PPS, действующие для этого кадра (у ключевого — всегда). */
class StreamSample(val key: Boolean, val ptsUs: Long, val data: ByteArray, val config: ByteArray?)

/**
 * Раздача потока на приёмнике: экран зала и браузеры зрителей подписываются независимо.
 * Отставший подписчик не тормозит остальных и не копит задержку: когда он отстаёт больше чем на
 * [MAX_LAG_FRAMES] кадров, пропущенное выбрасывается и он ждёт следующий ключевой кадр.
 * Короткая остановка картинки лучше растущего отставания от зала.
 */
class StreamHub {
    @Volatile
    var config: ByteArray? = null
        private set

    @Volatile
    private var latestSeq = -1L

    private val _active = MutableStateFlow(false)
    /** Идёт ли сейчас поток от источника. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _keyRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Новому подписчику нужен ключевой кадр: источник может выдать его раньше срока. */
    val keyRequests: SharedFlow<Unit> = _keyRequests.asSharedFlow()

    private val frames = MutableSharedFlow<StreamFrame>(extraBufferCapacity = MAX_LAG_FRAMES * 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun start() {
        config = null
        latestSeq = -1
        _active.value = true
    }

    fun publish(frame: StreamFrame) {
        if (frame.config) {
            config = frame.data
            return
        }
        latestSeq = frame.seq
        frames.tryEmit(frame)
    }

    fun stop() {
        _active.value = false
    }

    fun requestKey() {
        _keyRequests.tryEmit(Unit)
    }

    /** Кадры для одного подписчика, начиная с ближайшего ключевого. */
    fun subscribe(): Flow<StreamSample> = flow {
        var expect = -1L
        var waitingKey = true
        requestKey()
        frames.collect { f ->
            if (expect >= 0 && f.seq != expect) waitingKey = true
            if (latestSeq - f.seq > MAX_LAG_FRAMES) waitingKey = true
            expect = f.seq + 1
            if (waitingKey && !f.key) return@collect
            val cfg = config
            if (f.key && cfg == null) return@collect // без SPS/PPS кадр не декодировать
            if (waitingKey && latestSeq - f.seq > MAX_LAG_FRAMES) return@collect
            waitingKey = false
            emit(StreamSample(f.key, f.ptsUs, f.data, cfg))
        }
    }

    companion object {
        /** Около полсекунды при 30 кадрах в секунду. */
        const val MAX_LAG_FRAMES = 15
    }
}

private val START_CODE = byteArrayOf(0, 0, 0, 1)

/** Делит поток Annex-B на NAL-блоки (без стартовых кодов). */
fun splitNalUnits(data: ByteArray): List<ByteArray> {
    val starts = mutableListOf<IntArray>() // [начало NAL, длина стартового кода]
    var i = 0
    while (i + 3 <= data.size) {
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
            if (data[i + 2] == 1.toByte()) { starts += intArrayOf(i + 3, 3); i += 3; continue }
            if (i + 4 <= data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) { starts += intArrayOf(i + 4, 4); i += 4; continue }
        }
        i++
    }
    return starts.mapIndexed { n, (pos, _) ->
        val end = if (n + 1 < starts.size) starts[n + 1][0] - starts[n + 1][1] else data.size
        data.copyOfRange(pos, end)
    }.filter { it.isNotEmpty() }
}

fun nalType(nal: ByteArray): Int = nal[0].toInt() and 0x1F

const val NAL_IDR = 5
const val NAL_SPS = 7
const val NAL_PPS = 8
const val NAL_AUD = 9

/** Собирает NAL-блоки обратно в Annex-B. */
fun joinAnnexB(nals: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply {
    for (n in nals) { write(START_CODE); write(n) }
}.toByteArray()

/**
 * Разбирает запись Annex-B на кадры по разделителям AUD (или по началу каждого кадра без них).
 * У кадра с SPS/PPS они вынесены в [StreamSample.config]. Для тестов и передачи записи из файла.
 */
fun annexBSamples(data: ByteArray, fps: Int = 30): List<StreamSample> {
    val out = mutableListOf<StreamSample>()
    var cfg = mutableListOf<ByteArray>()
    var body = mutableListOf<ByteArray>()
    fun flush() {
        if (body.none { nalType(it) == 1 || nalType(it) == NAL_IDR }) return
        val pts = out.size * 1_000_000L / fps
        out += StreamSample(body.any { nalType(it) == NAL_IDR }, pts, joinAnnexB(body), cfg.takeIf { it.isNotEmpty() }?.let(::joinAnnexB))
        cfg = mutableListOf(); body = mutableListOf()
    }
    for (n in splitNalUnits(data)) {
        when (nalType(n)) {
            NAL_AUD -> flush()
            NAL_SPS, NAL_PPS -> { if (body.isNotEmpty()) flush(); cfg += n }
            1, NAL_IDR -> {
                // Новый кадр без AUD: первый срез кадра имеет first_mb_in_slice = 0 (старший бит после заголовка).
                if (body.any { nalType(it) == 1 || nalType(it) == NAL_IDR } && n.size > 1 && n[1].toInt() and 0x80 != 0) flush()
                body += n
            }
            else -> body += n
        }
    }
    flush()
    return out
}

/** Размер кадра из SPS (с учётом обрезки) или null, если SPS не разобрать. */
fun avcFrameSize(sps: ByteArray): Pair<Int, Int>? = runCatching {
    // Снимаем защиту от стартового кода: 00 00 03 → 00 00.
    val rbsp = ByteArrayOutputStream()
    var zeros = 0
    for (i in 1 until sps.size) {
        val b = sps[i].toInt() and 0xFF
        if (zeros >= 2 && b == 3) { zeros = 0; continue }
        zeros = if (b == 0) zeros + 1 else 0
        rbsp.write(b)
    }
    val bits = BitReader(rbsp.toByteArray())
    val profile = bits.bits(8)
    bits.bits(16) // флаги ограничений и уровень
    bits.ue() // id
    var chroma = 1
    if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
        chroma = bits.ue()
        if (chroma == 3) bits.bits(1)
        bits.ue(); bits.ue(); bits.bits(1)
        if (bits.bits(1) == 1) return null // свои матрицы квантования: редкость, не разбираем
    }
    bits.ue() // log2_max_frame_num
    when (bits.ue()) {
        0 -> bits.ue()
        1 -> { bits.bits(1); bits.se(); bits.se(); repeat(bits.ue()) { bits.se() } }
    }
    bits.ue(); bits.bits(1)
    val wMbs = bits.ue() + 1
    val hMap = bits.ue() + 1
    val frameMbsOnly = bits.bits(1)
    if (frameMbsOnly == 0) bits.bits(1)
    bits.bits(1)
    var w = wMbs * 16
    var h = hMap * 16 * (2 - frameMbsOnly)
    if (bits.bits(1) == 1) {
        val (l, r, t, b) = List(4) { bits.ue() }
        val cx = if (chroma == 0 || chroma == 3) 1 else 2
        val cy = (if (chroma == 1) 2 else 1) * (2 - frameMbsOnly)
        w -= (l + r) * cx
        h -= (t + b) * cy
    }
    w to h
}.getOrNull()

private class BitReader(private val data: ByteArray) {
    private var pos = 0
    fun bits(n: Int): Int {
        var v = 0
        repeat(n) {
            val byte = data[pos ushr 3].toInt()
            v = (v shl 1) or ((byte ushr (7 - (pos and 7))) and 1)
            pos++
        }
        return v
    }
    fun ue(): Int {
        var zeros = 0
        while (bits(1) == 0) zeros++
        return (1 shl zeros) - 1 + bits(zeros)
    }
    fun se(): Int = ue().let { if (it and 1 == 1) (it + 1) / 2 else -(it / 2) }
}

/** Строка кодека, например avc1.42E01F: профиль, флаги и уровень берутся из SPS. */
fun avcCodecString(sps: ByteArray): String =
    "avc1." + (1..3).joinToString("") { "%02X".format(sps[it].toInt() and 0xFF) }

/**
 * Упаковка H.264 во фрагментированный MP4 для браузеров зрителей (Media Source Extensions).
 * WebCodecs работает только на HTTPS-страницах, а MSE — и на обычной странице в локальной сети.
 * Один экземпляр на подписчика: время отсчитывается от его первого кадра.
 */
class Fmp4Muxer(val width: Int, val height: Int) {
    private var seq = 0
    private var offsetUs = 0L
    private var lastPts = -1L
    private var lastOutUs = -1L
    private var lastDuration = TIMESCALE / 30L

    /** Начальный сегмент (ftyp + moov) для SPS/PPS из [config]; null, если в нём нет SPS или PPS. */
    fun init(config: ByteArray): ByteArray? {
        val nals = splitNalUnits(config)
        val sps = nals.firstOrNull { nalType(it) == NAL_SPS } ?: return null
        val pps = nals.firstOrNull { nalType(it) == NAL_PPS } ?: return null
        return ftyp() + moov(sps, pps)
    }

    /** Один кадр — один фрагмент (moof + mdat): так браузер получает кадр сразу, без ожидания группы. */
    fun fragment(sample: StreamSample): ByteArray {
        val pts = sample.ptsUs
        when {
            lastPts < 0 -> offsetUs = -pts
            // Новый сеанс источника начинает время заново: шкала идёт дальше без возврата назад.
            // Паузы вперёд (выброшенные кадры, неподвижный экран) остаются как есть: проигрыватель с часами,
            // как VLC, иначе считал бы все следующие кадры опоздавшими.
            pts <= lastPts -> offsetUs = lastOutUs + lastDuration * 1_000_000 / TIMESCALE - pts
        }
        val outUs = pts + offsetUs
        if (lastOutUs >= 0) {
            // Не больше 0,1 с: после паузы неподвижного экрана новый кадр не растягивается на всю паузу.
            lastDuration = ((outUs - lastOutUs) * TIMESCALE / 1_000_000).coerceIn(1, TIMESCALE / 10L)
        }
        lastPts = pts
        lastOutUs = outUs
        val dts = outUs * TIMESCALE / 1_000_000
        val payload = avcc(sample.data)
        seq++
        fun moof(dataOffset: Int) = box("moof", box("mfhd", fullBox(0, 0) { writeInt(seq) }) +
            box("traf", box("tfhd", fullBox(0, 0x020000) { writeInt(1) }) +
                box("tfdt", fullBox(1, 0) { writeLong(dts) }) +
                trun(payload.size, sample.key, dataOffset)))
        // Смещение данных считается от начала moof до первого байта внутри mdat.
        return moof(moof(0).size + 8) + box("mdat", payload)
    }

    private fun trun(size: Int, key: Boolean, trunOffset: Int): ByteArray =
        // флаги: смещение данных, длительность, размер, флаги кадра
        box("trun", fullBox(0, 0x000701) {
            writeInt(1)
            writeInt(trunOffset)
            writeInt(lastDuration.toInt())
            writeInt(size)
            writeInt(if (key) 0x02000000 else 0x01010000)
        })

    /** Annex-B → длина NAL (4 байта) + NAL; служебные блоки AUD, SPS, PPS из кадра убираются. */
    private fun avcc(data: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        val out = DataOutputStream(this)
        for (n in splitNalUnits(data)) {
            if (nalType(n) in setOf(NAL_AUD, NAL_SPS, NAL_PPS)) continue
            out.writeInt(n.size)
            out.write(n)
        }
    }.toByteArray()

    private fun ftyp() = box("ftyp", bytes {
        writeBytes("isom"); writeInt(0x200); writeBytes("isom"); writeBytes("iso6"); writeBytes("avc1"); writeBytes("mp41")
    })

    private fun moov(sps: ByteArray, pps: ByteArray): ByteArray {
        val mvhd = box("mvhd", fullBox(0, 0) {
            writeInt(0); writeInt(0); writeInt(TIMESCALE); writeInt(0)
            writeInt(0x00010000); writeShort(0x0100); writeShort(0); writeInt(0); writeInt(0)
            matrix(); repeat(6) { writeInt(0) }; writeInt(2)
        })
        val tkhd = box("tkhd", fullBox(0, 3) {
            writeInt(0); writeInt(0); writeInt(1); writeInt(0); writeInt(0)
            writeInt(0); writeInt(0); writeShort(0); writeShort(0); writeShort(0); writeShort(0)
            matrix(); writeInt(width shl 16); writeInt(height shl 16)
        })
        val mdhd = box("mdhd", fullBox(0, 0) {
            writeInt(0); writeInt(0); writeInt(TIMESCALE); writeInt(0); writeShort(0x55C4); writeShort(0)
        })
        val hdlr = box("hdlr", fullBox(0, 0) {
            writeInt(0); writeBytes("vide"); repeat(3) { writeInt(0) }; writeBytes("Deixis"); writeByte(0)
        })
        val avcC = box("avcC", bytes {
            writeByte(1); writeByte(sps[1].toInt()); writeByte(sps[2].toInt()); writeByte(sps[3].toInt())
            writeByte(0xFF); writeByte(0xE1); writeShort(sps.size); write(sps)
            writeByte(1); writeShort(pps.size); write(pps)
        })
        val avc1 = box("avc1", bytes {
            repeat(6) { writeByte(0) }; writeShort(1)
            writeShort(0); writeShort(0); repeat(3) { writeInt(0) }
            writeShort(width); writeShort(height)
            writeInt(0x00480000); writeInt(0x00480000); writeInt(0); writeShort(1)
            repeat(32) { writeByte(0) }; writeShort(0x18); writeShort(-1)
        } + avcC)
        val stbl = box("stbl",
            box("stsd", fullBox(0, 0) { writeInt(1) } + avc1) +
                box("stts", fullBox(0, 0) { writeInt(0) }) +
                box("stsc", fullBox(0, 0) { writeInt(0) }) +
                box("stsz", fullBox(0, 0) { writeInt(0); writeInt(0) }) +
                box("stco", fullBox(0, 0) { writeInt(0) }))
        val minf = box("minf",
            box("vmhd", fullBox(0, 1) { writeLong(0) }) +
                box("dinf", box("dref", fullBox(0, 0) { writeInt(1) } + box("url ", fullBox(0, 1) {}))) +
                stbl)
        val trak = box("trak", tkhd + box("mdia", mdhd + hdlr + minf))
        val mvex = box("mvex", box("trex", fullBox(0, 0) { writeInt(1); writeInt(1); writeInt(0); writeInt(0); writeInt(0) }))
        return box("moov", mvhd + trak + mvex)
    }

    private fun DataOutputStream.matrix() {
        writeInt(0x00010000); writeInt(0); writeInt(0)
        writeInt(0); writeInt(0x00010000); writeInt(0)
        writeInt(0); writeInt(0); writeInt(0x40000000)
    }

    private fun box(type: String, body: ByteArray): ByteArray = bytes { writeInt(8 + body.size); writeBytes(type); write(body) }

    private fun fullBox(version: Int, flags: Int, body: DataOutputStream.() -> Unit): ByteArray =
        bytes { writeInt((version shl 24) or flags); body() }

    private fun bytes(body: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).body() }.toByteArray()

    companion object {
        const val TIMESCALE = 90_000
    }
}
