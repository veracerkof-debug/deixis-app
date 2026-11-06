package app.deixis.phone

import app.deixis.core.BibleModule
import app.deixis.core.BibleSearch
import app.deixis.core.Verse
import app.deixis.core.VerseHit
import app.deixis.core.parseBibleRef
import app.deixis.core.refLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Открытая глава: стихи основного и второго перевода. */
data class ChapterText(val book: Int, val chapter: Int, val title: String, val primary: List<Verse>, val second: List<Verse>?, val chapters: Int)

/** Место из истории: книга, глава, стихи. */
data class Passage(val book: Int, val chapter: Int, val from: Int, val to: Int) {
    fun key() = "$book:$chapter:$from:$to"
    companion object {
        fun parse(s: String): Passage? = s.split(':').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 }?.let { Passage(it[0], it[1], it[2], it[3]) }
    }
}

/**
 * Вкладка «Библия» без экрана: открытая глава, выбранные стихи, переводы, поиск и история.
 * Живёт в пульте, поэтому место не теряется, пока оператор работает в других вкладках.
 */
@OptIn(FlowPreview::class)
class BibleDesk(private val c: Controller, private val scope: CoroutineScope) {
    private val lib get() = c.bible

    val selected = MutableStateFlow<List<String>>(emptyList())
    val chapter = MutableStateFlow<ChapterText?>(null)
    /** Выбранные стихи (предпросмотр); стихи в зале — по живому показу. */
    val mark = MutableStateFlow<IntRange?>(null)
    val history = MutableStateFlow<List<Passage>>(emptyList())
    val query = MutableStateFlow("")
    val hits = MutableStateFlow<List<VerseHit>?>(null)
    val searching = MutableStateFlow(false)
    /** Стих, к которому прокрутить список после перехода. */
    val scrollTo = MutableStateFlow<Int?>(null)

    private var loaded = false
    private var searchJob: Job? = null
    private val searchers = java.util.concurrent.ConcurrentHashMap<String, BibleSearch>()
    private var openJob: Job? = null

    init {
        // Перевод добавили заново или удалили: прежний индекс поиска держит закрытый файл — забыть его.
        scope.launch { c.bibleModules.collect { searchers.clear() } }
    }

    /** Первое открытие вкладки: переводы и последнее место. */
    fun ensure() {
        if (loaded || c.bibleModules.value.isEmpty()) return
        loaded = true
        selected.value = lib.selected.filter { f -> c.bibleModules.value.any { it.fileName == f } }.ifEmpty { listOf(c.bibleModules.value.first().fileName) }
        history.value = lib.history.mapNotNull(Passage::parse)
        val last = lib.lastRef.split(':').mapNotNull { it.toIntOrNull() }
        open(last.getOrNull(0) ?: 500, last.getOrNull(1) ?: 3)
    }

    fun modules(): List<BibleModule> = selected.value.mapNotNull { f -> c.bibleModules.value.firstOrNull { it.fileName == f } }
    private fun primary(): BibleModule? = modules().firstOrNull()

    /** Перевод в паре: касание добавляет вторым или убирает; первый остаётся основным. */
    fun toggle(file: String) {
        val cur = selected.value
        selected.value = when {
            file in cur && cur.size > 1 -> cur - file
            file in cur -> cur
            cur.size >= 2 -> listOf(cur.first(), file)
            else -> cur + file
        }
        lib.selected = selected.value
        reload()
        refreshLive()
    }

    /** Переводы по списку (например, взятые из вкладок Библии Holyrics); первый — основной. */
    fun choose(files: List<String>) {
        val have = files.filter { f -> c.bibleModules.value.any { it.fileName == f } }.distinct().take(2)
        if (have.isEmpty()) return
        selected.value = have
        lib.selected = have
        reload()
    }

    /** Основной и второй перевод меняются местами. */
    fun swap() {
        if (selected.value.size < 2) return
        selected.value = selected.value.reversed()
        lib.selected = selected.value
        reload()
        refreshLive()
    }

