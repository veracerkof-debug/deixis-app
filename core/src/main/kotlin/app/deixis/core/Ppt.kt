package app.deixis.core

import java.io.File
import kotlin.math.roundToInt

/*
 * Встроенный перевод старого PPT (PowerPoint 97–2003, [MS-PPT]): своё чтение, без чужого кода.
 * Составной файл (Cfb) → поток «PowerPoint Document» → дерево записей → та же модель Deck,
 * что у PPTX, поэтому рисует её тот же DeckRender. Фигуры записаны в формате OfficeArt ([MS-ODRAW]).
 * Единицы файла — «мастер-единицы», 576 на дюйм: пункты = единицы / 8.
 */
object Ppt {

    fun read(file: File): Deck = try { readChecked(file) } catch (e: PptxException) { throw e }
        catch (e: OutOfMemoryError) { throw PptxException(Cfb.TOO_BIG) }
        catch (e: java.io.IOException) { throw PptxException("«${file.name}» не читается: файл повреждён или недоступен") }
        catch (e: Exception) { throw PptxException(Cfb.BROKEN) }

    private fun readChecked(file: File): Deck {
        val (doc, cur, pictures) = Cfb.open(file).use { c ->
            if (c.has("EncryptedPackage") || c.has("EncryptionInfo")) throw PptxException(ENCRYPTED)
            val doc = c.stream("PowerPoint Document") ?: throw PptxException(
                when {
                    c.has("WordDocument") -> "Это документ Word, а не презентация PowerPoint"
                    c.has("Workbook") || c.has("Book") -> "Это таблица Excel, а не презентация PowerPoint"
                    else -> Cfb.NOT_PPT
                },
            )
            if (c.has("EncryptedSummary")) throw PptxException(ENCRYPTED)
            Triple(doc, c.stream("Current User", 1L shl 16), c.size("Pictures"))
        }
        return Reader(file, Doc(doc), cur, pictures).deck()
    }

    /** Составной файл OLE2 (старые PPT, а также зашифрованные PPTX). */
    fun isCompound(file: File): Boolean = Cfb.isCompound(file)

    /** Архив ZIP (PPTX), по первым байтам. */
    fun isZip(file: File): Boolean = runCatching {
        file.inputStream().use { val b = ByteArray(4); it.read(b) == 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }
    }.getOrDefault(false)

    private const val ENCRYPTED = "Презентация защищена паролем: такой файл встроенный перевод не открывает, нужен файл без пароля"
    private const val PT_PER_MASTER = 1f / 8f
    private const val EMU_PER_POINT = 12700f

    // ——— Записи ———

    /** Поток документа и общий счётчик записей: испорченный файл не разбирается бесконечно. */
    private class Doc(val b: ByteArray) {
        var budget = 1_000_000

        fun parse(from: Int, to: Int, depth: Int): List<Rec> {
            val out = ArrayList<Rec>()
            var p = from
            val end = minOf(to, b.size)
            while (p + 8 <= end) {
                if (--budget < 0) throw PptxException(Cfb.BROKEN)
                val vi = Cfb.le16(b, p)
                val type = Cfb.le16(b, p + 2)
                var len = Cfb.le32(b, p + 4)
                if (len < 0 || len > end - p - 8) len = end - p - 8
                out += Rec(this, type, vi ushr 4, vi and 0xF, p + 8, len, depth)
                p += 8 + len
            }
            return out
        }

        /** Одна запись по смещению в потоке. */
        fun at(offset: Int): Rec? = if (offset < 0 || offset + 8 > b.size) null else parse(offset, b.size, 0).firstOrNull()
    }

    private class Rec(val d: Doc, val type: Int, val inst: Int, val ver: Int, val pos: Int, val len: Int, val depth: Int) {
        val kids: List<Rec> by lazy { if (ver != 0xF || depth > 48) emptyList() else d.parse(pos, pos + len, depth + 1) }
        fun kid(t: Int) = kids.firstOrNull { it.type == t }
        fun kids(t: Int) = kids.filter { it.type == t }
        fun u8(o: Int) = if (o in 0 until len) d.b[pos + o].toInt() and 0xFF else 0
        fun u16(o: Int) = if (o >= 0 && o + 2 <= len) Cfb.le16(d.b, pos + o) else 0
        fun i16(o: Int) = u16(o).toShort().toInt()
        fun i32(o: Int) = if (o >= 0 && o + 4 <= len) Cfb.le32(d.b, pos + o) else 0
        fun bytes(o: Int = 0, n: Int = len - o): ByteArray = if (o < 0 || n <= 0 || o >= len) ByteArray(0) else d.b.copyOfRange(pos + o, pos + o + minOf(n, len - o))

        /** Первая запись типа [t] в глубину (с ограничением шагов). */
        fun find(types: Set<Int>, limit: IntArray = intArrayOf(20000)): Rec? {
            for (k in kids) {
                if (--limit[0] < 0) return null
                if (k.type in types) return k
                k.find(types, limit)?.let { return it }
            }
            return null
        }
    }

    // Типы записей [MS-PPT] и [MS-ODRAW].
    private const val DOCUMENT = 0x03E8
    private const val DOCUMENT_ATOM = 0x03E9
    private const val SLIDE = 0x03EE
    private const val SLIDE_ATOM = 0x03EF
    private const val SLIDE_PERSIST = 0x03F3
    private const val ENVIRONMENT = 0x03F2
    private const val MAIN_MASTER = 0x03F8
    private const val SS_SLIDE_INFO = 0x03F9
    private const val DRAWING_GROUP = 0x040B
    private const val DRAWING = 0x040C
    private const val COLOR_SCHEME = 0x07F0
    private const val FONT_COLLECTION = 0x07D5
    private const val FONT_ENTITY = 0x0FB7
    private const val PLACEHOLDER = 0x0BC3
    private const val EXOBJ_REF = 0x0BC1
    private const val OUTLINE_TEXT_REF = 0x0F9E
    private const val TEXT_HEADER = 0x0F9F
    private const val TEXT_CHARS = 0x0FA0
    private const val STYLE_TEXT_PROP = 0x0FA1
    private const val TEXT_MASTER_STYLE = 0x0FA3
    private const val TEXT_CF_EXCEPTION = 0x0FA4
    private const val TEXT_PF_EXCEPTION = 0x0FA5
    private const val TEXT_RULER = 0x0FA6
    private const val TEXT_BYTES = 0x0FA8
    private const val SLIDE_NUMBER_MC = 0x0FD8
    private const val HEADERS_FOOTERS = 0x0FD9
    private const val HEADERS_FOOTERS_ATOM = 0x0FDA
    private const val CSTRING = 0x0FBA
    private const val SLWT = 0x0FF0
    private const val USER_EDIT = 0x0FF5
    private const val CURRENT_USER = 0x0FF6
    private const val PERSIST_DIR = 0x1772
    private const val CRYPT_SESSION = 0x2F14
    private const val ANIMATION_INFO = 0x1014
    private const val EXT_TIME_NODE = 0xF144
    private const val EXOBJ_LIST = 0x0409

    private const val DGG = 0xF000
    private const val BSTORE = 0xF001
    private const val DG = 0xF002
    private const val SPGR = 0xF003
    private const val SP = 0xF004
    private const val FBSE = 0xF007
    private const val FSPGR = 0xF009
    private const val FSP = 0xF00A
    private const val FOPT = 0xF00B
    private const val CLIENT_TEXTBOX = 0xF00D
    private const val CHILD_ANCHOR = 0xF00F
    private const val CLIENT_ANCHOR = 0xF010
    private const val CLIENT_DATA = 0xF011
    private const val TERTIARY_FOPT = 0xF122

    // ——— Свойства фигуры OfficeArt ———

    private class Props(val v: Map<Int, Int>, val complex: Map<Int, ByteArray>) {
        companion object {
            val EMPTY = Props(emptyMap(), emptyMap())

            fun of(vararg recs: Rec?): Props {
                val v = HashMap<Int, Int>()
                val cx = HashMap<Int, ByteArray>()
                for (r in recs) {
                    r ?: continue
                    val n = minOf(r.inst, r.len / 6)
                    var data = n * 6
                    for (i in 0 until n) {
                        val id = r.u16(i * 6)
                        val op = r.i32(i * 6 + 2)
                        val pid = id and 0x3FFF
                        v.putIfAbsent(pid, op)
                        if (id and 0x8000 != 0 && op > 0) {
                            // У массивов IMsoArray размер в свойстве часто без 6 байт заголовка: берём по заголовку.
                            var n = op
                            if (pid in ARRAYS && data + 6 <= r.len) {
                                val cb = r.u16(data + 4).let { if (it == 0xFFF0) 4 else it }
                                val full = 6 + r.u16(data) * cb
                                if (full == op + 6) n = full
                            }
                            if (data + n <= r.len) cx.putIfAbsent(pid, r.bytes(data, n))
                            data += n
                        }
                    }
                }
                return Props(v, cx)
            }
        }
    }

