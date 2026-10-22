package app.deixis.core

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PairingTest {
    @Test
    fun correctPinGivesTokenAndRotatesPin() {
        val p = Pairing(MemoryTokenStore())
        val pin = p.pin
        val r = assertIs<Pairing.Result.Ok>(p.pair(pin, "Телефон"))
        assertTrue(p.isValid(r.token))
        assertNotEquals(pin, p.pin, "PIN меняется после сопряжения")
        assertFalse(p.isValid("чужой"))
        assertFalse(p.isValid(null))
    }

    @Test
    fun pinChangesAfterFiveWrongAttempts() {
        val p = Pairing(MemoryTokenStore())
        val pin = p.pin
        val wrong = if (pin == "1000") "1001" else "1000"
        repeat(4) { assertEquals(Pairing.Result.Denied(DenyReason.WRONG_PIN), p.pair(wrong, "x")) }
        assertEquals(Pairing.Result.Denied(DenyReason.TOO_MANY_ATTEMPTS), p.pair(wrong, "x"))
        assertNotEquals(pin, p.pin)
    }

    @Test
    fun tokensSurviveRestart() {
        val file = createTempDirectory("tokens").resolve("tokens.json").toFile()
        val p = Pairing(FileTokenStore(file))
        val token = (p.pair(p.pin, "Телефон") as Pairing.Result.Ok).token
        assertTrue(Pairing(FileTokenStore(file)).isValid(token))
    }
}

class ViewKeyTest {
    @Test
    fun viewKeySurvivesRestart() {
        val file = createTempDirectory("vk").resolve("view-key").toFile()
        val first = loadViewKey(file)
        assertEquals(first, loadViewKey(file))
        assertTrue(first.length >= 8)
    }
}

class LocalNameTest {
    @Test
    fun localNameIsStableAndValid() {
        assertEquals("deixis-3f2a", localHostName("3F2A-91c0-ffff"))
        assertEquals("deixis-tv", localHostName("---"))
        assertEquals("deixis-12", localHostName("Ёж-12"), "в имени сети только латиница и цифры")
    }
}
