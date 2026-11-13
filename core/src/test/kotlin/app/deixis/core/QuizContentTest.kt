package app.deixis.core

import app.deixis.core.quiz.QuizFile
import app.deixis.core.quiz.QuizOption
import app.deixis.core.quiz.QuizQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuizContentTest {
    private val single = QuizQuestion("s", "single", "Какая из этих рек самая длинная в Европе и протекает по России?", options = listOf(
        QuizOption("a", "Волга", true), QuizOption("b", "Дон"), QuizOption("c", "Днепр"), QuizOption("d", "Урал")))
    private val tf = QuizQuestion("t", "truefalse", "Земля вращается вокруг Солнца.", answer = true)

    private val measure: (String, Float) -> Float = { s, size -> s.length * size * 0.55f }
    private val ascent: (Float) -> Float = { it * 0.9f }

    @Test
    fun questionPhaseNeverCarriesCorrectAnswers() {
        val c = quizContent(single, 0, 3, 20)
        assertEquals(emptySet(), c.correct)
        assertEquals(listOf("A", "B", "C", "D"), c.options.map { it.mark })
        val wire = DeixisJson.encodeToString(Msg.serializer(), Show(c))
        assertFalse("\"a\"" in wire.substringAfter("correct"), wire)
        val r = quizContent(single, 0, 3, 20, QuizPhase.REVEAL, picked = setOf("b"))
        assertEquals(setOf("a"), r.correct)
        assertEquals(QuizOptionState.RIGHT, r.stateOf(r.options[0]))
        assertEquals(QuizOptionState.WRONG, r.stateOf(r.options[1]))
        assertEquals(QuizOptionState.DIM, r.stateOf(r.options[2]))
    }

    @Test
    fun trueFalseUsesInterfaceTexts() {
        val c = quizContent(tf, 2, 3, null, trueText = "Верно", falseText = "Неверно")
        assertEquals(listOf("Верно", "Неверно"), c.options.map { it.text })
        assertEquals(setOf("true"), quizContent(tf, 0, 1, null, QuizPhase.REVEAL).correct)
        assertEquals(0L, c.totalMs)
    }

    @Test
    fun roundTripsThroughProtocol() {
        val c = quizContent(single, 1, 5, 30, picked = setOf("c"))
        val back = DeixisJson.decodeFromString(Msg.serializer(), DeixisJson.encodeToString(Msg.serializer(), Show(c)))
        assertEquals(c, (back as Show).content)
    }

    @Test
    fun timeRunsFromOwnClockAndTravelsAsRemainder() {
        val c = quizContent(single, 0, 1, 20).copy(running = true)
        val shown = c.anchored(1_000)
        assertEquals(20_000L, shown.remainingAt(1_000))
        assertEquals(5_000L, shown.remainingAt(16_000))
        assertEquals(0L, shown.remainingAt(99_000), "ниже нуля не уходит")
        assertEquals(0.25f, shown.timeFraction(16_000), 0.001f)
        assertEquals(5_000L, shown.forWire(16_000).remainingMs)
        assertEquals(1f, quizContent(single, 0, 1, null).timeFraction(0))
    }

    private fun check(c: QuizContent, w: Float, h: Float) {
        val l = layoutQuiz(c, w, h, 0, measure, ascent)
        val area = safeArea(w, h)
        fun inside(r: QuizRect) = r.left >= area.left - 0.5f && r.right <= area.right + 0.5f && r.top >= area.top - 0.5f && r.bottom <= area.bottom + 0.5f
        for (o in l.options) {
            assertTrue(inside(o.box), "плашка вне полей $o на ${w}x$h")
            assertTrue(o.badge.left >= o.box.left && o.badge.right <= o.box.right && o.badge.top >= o.box.top && o.badge.bottom <= o.box.bottom)
            for (t in o.lines) assertTrue(t.x + measure(t.text, o.size) <= o.box.right + 1f, "строка варианта шире плашки: ${t.text}")
        }
        for (a in l.options) for (b in l.options) if (a !== b) {
            val overlap = a.box.left < b.box.right && b.box.left < a.box.right && a.box.top < b.box.bottom && b.box.top < a.box.bottom
            assertFalse(overlap, "плашки пересекаются")
        }
        val qBottom = l.question.maxOfOrNull { it.baseline } ?: 0f
        val firstOptTop = l.options.minOfOrNull { it.box.top } ?: l.barTrack.top
        assertTrue(qBottom <= firstOptTop, "вопрос налезает на варианты на ${w}x$h")
        for (q in l.question) assertTrue(q.x >= area.left - 0.5f && q.x + measure(q.text, l.questionSize) <= area.right + 0.5f, "вопрос вне полей: ${q.text}")
        assertTrue(l.bar.right <= l.barTrack.right + 0.01f)
    }

    @Test
    fun layoutFitsAnyScreenShape() {
        val c = quizContent(single, 3, 12, 20)
        for ((w, h) in listOf(1920f to 1080f, 1280f to 720f, 1024f to 768f, 1080f to 1920f, 2560f to 1080f, 640f to 360f)) check(c, w, h)
        check(quizContent(tf, 0, 1, null), 1920f, 1080f)
    }

    @Test
    fun longTextsAndManyOptionsStillFit() {
        val long = QuizQuestion("l", "multiple", "Очень длинный вопрос ".repeat(12),
            options = (1..8).map { QuizOption("o$it", "Развёрнутый вариант ответа номер $it, который довольно длинный", it % 2 == 0) })
        for ((w, h) in listOf(1920f to 1080f, 1080f to 1920f, 800f to 600f)) check(quizContent(long, 0, 1, 30), w, h)
    }

    @Test
    fun noTimeLimitMeansFullBarAndNoMetaWhenTotalUnknown() {
        val l = layoutQuiz(quizContent(single, 0, 3, null), 1280f, 720f, 0, measure, ascent)
        assertEquals(l.barTrack.right, l.bar.right, 0.01f)
        assertNotNull(l.meta)
        assertNull(layoutQuiz(quizContent(single, 0, 0, null), 1280f, 720f, 0, measure, ascent).meta)
    }

    @Test
    fun contentOfFileWorksWithShownQuiz() {
        val q = QuizFile(title = "t", questions = listOf(single, tf))
        assertEquals(2, q.questions.size)
        assertNull(quizContent(single, 0, 2, null).file)
    }
}
