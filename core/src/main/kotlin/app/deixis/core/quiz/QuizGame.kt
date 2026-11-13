package app.deixis.core.quiz

import java.security.SecureRandom

/** Фаза живой игры. */
enum class GamePhase { LOBBY, QUESTION, REVEAL, FINISHED }

sealed interface JoinResult {
    /** [token] игрок хранит в браузере: по нему возвращается после обрыва связи. */
    data class Ok(val token: String, val name: String) : JoinResult
    data object Full : JoinResult
    /** Игра уже идёт или закончена: новых игроков не принимает (вернуться по токену можно всегда). */
    data object Closed : JoinResult
    data object BadName : JoinResult
    data object NameTaken : JoinResult
}

enum class AnswerResult { ACCEPTED, NOT_OPEN, UNKNOWN_PLAYER, BAD_CHOICE }

/** Строка таблицы лидеров. */
data class Standing(val name: String, val score: Int, val rank: Int)

/** Что игрок видит о себе после результата вопроса. */
data class PlayerResult(val correct: Boolean, val earned: Int, val score: Int, val rank: Int)

/**
 * Движок живой игры: игроки, приём ответов, очки, таблица. Живёт на приёмнике (ТВ, компьютер), поэтому сбой пульта
 * игру не трогает; пульт ведущего только подаёт команды ([start], [closeQuestion], [next]). Чистая логика: время —
 * из [clock], сети здесь нет; страница игрока и зал подключаются поверх.
 *
 * Данных об игроках нет вне памяти: имя и очки живут, пока жив объект игры.
 *
 * Правила:
 * - ответ можно менять, пока вопрос открыт; считается последний, и скорость — по времени последнего изменения;
 * - очки = очки вопроса за верность × (0,5 + 0,5 × доля оставшегося времени); без лимита времени — без бонуса;
 * - верные ответы наружу ([correctChoices]) отдаются только в фазе [GamePhase.REVEAL].
 */
