package app.deixis.core

import app.deixis.core.quiz.QuizQuestion
import app.deixis.core.quiz.QuizTypes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** В какой фазе вопрос: идёт приём ответов или показан результат. */
@Serializable
enum class QuizPhase { QUESTION, REVEAL }

/** Вариант на экране: [mark] — буква в значке (A, B, …), чтобы смысл не держался только на цвете. */
@Serializable
data class QuizShownOption(val id: String, val text: String, val mark: String)

/**
 * Вопрос викторины на экране (зал, ТВ, компьютер, браузер зрителя). Устроен как [CountdownContent]: время идёт
 * от часов самого экрана ([anchored]), по сети уходит остаток ([forWire]).
 *
 * Правильные ответы ([correct]) попадают в содержимое только в фазе [QuizPhase.REVEAL]: до открытия результата
 * экран и телефоны зрителей их не знают.
 */
@Serializable @SerialName("quiz")
data class QuizContent(
    /** Номер вопроса с нуля и сколько их всего: «2 / 10». */
    val index: Int,
    val total: Int,
    val text: String,
    val options: List<QuizShownOption>,
    val phase: QuizPhase = QuizPhase.QUESTION,
    /** Верные варианты; пусто, пока [phase] = QUESTION. */
    val correct: Set<String> = emptySet(),
    /** Что выбрал тот, чьим ответом показывается слайд (самостоятельное прохождение); в живой игре пусто. */
    val picked: Set<String> = emptySet(),
    /** Время на вопрос целиком (полоса времени) и сколько осталось; 0 — без ограничения. */
    val totalMs: Long = 0,
    val remainingMs: Long = 0,
    val running: Boolean = false,
    /** Мгновение нуля по часам того, кто держит время; 0 — не привязано. По сети не нужно. */
    val endsAt: Long = 0,
    val style: TextStyle = TextStyle(),
) : Content {
    fun anchored(now: Long): QuizContent = if (running) copy(endsAt = now + remainingMs) else copy(endsAt = 0)

    fun remainingAt(now: Long): Long = if (running && endsAt != 0L) (endsAt - now).coerceAtLeast(0) else remainingMs

    fun forWire(now: Long): QuizContent = copy(remainingMs = remainingAt(now), endsAt = 0)

    /** Доля оставшегося времени 0..1 для полосы; без ограничения — 1. */
    fun timeFraction(now: Long): Float = if (totalMs <= 0) 1f else (remainingAt(now).toFloat() / totalMs).coerceIn(0f, 1f)
}

/** Состояние варианта на слайде. */
enum class QuizOptionState { NORMAL, PICKED, RIGHT, WRONG, DIM }

fun QuizContent.stateOf(o: QuizShownOption): QuizOptionState = when {
    phase == QuizPhase.QUESTION -> if (o.id in picked) QuizOptionState.PICKED else QuizOptionState.NORMAL
    o.id in correct -> QuizOptionState.RIGHT
    o.id in picked -> QuizOptionState.WRONG
    else -> QuizOptionState.DIM
}

/**
 * Слайд для вопроса файла. [limitSec] — время на вопрос; null — без ограничения. В фазе вопроса верные ответы
 * не копируются. У вопроса «верно/неверно» варианты берутся готовые: тексты передаёт интерфейс на языке пользователя
 * ([trueText], [falseText]) — содержимое урока их не хранит.
 */
fun quizContent(
    q: QuizQuestion,
    index: Int,
    total: Int,
    limitSec: Int?,
    phase: QuizPhase = QuizPhase.QUESTION,
    picked: Set<String> = emptySet(),
    trueText: String = "Верно",
    falseText: String = "Неверно",
    style: TextStyle = TextStyle(),
): QuizContent {
    val opts = if (q.type == QuizTypes.TRUE_FALSE) listOf(
        QuizShownOption(QuizTypes.CHOICE_TRUE, trueText, "A"),
        QuizShownOption(QuizTypes.CHOICE_FALSE, falseText, "B"),
    ) else q.options.mapIndexed { i, o -> QuizShownOption(o.id, o.text, quizMark(i)) }
    val right = if (phase == QuizPhase.REVEAL) app.deixis.core.quiz.grade(q, emptySet()).correctChoices else emptySet()
    val ms = (limitSec ?: 0) * 1000L
    return QuizContent(index, total, q.text, opts, phase, right, picked, ms, ms, running = false, style = style)
}

/** Буква варианта: A…Z. */
fun quizMark(i: Int): String = ('A' + i.coerceIn(0, 25)).toString()
