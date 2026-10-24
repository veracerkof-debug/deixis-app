package app.deixis.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Сопряжённые пульты из прошлых версий (записаны их кодом) остаются сопряжёнными после обновления. */
class CompatTest {
    private val dir = File(System.getProperty("deixis.compat") ?: "../testdata/compat")

    @Test
    fun pairedRemotesSurviveTheUpdate() {
        val files = dir.listFiles().orEmpty().filter { it.name.startsWith("tokens-") }
        assertTrue(files.size >= 3, "образцы прошлых версий на месте: ${dir.absolutePath}")
        for (f in files) {
            val copy = File(createTempDirectory("compat").toFile(), "tokens.json").apply { writeText(f.readText()) }
            val store = FileTokenStore(copy)
            val all = store.all()
            assertEquals(1, all.size, f.name)
            assertEquals("Телефон ведущего", all[0].sender, f.name)
            assertTrue(Pairing(store).isValid(all[0].token), "${f.name}: пульт не просит PIN заново")
        }
    }
}
