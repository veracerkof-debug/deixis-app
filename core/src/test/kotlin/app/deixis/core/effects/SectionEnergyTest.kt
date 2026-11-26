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
        assertEquals(0.85f, out.backgroundScene!!.energy)
        assertEquals(st, SectionEnergy.styled(st, "Своя"))
        val plain = TextStyle()
        assertEquals(plain, SectionEnergy.styled(plain, "Припев"))
    }
}
