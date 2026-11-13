package app.deixis.core.quiz

import kotlinx.serialization.Serializable

/**
 * Файл викторины (`*.deixis-quiz.json`): вопросы, ответы, оформление. Это данные, а не код: файл от человека
 * и от нейросети читается одинаково и никогда не выполняется. Описание формата — docs/quiz/FORMAT.md.
 *
 * Версионирование: [version] растёт только при несовместимых изменениях. Новые необязательные поля версию
 * не меняют: старый Deixis их пропускает ([app.deixis.core.DeixisJson] с `ignoreUnknownKeys`).
 */
@Serializable
data class QuizFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val title: String,
    /** Язык содержимого (ru, uz, en): к языку интерфейса не привязан. */
    val language: String = "ru",
    val description: String = "",
    val settings: QuizSettings = QuizSettings(),
    /** Картинки внутри файла: id → данные. Вопросы ссылаются на них по id. */
    val assets: Map<String, QuizAsset> = emptyMap(),
    val questions: List<QuizQuestion>,
) {
    companion object {
        const val FORMAT = "deixis-quiz"
        const val VERSION = 1
        const val FILE_SUFFIX = ".deixis-quiz.json"
    }
}

@Serializable
data class QuizSettings(
    /** Сколько процентов очков нужно для «сдано»; 0 — зачёта нет. */
    val passPercent: Int = 0,
    val shuffleQuestions: Boolean = false,
    val shuffleOptions: Boolean = false,
    /** Показывать пояснение после ответа. */
    val showExplanations: Boolean = true,
    /** Время на вопрос по умолчанию, секунд; null — без ограничения. У вопроса своё значение главнее. */
    val defaultTimeLimitSec: Int? = null,
)

/** Картинка: тип и данные base64 (без префикса `data:`). */
@Serializable
data class QuizAsset(val mime: String, val data: String)

@Serializable
data class QuizQuestion(
    val id: String,
    /** Тип вопроса: [QuizTypes]. Неизвестный тип читается, но проверку не проходит. */
    val type: String,
    val text: String,
    /** id картинки из [QuizFile.assets]. */
    val image: String? = null,
    val points: Int = 1,
    /** Время на ответ, секунд; null — взять из настроек файла. */
    val timeLimitSec: Int? = null,
    /** Пояснение после ответа. */
    val explanation: String = "",
    /** Варианты для single и multiple. */
    val options: List<QuizOption> = emptyList(),
    /** Верный ответ для truefalse. */
    val answer: Boolean? = null,
    /** Для multiple: [SCORING_ALL] — очки только за точный набор, [SCORING_PARTIAL] — по доле. */
    val scoring: String = SCORING_ALL,
) {
    companion object {
        const val SCORING_ALL = "all"
        const val SCORING_PARTIAL = "partial"
    }
}

@Serializable
data class QuizOption(
    val id: String,
    val text: String,
    val correct: Boolean = false,
    val image: String? = null,
)

object QuizTypes {
    const val SINGLE = "single"
    const val MULTIPLE = "multiple"
    const val TRUE_FALSE = "truefalse"

    /** Типы, которые понимает эта версия. */
    val supported = setOf(SINGLE, MULTIPLE, TRUE_FALSE)

    /** Названы заранее, чтобы файл с ними не читался как опечатка; поддержка — позже. */
    val reserved = setOf("fill", "order", "match", "input", "poll", "wordcloud")

    /** Идентификаторы выбора для вопроса «верно/неверно». */
    const val CHOICE_TRUE = "true"
    const val CHOICE_FALSE = "false"
}
