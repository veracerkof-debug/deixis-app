package app.deixis.phone

import app.deixis.core.DeixisJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Библиотека, записанная прошлыми версиями приложения (их собственным кодом, `testdata/compat`),
 * открывается новой версией целиком: «обновился — и песни пропали» не должно случиться.
 * Если тест падает, выпуск не выходит.
 */
class CompatLibraryTest {
    private val dir = File(System.getProperty("deixis.compat") ?: "../../testdata/compat")

    private fun libraries() = dir.listFiles().orEmpty().filter { it.name.startsWith("library-") }.sortedBy { it.name }

    @Test
    fun everyPastLibraryOpensCompletely() {
        val files = libraries()
        assertTrue(files.size >= 3, "образцы 0.7, 0.8, 0.9 на месте: ${dir.absolutePath}")
        for (f in files) {
            val d = DeixisJson.decodeFromString(StoreData.serializer(), f.readText())
            val name = f.name
            assertEquals(7, d.items.size, "$name: все материалы")
            assertEquals(Kind.entries.toSet(), d.items.map { it.kind }.toSet(), "$name: все виды материалов")
            val song = d.items.first { it.kind == Kind.SONG }
            assertEquals(listOf("Великий Бог, когда на мир смотрю я", "Тогда поёт душа моя"), song.slides, "$name: текст песни")
            assertEquals(listOf("Куплет 1", "Припев"), song.labels, "$name: части песни")
            assertNotNull(song.style, "$name: оформление песни")
            val bible = d.items.first { it.kind == Kind.BIBLE }.bible
            assertEquals(listOf(43, 3, 16, 18), listOf(bible!!.book, bible.chapter, bible.from, bible.to), "$name: место Писания")
            assertEquals(listOf("RST", "KJV"), bible.modules, "$name: переводы")
            assertEquals(2, bible.options.versesPerSlide, "$name: стихов на слайде")
            assertEquals(listOf("s1", "b1", "p1", "v1"), d.setlists.first { it.id == "set1" }.items, "$name: порядок сетлиста")
            assertEquals("set1", d.active, "$name: активный сетлист")
            assertEquals("dark", d.settings.theme, "$name: настройки")
            assertTrue(d.settings.caps && d.settings.alwaysPreview, "$name: настройки")
            assertTrue(d.styles.containsKey("all") && d.styles.containsKey("item:t1"), "$name: оформление")
            assertEquals(12, d.conversions["p1"]?.pages, "$name: переводы презентаций")
            assertEquals(61000, d.items.first { it.kind == Kind.VIDEO }.durationMs, "$name: длина видео")
            if (!name.contains("0.7.0")) {
                assertEquals("Моя тема", d.themes.single().name, "$name: свои темы")
                assertNotNull(d.themes.single().style.backgroundImage, "$name: фон темы")
                assertTrue(d.demoSeeded, "$name: демо не добавляется второй раз")
            }
            // Сохранённая новой версией библиотека читается снова без потерь.
            val again = DeixisJson.decodeFromString(StoreData.serializer(), DeixisJson.encodeToString(StoreData.serializer(), d))
            assertEquals(d, again, "$name: запись и чтение без потерь")
        }
    }
}
