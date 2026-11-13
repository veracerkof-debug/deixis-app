package app.deixis.phone

import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** Переводы пульта: у каждой строки три языка, и в коде интерфейса не остаётся русских строк мимо [L]. */
class StringsTest {
    private val cyrillic = Regex("[А-Яа-яЁё]")

    @AfterTest
    fun reset() = I18n.apply(null)

    /** Каждое свойство и шаблон [L] на каждом языке — непустая строка; узбекский и английский — без кириллицы. */
    @Test
    fun everyStringHasAllThreeLanguages() {
        val methods = L::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) && it.returnType == String::class.java }
        assertTrue(methods.size > 500, "строки L найдены: ${methods.size}")
        val bad = mutableListOf<String>()
        for (lang in Lang.entries) {
            I18n.apply(lang.code)
            for (m in methods) {
                val argSets: List<Array<Any?>> = when {
                    m.parameterTypes.isEmpty() -> listOf(emptyArray())
                    // Строка по значению перечисления (вид элемента, раскладка монитора) — для каждого значения.
                    m.parameterTypes.size == 1 && m.parameterTypes[0].isEnum -> m.parameterTypes[0].enumConstants.map { arrayOf<Any?>(it) }
                    else -> listOf(1, 2, 5, 11, 22).mapIndexed { k, n ->
                        m.parameterTypes.map { t ->
                            when (t) {
                                Int::class.javaPrimitiveType, Int::class.javaObjectType -> n
                                // Логическое (в том числе допускающее null: «зачёт» у итога урока) — все три значения по кругу.
                                Boolean::class.javaPrimitiveType -> k % 2 == 0
                                Boolean::class.javaObjectType -> listOf(true, false, null)[k % 3]
                                else -> "X"
                            }
                        }.toTypedArray<Any?>()
                    }
                }
                for (args in argSets) {
                    val s = m.invoke(L, *args) as String
                    if (s.isBlank()) bad += "${lang.code} ${m.name}: пусто"
                    if (lang != Lang.RU && cyrillic.containsMatchIn(s)) bad += "${lang.code} ${m.name}: кириллица «$s»"
                }
            }
        }
        if (bad.isNotEmpty()) fail(bad.joinToString("\n"))
    }

    /**
     * Храповик: русская строка в коде пульта идёт только через [L] (Strings.kt).
     * Исключения — участки между `// i18n:off` и `// i18n:on` (демо-набор) и короткий список ниже.
     */
    @Test
    fun noRussianLiteralsOutsideStrings() {
        // Пульт: общий код (pult/), Android телефона и платформа компьютера.
        val dirs = listOf("src/main/kotlin/app/deixis/phone", "../../pult/src/main/kotlin/app/deixis/phone", "../../desktop/src/main/kotlin/app/deixis/phone").map(::File)
        dirs.forEach { assertTrue(it.isDirectory, "исходники пульта: ${it.absolutePath}") }
        val found = mutableListOf<String>()
        dirs.flatMap { d -> d.listFiles { f -> f.extension == "kt" && f.name != "Strings.kt" }!!.toList() }.sorted().forEach { f ->
            val lines = f.readLines()
            val off = mutableSetOf<Int>()
            var on = true
            lines.forEachIndexed { i, l ->
                if (l.trim().startsWith("// i18n:off")) on = false
                if (!on) off += i + 1
                if (l.trim().startsWith("// i18n:on")) on = true
            }
            for ((line, text) in kotlinLiterals(f.readText())) {
                if (line in off || !cyrillic.containsMatchIn(text)) continue
                if (ALLOWED[f.name]?.contains(text) == true) continue
                found += "${f.name}:$line «$text»"
            }
        }
        if (found.isNotEmpty()) fail("Строки мимо L (Strings.kt):\n" + found.joinToString("\n"))
    }

    private companion object {
        /** Русский, который остаётся в коде: не интерфейс. Список держится коротким. */
        val ALLOWED = mapOf(
            // Журнал (android.util.Log) читает разработчик, а не оператор.
            "ScreenActivity.kt" to setOf("Экран не запустился"),
            // Поиск слова в системном имени дисплея: так Android называет беспроводной экран на русском.
            "HdmiOutput.kt" to setOf("беспровод"),
            // Имя сетлиста демо-набора — его признак в данных; и старая демо-тема узнаётся по имени.
            "Controller.kt" to setOf("Демо", "Горы"),
        )
    }
}

/** Строковые литералы Kotlin-исходника вне комментариев: (номер строки, текст); части шаблонов и вложенные строки — отдельно. */
internal fun kotlinLiterals(src: String): List<Pair<Int, String>> = KotlinLexer(src).run { code(untilBrace = false); out }

private class KotlinLexer(val src: String) {
    val out = mutableListOf<Pair<Int, String>>()
    private var i = 0
    private var line = 1

    fun code(untilBrace: Boolean) {
        var depth = 0
        while (i < src.length) {
            val c = src[i]
            when {
                src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                src.startsWith("/*", i) -> {
                    val end = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it }
                    line += src.substring(i, end).count { it == '\n' }
                    i = end + 2
                }
                c == '\'' -> char()
                c == '"' -> string()
                c == '{' -> { depth++; i++ }
                c == '}' -> {
                    i++
                    if (depth == 0 && untilBrace) return
                    depth--
                }
                else -> { if (c == '\n') line++; i++ }
            }
        }
    }

    private fun char() {
        i++
        while (i < src.length && src[i] != '\'') { if (src[i] == '\\') i++; i++ }
        i++
    }

    private fun string() {
        val quote = if (src.startsWith("\"\"\"", i)) "\"\"\"" else "\""
        val raw = quote.length == 3
        i += quote.length
        var start = line
        val buf = StringBuilder()
        while (i < src.length) {
            if (src.startsWith(quote, i)) { out += start to buf.toString(); i += quote.length; return }
            val c = src[i]
            if (!raw && c == '\\') { buf.append(src, i, minOf(i + 2, src.length)); i += 2; continue }
            if (src.startsWith("\${", i)) {
                out += start to buf.toString()
                buf.clear()
                i += 2
                code(untilBrace = true)
                start = line
                continue
            }
            if (c == '\n') line++
            buf.append(c)
            i++
        }
    }
}
