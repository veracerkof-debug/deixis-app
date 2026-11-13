package app.deixis.core.quiz

import app.deixis.core.Content
import app.deixis.core.QuizPhase
import app.deixis.core.TextContent
import app.deixis.core.quizContent
import app.deixis.core.quizMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

// ——— Сообщения страницы игрока (/play/ws) ———

/** От браузера игрока: вход по имени, возврат по токену, ответ. Больше он ничего не шлёт. */
@Serializable
sealed interface PlayIn {
    @Serializable @SerialName("join") data class Join(val name: String) : PlayIn
    @Serializable @SerialName("rejoin") data class Rejoin(val token: String) : PlayIn
    @Serializable @SerialName("answer") data class Answer(val choices: List<String>) : PlayIn
}

@Serializable
data class PlayOption(val id: String, val text: String, val mark: String)

@Serializable
data class PlayRow(val name: String, val score: Int, val rank: Int)

/** К браузеру игрока. Верные ответы есть только в [Reveal]: до результата телефон их не знает. */
@Serializable
sealed interface PlayOut {
    @Serializable @SerialName("joined") data class Joined(val token: String, val name: String, val title: String, val lang: String) : PlayOut
    @Serializable @SerialName("lobby") data class Lobby(val players: Int) : PlayOut
    @Serializable @SerialName("question")
    data class Question(
        val index: Int, val total: Int, val text: String, val options: List<PlayOption>, val multiple: Boolean,
        val remainingMs: Long, val totalMs: Long, val picked: List<String> = emptyList(),
    ) : PlayOut
    @Serializable @SerialName("accepted") data class Accepted(val choices: List<String>) : PlayOut
    @Serializable @SerialName("reveal")
    data class Reveal(
        val correct: List<String>, val picked: List<String>, val right: Boolean, val earned: Int, val score: Int, val rank: Int,
        val explanation: String,
        /** Вопрос целиком: игрок мог вернуться после обрыва уже на результате и вопроса не видел. */
        val index: Int, val total: Int, val text: String, val options: List<PlayOption>,
    ) : PlayOut
    @Serializable @SerialName("board") data class Board(val top: List<PlayRow>, val me: PlayRow?) : PlayOut
    @Serializable @SerialName("end") data class End(val top: List<PlayRow>, val me: PlayRow?, val players: Int) : PlayOut
    /** Код: bad_name, name_taken, full, closed, unknown_token, bad_choice, not_open. */
    @Serializable @SerialName("error") data class Error(val code: String) : PlayOut
    /** Игру закрыл ведущий. */
    @Serializable @SerialName("closed") data object Closed : PlayOut
}

/** Для пульта ведущего: ход игры. Верные ответы здесь есть всегда: ведущий их знает. */
@Serializable
data class QuizHostState(
    val phase: GamePhase,
    val title: String,
    val index: Int,
    val total: Int,
    val players: List<String>,
    val answered: List<String>,
    val counts: Map<String, Int>,
    val top: List<PlayRow>,
    val joinUrl: String?,
    val remainingMs: Long,
    val limitMs: Long,
)

/** Подписи слайдов зала на языке самой викторины (`language` в файле). Интерфейс пульта к ним не относится. */
object QuizTexts {
    private fun pick(lang: String, ru: String, uz: String, en: String) = when (lang) { "uz" -> uz; "en" -> en; else -> ru }
    fun trueText(lang: String) = pick(lang, "Верно", "To‘g‘ri", "True")
    fun falseText(lang: String) = pick(lang, "Неверно", "Noto‘g‘ri", "False")
    fun joinHall(lang: String, url: String?, players: Int) =
        pick(lang, "Присоединяйтесь\n", "Qo‘shiling\n", "Join the game\n") + (url?.let { "$it\n" } ?: "") +
            pick(lang, "Игроков: $players", "O‘yinchilar: $players", "Players: $players")
    fun boardHall(lang: String, top: List<PlayRow>, final: Boolean) =
        pick(lang, if (final) "Игра окончена" else "Таблица", if (final) "O‘yin tugadi" else "Jadval", if (final) "Game over" else "Leaderboard") +
            top.joinToString("") { "\n${it.rank}. ${it.name} — ${it.score}" }
}

/**
 * Центр живой игры на приёмнике: держит [QuizGame], показывает ход в зале ([show]), рассылает сообщения игрокам
 * и закрывает вопрос по времени. Команды ведущего — `open`, `start`, `closeQuestion`, `board`, `next`, `end` — приходят
 * от оператора с токеном; игроки пишут только свои ответы. Сбой пульта игру не останавливает: время и очки здесь.
 */
