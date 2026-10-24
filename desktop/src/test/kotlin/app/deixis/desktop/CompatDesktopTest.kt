package app.deixis.desktop

import app.deixis.core.OutputLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Выбор мониторов из прошлых версий сохраняется после обновления. */
class CompatDesktopTest {
    private val dir = File(System.getProperty("deixis.compat") ?: "../testdata/compat")

    @Test
    fun monitorChoicesSurviveTheUpdate() {
        val files = dir.listFiles().orEmpty().filter { it.name.startsWith("monitors-") }
        assertTrue(files.isNotEmpty(), "образцы прошлых версий на месте: ${dir.absolutePath}")
        for (f in files) {
            val s = MonitorSettingsStore(f).load()
            assertEquals(SavedMonitor(true, OutputLayout.STAGE), s.monitors["\\.\\DISPLAY2"], f.name)
            assertEquals(false, s.monitors["\\.\\DISPLAY1"]?.enabled, f.name)
        }
    }
}
