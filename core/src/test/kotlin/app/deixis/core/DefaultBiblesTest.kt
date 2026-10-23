package app.deixis.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultBiblesTest {
    private val dir = File(createTempDirectory("defaults").toFile(), "bible")

    private fun store() = BibleStore(dir) { JdbcSql(it) }

    private fun slide(store: BibleStore, ref: String, vararg modules: String) =
        store.slides(ScriptureRequest(ref, modules.toList())).first().blocks.map { it.text }

    @Test
    fun defaultsInstallIntoEmptyDir() {
        val store = store()
        assertEquals(DefaultBibles.FILES.sorted(), store.modules().map { it.fileName })
        val rst = store.modules().first { it.fileName == "RST.SQLite3" }
        val kjv = store.modules().first { it.fileName == "KJV.SQLite3" }
        assertEquals("Синодальный перевод", rst.title)
        assertEquals("ru", rst.language)
        assertEquals("King James Version", kjv.title)
        assertEquals("en", kjv.language)
        assertEquals(66, rst.books.size)
        assertEquals(66, kjv.books.size)
        assertEquals(rst.books.map { it.number }, kjv.books.map { it.number }, "номера книг общие")
        assertEquals(BibleBook(500, "Ин", "От Иоанна"), rst.book(500))
        assertEquals(BibleBook(730, "Rev", "Revelation"), kjv.book(730))
        assertEquals(151, rst.chapterCount(230), "в Синодальной Псалтири есть 151-й псалом")
        assertEquals(150, kjv.chapterCount(230))
    }

    @Test
    fun referencesGiveTheRightText() {
        val store = store()
        val john = slide(store, "Ин 3:16", "RST.SQLite3", "KJV.SQLite3")
        assertTrue(john[0].startsWith("Ибо так возлюбил Бог мир, что отдал Сына Своего Единородного"), john[0])
        assertTrue(john[1].startsWith("For God so loved the world, that he gave his only begotten Son"), john[1])
        val ps = slide(store, "Пс 22:1", "RST.SQLite3").single()
        assertTrue(ps.contains("Господь — Пастырь мой"), ps)
        val kjv = slide(store, "John 3:16", "KJV.SQLite3").single()
        assertTrue(kjv.endsWith("should not perish, but have everlasting life."), kjv)
        val cor = slide(store, "1 Кор 13:4", "RST.SQLite3").single()
        assertTrue(cor.startsWith("Любовь долготерпит"), cor)
    }

    @Test
    fun removedDefaultIsNotReinstalled() {
        store().remove("KJV.SQLite3")
        assertEquals(listOf("RST.SQLite3"), store().modules().map { it.fileName })
        assertEquals(emptyList(), store().installDefaults())
        assertTrue(!File(dir, "KJV.SQLite3").exists())
    }

    @Test
    fun ownModuleWithTheSameNameIsKept() {
        dir.mkdirs()
        TestBibles.rst(dir)
        assertEquals(listOf("KJV.SQLite3"), DefaultBibles.install(dir))
        assertEquals(4, BibleModule(JdbcSql(File(dir, "RST.SQLite3")), "RST.SQLite3").use { it.books.size })
    }
}