    /** Свойства по цепочке: фигура → фигура мастера (hspMaster) → умолчания рисунка документа. */
    private class PropChain(val list: List<Props>) {
        operator fun get(pid: Int): Int? = list.firstNotNullOfOrNull { it.v[pid] }
        fun complex(pid: Int): ByteArray? = list.firstNotNullOfOrNull { it.complex[pid] }
        fun has(pid: Int) = list.any { it.v.containsKey(pid) }

        /** Логический флаг из набора: учитывается, только если поднят его бит «use». */
        fun flag(pid: Int, bit: Int, default: Boolean): Boolean {
            for (p in list) {
                val v = p.v[pid] ?: continue
                if (v and (bit shl 16) != 0) return v and bit != 0
            }
            return default
        }
    }

    // ——— Текст: исключения абзаца и знаков ———

    private class PF(
        val hasBullet: Boolean? = null, val bulletHasFont: Boolean? = null, val bulletHasColor: Boolean? = null, val bulletHasSize: Boolean? = null,
        val bulletChar: Int? = null, val bulletFont: Int? = null, val bulletSize: Int? = null, val bulletColor: Int? = null,
        val align: Int? = null, val lineSpacing: Int? = null, val spaceBefore: Int? = null, val spaceAfter: Int? = null,
        val leftMargin: Int? = null, val indent: Int? = null,
    ) {
        fun over(b: PF?): PF = if (b == null) this else PF(
            hasBullet ?: b.hasBullet, bulletHasFont ?: b.bulletHasFont, bulletHasColor ?: b.bulletHasColor, bulletHasSize ?: b.bulletHasSize,
            bulletChar ?: b.bulletChar, bulletFont ?: b.bulletFont, bulletSize ?: b.bulletSize, bulletColor ?: b.bulletColor,
            align ?: b.align, lineSpacing ?: b.lineSpacing, spaceBefore ?: b.spaceBefore, spaceAfter ?: b.spaceAfter,
            leftMargin ?: b.leftMargin, indent ?: b.indent,
        )
    }

    private class CF(
        val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null,
        val font: Int? = null, val size: Int? = null, val color: Int? = null,
    ) {
        fun over(b: CF?): CF = if (b == null) this else CF(
            bold ?: b.bold, italic ?: b.italic, underline ?: b.underline, font ?: b.font, size ?: b.size, color ?: b.color,
        )
    }

    /** TextPFException: маска и только те поля, что в ней отмечены. Возвращает конец записи. */
    private fun readPF(r: Rec, at: Int): Pair<PF, Int> {
        val m = r.i32(at)
        var p = at + 4
        fun next(n: Int): Int { val o = p; p += n; return o }
        val flags = if (m and 0xF != 0) r.u16(next(2)) else 0
        val bChar = if (m and 0x80 != 0) r.u16(next(2)) else null
        val bFont = if (m and 0x10 != 0) r.u16(next(2)) else null
        val bSize = if (m and 0x40 != 0) r.i16(next(2)) else null
        val bColor = if (m and 0x20 != 0) r.i32(next(4)) else null
        val align = if (m and 0x800 != 0) r.u16(next(2)) else null
        val ls = if (m and 0x1000 != 0) r.i16(next(2)) else null
        val sb = if (m and 0x2000 != 0) r.i16(next(2)) else null
        val sa = if (m and 0x4000 != 0) r.i16(next(2)) else null
        val lm = if (m and 0x100 != 0) r.i16(next(2)) else null
        val ind = if (m and 0x400 != 0) r.i16(next(2)) else null
        if (m and 0x8000 != 0) next(2)
        if (m and 0x100000 != 0) { val n = r.u16(next(2)).coerceAtMost(1000); next(n * 4) }
        if (m and 0x10000 != 0) next(2)
        if (m and 0xE0000 != 0) next(2)
        if (m and 0x200000 != 0) next(2)
        return PF(
            hasBullet = if (m and 1 != 0) flags and 1 != 0 else null,
            bulletHasFont = if (m and 2 != 0) flags and 2 != 0 else null,
            bulletHasColor = if (m and 4 != 0) flags and 4 != 0 else null,
            bulletHasSize = if (m and 8 != 0) flags and 8 != 0 else null,
            bulletChar = bChar, bulletFont = bFont, bulletSize = bSize, bulletColor = bColor,
            align = align, lineSpacing = ls, spaceBefore = sb, spaceAfter = sa, leftMargin = lm, indent = ind,
        ) to p
    }

    /** TextCFException. */
    private fun readCF(r: Rec, at: Int): Pair<CF, Int> {
        val m = r.i32(at)
        var p = at + 4
        fun next(n: Int): Int { val o = p; p += n; return o }
        val style = if (m and 0x3EB7 != 0) r.u16(next(2)) else 0
        val font = if (m and 0x10000 != 0) r.u16(next(2)) else null
        if (m and 0x200000 != 0) next(2)
        if (m and 0x400000 != 0) next(2)
        if (m and 0x800000 != 0) next(2)
        val size = if (m and 0x20000 != 0) r.u16(next(2)) else null
        val color = if (m and 0x40000 != 0) r.i32(next(4)) else null
        if (m and 0x80000 != 0) next(2)
        return CF(
            bold = if (m and 1 != 0) style and 1 != 0 else null,
            italic = if (m and 2 != 0) style and 2 != 0 else null,
            underline = if (m and 4 != 0) style and 4 != 0 else null,
            font = font, size = size?.takeIf { it in 1..4000 }, color = color,
        ) to p
    }

    /** Стили уровней 0–4 из TextMasterStyleAtom. */
    private fun masterStyle(r: Rec): List<Pair<PF, CF>> {
        val n = r.u16(0).coerceAtMost(5)
        var p = 2
        val out = ArrayList<Pair<PF, CF>>()
        repeat(n) {
            if (r.inst >= 5) p += 2
            val (pf, p1) = readPF(r, p)
            val (cf, p2) = readCF(r, p1)
            if (p2 > r.len) return out
            out += pf to cf
            p = p2
        }
        return out
    }

    /** Текст фигуры: тип, знаки, стили, линейка. */
    private class TextRec(val type: Int) {
        var chars: String = ""
        var style: Rec? = null
        var ruler: Rec? = null
        val slideNumbers = mutableListOf<Int>()
    }

    private fun collectTexts(kids: List<Rec>, into: MutableList<TextRec>) {
        for (k in kids) when (k.type) {
            TEXT_HEADER -> into += TextRec(k.i32(0))
            TEXT_CHARS -> into.lastOrNull()?.chars = String(k.bytes(0, k.len and 1.inv()), Charsets.UTF_16LE)
            TEXT_BYTES -> into.lastOrNull()?.chars = String(k.bytes(), Charsets.ISO_8859_1)
            STYLE_TEXT_PROP -> into.lastOrNull()?.style = k
            TEXT_RULER -> into.lastOrNull()?.ruler = k
            SLIDE_NUMBER_MC -> into.lastOrNull()?.slideNumbers?.add(k.i32(0))
        }
    }

    // ——— Слайд и мастер ———

    private class SlideRef(val persist: Int, val id: Int) { val texts = mutableListOf<TextRec>() }

    /** Мастер: свой рисунок, стили текста, цвета; у мастера титульного слайда есть главный мастер. */
    private class Master(val rec: Rec, val main: Master?) {
        var styles: Map<Int, List<Pair<PF, CF>>> = emptyMap()
        var scheme: IntArray? = null
        var flags = 0
        val placeholders = mutableMapOf<Int, PropChain>()
        val shapes = mutableMapOf<Int, Props>()
    }

    private class Blip(val type: Int, val delay: Long, val size: Int, val embedded: ByteArray?)

    /** Сдвиг и масштаб группы: координаты ребёнка → пункты слайда. */
    private data class Space(val dx: Float, val dy: Float, val sx: Float, val sy: Float, val ox: Float, val oy: Float) {
        fun box(l: Int, t: Int, r: Int, b: Int) = Box(dx + (l - ox) * sx, dy + (t - oy) * sy, (r - l) * sx, (b - t) * sy)
    }

