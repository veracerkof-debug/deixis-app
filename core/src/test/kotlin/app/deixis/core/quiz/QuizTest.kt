package app.deixis.core.quiz

import java.io.File
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuizTest {
    private val example = File("../docs/quiz/example.deixis-quiz.json").readText()

    private fun codes(text: String) = QuizValidator.validate(text).issues.map { it.code }

    private fun withQuestion(q: String) =
        """{"format":"deixis-quiz","version":1,"title":"t","questions":[$q]}"""

    @Test
    fun exampleFromDocsIsValid() {
        val r = QuizValidator.validate(example)
        assertTrue(r.ok, r.issues.toString())
        assertEquals(emptyList(), r.issues)
        assertEquals(3, r.quiz!!.questions.size)
    }

    @Test
    fun brokenJsonAndStructureAreReported() {
        assertEquals(listOf("bad_json"), codes("не json"))
        assertEquals(listOf("bad_json"), codes("[1,2]"))
        assertEquals(listOf("bad_structure"), codes("""{"title":"t"}"""))
    }

    @Test
    fun typosInKeysGiveWarningsButFileStillLoads() {
        val r = QuizValidator.validate(withQuestion(
            """{"id":"q","type":"single","text":"?","options":[{"id":"a","text":"x","correctt":true,"correct":true},{"id":"b","text":"y"}]}"""))
        assertTrue(r.ok)
        assertEquals(listOf("unknown_key"), r.warnings.map { it.code })
        assertEquals("questions[0].options[0].correctt", r.warnings[0].path)
    }

    @Test
    fun singleNeedsExactlyOneCorrect() {
        fun q(a: Boolean, b: Boolean) = withQuestion(
            """{"id":"q","type":"single","text":"?","options":[{"id":"a","text":"x","correct":$a},{"id":"b","text":"y","correct":$b}]}""")
        assertTrue(QuizValidator.validate(q(true, false)).ok)
        assertEquals(listOf("no_correct"), codes(q(false, false)))
        assertEquals(listOf("many_correct"), codes(q(true, true)).filter { it != "all_correct" })
    }

    @Test
    fun optionCountIdsAndAssetsAreChecked() {
        assertTrue("bad_option_count" in codes(withQuestion("""{"id":"q","type":"single","text":"?","options":[{"id":"a","text":"x","correct":true}]}""")))
        assertTrue("duplicate_id" in codes(withQuestion(
            """{"id":"q","type":"single","text":"?","options":[{"id":"a","text":"x","correct":true},{"id":"a","text":"y"}]}""")))
        assertTrue("missing_asset" in codes(withQuestion(
            """{"id":"q","type":"truefalse","text":"?","answer":true,"image":"нет"}""")))
        assertTrue("bad_id" in codes(withQuestion("""{"id":"с пробелом","type":"truefalse","text":"?","answer":true}""")))
    }

    @Test
    fun duplicateQuestionIdsAndEmptyQuizAreErrors() {
        val one = """{"id":"q","type":"truefalse","text":"?","answer":true}"""
        assertTrue("duplicate_id" in codes(withQuestion("$one,$one")))
        assertTrue("no_questions" in codes(withQuestion("")))
    }

    @Test
    fun unknownAndReservedTypesAreTold() {
        assertEquals(listOf("unsupported_type"), codes(withQuestion("""{"id":"q","type":"order","text":"?"}""")))
        assertEquals(listOf("unknown_type"), codes(withQuestion("""{"id":"q","type":"банан","text":"?"}""")))
        assertEquals(listOf("no_answer"), codes(withQuestion("""{"id":"q","type":"truefalse","text":"?"}""")))
    }

    @Test
    fun newerVersionIsRefusedFilesOfOldFormatAreNot() {
        assertEquals(listOf("newer_version"), codes("""{"format":"deixis-quiz","version":2,"title":"t","questions":[{"id":"q","type":"truefalse","text":"?","answer":true}]}"""))
        assertEquals(listOf("wrong_format"), codes("""{"format":"x","version":1,"title":"t","questions":[{"id":"q","type":"truefalse","text":"?","answer":true}]}"""))
    }

    @Test
    fun assetsAreLimitedAndOnlyRaster() {
        fun file(mime: String, data: String) = """{"title":"t","assets":{"i":{"mime":"$mime","data":"$data"}},"questions":[{"id":"q","type":"truefalse","text":"?","answer":true,"image":"i"}]}"""
        val ok = Base64.getEncoder().encodeToString(ByteArray(10))
        assertTrue(QuizValidator.validate(file("image/png", ok)).ok)
        assertEquals(listOf("bad_mime"), codes(file("image/svg+xml", ok)))
        assertEquals(listOf("bad_base64"), codes(file("image/png", "###")))
        val big = Base64.getEncoder().encodeToString(ByteArray(QuizValidator.MAX_ASSET_BYTES + 1))
        assertEquals(listOf("asset_too_big"), codes(file("image/png", big)))
    }

    private fun quiz(vararg q: QuizQuestion, settings: QuizSettings = QuizSettings()) =
        QuizFile(title = "t", questions = q.toList(), settings = settings)

    private val single = QuizQuestion("s", "single", "?", options = listOf(
        QuizOption("a", "x", correct = true), QuizOption("b", "y"), QuizOption("c", "z")))
    private val multi = QuizQuestion("m", "multiple", "?", points = 4, scoring = "partial", options = listOf(
        QuizOption("a", "1", true), QuizOption("b", "2"), QuizOption("c", "3", true), QuizOption("d", "4", true), QuizOption("e", "5")))
    private val tf = QuizQuestion("t", "truefalse", "?", answer = false)

    @Test
    fun gradingSingleAndTrueFalse() {
        assertEquals(1, grade(single, setOf("a")).earned)
        assertEquals(0, grade(single, setOf("b")).earned)
        assertEquals(0, grade(single, setOf("a", "b")).earned, "два ответа на single — неверно")
        assertEquals(0, grade(single, emptySet()).earned)
        assertEquals(1, grade(tf, setOf("false")).earned)
        assertEquals(0, grade(tf, setOf("true")).earned)
        assertEquals(setOf("false"), grade(tf, emptySet()).correctChoices)
    }

    @Test
    fun gradingMultipleExactAndPartial() {
        assertEquals(4, grade(multi, setOf("a", "c", "d")).earned)
        assertEquals(2, grade(multi, setOf("a", "c")).earned, "две из трёх — две трети очков, округляя вниз")
        assertEquals(0, grade(multi, setOf("a", "c", "b", "e")).earned, "лишние вычитаются, ниже нуля не бывает")
        val strict = multi.copy(scoring = "all")
        assertEquals(0, grade(strict, setOf("a", "c")).earned)
        assertEquals(4, grade(strict, setOf("a", "c", "d")).earned)
        assertEquals(4, grade(multi, setOf("a", "c", "d", "чужой")).earned, "чужой id не мешает")
    }

    @Test
    fun runGoesThroughQuestionsOnceAndScores() {
        val run = QuizRun(quiz(single, tf, settings = QuizSettings(passPercent = 60)))
        assertEquals("s", run.current!!.id)
        assertFalse(run.next(), "без ответа вперёд нельзя")
        assertNotNull(run.answer(setOf("a")))
        assertEquals(1, run.answer(setOf("b"))!!.earned, "повторный ответ не меняет результат")
        assertTrue(run.next())
        run.timeUp()
        assertFalse(run.next())
        assertTrue(run.finished)
        val s = run.score()
        assertEquals(QuizScore(1, 2, 50, false), s)
        assertNull(run.answer(setOf("a")))
    }

    @Test
    fun noPassMarkMeansNoVerdictAndShuffleKeepsContent() {
        val q = quiz(single, multi, tf, settings = QuizSettings(shuffleQuestions = true, shuffleOptions = true))
        val run = QuizRun(q, Random(7))
        assertEquals(q.questions.map { it.id }.toSet(), run.questions.map { it.id }.toSet())
        assertEquals(q.questions.flatMap { it.options.map { o -> o.id } }.toSet(), run.questions.flatMap { it.options.map { o -> o.id } }.toSet())
        assertNull(run.score().passed)
    }

    @Test
    fun timeLimitComesFromQuestionThenFromSettings() {
        val run = QuizRun(quiz(single.copy(timeLimitSec = 12), tf, settings = QuizSettings(defaultTimeLimitSec = 30)))
        assertEquals(12, run.currentTimeLimitSec)
        run.timeUp(); run.next()
        assertEquals(30, run.currentTimeLimitSec)
    }
}
