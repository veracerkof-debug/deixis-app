package app.deixis.core.quiz

import kotlin.random.Random

/** Итог проверки одного ответа. [earned] из [max] очков вопроса; [correct] — полностью верно. */
data class QuestionResult(
    val questionId: String,
    val earned: Int,
    val max: Int,
    val correct: Boolean,
    /** Какие варианты были верными (для показа после ответа). */
    val correctChoices: Set<String>,
)

/**
 * Проверка ответа. [chosen] — id выбранных вариантов; у truefalse это [QuizTypes.CHOICE_TRUE] или
 * [QuizTypes.CHOICE_FALSE]. Чистая функция: правильные ответы живут только на стороне, которая проверяет
 * (приёмник), и уходят игроку после открытия результата.
 */
fun grade(q: QuizQuestion, chosen: Set<String>): QuestionResult {
    val right: Set<String> = when (q.type) {
        QuizTypes.TRUE_FALSE -> setOf(if (q.answer == true) QuizTypes.CHOICE_TRUE else QuizTypes.CHOICE_FALSE)
        else -> q.options.filter { it.correct }.map { it.id }.toSet()
    }
    // Чужие id не засчитываются и не мешают: считаем только известные варианты.
    val known = if (q.type == QuizTypes.TRUE_FALSE) setOf(QuizTypes.CHOICE_TRUE, QuizTypes.CHOICE_FALSE) else q.options.map { it.id }.toSet()
    val picked = chosen.intersect(known)
    val exact = picked == right
    val earned = when {
        exact -> q.points
        q.type == QuizTypes.MULTIPLE && q.scoring == QuizQuestion.SCORING_PARTIAL && right.isNotEmpty() -> {
            val net = picked.count { it in right } - picked.count { it !in right }
            (q.points * net.coerceAtLeast(0) / right.size)
        }
        else -> 0
    }
    return QuestionResult(q.id, earned, q.points, exact, right)
}

/** Итог прохождения. */
data class QuizScore(val earned: Int, val max: Int, val percent: Int, val passed: Boolean?)

/**
 * Самостоятельное прохождение без ведущего: порядок вопросов и вариантов, ответы, итог.
 * Данных об учениках не хранит; живёт, пока открыт плеер.
 */
class QuizRun(val quiz: QuizFile, random: Random = Random.Default) {
    /** Вопросы в том порядке, в каком их увидит ученик; варианты перемешаны, если так задано. */
    val questions: List<QuizQuestion> = quiz.questions.let { qs ->
        val ordered = if (quiz.settings.shuffleQuestions) qs.shuffled(random) else qs
        if (quiz.settings.shuffleOptions) ordered.map { if (it.options.size > 1) it.copy(options = it.options.shuffled(random)) else it } else ordered
    }

    private val results = LinkedHashMap<String, QuestionResult>()

    var index = 0
        private set

    val current: QuizQuestion? get() = questions.getOrNull(index)
    val finished: Boolean get() = index >= questions.size

    /** Время на текущий вопрос, секунд; null — без ограничения. */
    val currentTimeLimitSec: Int? get() = current?.let { it.timeLimitSec ?: quiz.settings.defaultTimeLimitSec }

    /** Принять ответ на текущий вопрос. Повторный ответ на тот же вопрос не принимается. */
    fun answer(chosen: Set<String>): QuestionResult? {
        val q = current ?: return null
        if (q.id in results) return results[q.id]
        return grade(q, chosen).also { results[q.id] = it }
    }

    /** Время вышло: вопрос без ответа засчитывается как неверный. */
    fun timeUp(): QuestionResult? = answer(emptySet())

    /** К следующему вопросу; без ответа на текущий не уходит. */
    fun next(): Boolean {
        val q = current ?: return false
        if (q.id !in results) return false
        index++
        return !finished
    }

    fun result(questionId: String): QuestionResult? = results[questionId]

    fun score(): QuizScore {
        val earned = results.values.sumOf { it.earned }
        val max = questions.sumOf { it.points }
        val percent = if (max == 0) 0 else earned * 100 / max
        val pass = quiz.settings.passPercent
        return QuizScore(earned, max, percent, if (pass > 0) percent >= pass else null)
    }
}
