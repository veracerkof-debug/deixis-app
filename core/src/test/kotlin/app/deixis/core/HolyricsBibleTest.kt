package app.deixis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HolyricsBibleTest {
    @Test
    fun myBibleBooksBecomeHolyricsVerseIds() {
        assertEquals("01001001", HolyricsBible.verseId(10, 1, 1))
        assertEquals("19023001", HolyricsBible.verseId(230, 23, 1))
        assertEquals("43003016", HolyricsBible.verseId(500, 3, 16))
        assertEquals("45012002", HolyricsBible.verseId(520, 12, 2))
        assertEquals("66022021", HolyricsBible.verseId(730, 22, 21))
        assertNull(HolyricsBible.verseId(170, 1, 1), "Товит — неканоническая, в Holyrics её нет")
        assertEquals(listOf("43003016", "43003017", "43003018"), HolyricsBible.verseIds(500, 3, 16, 18))
    }

    private fun v(key: String, title: String, iso: String) = HolyricsBibleVersion(key, key, title, HolyricsBibleVersion.Language(iso, iso, ""))

    @Test
    fun deixisModuleFindsTheSameTranslationInHolyrics() {
        val versions = listOf(v("en_kjv", "King James Version", "en"), v("ru_rst", "Синодальный перевод", "ru"), v("ru_nrt", "Новый русский перевод", "ru"), v("uz_mukaddas", "Muqaddas Kitob", "uz"))
        assertEquals("ru_rst", HolyricsBible.match("Синодальный перевод", "RST", "ru", versions)?.id)
        assertEquals("ru_nrt", HolyricsBible.match("Новый русский перевод", "NRT", "ru", versions)?.id)
        assertEquals("en_kjv", HolyricsBible.match("King James Version (1769)", "KJV", "en", versions)?.id)
        assertEquals("ru_rst", HolyricsBible.match("Синодальный", "SYNO", "ru", versions)?.id, "по названию")
        assertEquals("uz_mukaddas", HolyricsBible.match("Ўзбек Библияси", "UZB", "uz", versions)?.id, "хотя бы тот же язык")
        assertNull(HolyricsBible.match("Biblia", "BB", "es", versions))
    }
}