class QuizGame(
    val quiz: QuizFile,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private class Player(val token: String, val name: String) {
        var score = 0
        var answer: Set<String>? = null
        var answeredAt = 0L
        var last: PlayerResult? = null
    }

    private val players = LinkedHashMap<String, Player>()
    private val names = HashSet<String>()

    var phase = GamePhase.LOBBY
        private set
    var index = -1
        private set

    private var openedAt = 0L
    private var limitMs = 0L

    val total: Int get() = quiz.questions.size
    val question: QuizQuestion? get() = quiz.questions.getOrNull(index)
    val playerCount: Int get() = players.size

    /** Игрок входит по имени. Имена уникальны без учёта регистра. */
    fun join(rawName: String): JoinResult {
        if (phase != GamePhase.LOBBY) return JoinResult.Closed
        val name = cleanName(rawName) ?: return JoinResult.BadName
        if (players.size >= MAX_PLAYERS) return JoinResult.Full
        if (!names.add(name.lowercase())) return JoinResult.NameTaken
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        players[token] = Player(token, name)
        return JoinResult.Ok(token, name)
    }

    /** Возврат после обрыва: имя и очки на месте. */
    fun rejoin(token: String): String? = players[token]?.name

    fun kick(token: String) {
        val p = players.remove(token) ?: return
        names.remove(p.name.lowercase())
    }

    /** Начать игру: открывается первый вопрос. */
    fun start(): Boolean {
        if (phase != GamePhase.LOBBY || quiz.questions.isEmpty()) return false
        index = -1
        return openNext()
    }

    private fun openNext(): Boolean {
        index++
        val q = question
        if (q == null) { phase = GamePhase.FINISHED; return false }
        phase = GamePhase.QUESTION
        openedAt = clock()
        limitMs = (q.timeLimitSec ?: quiz.settings.defaultTimeLimitSec ?: 0) * 1000L
        for (p in players.values) { p.answer = null; p.answeredAt = 0; p.last = null }
        return true
    }

    /** Сколько осталось на вопрос; null — без лимита или вопрос не открыт. */
    fun remainingMs(): Long? = if (phase == GamePhase.QUESTION && limitMs > 0) (openedAt + limitMs - clock()).coerceAtLeast(0) else null

    /** Время вышло: ведущему пора закрывать приём (само закрытие — по команде или таймеру приёмника). */
    fun timeIsUp(): Boolean = remainingMs() == 0L

    fun answer(token: String, chosen: Set<String>): AnswerResult {
        val p = players[token] ?: return AnswerResult.UNKNOWN_PLAYER
        val q = question
        if (phase != GamePhase.QUESTION || q == null) return AnswerResult.NOT_OPEN
        // Ответ на последней миллисекунде принимается, позже — нет: опоздавший не получает преимущества.
        if (limitMs > 0 && clock() > openedAt + limitMs) return AnswerResult.NOT_OPEN
        val known = if (q.type == QuizTypes.TRUE_FALSE) setOf(QuizTypes.CHOICE_TRUE, QuizTypes.CHOICE_FALSE) else q.options.map { it.id }.toSet()
        if (chosen.isEmpty() || !known.containsAll(chosen)) return AnswerResult.BAD_CHOICE
        // single и truefalse берут один вариант: лишние не принимаются, чтобы не «угадывать залпом».
        if (q.type != QuizTypes.MULTIPLE && chosen.size != 1) return AnswerResult.BAD_CHOICE
        p.answer = chosen
        p.answeredAt = clock()
        return AnswerResult.ACCEPTED
    }

    /** Закрыть приём: считаются очки, открывается результат. Верные ответы теперь можно отдавать наружу. */
    fun closeQuestion(): Boolean {
        val q = question ?: return false
        if (phase != GamePhase.QUESTION) return false
        phase = GamePhase.REVEAL
        for (p in players.values) {
            val g = grade(q, p.answer.orEmpty())
            val bonus = if (limitMs <= 0 || p.answer == null) 1.0 else 0.5 + 0.5 * ((openedAt + limitMs - p.answeredAt).coerceIn(0, limitMs).toDouble() / limitMs)
            val earned = Math.round(g.earned * bonus).toInt()
            p.score += earned
            p.last = PlayerResult(g.correct, earned, p.score, 0)
        }
        // Места считаются после начисления всем.
        val ranks = standings().associate { it.name to it.rank }
        for (p in players.values) p.last = p.last?.copy(rank = ranks[p.name] ?: 0)
        return true
    }

    /** К следующему вопросу или к концу игры. */
    fun next(): Boolean {
        if (phase != GamePhase.REVEAL) return false
        return openNext()
    }

    /** Верные ответы: пусто, пока вопрос открыт. */
    fun correctChoices(): Set<String> {
        val q = question ?: return emptySet()
        return if (phase == GamePhase.REVEAL || phase == GamePhase.FINISHED) grade(q, emptySet()).correctChoices else emptySet()
    }

    /** Таблица лидеров: по очкам, при равенстве — по имени; равные очки делят место. */
    fun standings(limit: Int = Int.MAX_VALUE): List<Standing> {
        val sorted = players.values.sortedWith(compareByDescending<Player> { it.score }.thenBy { it.name.lowercase() })
        var rank = 0
        var prev = Int.MIN_VALUE
        return sorted.mapIndexed { i, p ->
            if (p.score != prev) { rank = i + 1; prev = p.score }
            Standing(p.name, p.score, rank)
        }.take(limit)
    }

    /** Итог игрока после результата вопроса; null — игрока нет или результата ещё нет. */
    fun resultOf(token: String): PlayerResult? = if (phase == GamePhase.REVEAL || phase == GamePhase.FINISHED) players[token]?.last else null

    /** Что выбрал игрок сейчас (для его телефона после обрыва). */
    fun answerOf(token: String): Set<String> = players[token]?.answer.orEmpty()

    /** Для ведущего: сколько игроков выбрали каждый вариант и сколько ответили. Пока вопрос открыт — только у ведущего. */
    data class Progress(val answered: Int, val of: Int, val counts: Map<String, Int>)

    fun progress(): Progress {
        val counts = LinkedHashMap<String, Int>()
        var answered = 0
        for (p in players.values) p.answer?.let { a -> answered++; for (id in a) counts[id] = (counts[id] ?: 0) + 1 }
        return Progress(answered, players.size, counts)
    }

    /** Имена ответивших: на экране ведущего они зеленеют. */
    fun answeredNames(): List<String> = players.values.filter { it.answer != null }.map { it.name }

    fun playerNames(): List<String> = players.values.map { it.name }

    companion object {
        /** Предел игроков: класс — около 60; отдельно от 32 зрителей трансляции. */
        const val MAX_PLAYERS = 60
        const val MAX_NAME = 24

        /** Имя: без управляющих знаков, пробелы по краям и повторы схлопнуты, 1–24 знака. */
        fun cleanName(raw: String): String? {
            val s = raw.filter { !it.isISOControl() }.trim().replace(Regex("\\s+"), " ")
            return s.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
        }
    }
}
