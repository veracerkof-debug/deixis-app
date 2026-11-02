package app.deixis.phone

import kotlin.test.Test
import kotlin.test.assertTrue

/** Цвета пульта читаются во всех темах и акцентах профилей (WCAG 2). */
class ContrastTest {
    private val themes = listOf("light", "dark", "paper", "oled")

    @Test
    fun textOnAccentIsReadable() {
        for (t in themes) for (a in Accent.entries) {
            val p = paletteOf(t, a)
            val c = contrast(p.onTint, p.tint)
            assertTrue(c >= 3f, "$t/$a: текст на акценте ${"%.2f".format(c)}:1")
        }
    }

    @Test
    fun mainTextIsReadable() {
        for (t in themes) for (a in Accent.entries) {
            val p = paletteOf(t, a)
            assertTrue(contrast(p.ink, p.bg) >= 7f, "$t: основной текст")
            assertTrue(contrast(p.ink, p.cell) >= 7f, "$t: основной текст в карточке")
            assertTrue(contrast(p.l2, p.cell) >= 3f, "$t: второстепенный текст")
        }
    }
}
