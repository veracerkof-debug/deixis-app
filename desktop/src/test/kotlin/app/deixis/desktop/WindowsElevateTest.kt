package app.deixis.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Запуск помощника виртуального монитора: путь с пробелом доходит до него одним аргументом (KNOWLEDGE 1.2). */
class WindowsElevateTest {
    @Test
    fun pathWithSpacesStaysOneArgument() {
        assertEquals("\"C:\\Program Files\\Deixis\\deixis-display\\DeixisDisplay.inf\"",
            WindowsVirtualScreen.winArg("C:\\Program Files\\Deixis\\deixis-display\\DeixisDisplay.inf"))
    }

    @Test
    fun quotesAndTrailingSlashesAreEscaped() {
        assertEquals("\"a\\\\\\\"b\"", WindowsVirtualScreen.winArg("a\\\"b"))
        assertEquals("\"C:\\dir\\\\\"", WindowsVirtualScreen.winArg("C:\\dir\\"))
        assertEquals("\"\"", WindowsVirtualScreen.winArg(""))
    }

    @Test
    fun scriptPassesOneQuotedLine() {
        val s = WindowsVirtualScreen.elevateScript("C:\\Program Files\\Deixis\\deixis-display\\deixis-display.exe",
            listOf("install", "C:\\Program Files\\Deixis\\x.inf", "C:\\Users\\O'Neil\\t.log"))
        assertTrue(s.contains("-FilePath 'C:\\Program Files\\Deixis\\deixis-display\\deixis-display.exe'"), s)
        assertTrue(s.contains("-ArgumentList '\"install\" \"C:\\Program Files\\Deixis\\x.inf\" \"C:\\Users\\O''Neil\\t.log\"'"), s)
    }

    @Test
    fun encodedIsUtf16Base64() {
        val e = WindowsVirtualScreen.encoded("exit 0")
        assertEquals("exit 0", String(java.util.Base64.getDecoder().decode(e), Charsets.UTF_16LE))
    }
}