    private class Ctx(val scheme: IntArray, val master: Master?, val texts: List<TextRec>, val number: Int, val background: DeckPaint?)

    private class Reader(private val file: File, private val d: Doc, private val cur: ByteArray?, private val picturesSize: Long) {
        private val warnings = linkedSetOf<String>()
        private val persist = HashMap<Int, Int>()
        private var fonts: List<String> = emptyList()
        private val blips = HashMap<Int, Blip>()
        private var defaults = Props.EMPTY
        private var envStyles: Map<Int, List<Pair<PF, CF>>> = emptyMap()
        private var envPF: PF? = null
        private var envCF: CF? = null
        private val masters = HashMap<Int, Master>()
        private var footers: Rec? = null

        fun deck(): Deck {
            val docRec = document() ?: throw PptxException(Cfb.BROKEN)
            val atom = docRec.kid(DOCUMENT_ATOM)
            val w = (atom?.i32(0) ?: 5760).let { if (it in 100..200000) it else 5760 } * PT_PER_MASTER
            val h = (atom?.i32(4) ?: 4320).let { if (it in 100..200000) it else 4320 } * PT_PER_MASTER

            docRec.kid(ENVIRONMENT)?.let { env ->
                fonts = env.kid(FONT_COLLECTION)?.kids(FONT_ENTITY).orEmpty().map { f ->
                    String(f.bytes(0, 64), Charsets.UTF_16LE).substringBefore('\u0000').trim()
                }
                envStyles = env.kids(TEXT_MASTER_STYLE).associate { it.inst to masterStyle(it) }
                envCF = env.kid(TEXT_CF_EXCEPTION)?.let { readCF(it, 0).first }
                envPF = env.kid(TEXT_PF_EXCEPTION)?.let { readPF(it, 2).first }
            }
            docRec.kid(DRAWING_GROUP)?.kid(DGG)?.let { dgg ->
                defaults = Props.of(dgg.kid(FOPT), dgg.kid(TERTIARY_FOPT))
                dgg.kid(BSTORE)?.kids(FBSE)?.forEachIndexed { i, f ->
                    val cbName = f.u8(33)
                    val embedded = if (f.len > 36 + cbName + 8) f.bytes(36 + cbName) else null
                    blips[i + 1] = Blip(f.u8(0), f.i32(28).toLong() and 0xFFFFFFFFL, f.i32(20), embedded)
                }
            }
            footers = docRec.kids(HEADERS_FOOTERS).firstOrNull { it.inst == 3 }
            if (docRec.kid(EXOBJ_LIST)?.kids?.any { it.type in MEDIA } == true) {
                warnings += "видео и звук внутри презентации не играют: показывается их заставка; видео лучше показать отдельным файлом"
            }

            // Мастера: главный и титульный (это слайд, у которого свой мастер).
            val lists = docRec.kids(SLWT)
            lists.firstOrNull { it.inst == 1 }?.kids(SLIDE_PERSIST)?.forEach { mp ->
                val rec = record(mp.i32(0)) ?: return@forEach
                if (rec.type == MAIN_MASTER) masters[mp.i32(12)] = Master(rec, null)
            }
            lists.firstOrNull { it.inst == 1 }?.kids(SLIDE_PERSIST)?.forEach { mp ->
                val rec = record(mp.i32(0)) ?: return@forEach
                if (rec.type == SLIDE) {
                    val main = masters[rec.kid(SLIDE_ATOM)?.i32(12) ?: 0] ?: masters.values.firstOrNull()
                    masters[mp.i32(12)] = Master(rec, main)
                }
            }
            for (m in masters.values) prepareMaster(m)

            val refs = mutableListOf<SlideRef>()
            lists.firstOrNull { it.inst == 0 }?.let { list ->
                var cur: SlideRef? = null
                val group = mutableListOf<Rec>()
                fun flush() { cur?.let { c -> collectTexts(group, c.texts); refs += c }; group.clear() }
                for (k in list.kids) {
                    if (k.type == SLIDE_PERSIST) { flush(); cur = SlideRef(k.i32(0), k.i32(12)) } else group += k
                }
                flush()
            }
            if (refs.isEmpty()) throw PptxException("В презентации нет слайдов для показа")

            val slides = mutableListOf<DeckSlide>()
            for ((i, ref) in refs.withIndex()) {
                val rec = record(ref.persist)?.takeIf { it.type == SLIDE } ?: continue
                val info = rec.kid(SS_SLIDE_INFO)
                if (info != null && info.u16(10) and 0x4 != 0) continue
                if (info != null && info.u8(9) != 0) warnings += ANIMATION
                slides += runCatching { slide(rec, ref, i + 1) }.getOrElse { e -> if (e is PptxException) throw e; warnings += "слайд ${i + 1} прочитан не полностью"; DeckSlide(i + 1, null, emptyList()) }
            }
            if (slides.isEmpty()) throw PptxException("В презентации нет слайдов для показа")
            val blipsCopy = HashMap(blips)
            return Deck(w, h, slides, warnings.toList()) { key -> image(blipsCopy, key) }
        }

        // ——— Карта сохранённых объектов ———

        private fun document(): Rec? {
            val start = cur?.takeIf { it.size >= 20 && Cfb.le16(it, 2) == CURRENT_USER }?.let { c ->
                if (Cfb.le32(c, 12) == 0xF3D1C4DF.toInt()) throw PptxException(ENCRYPTED)
                Cfb.le32(c, 16)
            }?.takeIf { d.at(it)?.type == USER_EDIT } ?: lastUserEdit()
            var docRef: Int? = null
            var off = start
            val seen = HashSet<Int>()
            while (off != null && off > 0 && seen.add(off) && seen.size < 10000) {
                val edit = d.at(off)?.takeIf { it.type == USER_EDIT } ?: break
                if (docRef == null) {
                    docRef = edit.i32(16)
                    if (edit.len >= 32 && edit.i32(28) != 0) throw PptxException(ENCRYPTED)
                }
                d.at(edit.i32(12))?.takeIf { it.type == PERSIST_DIR }?.let { dir ->
                    var p = 0
                    while (p + 4 <= dir.len) {
                        val v = dir.i32(p)
                        val id = v and 0xFFFFF
                        val n = v ushr 20
                        for (k in 0 until n) if (p + 4 + k * 4 + 4 <= dir.len) persist.putIfAbsent(id + k, dir.i32(p + 4 + k * 4))
                        p += 4 + n * 4
                    }
                }
                off = edit.i32(8)
            }
            // Без карты: последний документ в потоке.
            return docRef?.let { record(it) }?.takeIf { it.type == DOCUMENT }
                ?: d.parse(0, d.b.size, 0).also { top -> if (top.any { it.type == CRYPT_SESSION }) throw PptxException(ENCRYPTED) }.lastOrNull { it.type == DOCUMENT }
        }

        private fun lastUserEdit(): Int? = d.parse(0, d.b.size, 0).lastOrNull { it.type == USER_EDIT }?.let { it.pos - 8 }

        private fun record(id: Int): Rec? = persist[id]?.let { d.at(it) }

        // ——— Мастер ———

        private fun prepareMaster(m: Master) {
            val main = m.main
            m.styles = m.rec.kids(TEXT_MASTER_STYLE).associate { it.inst to masterStyle(it) }.ifEmpty { main?.styles.orEmpty() }
            m.flags = m.rec.kid(SLIDE_ATOM)?.u16(20) ?: 0
            m.scheme = scheme(m.rec) ?: main?.scheme
            val dg = m.rec.kid(DRAWING)?.kid(DG) ?: return
            // Заполнители мастера: от них слайд берёт отступы и привязку текста.
            forEachShape(dg) { sp ->
                val props = Props.of(sp.kid(FOPT), sp.kid(TERTIARY_FOPT))
                sp.kid(FSP)?.let { m.shapes[it.i32(0)] = props }
                val ph = sp.kid(CLIENT_DATA)?.kid(PLACEHOLDER)?.u8(4) ?: return@forEachShape
                m.placeholders.putIfAbsent(ph, PropChain(listOf(props, defaults)))
            }
        }

        private fun forEachShape(r: Rec, f: (Rec) -> Unit) {
            for (k in r.kids) when (k.type) {
                SP -> f(k)
                SPGR -> forEachShape(k, f)
            }
        }

        private fun scheme(r: Rec): IntArray? {
            val c = r.kids(COLOR_SCHEME).firstOrNull { it.inst == 1 } ?: return null
            if (c.len < 32) return null
            return IntArray(8) { i -> rgb(c.u8(i * 4), c.u8(i * 4 + 1), c.u8(i * 4 + 2)) }
        }

