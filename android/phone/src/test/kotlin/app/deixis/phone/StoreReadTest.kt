package app.deixis.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Библиотека из более новой версии Deixis открывается в старой почти целиком, а не пустой. */
class StoreReadTest {
    @Test
    fun unknownItemKindIsSkippedRestStays() {
        val json = """
            {"items":[
              {"id":"a","kind":"SONG","title":"Песня"},
              {"id":"b","kind":"HOLOGRAM","title":"Из будущего"},
              {"id":"c","kind":"TEXT","title":"Объявление","videoFit":"WARP"}
            ],
            "setlists":[{"id":"s","title":"Сегодня","date":"2026-11-02","items":["a","b","c"]}],
            "active":"s",
            "futureField":{"x":1}}
        """.trimIndent()
        val r = assertNotNull(StoreRead.read(json))
        assertEquals(listOf("a", "c"), r.data.items.map { it.id }, "неизвестный вид пропущен, неизвестное значение — по умолчанию")
        assertEquals(1, r.skipped)
        assertEquals("s", r.data.active)
        assertEquals(1, r.data.setlists.size)
    }

    @Test
    fun ordinaryFileReadsStrictly() {
        val r = assertNotNull(StoreRead.read("""{"items":[{"id":"a","kind":"SONG","title":"Песня"}]}"""))
        assertEquals(0, r.skipped)
    }

    @Test
    fun notALibrary() {
        assertNull(StoreRead.read("не json"))
    }
}