class QuizHub(
    private val scope: CoroutineScope,
    private val show: (Content) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var game: QuizGame? = null
    private var joinUrl: String? = null
    private var timer: Job? = null

    /** Подписчики страницы игрока: id соединения → очередь сообщений. Переполнение выбрасывает старые: отставший догонит снимком. */
    private class Link(val out: Channel<PlayOut>, var token: String? = null)
    private val links = ConcurrentHashMap<Long, Link>()
    private var nextLink = 0L

    val active: Boolean get() = synchronized(lock) { game != null }

    // ——— Ведущий ———

    /** Открыть игру из файла. Файл проверяется; игра встаёт в ожидание игроков. Прежняя закрывается. */
    fun open(json: String, joinUrl: String?): QuizReport {
        val report = QuizValidator.validate(json)
        val quiz = report.quiz ?: return report
        synchronized(lock) {
            endLocked()
            game = QuizGame(quiz, clock)
            this.joinUrl = joinUrl
            showLobby()
        }
        return report
    }

    fun start(): Boolean = synchronized(lock) {
        val g = game ?: return false
        if (!g.start()) return false
        questionOpened(g)
        true
    }

    fun closeQuestion(): Boolean = synchronized(lock) {
        val g = game ?: return false
        closeLocked(g)
    }

    /** Показать таблицу в зале (между вопросами). */
    fun board(): Boolean = synchronized(lock) {
        val g = game ?: return false
        if (g.phase != GamePhase.REVEAL && g.phase != GamePhase.FINISHED) return false
        show(TextContent(QuizTexts.boardHall(g.quiz.language, g.standings(5).map(::row), g.phase == GamePhase.FINISHED)))
        for ((_, l) in links) l.token?.let { t -> l.out.trySend(PlayOut.Board(g.standings(5).map(::row), me(g, t))) }
        true
    }

    /** К следующему вопросу или, если вопросы кончились, к итогу. */
    fun next(): Boolean = synchronized(lock) {
        val g = game ?: return false
        if (g.phase != GamePhase.REVEAL) return false
        if (g.next()) questionOpened(g) else finishedLocked(g)
        true
    }

    fun end() = synchronized(lock) { endLocked() }

    fun hostState(): QuizHostState? = synchronized(lock) {
        val g = game ?: return null
        val pr = g.progress()
        QuizHostState(g.phase, g.quiz.title, g.index, g.total, g.playerNames(), g.answeredNames(), pr.counts, g.standings(5).map(::row), joinUrl,
            g.remainingMs() ?: 0, g.limitMillis)
    }

    // ——— Игроки ———

    /** Новое соединение страницы игрока. Возвращает id и очередь сообщений для отправки в браузер. */
    fun connect(): Pair<Long, Channel<PlayOut>> = synchronized(lock) {
        val id = ++nextLink
        val l = Link(Channel(CAPACITY, BufferOverflow.DROP_OLDEST))
        links[id] = l
        id to l.out
    }

    fun disconnect(id: Long) { links.remove(id)?.out?.close() }

    fun handle(id: Long, msg: PlayIn) = synchronized(lock) {
        val link = links[id] ?: return@synchronized
        val g = game
        if (g == null) { link.out.trySend(PlayOut.Closed); return@synchronized }
        when (msg) {
            is PlayIn.Join -> when (val r = g.join(msg.name)) {
                is JoinResult.Ok -> {
                    link.token = r.token
                    link.out.trySend(PlayOut.Joined(r.token, r.name, g.quiz.title, g.quiz.language))
                    // Число игроков получают все, и этот игрок тоже: отдельно ему не шлётся.
                    refreshLobby(g)
                }
                JoinResult.BadName -> link.out.trySend(PlayOut.Error("bad_name"))
                JoinResult.NameTaken -> link.out.trySend(PlayOut.Error("name_taken"))
                JoinResult.Full -> link.out.trySend(PlayOut.Error("full"))
                JoinResult.Closed -> link.out.trySend(PlayOut.Error("closed"))
            }
            is PlayIn.Rejoin -> {
                val name = g.rejoin(msg.token)
                if (name == null) link.out.trySend(PlayOut.Error("unknown_token"))
                else {
                    link.token = msg.token
                    link.out.trySend(PlayOut.Joined(msg.token, name, g.quiz.title, g.quiz.language))
                    snapshot(g, msg.token)?.let { link.out.trySend(it) }
                }
            }
            is PlayIn.Answer -> {
                val t = link.token
                if (t == null) link.out.trySend(PlayOut.Error("unknown_token"))
                else when (g.answer(t, msg.choices.toSet())) {
                    AnswerResult.ACCEPTED -> link.out.trySend(PlayOut.Accepted(msg.choices))
                    AnswerResult.NOT_OPEN -> link.out.trySend(PlayOut.Error("not_open"))
                    AnswerResult.BAD_CHOICE -> link.out.trySend(PlayOut.Error("bad_choice"))
                    AnswerResult.UNKNOWN_PLAYER -> link.out.trySend(PlayOut.Error("unknown_token"))
                }
            }
        }
    }

    // ——— Внутреннее: вызывается под lock ———

    private fun row(s: Standing) = PlayRow(s.name, s.score, s.rank)

    private fun me(g: QuizGame, token: String): PlayRow? {
        val name = g.rejoin(token) ?: return null
        return g.standings().firstOrNull { it.name == name }?.let(::row)
    }

    private fun showLobby() {
        val g = game ?: return
        show(TextContent(QuizTexts.joinHall(g.quiz.language, joinUrl, g.playerCount)))
    }

    private fun refreshLobby(g: QuizGame) {
        showLobby()
        for ((_, l) in links) if (l.token != null) l.out.trySend(PlayOut.Lobby(g.playerCount))
    }

    private fun questionOpened(g: QuizGame) {
        val q = g.question ?: return
        val limit = g.limitMillis
        val lang = g.quiz.language
        show(quizContent(q, g.index, g.total, (limit / 1000).toInt().takeIf { it > 0 }, QuizPhase.QUESTION,
            trueText = QuizTexts.trueText(lang), falseText = QuizTexts.falseText(lang)).copy(running = limit > 0))
        for ((_, l) in links) l.token?.let { t -> snapshot(g, t)?.let(l.out::trySend) }
        timer?.cancel()
        if (limit > 0) {
            val idx = g.index
            timer = scope.launch {
                delay(limit + QuizGame.ANSWER_GRACE_MS + 100)
                synchronized(lock) { val cur = game; if (cur != null && cur.index == idx && cur.phase == GamePhase.QUESTION) closeLocked(cur) }
            }
        }
    }

    private fun closeLocked(g: QuizGame): Boolean {
        if (!g.closeQuestion()) return false
        timer?.cancel()
        val q = g.question ?: return false
        val lang = g.quiz.language
        show(quizContent(q, g.index, g.total, (g.limitMillis / 1000).toInt().takeIf { it > 0 }, QuizPhase.REVEAL,
            trueText = QuizTexts.trueText(lang), falseText = QuizTexts.falseText(lang)))
        for ((_, l) in links) l.token?.let { t -> snapshot(g, t)?.let(l.out::trySend) }
        return true
    }

    private fun finishedLocked(g: QuizGame) {
        val top = g.standings(5).map(::row)
        show(TextContent(QuizTexts.boardHall(g.quiz.language, top, true)))
        for ((_, l) in links) l.token?.let { t -> l.out.trySend(PlayOut.End(top, me(g, t), g.playerCount)) }
    }

    private fun endLocked() {
        timer?.cancel()
        if (game != null) for ((_, l) in links) l.out.trySend(PlayOut.Closed)
        game = null
    }

    /** Что показывать игроку сейчас: для входа, возврата после обрыва и смены фазы. */
    private fun snapshot(g: QuizGame, token: String): PlayOut? = when (g.phase) {
        GamePhase.LOBBY -> PlayOut.Lobby(g.playerCount)
        GamePhase.QUESTION -> g.question?.let { q ->
            PlayOut.Question(g.index, g.total, q.text, playOptions(q, g.quiz.language), q.type == QuizTypes.MULTIPLE, g.remainingMs() ?: 0, g.limitMillis, g.answerOf(token).toList())
        }
        GamePhase.REVEAL -> g.question?.let { q ->
            val r = g.resultOf(token)
            PlayOut.Reveal(g.correctChoices().toList(), g.answerOf(token).toList(), r?.correct ?: false, r?.earned ?: 0, r?.score ?: 0, r?.rank ?: 0,
                if (g.quiz.settings.showExplanations) q.explanation else "", g.index, g.total, q.text, playOptions(q, g.quiz.language))
        }
        GamePhase.FINISHED -> PlayOut.End(g.standings(5).map(::row), me(g, token), g.playerCount)
    }

    private fun playOptions(q: QuizQuestion, lang: String): List<PlayOption> =
        if (q.type == QuizTypes.TRUE_FALSE) listOf(PlayOption(QuizTypes.CHOICE_TRUE, QuizTexts.trueText(lang), "A"), PlayOption(QuizTypes.CHOICE_FALSE, QuizTexts.falseText(lang), "B"))
        else q.options.mapIndexed { i, o -> PlayOption(o.id, o.text, quizMark(i)) }

    private companion object {
        const val CAPACITY = 32
    }
}
