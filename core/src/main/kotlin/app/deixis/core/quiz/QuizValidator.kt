package app.deixis.core.quiz

import app.deixis.core.DeixisJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Base64

enum class IssueLevel { ERROR, WARNING }

/**
 * Замечание к файлу. [code] — стабильный код (для переводов в интерфейсе и для нейросети), [path] — где
 * (`questions[2].options[1].text`), [message] — понятная фраза для автора.
 */
data class QuizIssue(val level: IssueLevel, val code: String, val path: String, val message: String)

/** Итог проверки: [quiz] есть, только если ошибок нет. Предупреждения файл не блокируют. */
data class QuizReport(val quiz: QuizFile?, val issues: List<QuizIssue>) {
    val errors get() = issues.filter { it.level == IssueLevel.ERROR }
    val warnings get() = issues.filter { it.level == IssueLevel.WARNING }
    val ok get() = quiz != null
}

/** Проверка файла викторины. Файл не доверенный: размеры ограничены, картинки только растровые. */
object QuizValidator {
    const val MAX_FILE_BYTES = 32L * 1024 * 1024
    const val MAX_ASSET_BYTES = 2 * 1024 * 1024
    const val MAX_QUESTIONS = 200
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 8
    const val MAX_TITLE = 200
    const val MAX_TEXT = 1000
    const val MAX_OPTION_TEXT = 300
    const val MAX_EXPLANATION = 2000
    const val MIN_TIME_SEC = 5
    const val MAX_TIME_SEC = 600
    const val MAX_POINTS = 1000
    val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    private val ID = Regex("[A-Za-z0-9_-]{1,32}")

    private val FILE_KEYS = setOf("format", "version", "title", "language", "description", "settings", "assets", "questions")
    private val SETTINGS_KEYS = setOf("passPercent", "shuffleQuestions", "shuffleOptions", "showExplanations", "defaultTimeLimitSec")
    private val QUESTION_KEYS = setOf("id", "type", "text", "image", "points", "timeLimitSec", "explanation", "options", "answer", "scoring")
    private val OPTION_KEYS = setOf("id", "text", "correct", "image")

