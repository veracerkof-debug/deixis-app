package app.deixis.phone

import app.deixis.core.DeixisJson
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilesTest {
    private val root: File = Files.createTempDirectory("profiles").toFile()

    private fun library(dir: File) = DeixisJson.decodeFromString(StoreData.serializer(), File(dir, "library.json").readText())

    @Test
    fun firstProfileIsTheOldLibraryInPlace() {
        val p = Profiles(root)
        val book = p.load()
        assertEquals(listOf(Profiles.MAIN), book.list.map { it.id })
        assertEquals(root, p.dirOf(Profiles.MAIN))
    }

    @Test
    fun newProfileHasItsOwnEmptyLibraryAndLook() {
        val p = Profiles(root)
        val (book, uni) = p.create(p.load(), "Университет", ProfileKind.STUDY, Accent.BLUE)
        assertEquals(2, book.list.size)
        assertEquals(book, p.load())
        val dir = p.dirOf(uni.id)
        assertTrue(dir != root && dir.startsWith(root))
        val d = library(dir)
        assertTrue(d.items.isEmpty() && d.themes.isEmpty())
        assertEquals(listOf("Сегодня"), d.setlists.map { it.name })
        assertFalse(d.settings.church, "у учёбы нет церковного раздела")
        assertTrue(d.demoSeeded, "демо-песни не попадают в новый профиль")
        val (_, church) = p.create(book, "", ProfileKind.CHURCH, Accent.BRONZE)
        assertEquals("Церковь", church.name)
        assertTrue(library(p.dirOf(church.id)).settings.church)
    }

    @Test
    fun deletingRemovesOnlyThatProfile() {
        val p = Profiles(root)
        File(root, "library.json").writeText("{}")
        val (b1, work) = p.create(p.load(), "Работа", ProfileKind.WORK, Accent.SLATE)
        val (b2, uni) = p.create(b1, "Учёба", ProfileKind.STUDY, Accent.GREEN)
        val workDir = p.dirOf(work.id)
        assertEquals(b2, p.delete(b2, Profiles.MAIN), "первый профиль не удаляется")
        val uniOpen = b2.copy(active = uni.id)
        assertEquals(uniOpen, p.delete(uniOpen, uni.id), "открытый не удаляется")
        val b3 = p.delete(b2, work.id)
        assertEquals(listOf(Profiles.MAIN, uni.id), b3.list.map { it.id })
        assertFalse(workDir.exists())
        assertTrue(File(p.dirOf(uni.id), "library.json").isFile)
        assertTrue(File(root, "library.json").isFile, "данные первого профиля на месте")
    }
}
