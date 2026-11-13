package app.deixis.core.quiz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuizGameTest {
    private var now = 1_000_000L
    private val single = QuizQuestion("s", "single", "Река?", points = 100, timeLimitSec = 20, options = listOf(
        QuizOption("a", "Волга", true), QuizOption("b", "Дон")))
    private val multi = QuizQuestion("m", "multiple", "Простые?", points = 90, scoring = "partial", options = listOf(
        QuizOption("a", "2", true), QuizOption("b", "9"), QuizOption("c", "11", true), QuizOption("d", "13", true)))
    private val tf = QuizQuestion("t", "truefalse", "Земля вокруг Солнца?", points = 50, answer = true)

    private fun game(vararg q: QuizQuestion) = QuizGame(QuizFile(title = "t", questions = q.toList()), { now })
    private fun QuizGame.add(name: String) = (join(name) as JoinResult.Ok).token

    @Test
    fun joinRulesNamesAndLimit() {
        val g = game(single)
        assertIs<JoinResult.Ok>(g.join("  Аня   Иванова "))
        assertEquals(listOf("Аня Иванова"), g.playerNames(), "пробелы схлопнуты")
        assertEquals(JoinResult.NameTaken, g.join("аня иванова"))
        assertEquals(JoinResult.BadName, g.join("   "))
        assertEquals(JoinResult.BadName, g.join("x".repeat(25)))
        assertEquals(JoinResult.BadName, g.join("\u0000\u0007"))
        repeat(QuizGame.MAX_PLAYERS - 1) { g.add("p$it") }
        assertEquals(JoinResult.Full, g.join("лишний"))
        assertEquals(QuizGame.MAX_PLAYERS, g.playerCount)
    }

    @Test
    fun gameStartsOnlyOnceAndClosesJoining() {
        val g = game(single)
        val t = g.add("Аня")
        assertEquals(GamePhase.LOBBY, g.phase)
        assertTrue(g.start())
        assertFalse(g.start())
        assertEquals(JoinResult.Closed, g.join("Поздний"))
        assertEquals("Аня", g.rejoin(t), "по токену вернуться можно")
        assertNull(g.rejoin("чужой"))
    }

    @Test
    fun answersAreValidatedAndCanBeChangedWhileOpen() {
        val g = game(single, multi, tf)
        val t = g.add("Аня")
        assertEquals(AnswerResult.NOT_OPEN, g.answer(t, setOf("a")), "до старта нельзя")
        g.start()
        assertEquals(AnswerResult.UNKNOWN_PLAYER, g.answer("нет", setOf("a")))
        assertEquals(AnswerResult.BAD_CHOICE, g.answer(t, emptySet()))
        assertEquals(AnswerResult.BAD_CHOICE, g.answer(t, setOf("zzz")))
        assertEquals(AnswerResult.BAD_CHOICE, g.answer(t, setOf("a", "b")), "у single один вариант")
        assertEquals(AnswerResult.ACCEPTED, g.answer(t, setOf("b")))
        assertEquals(AnswerResult.ACCEPTED, g.answer(t, setOf("a")))
        assertEquals(setOf("a"), g.answerOf(t))
        now += 21_000
        assertEquals(AnswerResult.NOT_OPEN, g.answer(t, setOf("b")), "после времени не принимается")
        assertEquals(setOf("a"), g.answerOf(t))
    }

    @Test
    fun speedBonusScalesBetweenHalfAndFull() {
        val g = game(single)
        val fast = g.add("Быстрый"); val slow = g.add("Медленный"); val wrong = g.add("Мимо"); val silent = g.add("Молчун")
        g.start()
        now += 0; g.answer(fast, setOf("a"))
        now += 10_000; g.answer(slow, setOf("a"))
        g.answer(wrong, setOf("b"))
        g.closeQuestion()
        assertEquals(100, g.resultOf(fast)!!.earned, "мгновенно — полные очки")
        assertEquals(75, g.resultOf(slow)!!.earned, "на середине времени — три четверти")
        assertEquals(0, g.resultOf(wrong)!!.earned)
        assertFalse(g.resultOf(wrong)!!.correct)
        assertEquals(0, g.resultOf(silent)!!.earned)
        assertEquals(listOf("Быстрый" to 1, "Медленный" to 2), g.standings().take(2).map { it.name to it.rank })
    }

    @Test
    fun partialMultipleAndNoLimitHasNoBonus() {
        val g = game(multi)
        val t = g.add("Аня")
        g.start()
        now += 5_000
        g.answer(t, setOf("a", "c"))
        g.closeQuestion()
        assertEquals(60, g.resultOf(t)!!.earned, "две из трёх верных, лимита нет — без бонуса")
    }

    @Test
    fun correctAnswersAreHiddenUntilReveal() {
        val g = game(single, tf)
        val t = g.add("Аня")
        g.start()
        assertEquals(emptySet(), g.correctChoices())
        assertNull(g.resultOf(t))
        g.answer(t, setOf("a"))
        g.closeQuestion()
        assertEquals(setOf("a"), g.correctChoices())
        assertNotNull(g.resultOf(t))
        assertTrue(g.next())
        assertEquals(emptySet(), g.correctChoices(), "следующий вопрос — снова скрыто")
        assertNull(g.resultOf(t), "прошлый результат не виден на новом вопросе")
    }

    @Test
    fun answersResetBetweenQuestionsAndGameFinishes() {
        val g = game(single, tf)
        val t = g.add("Аня")
        g.start(); g.answer(t, setOf("a")); g.closeQuestion()
        g.next()
        assertEquals(emptySet(), g.answerOf(t))
        assertEquals(1, g.index)
        g.answer(t, setOf("true")); g.closeQuestion()
        assertFalse(g.next())
        assertEquals(GamePhase.FINISHED, g.phase)
        assertEquals(150, g.standings().single().score)
        assertFalse(g.closeQuestion())
    }

    @Test
    fun equalScoresShareRankAndKickFreesTheName() {
        val g = game(tf)
        val a = g.add("Борис"); val b = g.add("Аня"); val c = g.add("Вика")
        g.start()
        g.answer(a, setOf("true")); g.answer(b, setOf("true")); g.answer(c, setOf("false"))
        g.closeQuestion()
        val s = g.standings()
        assertEquals(listOf("Аня" to 1, "Борис" to 1, "Вика" to 3), s.map { it.name to it.rank })
        g.kick(c)
        assertIs<JoinResult.Closed>(g.join("Вика"), "игра идёт, новые не входят")
        assertEquals(2, g.playerCount)
    }

    @Test
    fun progressCountsChoicesForTheHost() {
        val g = game(single)
        val a = g.add("А"); val b = g.add("Б"); g.add("В")
        g.start()
        g.answer(a, setOf("a")); g.answer(b, setOf("b"))
        val p = g.progress()
        assertEquals(2, p.answered); assertEquals(3, p.of)
        assertEquals(mapOf("a" to 1, "b" to 1), p.counts)
        assertEquals(listOf("А", "Б"), g.answeredNames())
        now += 20_001
        assertTrue(g.timeIsUp())
    }
}
