package app.deixis.phone

import app.deixis.core.Content
import app.deixis.core.QuizContent
import app.deixis.core.QuizPhase
import app.deixis.core.TextContent
import app.deixis.core.quiz.QuizFile
import app.deixis.core.quiz.QuizOption
import app.deixis.core.quiz.QuizQuestion
import app.deixis.core.quiz.QuizSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QuizSessionTest {
    private val shown = mutableListOf<Content>()
    private val scope = TestScope()
    private val session = QuizSession(scope, { shown += it })

    private val single = QuizQuestion("s", "single", "Река?", explanation = "Волга", options = listOf(
        QuizOption("a", "Волга", true), QuizOption("b", "Дон")))
    private val multi = QuizQuestion("m", "multiple", "Простые?", points = 2, options = listOf(
        QuizOption("a", "2", true), QuizOption("b", "9"), QuizOption("c", "11", true)))
    private val tf = QuizQuestion("t", "truefalse", "Земля вокруг Солнца?", answer = true)

    private fun file(vararg q: QuizQuestion, settings: QuizSettings = QuizSettings()) = QuizFile(title = "t", questions = q.toList(), settings = settings)

    @Test
    fun walksThroughQuestionsAndShowsRevealThenResult() {
        session.start(file(single, tf, settings = QuizSettings(passPercent = 50)))
        val q1 = assertIs<QuizContent>(shown.last())
        assertEquals(QuizPhase.QUESTION, q1.phase)
        assertTrue(q1.correct.isEmpty(), "верные ответы до результата не уходят")

        session.answer()
        assertNull(session.state.value!!.result, "без выбора ответ не принимается")
        session.toggle("a")
        session.answer()
        assertEquals(QuizPhase.REVEAL, (shown.last() as QuizContent).phase)
        assertEquals(setOf("a"), (shown.last() as QuizContent).correct)
        assertEquals(1, session.state.value!!.score.earned)

        session.next()
        assertEquals("t", (shown.last() as QuizContent).let { session.state.value!!.question!!.id })
        session.toggle("false"); session.answer(); session.next()

        assertTrue(session.state.value!!.finished)
        val end = assertIs<TextContent>(shown.last())
        assertTrue(end.text.contains("1") && end.text.contains("2"), end.text)
        assertEquals(50, session.state.value!!.score.percent)
        assertEquals(true, session.state.value!!.score.passed)
    }

    @Test
    fun multipleToggleAddsAndRemovesSingleReplaces() {
        session.start(file(multi, single))
        session.toggle("a"); session.toggle("c")
        assertEquals(setOf("a", "c"), session.state.value!!.picked)
        session.toggle("a")
        assertEquals(setOf("c"), session.state.value!!.picked)
        session.toggle("a"); session.answer(); session.next()
        session.toggle("a"); session.toggle("b")
        assertEquals(setOf("b"), session.state.value!!.picked, "у одного ответа выбор заменяется")
    }

    @Test
    fun picksAreFrozenAfterTheAnswer() {
        session.start(file(single))
        session.toggle("a"); session.answer()
        session.toggle("b")
        assertEquals(setOf("a"), session.state.value!!.picked)
        assertEquals(1, session.state.value!!.score.earned)
    }

    @Test
    fun timeRunningOutCountsAsWrongAndReveals() {
        session.start(file(single, settings = QuizSettings(defaultTimeLimitSec = 20)))
        val q = shown.last() as QuizContent
        assertTrue(q.running && q.totalMs == 20_000L)
        scope.advanceTimeBy(19_000); scope.runCurrent()
        assertNull(session.state.value!!.result)
        scope.advanceTimeBy(2_000); scope.runCurrent()
        val st = session.state.value!!
        assertNotNull(st.result)
        assertTrue(st.timedOut)
        assertEquals(0, st.score.earned)
        assertEquals(QuizPhase.REVEAL, (shown.last() as QuizContent).phase)
    }

    @Test
    fun answerCancelsTheTimer() {
        session.start(file(single, tf, settings = QuizSettings(defaultTimeLimitSec = 20)))
        session.toggle("a"); session.answer()
        val before = shown.size
        scope.advanceTimeBy(60_000); scope.runCurrent()
        assertEquals(before, shown.size, "после ответа таймер ничего не показывает")
        assertFalse(session.state.value!!.timedOut)
    }

    @Test
    fun badFileNeverStartsAndNothingGoesToTheScreen() {
        val report = session.load("не json".byteInputStream())
        assertFalse(report.ok)
        assertTrue(shown.isEmpty())
        assertNull(session.state.value)
    }

    @Test
    fun closeClearsStateAndRestartResetsScore() {
        session.start(file(single))
        session.toggle("a"); session.answer(); session.next()
        assertTrue(session.state.value!!.finished)
        session.start(file(single))
        assertEquals(0, session.state.value!!.score.earned)
        session.close()
        assertNull(session.state.value)
    }
}