        // ——— Слайд ———

        private fun slide(rec: Rec, ref: SlideRef, number: Int): DeckSlide {
            val atom = rec.kid(SLIDE_ATOM)
            val flags = atom?.u16(20) ?: 0x7
            val master = masters[atom?.i32(12) ?: 0] ?: masters.values.firstOrNull { it.main == null }
            val scheme = (if (flags and 0x2 == 0) scheme(rec) else null) ?: master?.scheme ?: DEFAULT_SCHEME
            val dg = rec.kid(DRAWING)?.kid(DG)

            // Фон: свой, если слайд не берёт фон мастера, иначе мастера (титульного, затем главного).
            val bgCtx = Ctx(scheme, master, ref.texts, number, null)
            var background = if (flags and 0x4 == 0) dg?.let { backgroundOf(it, bgCtx) } else null
            if (background == null) {
                var m = master
                var guard = 0
                while (m != null && background == null && guard++ < 4) {
                    background = m.rec.kid(DRAWING)?.kid(DG)?.let { backgroundOf(it, bgCtx) }
                    m = if (m.flags and 0x4 != 0) m.main else null
                }
            }
            val ctx = Ctx(scheme, master, ref.texts, number, background)

            val items = mutableListOf<DeckItem>()
            if (flags and 0x1 != 0 && master != null) {
                val chain = mutableListOf(master)
                if (master.flags and 0x1 != 0) master.main?.let { chain += it }
                for (m in chain.asReversed()) {
                    val mdg = m.rec.kid(DRAWING)?.kid(DG) ?: continue
                    mdg.kid(SPGR)?.let { walk(it, null, Ctx(scheme, m.main ?: m, emptyList(), number, background), true, items) }
                }
                footerItems(rec, master, ctx, items)
            }
            dg?.kid(SPGR)?.let { walk(it, null, ctx, false, items) }
            if (rec.find(setOf(ANIMATION_INFO, EXT_TIME_NODE)) != null) warnings += ANIMATION
            return DeckSlide(number, background, items)
        }

        /** Колонтитулы: номер слайда, нижний колонтитул и дата берутся из заполнителей мастера. */
        private fun footerItems(rec: Rec, master: Master, ctx: Ctx, items: MutableList<DeckItem>) {
            val hf = rec.kids(HEADERS_FOOTERS).firstOrNull() ?: footers ?: return
            val f = hf.kid(HEADERS_FOOTERS_ATOM)?.u16(2) ?: return
            val strings = hf.kids(CSTRING).associate { it.inst to String(it.bytes(0, it.len and 1.inv()), Charsets.UTF_16LE) }
            val mdg = master.rec.kid(DRAWING)?.kid(DG) ?: master.main?.rec?.kid(DRAWING)?.kid(DG) ?: return
            // Слайд, на котором уже есть свои колонтитулы, рисует их сам.
            val own = rec.kid(DRAWING)?.kid(DG)?.let { dg -> val s = mutableSetOf<Int>(); forEachShape(dg) { sp -> sp.kid(CLIENT_DATA)?.kid(PLACEHOLDER)?.u8(4)?.let { s += it } }; s }.orEmpty()
            forEachShape(mdg) { sp ->
                val ph = sp.kid(CLIENT_DATA)?.kid(PLACEHOLDER)?.u8(4) ?: return@forEachShape
                val text = when (ph) {
                    8 -> if (f and 0x8 != 0 && 8 !in own) "#" else null
                    9 -> if (f and 0x20 != 0 && 9 !in own) strings[2] else null
                    7 -> if (f and 0x1 != 0 && f and 0x4 != 0 && 7 !in own) strings[0] else null
                    else -> null
                } ?: return@forEachShape
                shape(sp, null, ctx, footer = text)?.let { items += it }
            }
        }

        private fun backgroundOf(dg: Rec, ctx: Ctx): DeckPaint? {
            val sp = dg.kids(SP).firstOrNull { (it.kid(FSP)?.i32(4) ?: 0) and 0x400 != 0 } ?: return null
            val p = PropChain(listOf(Props.of(sp.kid(FOPT), sp.kid(TERTIARY_FOPT)), defaults))
            return fillOf(p, ctx, Box(0f, 0f, 1f, 1f), background = true)
        }

        // ——— Дерево фигур ———

        private fun walk(spgr: Rec, space: Space?, ctx: Ctx, decorOnly: Boolean, out: MutableList<DeckItem>) {
            for (k in spgr.kids) {
                when (k.type) {
                    // Первая фигура группы с FSPGR — сама группа, не рисуется.
                    SP -> if (k.kid(FSPGR) == null) shape(k, space, ctx, decorOnly = decorOnly)?.let { out += it }
                    SPGR -> {
                        val head = k.kids.firstOrNull { it.type == SP } ?: continue
                        val g = head.kid(FSPGR) ?: continue
                        val box = anchor(head, space) ?: continue
                        val gl = g.i32(0); val gt = g.i32(4); val gr = g.i32(8); val gb = g.i32(12)
                        val sx = if (gr != gl) box.w / (gr - gl) else 1f
                        val sy = if (gb != gt) box.h / (gb - gt) else 1f
                        walk(k, Space(box.x, box.y, sx, sy, gl.toFloat(), gt.toFloat()), ctx, decorOnly, out)
                    }
                }
            }
        }

        /** Рамка фигуры: на слайде — ClientAnchor (8 или 16 байт), в группе — ChildAnchor. */
        private fun anchor(sp: Rec, space: Space?): Box? {
            sp.kid(CLIENT_ANCHOR)?.let { a ->
                val (t, l, r, b) = if (a.len >= 16) listOf(a.i32(0), a.i32(4), a.i32(8), a.i32(12)) else listOf(a.i16(0), a.i16(2), a.i16(4), a.i16(6))
                return Box(l * PT_PER_MASTER, t * PT_PER_MASTER, (r - l) * PT_PER_MASTER, (b - t) * PT_PER_MASTER)
            }
            sp.kid(CHILD_ANCHOR)?.let { a ->
                val s = space ?: Space(0f, 0f, PT_PER_MASTER, PT_PER_MASTER, 0f, 0f)
                return s.box(a.i32(0), a.i32(4), a.i32(8), a.i32(12))
            }
            return null
        }

        private fun shape(sp: Rec, space: Space?, ctx: Ctx, decorOnly: Boolean = false, footer: String? = null): DeckItem? {
            val fsp = sp.kid(FSP) ?: return null
            val spt = fsp.inst
            val fl = fsp.i32(4)
            if (fl and 0x8 != 0 || fl and 0x400 != 0) return null
            val data = sp.kid(CLIENT_DATA)
            val ph = data?.kid(PLACEHOLDER)?.u8(4)
            if (decorOnly && ph != null && ph != 0) return null
            if (footer == null && ph in FOOTER_PLACEHOLDERS && ctx.master != null && decorOnly) return null
            val own = Props.of(sp.kid(FOPT), sp.kid(TERTIARY_FOPT))
            val chain = mutableListOf(own)
            if (fl and 0x20 != 0) own.v[0x0301]?.let { id -> ctx.master?.shapes?.get(id) ?: ctx.master?.main?.shapes?.get(id) }?.let { chain += it }
            val masterPh = ph?.let { masterPlaceholder(ctx.master, it) }
            chain += defaults
            val p = PropChain(chain)
            if (p.flag(0x03BF, 0x2, false)) return null

            var box = anchor(sp, space) ?: return null
            val rot = ((p[0x0004] ?: 0) / 65536f).let { ((it % 360f) + 360f) % 360f }
            // При повороте около 90° и 270° рамка в файле записана повёрнутой: возвращаем её.
            if (rot in 45f..135f || rot in 225f..315f) box = Box(box.cx - box.h / 2, box.cy - box.w / 2, box.h, box.w)
            var flipH = fl and 0x40 != 0
            var flipV = fl and 0x80 != 0
            val rotation = if (rot > 180f) rot - 360f else rot

            val ole = fl and 0x10 != 0 || data?.kid(EXOBJ_REF) != null
            val pib = p[0x0104]
            if (ole && (pib == null || pib <= 0 || blips[pib]?.type !in BITMAPS)) {
                warnings += "диаграммы и вложенные объекты (OLE) встроенный перевод не рисует: точный перевод — PowerPoint или LibreOffice на компьютере"
                return null
            }
            if (ole) warnings += "диаграммы и вложенные объекты (OLE) показываются сохранённой картинкой, без правки и обновления"
            if (pib != null && pib > 0 && (spt == 75 || ole || !p.has(0x0180) || spt == 0 || spt == 1)) {
                val img = blipKey(pib) ?: return null
                val crop = Crop(fix(p[0x0102]), fix(p[0x0100]), fix(p[0x0103]), fix(p[0x0101]))
                // Рамка картинки — только своя: умолчание рисунка (линия есть) к картинкам не относится.
                val own = PropChain(chain.filter { it !== defaults })
                return DeckItem.Picture(box, img, crop, lineOf(own, ctx, picture = true), rotation, flipH, flipV)
            }

            val geomInfo = geom(spt, p)
            val geom = geomInfo.first
            if (spt == 8) flipV = !flipV
            val isLine = geom == Geom.LINE
            val fill = if (isLine) null else fillOf(p, ctx, box, background = false)
            val line = lineOf(p, ctx, picture = false)
            val text = textOf(sp, p, masterPh, ctx, ph, footer)
            if (fill == null && line == null && text == null) return null
            // Поправка скругления: у фигур OfficeArt — доля 21600, у фигур LibreOffice (0xFFF) — доля 100000, как в OOXML.
            val corner = if (geom == Geom.ROUND_RECT) ((p[0x0147] ?: 3600) / (if (spt == 0xFFF) 100000f else 21600f)).coerceIn(0f, 0.5f) else 0.16667f
            return DeckItem.Shape(box, geom, fill, line, text, rotation, flipH, flipV, corner, geomInfo.second)
        }

