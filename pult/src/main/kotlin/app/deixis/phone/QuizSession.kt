package app.deixis.phone

import app.deixis.core.Content
import app.deixis.core.QuizPhase
import app.deixis.core.TextContent
import app.deixis.core.quiz.QuizFile
import app.deixis.core.quiz.QuizQuestion
import app.deixis.core.quiz.QuizReport
import app.deixis.core.quiz.QuizRun
import app.deixis.core.quiz.QuizScore
import app.deixis.core.quiz.QuizTypes
import app.deixis.core.quiz.QuizValidator
import app.deixis.core.quiz.QuestionResult
import app.deixis.core.quizContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * Прохождение викторины с пульта: вопрос идёт на экраны ([show]), оператор (или ученик с телефоном на ТВ) выбирает
 * ответ, результат и пояснение — на пульте и в зале. Ничего не сохраняется и не уходит в сеть, кроме самих слайдов.
 * Сбой связи экрана игру не прерывает: пульт ведёт её сам, зал просто не получает слайд ([show] не бросает).
 */
class QuizSession(
    private val scope: CoroutineScope,
    private val show: (Content) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class State(
        val file: QuizFile,
        val question: QuizQuestion?,
        val index: Int,
        val total: Int,
        val picked: Set<String>,
        /** Ответ принят (или время вышло): показан результат. */
        val result: QuestionResult?,
        /** Когда истекает время на вопрос по часам пульта; 0 — без ограничения или ответ принят. */
        val endsAt: Long,
        val limitSec: Int?,
        val score: QuizScore,
        val finished: Boolean,
        val timedOut: Boolean = false,
    )

    private var run: QuizRun? = null
    private var timer: Job? = null
    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> get() = _state

    /** Читает и проверяет файл: ошибки возвращаются с путями, на экран ничего не попадает. */
    fun load(stream: InputStream): QuizReport = QuizValidator.validate(readText(stream))

    /** Текст файла викторины (не больше предела + 1 байт: больший отвергнет проверка). */
    fun readText(stream: InputStream): String =
        String(stream.use { it.readNBytes(QuizValidator.MAX_FILE_BYTES.toInt() + 1) }, Charsets.UTF_8)

    fun start(file: QuizFile) {
        timer?.cancel()
        run = QuizRun(file)
        publish()
        showQuestion()
    }

    /** Выбор варианта: у «одного ответа» и «верно/неверно» заменяет прежний, у «нескольких» переключает. */
    fun toggle(id: String) {
        val st = _state.value ?: return
        val q = st.question ?: return
        if (st.result != null || st.finished) return
        val next = if (q.type == QuizTypes.MULTIPLE) (if (id in st.picked) st.picked - id else st.picked + id) else setOf(id)
        _state.value = st.copy(picked = next)
    }

    /** Принять ответ. Без выбора не принимает: пустой ответ — это «время вышло». */
    fun answer() {
        val st = _state.value ?: return
        if (st.picked.isEmpty() || st.result != null) return
        reveal(timedOut = false)
    }

    private fun reveal(timedOut: Boolean) {
        val r = run ?: return
        val st = _state.value ?: return
        val q = st.question ?: return
        timer?.cancel()
        val picked = if (timedOut) emptySet() else st.picked
        val res = r.answer(picked) ?: return
        _state.value = st.copy(picked = picked, result = res, endsAt = 0, score = r.score(), timedOut = timedOut)
        show(quizContent(q, st.index, st.total, st.limitSec, QuizPhase.REVEAL, picked, L.quizTrue, L.quizFalse))
    }

    fun next() {
        val r = run ?: return
        if (_state.value?.result == null) return
        r.next()
        publish()
        if (r.finished) showResult() else showQuestion()
    }

    fun close() {
        timer?.cancel()
        run = null
        _state.value = null
    }

    private fun showQuestion() {
        val r = run ?: return
        val q = r.current ?: return
        val limit = r.currentTimeLimitSec
        val st = _state.value ?: return
        val now = clock()
        _state.value = st.copy(endsAt = if (limit != null) now + limit * 1000L else 0)
        // Время на экране идёт от его часов: уходит остаток и признак «идёт», экран привязывает сам.
        show(quizContent(q, r.index, r.questions.size, limit, trueText = L.quizTrue, falseText = L.quizFalse).copy(running = limit != null))
        timer?.cancel()
        if (limit != null) timer = scope.launch {
            delay(limit * 1000L)
            reveal(timedOut = true)
        }
    }

    private fun showResult() {
        val r = run ?: return
        val s = r.score()
        show(TextContent(L.quizResultHall(s.earned, s.max, s.percent, s.passed)))
    }

    private fun publish() {
        val r = run ?: return
        _state.value = State(
            file = r.quiz, question = r.current, index = r.index, total = r.questions.size, picked = emptySet(), result = null,
            endsAt = 0, limitSec = r.currentTimeLimitSec, score = r.score(), finished = r.finished,
        )
    }
}
