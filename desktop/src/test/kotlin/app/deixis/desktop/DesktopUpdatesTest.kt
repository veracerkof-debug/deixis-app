package app.deixis.desktop

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUpdatesTest {

    @Test
    fun windowsScriptWaitsInstallsAndRelaunches() {
        val s = DesktopUpdates.windowsScript(File("C:\\Users\\a b\\update-0.10.0-Deixis-0.10.0.msi"), File("C:\\Program Files\\Deixis\\Deixis.exe"))
        assertTrue(s.contains("msiexec /i \"C:\\Users\\a b\\update-0.10.0-Deixis-0.10.0.msi\" /passive /norestart"), s)
        assertTrue(s.contains("start \"\" \"C:\\Program Files\\Deixis\\Deixis.exe\""), s)
        assertTrue(s.indexOf("timeout") < s.indexOf("msiexec"), "сначала ждёт выхода программы")
        assertFalse(DesktopUpdates.windowsScript(File("x.msi"), null).contains("start \"\""), "без перезапуска при выходе")
    }

    /** На настоящем Маке: образ .dmg с новой программой заменяет старую Deixis.app. */
    @Test
    fun macScriptReplacesTheAppFromTheImage() {
        assumeTrue(MacScreen.isMac, "только на macOS")
        val dir = createTempDirectory("mac-upd").toFile()
        val src = File(dir, "src/Deixis.app/Contents").apply { mkdirs() }
        File(src, "version").writeText("новая")
        val dmg = File(dir, "Deixis-1.0.1000.dmg")
        val mk = ProcessBuilder("hdiutil", "create", "-quiet", "-srcfolder", File(dir, "src").path, "-volname", "Deixis", dmg.path).inheritIO().start()
        assertTrue(mk.waitFor(120, TimeUnit.SECONDS) && mk.exitValue() == 0, "образ создан")
        val apps = File(dir, "Applications").apply { mkdirs() }
        val app = File(apps, "Deixis.app/Contents").apply { mkdirs() }.parentFile
        File(app, "Contents/version").writeText("старая")
        val script = File(dir, "install.sh").apply { writeText(DesktopUpdates.macScript(dmg, app, relaunch = false).replace("sleep 3", "sleep 0")) }
        val p = ProcessBuilder("/bin/sh", script.path).inheritIO().start()
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "установка закончилась")
        assertEquals("новая", File(app, "Contents/version").readText())
        assertFalse(File(app.path + ".old").exists(), "старая копия убрана")
    }
}