        private fun masterPlaceholder(m: Master?, ph: Int): PropChain? {
            m ?: return null
            val want = when (ph) {
                13, 17 -> listOf(1, 3)
                15 -> listOf(3, 1)
                16 -> listOf(4, 2)
                in 14..26 -> listOf(2)
                else -> listOf(ph)
            }
            for (w in want) (m.placeholders[w] ?: m.main?.placeholders?.get(w))?.let { return it }
            return null
        }

        // ——— Форма ———

        private fun geom(spt: Int, p: PropChain): Pair<Geom, List<List<PathCmd>>> {
            val g = when (spt) {
                1, 202, 109, 75 -> Geom.RECT
                2, 176 -> Geom.ROUND_RECT
                3, 120 -> Geom.ELLIPSE
                4, 110 -> Geom.DIAMOND
                5, 127 -> Geom.TRIANGLE
                6 -> Geom.RT_TRIANGLE
                7, 111 -> Geom.PARALLELOGRAM
                8 -> Geom.TRAPEZOID
                9 -> Geom.HEXAGON
                13 -> Geom.RIGHT_ARROW
                15 -> Geom.PENTAGON
                55 -> Geom.CHEVRON
                66 -> Geom.LEFT_ARROW
                20, in 32..40 -> Geom.LINE
                else -> null
            }
            if (g != null) return g to emptyList()
            PRESET_PATHS[spt]?.let { return Geom.CUSTOM to listOf(it) }
            val paths = customPaths(p)
            if (paths == null) return (if (p[0x0147] != null) Geom.ROUND_RECT else Geom.RECT) to emptyList()
            return if (paths.isNotEmpty()) Geom.CUSTOM to paths else Geom.RECT to emptyList()
        }

        /**
         * Произвольная фигура: вершины (pVertices) и отрезки (pSegmentInfo) в координатах geoLeft…geoBottom.
         * Вершины могут ссылаться на формулы (pGuides) — так пишет фигуры LibreOffice.
         */
        private fun customPaths(p: PropChain): List<List<PathCmd>>? {
            val vb = p.complex(0x0145) ?: return emptyList()
            val guides = guides(p)
            fun coord(raw: Int, wide: Boolean): Float {
                val ref = if (wide) raw ushr 31 == 1 && raw and 0x7FFF0000 == 0 else false
                return if (ref) guides.getOrElse(raw and 0xFFFF) { 0.0 }.toFloat() else raw.toFloat()
            }
            val pts = array(vb, 8) { b, o, size ->
                if (size == 4) Cfb.le16(b, o).toShort().toFloat() to Cfb.le16(b, o + 2).toShort().toFloat()
                else coord(Cfb.le32(b, o), true) to coord(Cfb.le32(b, o + 4), true)
            }
            if (pts.isEmpty()) return emptyList()
            val segs = p.complex(0x0146)?.let { sb -> array(sb, 2) { bb, o, _ -> Cfb.le16(bb, o) } }
            var l = (p[0x0140] ?: 0).toFloat(); var t = (p[0x0141] ?: 0).toFloat()
            var r = (p[0x0142] ?: 21600).toFloat(); var b = (p[0x0143] ?: 21600).toFloat()
            if (r <= l || b <= t) {
                // Пустая система координат — так пишет фигуры LibreOffice. Если вершин больше, чем требуют
                // отрезки, это его дуги, записанные как прямые: контур не восстановить.
                if (!segs.isNullOrEmpty() && expectedVertices(segs) != pts.size) return null
                l = pts.minOf { it.first }; r = pts.maxOf { it.first }; t = pts.minOf { it.second }; b = pts.maxOf { it.second }
            }
            val w = if (r > l) r - l else 1f
            val h = if (b > t) b - t else 1f
            fun pt(i: Int) = pts[i.coerceIn(0, pts.lastIndex)].let { (x, y) -> (x - l) / w to (y - t) / h }
            val out = mutableListOf<List<PathCmd>>()
            var cur = mutableListOf<PathCmd>()
            if (segs.isNullOrEmpty()) {
                cur += pt(0).let { PathCmd.Move(it.first, it.second) }
                for (i in 1 until pts.size) cur += pt(i).let { PathCmd.Line(it.first, it.second) }
                return listOf(cur)
            }
            var v = 0
            var steps = 0
            for (s in segs) {
                val type = s ushr 13
                val count = s and 0x1FFF
                if (steps++ > 100000) break
                when (type) {
                    0 -> repeat(maxOf(count, 1)) { if (v < pts.size) { val q = pt(v++); cur += if (cur.isEmpty()) PathCmd.Move(q.first, q.second) else PathCmd.Line(q.first, q.second) } }
                    1 -> repeat(maxOf(count, 1)) {
                        if (v + 2 < pts.size) {
                            val a = pt(v); val bq = pt(v + 1); val c = pt(v + 2); v += 3
                            cur += PathCmd.Cubic(a.first, a.second, bq.first, bq.second, c.first, c.second)
                        } else v = pts.size
                    }
                    2 -> { if (cur.isNotEmpty()) out += cur; cur = mutableListOf(); if (v < pts.size) pt(v++).let { cur += PathCmd.Move(it.first, it.second) } }
                    3 -> cur += PathCmd.Close
                    4 -> { if (cur.isNotEmpty()) out += cur; cur = mutableListOf() }
                    else -> {
                        val n = s and 0xFF
                        val code = (s ushr 8) and 0x1F
                        fun last(): Pair<Float, Float> = when (val c = cur.lastOrNull { it !is PathCmd.Close }) {
                            is PathCmd.Move -> c.x to c.y; is PathCmd.Line -> c.x to c.y; is PathCmd.Cubic -> c.x to c.y; else -> 0f to 0f
                        }
                        fun lineTo(q: Pair<Float, Float>) { cur += if (cur.isEmpty()) PathCmd.Move(q.first, q.second) else PathCmd.Line(q.first, q.second) }
                        when (code) {
                            // Четверти эллипса: первая начинается по X (7) или по Y (8), дальше чередуются.
                            7, 8 -> for (k in 0 until n) {
                                if (v >= pts.size) break
                                val q = pt(v++)
                                if (cur.isEmpty()) { lineTo(q); continue }
                                val (x0, y0) = last()
                                val xFirst = (code == 7) == (k % 2 == 0)
                                val kk = 0.5523f
                                cur += if (xFirst) PathCmd.Cubic(x0 + (q.first - x0) * kk, y0, q.first, q.second - (q.second - y0) * kk, q.first, q.second)
                                else PathCmd.Cubic(x0, y0 + (q.second - y0) * kk, q.first - (q.first - x0) * kk, q.second, q.first, q.second)
                            }
                            9 -> for (k in 0 until n / 2) {
                                if (v + 1 >= pts.size) { v = pts.size; break }
                                val c1 = pt(v); val e = pt(v + 1); v += 2
                                val (x0, y0) = last()
                                cur += PathCmd.Cubic(x0 + 2f / 3 * (c1.first - x0), y0 + 2f / 3 * (c1.second - y0), e.first + 2f / 3 * (c1.first - e.first), e.second + 2f / 3 * (c1.second - e.second), e.first, e.second)
                            }
                            // Дуги по рамке: упрощаются до отрезка к концу дуги.
                            3, 5 -> repeat(n / 4) { if (v + 3 < pts.size) { lineTo(pt(v + 3)); v += 4 } else v = pts.size }
                            else -> v += n
                        }
                    }
                }
            }
            if (cur.isNotEmpty()) out += cur
            return out.filter { path -> path.size > 1 && path.first() is PathCmd.Move }
        }