    fun validate(text: String): QuizReport {
        val issues = mutableListOf<QuizIssue>()
        fun err(code: String, path: String, msg: String) { issues += QuizIssue(IssueLevel.ERROR, code, path, msg) }
        fun warn(code: String, path: String, msg: String) { issues += QuizIssue(IssueLevel.WARNING, code, path, msg) }

        if (text.toByteArray().size > MAX_FILE_BYTES) {
            err("file_too_big", "", "Файл больше ${MAX_FILE_BYTES / 1024 / 1024} МБ")
            return QuizReport(null, issues)
        }
        val root = try {
            DeixisJson.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            err("bad_json", "", "Это не JSON или корень не объект: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
            return QuizReport(null, issues)
        }
        unknownKeys(root, FILE_KEYS, "", ::warn)
        (root["settings"] as? JsonObject)?.let { unknownKeys(it, SETTINGS_KEYS, "settings", ::warn) }
        (root["questions"] as? kotlinx.serialization.json.JsonArray)?.forEachIndexed { i, q ->
            val qo = q as? JsonObject ?: return@forEachIndexed
            unknownKeys(qo, QUESTION_KEYS, "questions[$i]", ::warn)
            (qo["options"] as? kotlinx.serialization.json.JsonArray)?.forEachIndexed { j, o ->
                (o as? JsonObject)?.let { unknownKeys(it, OPTION_KEYS, "questions[$i].options[$j]", ::warn) }
            }
        }

        val quiz = try {
            DeixisJson.decodeFromJsonElement(QuizFile.serializer(), root)
        } catch (e: SerializationException) {
            err("bad_structure", "", "Не хватает обязательного поля или тип поля неверный: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
            return QuizReport(null, issues)
        } catch (e: IllegalArgumentException) {
            err("bad_structure", "", "Структура файла неверна: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
            return QuizReport(null, issues)
        }

        if (quiz.format != QuizFile.FORMAT) err("wrong_format", "format", "Поле format должно быть \"${QuizFile.FORMAT}\"")
        if (quiz.version > QuizFile.VERSION) err("newer_version", "version", "Файл версии ${quiz.version}, эта программа понимает до ${QuizFile.VERSION}")
        else if (quiz.version < 1) err("bad_version", "version", "Версия должна быть 1 или больше")
        if (quiz.title.isBlank()) err("empty_title", "title", "Нет названия")
        if (quiz.title.length > MAX_TITLE) err("title_too_long", "title", "Название длиннее $MAX_TITLE символов")
        quiz.settings.defaultTimeLimitSec?.let { if (it !in MIN_TIME_SEC..MAX_TIME_SEC) err("bad_time", "settings.defaultTimeLimitSec", "Время на вопрос — от $MIN_TIME_SEC до $MAX_TIME_SEC секунд") }
        if (quiz.settings.passPercent !in 0..100) err("bad_percent", "settings.passPercent", "Порог зачёта — от 0 до 100 процентов")

        var total = 0L
        for ((id, a) in quiz.assets) {
            val p = "assets.$id"
            if (!ID.matches(id)) err("bad_id", p, "id картинки: латиница, цифры, «_» и «-», до 32 знаков")
            if (a.mime !in IMAGE_TYPES) { err("bad_mime", "$p.mime", "Тип картинки ${a.mime} не поддерживается (png, jpeg, webp, gif)"); continue }
            val size = try { Base64.getDecoder().decode(a.data).size } catch (e: IllegalArgumentException) {
                err("bad_base64", "$p.data", "Данные картинки — не base64 (без префикса data:)"); continue
            }
            if (size > MAX_ASSET_BYTES) err("asset_too_big", p, "Картинка больше ${MAX_ASSET_BYTES / 1024 / 1024} МБ")
            total += size
        }
        if (total > MAX_FILE_BYTES) err("assets_too_big", "assets", "Картинки в сумме больше ${MAX_FILE_BYTES / 1024 / 1024} МБ")

        if (quiz.questions.isEmpty()) err("no_questions", "questions", "Нет ни одного вопроса")
        if (quiz.questions.size > MAX_QUESTIONS) err("too_many_questions", "questions", "Вопросов больше $MAX_QUESTIONS")
        val seen = HashSet<String>()
        quiz.questions.forEachIndexed { i, q ->
            val p = "questions[$i]"
            if (!ID.matches(q.id)) err("bad_id", "$p.id", "id вопроса: латиница, цифры, «_» и «-», до 32 знаков")
            else if (!seen.add(q.id)) err("duplicate_id", "$p.id", "id «${q.id}» уже занят другим вопросом")
            if (q.text.isBlank()) err("empty_text", "$p.text", "Нет текста вопроса")
            if (q.text.length > MAX_TEXT) err("text_too_long", "$p.text", "Текст вопроса длиннее $MAX_TEXT символов")
            if (q.explanation.length > MAX_EXPLANATION) err("text_too_long", "$p.explanation", "Пояснение длиннее $MAX_EXPLANATION символов")
            if (q.points !in 0..MAX_POINTS) err("bad_points", "$p.points", "Очки — от 0 до $MAX_POINTS")
            q.timeLimitSec?.let { if (it !in MIN_TIME_SEC..MAX_TIME_SEC) err("bad_time", "$p.timeLimitSec", "Время на вопрос — от $MIN_TIME_SEC до $MAX_TIME_SEC секунд") }
            q.image?.let { if (it !in quiz.assets) err("missing_asset", "$p.image", "Картинки «$it» нет в assets") }
            when (q.type) {
                QuizTypes.SINGLE, QuizTypes.MULTIPLE -> choice(q, p, quiz, ::err, ::warn)
                QuizTypes.TRUE_FALSE -> {
                    if (q.answer == null) err("no_answer", "$p.answer", "Для truefalse нужно поле answer: true или false")
                    if (q.options.isNotEmpty()) warn("ignored_options", "$p.options", "У truefalse варианты не нужны, они игнорируются")
                }
                in QuizTypes.reserved -> err("unsupported_type", "$p.type", "Тип «${q.type}» задуман, но эта версия его не поддерживает")
                else -> err("unknown_type", "$p.type", "Неизвестный тип «${q.type}». Есть: ${QuizTypes.supported.joinToString()}")
            }
        }
        return QuizReport(if (issues.none { it.level == IssueLevel.ERROR }) quiz else null, issues)
    }

    private fun choice(
        q: QuizQuestion, p: String, quiz: QuizFile,
        err: (String, String, String) -> Unit, warn: (String, String, String) -> Unit,
    ) {
        if (q.options.size !in MIN_OPTIONS..MAX_OPTIONS) err("bad_option_count", "$p.options", "Вариантов от $MIN_OPTIONS до $MAX_OPTIONS, а здесь ${q.options.size}")
        val ids = HashSet<String>()
        q.options.forEachIndexed { j, o ->
            val op = "$p.options[$j]"
            if (!ID.matches(o.id)) err("bad_id", "$op.id", "id варианта: латиница, цифры, «_» и «-», до 32 знаков")
            else if (!ids.add(o.id)) err("duplicate_id", "$op.id", "id «${o.id}» уже занят другим вариантом")
            if (o.text.isBlank() && o.image == null) err("empty_text", "$op.text", "У варианта нет ни текста, ни картинки")
            if (o.text.length > MAX_OPTION_TEXT) err("text_too_long", "$op.text", "Текст варианта длиннее $MAX_OPTION_TEXT символов")
            o.image?.let { if (it !in quiz.assets) err("missing_asset", "$op.image", "Картинки «$it» нет в assets") }
        }
        val right = q.options.count { it.correct }
        if (right == 0) err("no_correct", "$p.options", "Ни один вариант не помечен верным (correct: true)")
        if (q.type == QuizTypes.SINGLE && right > 1) err("many_correct", "$p.options", "Для single верный вариант один, а помечено $right; для нескольких используйте multiple")
        if (q.type == QuizTypes.MULTIPLE && right == 1) warn("one_correct", "$p.options", "У multiple верный вариант один — возможно, нужен single")
        if (q.scoring != QuizQuestion.SCORING_ALL && q.scoring != QuizQuestion.SCORING_PARTIAL) err("bad_scoring", "$p.scoring", "scoring: \"all\" или \"partial\"")
        if (q.options.all { it.correct } && q.options.size >= MIN_OPTIONS) warn("all_correct", "$p.options", "Все варианты верные")
    }

    private fun unknownKeys(o: JsonObject, known: Set<String>, path: String, warn: (String, String, String) -> Unit) {
        for (k in o.keys) if (k !in known) {
            val where = if (path.isEmpty()) k else "$path.$k"
            warn("unknown_key", where, "Поле «$k» неизвестно и будет пропущено — возможно, опечатка")
        }
    }
}
