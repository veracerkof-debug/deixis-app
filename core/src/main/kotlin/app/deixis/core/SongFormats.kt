package app.deixis.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Песни из открытых форматов: ChordPro (`.cho`, `.chopro`, `.chordpro`, `.crd`) и OpenLyrics (`.xml`).
 * Разбор свой, по спецификациям (chordpro.org, openlyrics.org): аккорды убираются, части песни становятся
 * слайдами с подписями «Куплет 1», «Припев», длинная часть делится на несколько слайдов.
 */
data class ImportedSong(val title: String, val slides: List<String>, val labels: List<String?>)

object SongFormats {
    private val CHORDPRO = setOf("cho", "chopro", "chordpro", "crd")

    /** Песня из файла; null — это не ChordPro и не OpenLyrics (тогда файл — обычный текст или не подходит). */
    fun import(fileName: String, text: String): ImportedSong? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext in CHORDPRO -> chordPro(text, fileName.substringBeforeLast('.'))
            ext == "xml" || text.trimStart().startsWith("<?xml") && text.contains("openlyrics") -> openLyrics(text)
            else -> null
        }
    }

    // ——— ChordPro ———

    private val DIRECTIVE = Regex("""^\{\s*([a-zA-Z_]+)\s*(?::\s*(.*?))?\s*}\s*$""")
    private val CHORD = Regex("""\[[^\]]*]""")

    fun chordPro(text: String, fallbackTitle: String = ""): ImportedSong {
        var title = ""
        val parts = mutableListOf<Pair<String?, MutableList<String>>>()
        var current: Pair<String?, MutableList<String>>? = null
        var verses = 0
        // Часть, открытая директивой, закрывается только своей директивой; неявный куплет — пустой строкой.
        var explicit = false
        fun open(label: String?, byDirective: Boolean) { current = label to mutableListOf<String>().also { parts += (label to it) }; explicit = byDirective }
        fun close() { current = null; explicit = false }
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            val d = DIRECTIVE.find(line.trim())
            if (d != null) {
                val name = d.groupValues[1].lowercase()
                val value = d.groupValues.getOrNull(2).orEmpty()
                when (name) {
                    "title", "t" -> title = value
                    "start_of_chorus", "soc" -> open(value.ifBlank { "Припев" }, true)
                    "start_of_verse", "sov" -> open(value.ifBlank { "Куплет ${++verses}" }, true)
                    "start_of_bridge", "sob" -> open(value.ifBlank { "Бридж" }, true)
                    "end_of_chorus", "eoc", "end_of_verse", "eov", "end_of_bridge", "eob" -> close()
                    // Остальное (key, capo, comment, tab…) — для музыкантов, на экран не идёт.
                }
                continue
            }
            if (line.startsWith("#")) continue
            val clean = CHORD.replace(line, "").replace(Regex("\\s{2,}"), " ").trim()
            if (clean.isEmpty()) {
                if (!explicit && current?.second?.isNotEmpty() == true) close()
                continue
            }
            if (current == null) open(null, false)
            current!!.second += clean
        }
        val named = parts.filter { it.second.isNotEmpty() }.map { (label, lines) -> (label ?: "Куплет ${++verses}") to lines }
        return toSong(title.ifBlank { fallbackTitle }, named)
    }

    // ——— OpenLyrics ———

    private val VERSE_NAMES = mapOf('v' to "Куплет", 'c' to "Припев", 'b' to "Бридж", 'p' to "Предприпев", 'e' to "Концовка", 'i' to "Вступление")

    fun openLyrics(xml: String): ImportedSong? {
        val doc = runCatching {
            val f = DocumentBuilderFactory.newInstance().apply {
                // Чужой файл не подтянет ничего извне (XXE).
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                isExpandEntityReferences = false
                isNamespaceAware = false
            }
            f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return null
        val root = doc.documentElement ?: return null
        if (!root.tagName.equals("song", ignoreCase = true)) return null
        val title = root.getElementsByTagName("title").item(0)?.textContent?.trim().orEmpty()
        val order = root.getElementsByTagName("verseOrder").item(0)?.textContent?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
        val verses = linkedMapOf<String, List<String>>()
        val nodes = root.getElementsByTagName("verse")
        for (i in 0 until nodes.length) {
            val v = nodes.item(i) as Element
            val name = v.getAttribute("name").ifBlank { "v${i + 1}" }
            val lines = mutableListOf<String>()
            val lineNodes = v.getElementsByTagName("lines")
            for (j in 0 until lineNodes.length) {
                if (j > 0) lines += ""
                lines += linesText(lineNodes.item(j)).lines().map { it.trim() }
            }
            verses[name] = lines.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        }
        if (verses.isEmpty()) return null
        val names = order.filter { it in verses }.ifEmpty { verses.keys.toList() }
        return toSong(title, names.map { label(it) to verses.getValue(it) })
    }

    /** Текст строк без аккордов и комментариев; `<br/>` — перенос строки. */
    private fun linesText(n: Node): String = buildString {
        val kids = n.childNodes
        for (i in 0 until kids.length) {
            val k = kids.item(i)
            when {
                k.nodeType == Node.TEXT_NODE || k.nodeType == Node.CDATA_SECTION_NODE -> append(k.nodeValue.replace(Regex("\\s*\\n\\s*"), " "))
                k.nodeName.equals("br", true) -> append('\n')
                k.nodeName.equals("chord", true) -> append(linesText(k))
                k.nodeName.equals("comment", true) -> Unit
                else -> append(linesText(k))
            }
        }
    }

    private fun label(name: String): String {
        val kind = VERSE_NAMES[name.firstOrNull()?.lowercaseChar()] ?: return name
        val num = name.drop(1).takeWhile { it.isDigit() }
        return if (num.isEmpty() || kind != "Куплет" && num == "1") kind else "$kind $num"
    }

    /** Часть длиннее слайда делится; подпись остаётся у первого из её слайдов. */
    private fun toSong(title: String, parts: List<Pair<String, List<String>>>): ImportedSong {
        val slides = mutableListOf<String>()
        val labels = mutableListOf<String?>()
        for ((label, lines) in parts) {
            val text = lines.joinToString("\n").trim()
            if (text.isEmpty()) continue
            splitIntoSlides(text).forEachIndexed { i, s -> slides += s; labels += if (i == 0) label else null }
        }
        return ImportedSong(title, slides, labels)
    }
}