        /** Сколько вершин требуют отрезки pSegmentInfo. */
        private fun expectedVertices(segs: List<Int>): Int = segs.sumOf { s ->
            val n = s and 0x1FFF
            when (s ushr 13) {
                0 -> maxOf(n, 1)
                1 -> maxOf(n, 1) * 3
                2 -> 1
                5, 6 -> s and 0xFF
                else -> 0
            }
        }

        /** Формулы фигуры (pGuides): ссылки на поправки, другие формулы и размеры. */
        private fun guides(p: PropChain): DoubleArray {
            val gb = p.complex(0x0156) ?: return DoubleArray(0)
            val list = array(gb, 8) { b, o, _ -> intArrayOf(Cfb.le16(b, o), Cfb.le16(b, o + 2), Cfb.le16(b, o + 4), Cfb.le16(b, o + 6)) }
            val out = DoubleArray(list.size)
            val gw = ((p[0x0142] ?: 21600) - (p[0x0140] ?: 0)).toDouble().takeIf { it > 0 } ?: 21600.0
            val gh = ((p[0x0143] ?: 21600) - (p[0x0141] ?: 0)).toDouble().takeIf { it > 0 } ?: 21600.0
            fun ref(v: Int): Double = when (v) {
                in 0x0400 until 0x0400 + list.size -> out[v - 0x0400]
                in 0x0147..0x0150 -> (p[v] ?: if (v == 0x0147) 5400 else 0).toDouble()
                0x0140, 0x0141 -> (p[v] ?: 0).toDouble()
                0x0142 -> (p[v] ?: 21600).toDouble()
                0x0143 -> (p[v] ?: 21600).toDouble()
                0x0153 -> gw
                0x0154 -> gh
                else -> 0.0
            }
            for ((i, g) in list.withIndex()) {
                val sgf = g[0]
                val a = if (sgf and 0x2000 != 0) ref(g[1]) else g[1].toShort().toDouble()
                val b = if (sgf and 0x4000 != 0) ref(g[2]) else g[2].toShort().toDouble()
                val c = if (sgf and 0x8000 != 0) ref(g[3]) else g[3].toShort().toDouble()
                fun rad(x: Double) = Math.toRadians(x / 65536.0)
                val r = when (sgf and 0x1FFF) {
                    0 -> a + b - c
                    1 -> if (c == 0.0) 0.0 else a * b / c
                    2 -> (a + b) / 2
                    3 -> kotlin.math.abs(a)
                    4 -> minOf(a, b)
                    5 -> maxOf(a, b)
                    6 -> if (a > 0) b else c
                    7 -> kotlin.math.sqrt(a * a + b * b + c * c)
                    8 -> Math.toDegrees(kotlin.math.atan2(b, a)) * 65536.0
                    9 -> a * kotlin.math.sin(rad(b))
                    10 -> a * kotlin.math.cos(rad(b))
                    11 -> a * kotlin.math.cos(kotlin.math.atan2(c, b))
                    12 -> a * kotlin.math.sin(kotlin.math.atan2(c, b))
                    13 -> kotlin.math.sqrt(kotlin.math.abs(a))
                    14 -> a + b * 65536.0 - c * 65536.0
                    15 -> if (b == 0.0) 0.0 else c * kotlin.math.sqrt(kotlin.math.abs(1 - (a / b) * (a / b)))
                    16 -> a * kotlin.math.tan(rad(b))
                    else -> 0.0
                }
                out[i] = if (r.isFinite()) r else 0.0
            }
            return out
        }

        /** IMsoArray: число элементов, размер элемента (0xFFF0 — половина обычного), сами элементы. */
        private fun <T> array(b: ByteArray, full: Int, f: (ByteArray, Int, Int) -> T): List<T> {
            if (b.size < 6) return emptyList()
            val n = Cfb.le16(b, 0)
            val cb = Cfb.le16(b, 4)
            val size = when (cb) { 0xFFF0 -> full / 2; 0 -> full; else -> cb }
            if (size <= 0) return emptyList()
            return (0 until minOf(n, (b.size - 6) / size)).map { f(b, 6 + it * size, size) }
        }

        // ——— Заливка и линия ———

        private fun fillOf(p: PropChain, ctx: Ctx, box: Box, background: Boolean): DeckPaint? {
            if (!p.flag(0x01BF, 0x10, true)) return null
            val type = p[0x0180] ?: 0
            val alpha = ((p[0x0182] ?: 0x10000) / 65536f).coerceIn(0f, 1f)
            val color = withAlpha(color(p[0x0181] ?: 0xFFFFFF, ctx, p), alpha)
            return when (type) {
                0 -> DeckPaint.Solid(color)
                1 -> DeckPaint.Solid(color)
                2, 3 -> p[0x0186]?.let { blipKey(it) }?.let { DeckPaint.Picture(it) } ?: DeckPaint.Solid(color)
                in 4..8 -> {
                    if (type == 5 || type == 6) warnings += "радиальные градиенты показываются линейными"
                    val back = withAlpha(color(p[0x0183] ?: 0xFFFFFF, ctx, p), ((p[0x0184] ?: 0x10000) / 65536f).coerceIn(0f, 1f))
                    gradient(p, ctx, color, back)
                }
                9 -> if (background) DeckPaint.Solid(color) else ctx.background
                else -> DeckPaint.Solid(color)
            }
        }

        /** Линейный градиент: угол fillAngle, фокус 0 / 100 / ±50 (зеркальный), свои точки fillShadeColors. */
        private fun gradient(p: PropChain, ctx: Ctx, fore: Int, back: Int): DeckPaint {
            val angle = (p[0x018B] ?: 0) / 65536f
            val focus = p[0x018C] ?: 0
            val custom = p.complex(0x0197)?.let { b -> array(b, 8) { bb, o, _ -> (Cfb.le32(bb, o + 4) / 65536f).coerceIn(0f, 1f) to color(Cfb.le32(bb, o), ctx, p) } }
            var stops: List<Pair<Float, Int>> = custom?.takeIf { it.size >= 2 }?.sortedBy { it.first } ?: listOf(0f to fore, 1f to back)
            stops = when {
                focus >= 75 || focus <= -75 -> stops.map { (1f - it.first) to it.second }.reversed()
                focus in 25..74 -> stops.map { it.first / 2 to it.second } + stops.reversed().map { (1f - it.first / 2) to it.second }
                focus in -74..-25 -> stops.reversed().map { (1f - it.first) / 2 to it.second } + stops.map { (0.5f + it.first / 2) to it.second }
                else -> stops
            }
            // Фокус 0: от fillColor к fillBackColor. Угол OfficeArt −270° — слева направо (как проверено на LibreOffice),
            // в модели угол по часовой от горизонтали: deg = 90 − angle.
            val deg = ((90f - angle) % 360f + 360f) % 360f
            return DeckPaint.Linear(stops, deg)
        }

        private fun lineOf(p: PropChain, ctx: Ctx, picture: Boolean): DeckLine? {
            if (!p.flag(0x01FF, 0x8, !picture)) return null
            val w = ((p[0x01CB] ?: 9525) / EMU_PER_POINT).coerceIn(0f, 200f)
            val alpha = ((p[0x01C1] ?: 0x10000) / 65536f).coerceIn(0f, 1f)
            val c = withAlpha(color(p[0x01C0] ?: 0, ctx, p), alpha)
            return DeckLine(c, w, (p[0x01CE] ?: 0) != 0)
        }

        /** OfficeArtCOLORREF: RGB, цвет схемы слайда или ссылка на другой цвет фигуры. */
        private fun color(v: Int, ctx: Ctx, p: PropChain?, depth: Int = 0): Int {
            val flags = v ushr 24
            return when {
                flags and 0x08 != 0 -> ctx.scheme[(v and 0xFF).coerceIn(0, 7)]
                flags and 0x10 != 0 -> {
                    val ref = when (v and 0xFF) { 0xF0 -> 0x0181; 0xF1, 0xF2 -> 0x01C0; 0xF3 -> 0x0183; 0xF4 -> 0x01C2; 0xF5 -> 0x0181; 0xF6 -> 0x0201; else -> null }
                    val target = ref?.let { p?.get(it) }
                    if (target != null && depth < 3 && (target ushr 24) and 0x10 == 0) color(target, ctx, p, depth + 1) else ctx.scheme[1]
                }
                else -> rgb(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF)
            }
        }

