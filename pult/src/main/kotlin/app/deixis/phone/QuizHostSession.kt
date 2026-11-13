package app.deixis.phone

import app.deixis.core.SenderClient
import app.deixis.core.quiz.QuizFile
import app.deixis.core.quiz.QuizHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ведущий живой игры. Игра, таймер и очки живут на экране (приёмнике): пульт только открывает игру, шлёт команды и
 * опрашивает ход. Если пульт пропал или закрыт, игра идёт дальше; вернувшись, он подхватывает её по состоянию экрана.
 */
class QuizHostSession(
    private val scope: CoroutineScope,
    private val client: () -> SenderClient?,
    private val playUrl: () -> String?,
) {
    /** Игра у ведущего: файл (для текстов вариантов) и последний ход с экрана. [fetchedAt] — когда он получен, для времени на пульте. */
    data class Live(val file: QuizFile, val state: QuizHostState?, val fetchedAt: Long = System.currentTimeMillis())

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> get() = _live
    private var poll: Job? = null

    /** Открыть игру на экране. Результат — для сообщения оператору. */
    suspend fun open(file: QuizFile, json: String): SenderClient.QuizOpenResult {
        val c = client() ?: return SenderClient.QuizOpenResult.Failed
        val r = withContext(Dispatchers.IO) { c.quizOpen(json, playUrl()) }
        if (r is SenderClient.QuizOpenResult.Ok) {
            _live.value = Live(file, null)
            refresh()
            startPolling()
        }
        return r
    }

    fun command(cmd: String) {
        scope.launch {
            val c = client() ?: return@launch
            withContext(Dispatchers.IO) { c.quizCommand(cmd) }
            refresh()
        }
    }

    /** Закончить игру: экран закрывает её у всех игроков. */
    fun end() {
        poll?.cancel()
        val c = client()
        _live.value = null
        if (c != null) scope.launch { withContext(Dispatchers.IO) { c.quizCommand("end") } }
    }

    private suspend fun refresh() {
        val cur = _live.value ?: return
        val c = client() ?: return
        val st = withContext(Dispatchers.IO) { c.quizState() }
        // Игры на экране уже нет (закрыта другим пультом или экран перезапущен): у ведущего она тоже кончается.
        _live.value = if (st == null && cur.state != null) null else cur.copy(state = st ?: cur.state, fetchedAt = System.currentTimeMillis())
    }

    private fun startPolling() {
        poll?.cancel()
        poll = scope.launch {
            while (_live.value != null) {
                delay(POLL_MS)
                refresh()
            }
        }
    }

    private companion object { const val POLL_MS = 800L }
}
