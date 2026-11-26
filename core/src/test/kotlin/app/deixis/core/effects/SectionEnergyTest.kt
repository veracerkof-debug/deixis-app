package app.deixis.core.effects

import app.deixis.core.SceneBackground
import app.deixis.core.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SectionEnergyTest {
    @Test
    fun chorusIsBrighterThanVerseAndIntroIsQuietest() {
        val v = SectionEnergy.forLabel("Куплет 1")!!
        val c = SectionEnergy.forLabel("Припев")!!
        val i = SectionEnergy.forLabel("Вступление")!!
        assertTrue(c > SectionEnergy.forLabel("Бридж")!! && SectionEnergy.forLabel("Бридж")!! > v && v > i)
        assertNull(SectionEnergy.forLabel("Что-то своё"))
        assertNull(SectionEnergy.forLabel(null))
    }

    @Test
    fun styleKeepsLookAndOnlyChangesEnergy() {
        val st = TextStyle(backgroundScene = SceneBackground("evening", energy = 0.2f, quiet = 0.5f))
        val out = SectionEnergy.styled(st, "Припев")
        assertEquals("evening", out.backgroundScene!!.look)
        assertEquals(0.5f, out.backgroundScene!!.quiet)
        assertEquals(0.75f, out.backgroundScene!!.energy, 1e-6f)
        assertEquals(0.9f, out.backgroundScene!!.energyTo!!, 1e-6f)
        assertEquals(20f, out.backgroundScene!!.rampSec)
        assertEquals(st, SectionEnergy.styled(st, "Своя"))
        val plain = TextStyle()
        assertEquals(plain, SectionEnergy.styled(plain, "Припев"))
    }

    @Test
    fun energyRisesSmoothlyAcrossTheSectionAndStaysAtTheEnd() {
        val b = SectionEnergy.styled(TextStyle(backgroundScene = SceneBackground("evening")), "Бридж").backgroundScene!!
        val start = b.energyAt(0.0); val mid = b.energyAt(b.rampSec / 2.0); val end = b.energyAt(b.rampSec.toDouble()); val later = b.energyAt(500.0)
        assertTrue(start < mid && mid < end, "$start $mid $end")
        assertEquals(end, later, 1e-9)
        // без кривой — постоянная
        assertEquals(0.4, SceneBackground("x", energy = 0.4f).energyAt(100.0), 1e-6)
        // бридж нарастает сильнее куплета
        val v = SectionEnergy.styled(TextStyle(backgroundScene = SceneBackground("evening")), "Куплет 1").backgroundScene!!
        assertTrue((b.energyTo!! - b.energy) > (v.energyTo!! - v.energy))
    }
}

class ShowImportTest {
    @Test
    fun importsTheExampleShowAsSlidesAndSkipsBackgroundOnlySections() {
        val text = java.io.File("../docs/ai/example.deixis-show.json").takeIf { it.exists() }?.readText() ?: return
        val (songs, errors) = ShowImport.parse(text)
        assertTrue(errors.isEmpty(), errors.toString())
        assertTrue(songs.isNotEmpty())
        for (s in songs) { assertEquals(s.texts.size, s.labels.size); assertTrue(s.texts.all { it.isNotBlank() }) }
        val (none, errs) = ShowImport.parse("{не json")
        assertTrue(none.isEmpty() && errs.isNotEmpty())
    }
}