        // ——— Текст ———

        private fun textOf(sp: Rec, p: PropChain, masterPh: PropChain?, ctx: Ctx, ph: Int?, footer: String?): DeckText? {
            val box = sp.kid(CLIENT_TEXTBOX)
            val texts = mutableListOf<TextRec>()
            if (box != null) {
                box.kid(OUTLINE_TEXT_REF)?.let { ref -> ctx.texts.getOrNull(ref.i32(0))?.let { texts += it } }
                if (texts.isEmpty()) collectTexts(box.kids, texts)
            }
            var t = texts.firstOrNull() ?: return null
            // Номер слайда — поле «*» в тексте мастера; нижний колонтитул и дата — строки из HeadersFooters.
            if (footer != null && footer != "#") {
                val src = t
                t = TextRec(src.type).also { it.chars = footer; it.style = src.style; it.ruler = src.ruler }
            }
            val chars = t.chars
            if (chars.isEmpty()) return null
            fun prop(pid: Int) = p[pid] ?: masterPh?.get(pid)
            val insets = Box(
                (prop(0x0081) ?: 91440) / EMU_PER_POINT, (prop(0x0082) ?: 45720) / EMU_PER_POINT,
                (prop(0x0083) ?: 91440) / EMU_PER_POINT, (prop(0x0084) ?: 45720) / EMU_PER_POINT,
            )
            val anchor = when (prop(0x0087) ?: 0) { 1, 4 -> TextAnchor.MIDDLE; 2, 5, 7, 9 -> TextAnchor.BOTTOM; else -> TextAnchor.TOP }
            val wrap = (prop(0x0085) ?: 0) != 2
            val flow = prop(0x0088) ?: 0
            if (flow != 0 && flow != 4) warnings += "вертикальный текст показывается горизонтально"
            val type = t.type.takeIf { it in 0..8 } ?: when (ph) { 13, 15, 17, 1, 3 -> 0; null -> 4; else -> 1 }

            val pfRuns = mutableListOf<Triple<Int, Int, PF>>()
            val cfRuns = mutableListOf<Pair<Int, CF>>()
            t.style?.let { st ->
                var pos = 0
                var total = 0
                while (total < chars.length + 1 && pos + 6 <= st.len) {
                    val n = st.i32(pos)
                    if (n <= 0) break
                    val (pf, np) = readPF(st, pos + 6)
                    pfRuns += Triple(n, st.u16(pos + 4).coerceIn(0, 4), pf)
                    total += n; pos = np
                }
                total = 0
                while (total < chars.length + 1 && pos + 4 <= st.len) {
                    val n = st.i32(pos)
                    if (n <= 0) break
                    val (cf, np) = readCF(st, pos + 4)
                    cfRuns += n to cf
                    total += n; pos = np
                }
            }
            val pfStarts = starts(pfRuns.map { it.first })
            val cfStarts = starts(cfRuns.map { it.first })
            val ruler = t.ruler?.let(::rulerLevels)
            val number = ctx.number.toString()

            val paras = mutableListOf<DeckPara>()
            var start = 0
            while (start <= chars.length) {
                var end = chars.indexOf('\r', start)
                if (end < 0) end = chars.length
                val pfAt = runAt(pfStarts, start)
                val level = pfAt?.let { pfRuns[it].second } ?: 0
                val basePF = masterPF(ctx.master, type, level)
                val pf = (pfAt?.let { pfRuns[it].third } ?: PF()).over(ruler?.getOrNull(level)).over(basePF)
                val baseCF = masterCF(ctx.master, type, level)

                val runs = mutableListOf<DeckRun>()
                var i = start
                while (i < end) {
                    val ri = runAt(cfStarts, i)
                    val runEnd = ri?.let { minOf(end, cfStarts[it] + cfRuns[it].first) } ?: end
                    val stop = if (runEnd <= i) end else runEnd
                    val cf = (ri?.let { cfRuns[it].second } ?: CF()).over(baseCF)
                    val sb = StringBuilder()
                    for (k in i until stop) {
                        val ch = chars[k]
                        when {
                            ch == '\u000B' -> sb.append('\n')
                            ch == '\t' -> sb.append('\t')
                            ch == '*' && (k in t.slideNumbers || footer == "#") -> sb.append(number)
                            ch < ' ' -> {}
                            else -> sb.append(ch)
                        }
                    }
                    if (sb.isNotEmpty()) runs += deckRun(sb.toString(), cf, ctx)
                    i = stop
                }
                paras += para(runs, pf, baseCF, ctx)
                start = end + 1
            }
            if (paras.none { pa -> pa.runs.any { it.text.isNotBlank() } }) return null
            return DeckText(paras, insets, anchor, wrap)
        }

        /** Начала прогонов: номер прогона по позиции знака — двоичным поиском. */
        private fun starts(counts: List<Int>): IntArray {
            val out = IntArray(counts.size)
            var acc = 0L
            for ((k, n) in counts.withIndex()) { out[k] = acc.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(); acc += n }
            return out
        }

        private fun runAt(starts: IntArray, i: Int): Int? {
            if (starts.isEmpty()) return null
            val k = java.util.Arrays.binarySearch(starts, i)
            return if (k >= 0) k else (-k - 2).coerceAtLeast(0)
        }

        /** Отступы уровней из TextRulerAtom. */
        private fun rulerLevels(r: Rec): List<PF> {
            val m = r.i32(0)
            var p = 4
            if (m and 0x2 != 0) p += 2
            if (m and 0x1 != 0) p += 2
            if (m and 0x4 != 0) { val n = r.u16(p).coerceAtMost(1000); p += 2 + n * 4 }
            return (0 until 5).map { lv ->
                val lm = if (m and (0x8 shl lv) != 0) r.i16(p).also { p += 2 } else null
                val ind = if (m and (0x100 shl lv) != 0) r.i16(p).also { p += 2 } else null
                PF(leftMargin = lm, indent = ind)
            }
        }

        private fun typeChain(type: Int): List<Int> = when (type) {
            5, 7, 8 -> listOf(type, 1)
            6 -> listOf(6, 0)
            else -> listOf(type)
        }

        private fun masterPF(m: Master?, type: Int, level: Int): PF {
            var pf = PF()
            for (styles in listOfNotNull(m?.styles, m?.main?.styles, envStyles)) for (t in typeChain(type) + 4) {
                val lv = styles[t] ?: continue
                for (l in minOf(level, lv.lastIndex) downTo 0) pf = pf.over(lv[l].first)
            }
            return pf.over(envPF)
        }

        private fun masterCF(m: Master?, type: Int, level: Int): CF {
            var cf = CF()
            for (styles in listOfNotNull(m?.styles, m?.main?.styles, envStyles)) for (t in typeChain(type) + 4) {
                val lv = styles[t] ?: continue
                for (l in minOf(level, lv.lastIndex) downTo 0) cf = cf.over(lv[l].second)
            }
            return cf.over(envCF)
        }

        private fun fontName(i: Int?): String = fonts.getOrNull(i ?: 0)?.takeIf { it.isNotBlank() } ?: fonts.firstOrNull() ?: "Arial"

        private fun textColor(v: Int?, ctx: Ctx): Int {
            v ?: return ctx.scheme[1]
            val idx = v ushr 24
            return when {
                idx == 0xFE -> rgb(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF)
                idx < 8 -> ctx.scheme[idx]
                else -> ctx.scheme[1]
            }
        }

        private fun deckRun(text: String, cf: CF, ctx: Ctx) = DeckRun(
            text, FontSpec(fontName(cf.font), (cf.size ?: 18).toFloat(), cf.bold == true, cf.italic == true), textColor(cf.color, ctx), cf.underline == true,
        )

