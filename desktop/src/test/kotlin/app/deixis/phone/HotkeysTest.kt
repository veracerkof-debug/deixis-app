package app.deixis.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HotkeysTest {
    @Test
    fun defaultsWorkLikeAClickerAndEveryActionHasAKey() {
        assertEquals(KeyAction.NEXT, Hotkeys.actionOf("Right", emptyMap()))
        assertEquals(KeyAction.NEXT, Hotkeys.actionOf("Space", emptyMap()))
        assertEquals(KeyAction.BLACK, Hotkeys.actionOf("B", emptyMap()))
        assertEquals(KeyAction.ASSISTANT, Hotkeys.actionOf("Ctrl+J", emptyMap()))
        assertNull(Hotkeys.actionOf("Q", emptyMap()))
        for (a in KeyAction.entries) assertTrue(Hotkeys.combosOf(a, emptyMap()).isNotEmpty(), a.name)
    }

    @Test
    fun reassigningTakesTheKeyAwayFromTheOldActionAndResetBringsItBack() {
        val keys = Hotkeys.assign(emptyMap(), KeyAction.LOGO, listOf("B"))
        assertEquals(KeyAction.LOGO, Hotkeys.actionOf("B", keys))
        assertTrue(Hotkeys.combosOf(KeyAction.BLACK, keys).isEmpty(), "у «Чёрного» клавиши больше нет")
        val back = Hotkeys.assign(keys, KeyAction.LOGO, null)
        assertEquals(KeyAction.BLACK, Hotkeys.actionOf("B", back + (KeyAction.BLACK.id to "B")))
        assertEquals(listOf("L"), Hotkeys.combosOf(KeyAction.LOGO, back))
    }

    @Test
    fun severalKeysPerActionAreKept() {
        val keys = Hotkeys.assign(emptyMap(), KeyAction.NEXT, listOf("N", "Right"))
        assertEquals(KeyAction.NEXT, Hotkeys.actionOf("N", keys))
        assertEquals(KeyAction.NEXT, Hotkeys.actionOf("Right", keys))
        assertNull(Hotkeys.actionOf("Space", keys), "Пробел больше не «дальше»")
    }
}
