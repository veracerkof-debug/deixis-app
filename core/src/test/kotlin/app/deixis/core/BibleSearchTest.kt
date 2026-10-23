package app.deixis.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BibleSearchTest {
    private fun store(): BibleStore {
        val dir = Files.createTempDirectory("bible").toFile()
        return BibleStore(dir) { JdbcSql(it) }
    }

    @Test
    fun findsVersesIgnoringCaseAndYo() {
        val rst = store().modules().first { it.fileName == "RST.SQLite3" }
        val hits = BibleSearch(rst).search("ТАК ВОЗЛЮБИЛ бог")
        assertTrue(hits.any { it.book == 500 && it.chapter == 3 && it.verse == 16 }, "Ин 3:16 найден")
        val yo = BibleSearch(rst).search("пастырь мой")
        assertTrue(yo.isNotEmpty())
        assertEquals(emptyList(), BibleSearch(rst).search(" ").map { it.verse })
    }

    @Test
    fun englishSearch() {
        val kjv = store().modules().first { it.fileName == "KJV.SQLite3" }
        val hits = BibleSearch(kjv).search("God so loved")
        assertTrue(hits.any { it.book == 500 && it.chapter == 3 && it.verse == 16 })
    }
}