        private fun para(runs: List<DeckRun>, pf: PF, baseCF: CF, ctx: Ctx): DeckPara {
            val size = runs.firstOrNull()?.font?.size ?: (baseCF.size ?: 18).toFloat()
            val bullet = if (pf.hasBullet == true && runs.any { it.text.isNotBlank() }) {
                val first = runs.first()
                val family = if (pf.bulletHasFont == true && pf.bulletFont != null) fontName(pf.bulletFont) else first.font.family
                var c = pf.bulletChar ?: 0x2022
                if (c in 0xF000..0xF0FF) c -= 0xF000
                if (c == 0) c = 0x2022
                val bs = pf.bulletSize
                val bsize = when {
                    pf.bulletHasSize != true || bs == null -> first.font.size
                    bs in 25..400 -> first.font.size * bs / 100f
                    bs < 0 -> -bs / 100f
                    else -> first.font.size
                }
                val color = if (pf.bulletHasColor == true && pf.bulletColor != null) textColor(pf.bulletColor, ctx) else first.argb
                DeckRun(Pptx.bulletChar(c.toChar().toString(), family), first.font.copy(family = Pptx.safeBulletFont(family), size = bsize), color)
            } else null
            fun spacing(v: Int?): Float = when {
                v == null -> 0f
                v < 0 -> -v * PT_PER_MASTER
                else -> v / 100f * size * 1.2f
            }
            val ls = pf.lineSpacing
            val left = (pf.leftMargin ?: 0) * PT_PER_MASTER
            val indent = (pf.indent ?: 0) * PT_PER_MASTER
            return DeckPara(
                runs,
                when (pf.align) { 1 -> TextAlign.CENTER; 2 -> TextAlign.RIGHT; 3, 4, 6 -> TextAlign.JUSTIFY; else -> TextAlign.LEFT },
                marL = left, indent = indent - left, bullet = bullet,
                spaceBefore = spacing(pf.spaceBefore), spaceAfter = spacing(pf.spaceAfter),
                lineSpacing = if (ls != null && ls > 0) (ls / 100f).coerceIn(0.5f, 5f) else 1f,
                lineSpacingPt = if (ls != null && ls < 0) -ls * PT_PER_MASTER else null,
                emptySize = size,
            )
        }

        // ——— Картинки ———

        private fun blipKey(pib: Int): String? {
            val b = blips[pib] ?: return null
            return when (b.type) {
                in BITMAPS -> "blip:$pib"
                2, 3, 4 -> { warnings += "картинки ${listOf("EMF", "WMF", "PICT")[b.type - 2]} встроенный перевод не рисует: их лучше заменить на PNG или JPEG"; null }
                0x11 -> { warnings += "картинки TIFF встроенный перевод не рисует: их лучше заменить на PNG или JPEG"; null }
                else -> null
            }
        }

        private fun image(table: Map<Int, Blip>, key: String): ByteArray? {
            val b = table[key.removePrefix("blip:").toIntOrNull() ?: return null] ?: return null
            val rec: ByteArray = b.embedded ?: run {
                if (b.delay < 0 || b.delay >= picturesSize) return null
                Cfb.open(file).use { c ->
                    val head = c.read("Pictures", b.delay, 8) ?: return null
                    val len = Cfb.le32(head, 4)
                    if (len <= 0 || len > MAX_IMAGE) return null
                    c.read("Pictures", b.delay, len + 8)
                }
            } ?: return null
            return blipData(rec)
        }
    }

    private const val MAX_IMAGE = 64 shl 20

    /** Запись BLIP → байты картинки: JPEG, PNG как есть, DIB → BMP. */
    private fun blipData(rec: ByteArray): ByteArray? {
        if (rec.size < 8) return null
        val inst = Cfb.le16(rec, 0) ushr 4
        val type = Cfb.le16(rec, 2)
        val skip = 8 + 16 * (1 + (inst and 1)) + 1
        if (skip >= rec.size) return null
        val data = rec.copyOfRange(skip, rec.size)
        return when (type) {
            0xF01D, 0xF02A, 0xF01E -> data
            0xF01F -> dibToBmp(data)
            else -> null
        }
    }

    /** DIB без заголовка файла: дописываем BITMAPFILEHEADER, чтобы картинку прочитал любой декодер. */
    private fun dibToBmp(dib: ByteArray): ByteArray? {
        if (dib.size < 12) return null
        val hs = Cfb.le32(dib, 0)
        if (hs < 12 || hs > dib.size) return null
        val bits: Int
        var colors: Int
        var masks = 0
        if (hs == 12) { bits = Cfb.le16(dib, 10); colors = 0 } else {
            bits = Cfb.le16(dib, 14)
            colors = Cfb.le32(dib, 32)
            if (Cfb.le32(dib, 16) == 3 && hs == 40) masks = 12
        }
        if (colors <= 0 && bits <= 8) colors = 1 shl bits
        val palette = colors.coerceIn(0, 256) * (if (hs == 12) 3 else 4)
        val offset = 14 + hs + masks + palette
        val total = 14 + dib.size
        val head = ByteArray(14)
        head[0] = 'B'.code.toByte(); head[1] = 'M'.code.toByte()
        for (i in 0 until 4) { head[2 + i] = (total ushr (8 * i)).toByte(); head[10 + i] = (offset ushr (8 * i)).toByte() }
        return head + dib
    }

    private fun fix(v: Int?): Float = ((v ?: 0) / 65536f).coerceIn(-1f, 1f)

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun withAlpha(argb: Int, a: Float): Int = (argb and 0x00FFFFFF) or ((a * 255).roundToInt().coerceIn(0, 255) shl 24)

    private val ARRAYS = setOf(0x0145, 0x0146, 0x0151, 0x0152, 0x0155, 0x0156, 0x0157, 0x0197, 0x0158)
    private const val ANIMATION = "анимация и переходы не переносятся: каждый слайд показывается целиком"
    private val FOOTER_PLACEHOLDERS = setOf(7, 8, 9)
    /** Типы BLIP, которые рисуются: JPEG, PNG, DIB, CMYK JPEG. */
    private val BITMAPS = setOf(5, 6, 7, 0x12)
    private val MEDIA = setOf(0x1006, 0x1007, 0x100D, 0x100E, 0x1010, 0x1011)

    /** Схема по умолчанию: фон, текст, тень, заголовок, заливка, акценты. */
    private val DEFAULT_SCHEME = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF808080.toInt(), 0xFF000000.toInt(),
        0xFFBBE0E3.toInt(), 0xFF333399.toInt(), 0xFF009999.toInt(), 0xFF99CC00.toInt(),
    )

    /** Фигуры, которых нет среди готовых форм модели: контуры в долях рамки. */
    private val PRESET_PATHS: Map<Int, List<PathCmd>> = run {
        fun poly(vararg xy: Float): List<PathCmd> = xy.toList().chunked(2).mapIndexed { i, (x, y) -> if (i == 0) PathCmd.Move(x, y) else PathCmd.Line(x, y) } + PathCmd.Close
        mapOf(
            10 to poly(0.29f, 0f, 0.71f, 0f, 1f, 0.29f, 1f, 0.71f, 0.71f, 1f, 0.29f, 1f, 0f, 0.71f, 0f, 0.29f),
            11 to poly(0.25f, 0f, 0.75f, 0f, 0.75f, 0.25f, 1f, 0.25f, 1f, 0.75f, 0.75f, 0.75f, 0.75f, 1f, 0.25f, 1f, 0.25f, 0.75f, 0f, 0.75f, 0f, 0.25f, 0.25f, 0.25f),
            12 to poly(0.5f, 0f, 0.62f, 0.38f, 1f, 0.38f, 0.69f, 0.62f, 0.81f, 1f, 0.5f, 0.76f, 0.19f, 1f, 0.31f, 0.62f, 0f, 0.38f, 0.38f, 0.38f),
            56 to poly(0.5f, 0f, 1f, 0.38f, 0.81f, 1f, 0.19f, 1f, 0f, 0.38f),
            67 to poly(0.25f, 0f, 0.75f, 0f, 0.75f, 0.7f, 1f, 0.7f, 0.5f, 1f, 0f, 0.7f, 0.25f, 0.7f),
            68 to poly(0.5f, 0f, 1f, 0.3f, 0.75f, 0.3f, 0.75f, 1f, 0.25f, 1f, 0.25f, 0.3f, 0f, 0.3f),
            69 to poly(0f, 0.5f, 0.2f, 0f, 0.2f, 0.25f, 0.8f, 0.25f, 0.8f, 0f, 1f, 0.5f, 0.8f, 1f, 0.8f, 0.75f, 0.2f, 0.75f, 0.2f, 1f),
            14 to poly(0f, 0.25f, 0.7f, 0.25f, 0.7f, 0f, 1f, 0.5f, 0.7f, 1f, 0.7f, 0.75f, 0f, 0.75f),
            16 to poly(0f, 0.25f, 0.25f, 0f, 1f, 0f, 1f, 0.75f, 0.75f, 1f, 0f, 1f),
        )
    }
}
