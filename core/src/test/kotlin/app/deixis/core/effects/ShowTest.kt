package app.deixis.core.effects

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShowTest {
    private val example = File("../docs/ai/example.deixis-show.json").readText()

    private fun song(sections: String, extra: String = "") =
        """{"format":"deixis-show","version":1,"songs":[{"id":"s1","title":"t",$extra"sections":[$sections]}]}"""

    private fun errorCodes(text: String) = ShowValidator.validate(text).errors.map { it.code to it.path }

    @Test
    fun exampleFromDocsIsValid() {
        val r = ShowValidator.validate(example)
        assertTrue(r.ok, r.issues.toString())
        assertEquals(emptyList(), r.issues)
        val s = r.show!!.songs.single()
        assertEquals(7, s.sections.size)
        assertEquals(76, s.bpm)
        assertEquals("angel", s.sections[2].emb)
        assertEquals(92, s.sections[2].bpm)
        assertNull(s.sections[0].emb)
        assertEquals(emptyList(), s.sections[0].lines)
    }

    @Test
    fun brokenInput() {
        assertEquals(listOf("bad_json" to ""), errorCodes("не json"))
        assertTrue("wrong_format" to "format" in errorCodes("""{"format":"x","songs":[]}"""))
        assertTrue("bad_structure" to "songs" in errorCodes("""{"songs":"нет"}"""))
        assertTrue("no_songs" to "songs" in errorCodes("""{"songs":[]}"""))
        assertTrue("no_sections" to "songs[0].sections" in errorCodes("""{"songs":[{"id":"a","sections":[]}]}"""))
    }

    @Test
    fun fieldsAreCheckedWithPaths() {
        val bad = song("""{"tag":"a","emb":"unicorn","place":"up","font":"comic","lines":${(1..13).joinToString(",", "[", "]") { "\"x\"" }}}""")
        val codes = errorCodes(bad)
        assertTrue("bad_option" to "songs[0].sections[0].emb" in codes, codes.toString())
        assertTrue("bad_option" to "songs[0].sections[0].place" in codes, codes.toString())
        assertTrue("bad_option" to "songs[0].sections[0].font" in codes, codes.toString())
        assertTrue("too_many_lines" to "songs[0].sections[0].lines" in codes, codes.toString())
    }

    @Test
    fun numbersAreClampedWithWarnings() {
        val r = ShowValidator.validate(song("""{"tag":"a","e0":2,"e1":-1,"d":1,"bpm":999,"ts":10,"lines":["${"я".repeat(200)}"]}"""))
        assertTrue(r.ok, r.issues.toString())
        val s = r.show!!.songs[0].sections[0]
        assertEquals(1.0, s.e0, 0.0); assertEquals(0.0, s.e1, 0.0); assertEquals(3.0, s.d, 0.0)
        assertEquals(240, s.bpm); assertEquals(50, s.ts); assertEquals(140, s.lines[0].length)
        assertEquals(setOf("e0", "e1", "d", "bpm", "ts"), r.warnings.filter { it.code == "out_of_range" }.map { it.path.substringAfterLast('.') }.toSet())
        assertTrue(r.warnings.any { it.code == "text_too_long" })
    }

    @Test
    fun duplicateSongIdsAreErrors() {
        val two = """{"songs":[{"id":"a","sections":[{"tag":"x"}]},{"id":"a","sections":[{"tag":"x"}]}]}"""
        assertTrue("duplicate_id" to "songs[1].id" in errorCodes(two))
    }

    @Test
    fun timelineLocatesSectionsAndEnergy() {
        val tl = ShowTimeline(ShowValidator.validate(example).show!!.songs.single())
        assertEquals(98.0, tl.total, 1e-9)
        assertEquals(listOf(0.0, 10.0, 26.0, 40.0, 56.0, 72.0, 86.0), tl.starts.toList())
        assertEquals(0, tl.indexAt(0.0)); assertEquals(1, tl.indexAt(10.0)); assertEquals(2, tl.indexAt(30.0)); assertEquals(6, tl.indexAt(97.9))
        assertEquals(6, tl.indexAt(500.0)); assertEquals(0, tl.indexAt(-3.0))
        assertEquals(16.0, tl.duration(1), 1e-9); assertEquals(12.0, tl.duration(6), 1e-9)
        assertEquals(0.5, tl.fraction(2, 33.0), 1e-9)
        // энергия идёт от e0 к e1 плавно и не превышает 1
        assertEquals(0.72, tl.targetEnergy(2, 26.0), 1e-9)
        assertEquals(0.8, tl.targetEnergy(2, 40.0), 1e-9)
        assertTrue(tl.targetEnergy(4, 64.0) in 0.55..0.92)
        assertEquals(1.0, tl.targetEnergy(5, 85.0, power = 1.3), 1e-9)
        // «Поклонение»: потолок энергии ниже
        assertEquals(0.8 * 0.72, tl.targetEnergy(2, 40.0, cap = 0.72), 1e-9)
    }

    @Test
    fun durationScaleStretchesEverySection() {
        val song = ShowValidator.validate(example).show!!.songs.single()
        val tl = ShowTimeline(song, durationScale = 1.5)
        assertEquals(98.0 * 1.5, tl.total, 1e-9)
        assertEquals(15.0, tl.starts[1], 1e-9)
        assertFalse(tl.starts.toList().zipWithNext().any { (a, b) -> b <= a })
    }
}