    /** Открывает главу; [then] — после загрузки (например выбрать стихи и показать). */
    fun open(book: Int, chapter: Int, verse: Int? = null, range: IntRange? = null, then: (() -> Unit)? = null) {
        lib.lastRef = "$book:$chapter"
        // Новое открытие отменяет прежнее: быстрые касания не приводят к главе, выбранной раньше.
        openJob?.cancel()
        openJob = scope.launch {
            val ms = modules()
            val p = ms.firstOrNull() ?: return@launch
            // Модуль заменили или удалили во время чтения, файл испорчен — глава не открывается, приложение не падает.
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    ChapterText(
                        book, chapter, p.book(book)?.longName ?: "", p.verses(book, chapter),
                        runCatching { ms.getOrNull(1)?.verses(book, chapter) }.getOrNull(), p.chapterCount(book),
                    )
                }.getOrNull()
            } ?: return@launch
            this@BibleDesk.chapter.value = text
            mark.value = range ?: verse?.let { it..it }
            scrollTo.value = verse ?: 1
            then?.invoke()
        }
    }

    /** После смены переводов — то же место и тот же выбранный диапазон стихов (а не только первый стих). */
    private fun reload() { chapter.value?.let { ch -> open(ch.book, ch.chapter, mark.value?.first, mark.value) } }

    fun nextChapter(d: Int) {
        val ch = chapter.value ?: return
        val p = primary() ?: return
        val n = ch.chapter + d
        when {
            n in 1..ch.chapters -> open(ch.book, n)
            d > 0 -> p.books.firstOrNull { it.number > ch.book }?.let { open(it.number, 1) }
            else -> p.books.lastOrNull { it.number < ch.book }?.let { b -> open(b.number, p.chapterCount(b.number)) }
        }
    }

    // ——— Выбор и показ ———

    fun select(v: Int) { mark.value = v..v; preview() }

    /** Удержание: от выбранного стиха до этого. */
    fun extend(v: Int) {
        val m = mark.value ?: return select(v)
        mark.value = minOf(m.first, v)..maxOf(m.last, v)
        preview()
    }

    fun label(range: IntRange? = mark.value): String {
        val ch = chapter.value ?: return ""
        val p = primary() ?: return ""
        val r = range ?: return "${p.book(ch.book)?.shortName ?: ""} ${ch.chapter}"
        return refLabel(p, ch.book, ch.chapter, r.first, r.last, full = false, withModule = false)
    }

    private fun pick(range: IntRange): BiblePick? {
        val ch = chapter.value ?: return null
        val opts = lib.options.let { o -> if (range.count() > 1) o.copy(versesPerSlide = range.count().coerceIn(1, 3)) else o }
        return BiblePick(ch.book, ch.chapter, range.first, range.last, selected.value, opts)
    }

    private fun cue(range: IntRange): Cue? {
        val pk = pick(range) ?: return null
        val slides = c.scripture(pk)
        if (slides.isEmpty()) return null
        return Cue(Source(null, label(range), Kind.BIBLE, slides.map { Slide.Scripture(it) }, bible = pk), 0)
    }

    fun preview() { mark.value?.let { r -> cue(r)?.let { c.toPreview(it) } } }

    fun live(range: IntRange? = mark.value) {
        val r = range ?: return
        mark.value = r
        // Пульт Holyrics: стихи — на экран Holyrics, его переводом, тем же, что выбран здесь.
        if (c.toHolyrics()) {
            val pk = pick(r) ?: return
            // «В Holyrics» отмечается, когда Holyrics показал стихи (HolyricsSource), а не сразу.
            c.holyricsRemote?.showBible(pk)
            remember(r)
            return
        }
        val cue = cue(r) ?: return
        c.goLive(cue)
        remember(r)
    }

    /** Следующий или предыдущий стих (или та же длина диапазона): в зале — сразу в зал. */
    fun step(d: Int) {
        val ch = chapter.value ?: return
        val r = mark.value ?: (1..1)
        val size = r.count()
        val from = r.first + d * size
        val last = ch.primary.lastOrNull()?.number ?: return
        if (from < 1 || from > last) return
        val next = from..minOf(from + size - 1, last)
        val shown = if (c.toHolyrics()) c.holyricsVerse.value else c.live.value?.source?.bible
        val showing = shown?.let { it.book == ch.book && it.chapter == ch.chapter } == true
        mark.value = next
        scrollTo.value = next.first
        if (showing) live(next) else preview()
    }

    fun liveRange(): IntRange? {
        val b = (if (c.toHolyrics()) c.holyricsVerse.value else c.live.value?.source?.bible) ?: return null
        val ch = chapter.value ?: return null
        return if (b.book == ch.book && b.chapter == ch.chapter) b.from..b.to else null
    }

    private fun remember(r: IntRange) {
        val ch = chapter.value ?: return
        val p = Passage(ch.book, ch.chapter, r.first, r.last)
        history.value = (listOf(p) + history.value.filter { it != p }).take(30)
        lib.history = history.value.map { it.key() }
    }

    fun openPassage(p: Passage) = open(p.book, p.chapter, p.from) { mark.value = p.from..p.to; preview() }

    /** Из поиска или по адресу: открыть и выбрать стихи, в предпросмотр или сразу в зал. */
    fun go(book: Int, chapter: Int, from: Int?, to: Int?, toHall: Boolean) = open(book, chapter, from) {
        if (from != null) {
            mark.value = from..(to ?: from)
            if (toHall) live() else preview()
        }
    }

    fun forget(p: Passage) {
        history.value = history.value - p
        lib.history = history.value.map { it.key() }
    }

    fun clearHistory() { history.value = emptyList(); lib.history = emptyList() }

    fun passageLabel(p: Passage): String {
        val m = primary() ?: return ""
        return refLabel(m, p.book, p.chapter, p.from, p.to, full = false, withModule = false)
    }

    /** Если в зале Писание из этой вкладки — после смены переводов оно обновляется. */
    fun refreshLive() {
        val b = (if (c.toHolyrics()) c.holyricsVerse.value else c.live.value?.source?.bible) ?: return
        val ch = chapter.value ?: return
        if (b.book == ch.book && b.chapter == ch.chapter) live(b.from..b.to)
    }

    /** Сохранить выбранное место в сетлист. */
    fun saveToSet(): String? {
        val r = mark.value ?: return null
        val pk = pick(r) ?: return null
        val item = LibItem(Store.newId(), Kind.BIBLE, label(r), bible = pk)
        c.saveItem(item)
        c.addToSet(item.id)
        return item.title
    }

    // ——— Поиск ———

    fun search(text: String) {
        query.value = text
        searchJob?.cancel()
        if (text.isBlank()) { hits.value = null; searching.value = false; return }
        val p = primary() ?: return
        // Адрес вида «Ин 3:16» — переход, а не поиск по тексту.
        if (parseBibleRef(text, p.books) != null) { hits.value = null; return }
        searchJob = scope.launch {
            delay(250)
            searching.value = true
            val found = withContext(Dispatchers.Default) { runCatching { searchers.getOrPut(p.fileName) { BibleSearch(p) }.search(text) }.getOrNull() }
            hits.value = found.orEmpty()
            searching.value = false
        }
    }

    fun refOf(text: String) = primary()?.let { parseBibleRef(text, it.books) }

    fun hitLabel(h: VerseHit): String = primary()?.let { refLabel(it, h.book, h.chapter, h.verse, h.verse, full = false, withModule = false) } ?: ""

    fun books() = primary()?.books.orEmpty()
    fun chapterCount(book: Int) = primary()?.chapterCount(book) ?: 0
}

