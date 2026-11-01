package app.deixis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongFormatsTest {
    @Test
    fun chordProBecomesSlidesWithoutChords() {
        val s = SongFormats.import("amazing.cho", """
            {title: Amazing Grace}
            {key: G}
            # комментарий
            [G]Amazing [G7]grace! How [C]sweet the [G]sound
            That [G]saved a wretch like [D]me!

            I [G]once was lost, but [G7]now am [C]found;
            Was [G]blind, but [D]now I [G]see.

            {start_of_chorus}
            [C]Praise the Lord
            {end_of_chorus}
        """.trimIndent())!!
        assertEquals("Amazing Grace", s.title)
        assertEquals(listOf("Куплет 1", "Куплет 2", "Припев"), s.labels.filterNotNull())
        assertEquals("Amazing grace! How sweet the sound\nThat saved a wretch like me!", s.slides[0])
        assertTrue(s.slides.none { it.contains('[') || it.contains('{') })
    }

    @Test
    fun openLyricsFollowsVerseOrder() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <song xmlns="http://openlyrics.info/namespace/2009/song" version="0.9">
              <properties><titles><title>Тихая ночь</title></titles><verseOrder>v1 c v2 c</verseOrder></properties>
              <lyrics>
                <verse name="v1"><lines>Строка <chord name="C"/>один<br/>строка два</lines></verse>
                <verse name="c"><lines>Припев<comment>тихо</comment></lines></verse>
                <verse name="v2"><lines>Второй куплет</lines></verse>
              </lyrics>
            </song>"""
        val s = SongFormats.import("song.xml", xml)!!
        assertEquals("Тихая ночь", s.title)
        assertEquals(listOf("Строка один\nстрока два", "Припев", "Второй куплет", "Припев"), s.slides)
        assertEquals(listOf("Куплет 1", "Припев", "Куплет 2", "Припев"), s.labels)
    }

    @Test
    fun foreignXmlAndExternalEntitiesAreRejected() {
        assertNull(SongFormats.import("data.xml", "<?xml version=\"1.0\"?><catalog><item/></catalog>"))
        val evil = """<?xml version="1.0"?><!DOCTYPE song [<!ENTITY x SYSTEM "file:///etc/passwd">]><song><lyrics><verse name="v1"><lines>&x;</lines></verse></lyrics></song>"""
        assertNull(SongFormats.import("evil.xml", evil), "файл с DOCTYPE не читается")
        assertNull(SongFormats.import("notes.txt", "просто текст"))
    }
}
