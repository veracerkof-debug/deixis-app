package app.deixis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Любой формат экрана: 16:9, 4:3, 1:1, вертикальный 9:16, сверхширокий 32:9 и узкая колонна 1:3.
 * Текст, Писание и подписи целиком внутри экрана и безопасных полей, ничего не обрезано.
 */
class AspectTest {
    private val formats = listOf("16:9" to (1920f to 1080f), "4:3" to (1024f to 768f), "1:1" to (1080f to 1080f),
        "9:16" to (1080f to 1920f), "32:9" to (5120f to 1440f), "1:3" to (600f to 1800f))

    // Шрифт для проверки: ширина буквы — 0.55 кегля.
    private val measure = { s: String, size: Float -> s.length * size * 0.55f }
    private val ascent = { size: Float -> size * 0.8f }
    private val descent = { size: Float -> size * 0.2f }

    private val scripture = ScriptureContent(
        blocks = listOf(
            ScriptureBlock("16 Ибо так возлюбил Бог мир, что отдал Сына Своего Единородного, дабы всякий верующий в Него не погиб, но имел жизнь вечную.", "Синодальный"),
            ScriptureBlock("16 For God so loved the world, that he gave his only begotten Son, that whosoever believeth in him should not perish, but have everlasting life.", "King James"),
        ),
        ref = "Евангелие от Иоанна 3:16",
        sideBySide = true,
    )

    @Test
    fun scriptureFitsEveryFormatAndStacksWhenNotWide() {
        for ((name, wh) in formats) {
            val (w, h) = wh
            val area = safeArea(w, h)
            val lines = layoutScripture(scripture, w, h, measure, ascent, descent)
            assertTrue(lines.isNotEmpty(), name)
            for (l in lines) {
                val right = l.x + measure(l.text, l.size)
                assertTrue(l.x >= area.left - 1 && right <= area.right + 1, "$name: строка «${l.text}» по ширине в полях (${l.x}..$right, поля ${area.left}..${area.right})")
                assertTrue(l.baseline - ascent(l.size) >= area.top - 1 && l.baseline + descent(l.size) <= area.bottom + 1, "$name: строка «${l.text}» по высоте в полях")
            }
            // Друг под другом — весь второй перевод ниже первого; рядом — начинается на той же высоте, что и первый.
            val kjv = lines.first { it.text.startsWith("16 For") }
            val rstLast = lines.filter { !it.ref }.takeWhile { !it.text.startsWith("16 For") }.maxOf { it.baseline }
            val side = kjv.baseline < rstLast
            assertEquals(w >= h * SIDE_BY_SIDE_MIN_ASPECT, side, "$name: колонки только на широком экране")
            assertTrue(lines.filter { !it.ref }.all { it.size >= minOf(w, h) * 0.015f }, "$name: текст не мельче предела")
        }
    }

    @Test
    fun textSlideAndRefFitEveryFormat() {
        for ((name, wh) in formats) {
            val (w, h) = wh
            val area = safeArea(w, h)
            val l = layoutSlideText("Великий Бог, когда на мир смотрю я,\nНа всё, что Ты создал Своим перстом", "Великий Бог · Куплет 1", w, h, TextStyle(), measure, ascent, descent)
            for (p in l.lines) {
                assertTrue(p.x >= area.left - 1 && p.x + measure(p.text, l.fontSize) <= area.right + 1, "$name: «${p.text}» в полях")
                assertTrue(p.baseline + descent(l.fontSize) <= area.bottom + 1 && p.baseline - ascent(l.fontSize) >= area.top - 1, "$name: «${p.text}» по высоте")
            }
            assertTrue(measure(l.ref!!, l.refFontSize) <= area.width + 1, "$name: подпись помещается")
            // Поле одинаково со всех сторон: от меньшей стороны.
            assertEquals(area.left, area.top, 0.01f, name)
            assertEquals(w - area.right, h - area.bottom, 0.01f, name)
            val logo = LogoMark.size(w, h)
            assertTrue(logo * LogoMark.WIDTH_EM <= w && logo <= h, "$name: знак помещается")
        }
    }
}
