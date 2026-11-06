package app.deixis.diag

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Диагностика: общие проверки, зависшая или упавшая проверка — сама итог FAIL, отчёт текстом с журналом. */
class DiagnosticsTest {
    @Test
    fun commonChecksAndReport() = runBlocking {
        val dir = createTempDirectory("diag").toFile()
        Log.toFile(dir)
        Log.e("test", "что-то сломалось", IllegalStateException("причина"))
        val slow = Check("slow", { "Медленная" }) { delay(5_000); error("не дойдёт") }
        val broken = Check("broken", { "Сломанная" }) { error("упала") }
        val r = Diagnostics.run(Checks.common(dir) + slow + broken, Lang("ru"), "Тест", "Deixis 0", timeoutMs = 300)
        val byId = r.checks.associateBy { it.id }
        assertEquals(Status.OK, byId.getValue("storage").status, "папка записывается")
        assertEquals(Status.WARN, byId.getValue("journal").status, "ошибка в журнале замечена")
        assertEquals(Status.FAIL, byId.getValue("slow").status, "зависшая проверка — FAIL, а не зависший отчёт")
        assertTrue(byId.getValue("broken").detail.contains("упала"))
        assertEquals(Status.FAIL, r.worst)
        val text = r.toText()
        assertTrue(text.contains("✗ Медленная") && text.contains("что-то сломалось"), text)
        assertTrue(Log.logFile!!.readText().contains("причина"), "журнал в файле")
    }

    @Test
    fun languagesOfChecks() = runBlocking {
        val r = Diagnostics.run(listOf(Checks.memory()), Lang("uz"), "x", "y")
        assertEquals("Xotira", r.checks.single().title)
        val en = Diagnostics.run(listOf(Checks.memory()), Lang("en"), "x", "y")
        assertEquals("Memory", en.checks.single().title)
    }
}
