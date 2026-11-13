package app.deixis.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import app.deixis.core.quiz.IssueLevel
import app.deixis.core.quiz.QuizIssue
import app.deixis.core.quiz.QuizQuestion
import app.deixis.core.quiz.QuizTypes
import app.deixis.core.quizMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun LibItem.isQuizFile() = title.endsWith(".json", ignoreCase = true) && title.contains("quiz", ignoreCase = true)

/** «Урок»: открыть файл викторины и пройти её, пока вопросы идут на экраны. Ничего не сохраняется. */
@Composable
fun QuizSheet(c: Controller, ui: Ui) {
    val st by c.quiz.state.collectAsState()
    val host by c.quizHost.live.collectAsState()
    val s = st
    val h = host
    when {
        h != null -> QuizHostPlay(c, ui, h)
        s == null -> QuizOpen(c, ui)
        else -> QuizPlay(c, ui, s)
    }
}

@Composable
private fun QuizOpen(c: Controller, ui: Ui) {
    val data by c.data.collectAsState()
    val acts = LocalActs.current
    val platform = LocalHost.current.platform
    val scope = rememberCoroutineScope()
    var problems by remember { mutableStateOf<List<QuizIssue>>(emptyList()) }
    var unreadable by remember { mutableStateOf(false) }
    // Файл прошёл проверку: оператор выбирает, как играть — самому или живой игрой с телефонами в зале.
    var pending by remember { mutableStateOf<Pair<app.deixis.core.quiz.QuizFile, String>?>(null) }
    var liveError by remember { mutableStateOf<String?>(null) }

    fun open(item: LibItem) {
        problems = emptyList(); unreadable = false
        val uri = item.uri
        if (uri == null) { unreadable = true; return }
        scope.launch {
            // Чтение и разбор файла — не на главном потоке.
            val text = withContext(Dispatchers.IO) { runCatching { platform.open(uri)?.let { c.quiz.readText(it) } }.getOrNull() }
            val report = text?.let { app.deixis.core.quiz.QuizValidator.validate(it) }
            when {
                report == null -> unreadable = true
                report.quiz != null -> pending = report.quiz!! to text
                else -> problems = report.errors
            }
        }
    }

    pending?.let { (file, json) ->
        GHead(file.title)
        Group {
            Cell(L.quizSolo, null, "play", onClick = { pending = null; c.quiz.start(file) })
            Sep()
            Cell(L.quizLive, if (c.canHostQuiz()) L.quizLiveSub else L.quizNoScreen, "phone", onClick = if (c.canHostQuiz()) ({
                liveError = null
                scope.launch {
                    when (val r = c.quizHost.open(file, json)) {
                        is app.deixis.core.SenderClient.QuizOpenResult.Ok -> pending = null
                        is app.deixis.core.SenderClient.QuizOpenResult.Invalid -> { pending = null; problems = r.issues }
                        else -> liveError = L.quizLiveFailed
                    }
                }
            }) else null)
        }
        liveError?.let { Note(it) }
        return
    }

    val recent = data.items.filter { it.isQuizFile() }.take(8)
    if (recent.isNotEmpty()) {
        GHead(L.quizRecent)
        Group {
            recent.forEachIndexed { i, it ->
                if (i > 0) Sep()
                Cell(it.title.removeSuffix(".json").removeSuffix(".deixis-quiz"), null, "pres", onClick = { open(it) })
            }
        }
    }
    Btn(L.phoneFilesDots, icon = "file") { acts.pick(arrayOf("application/json", "text/plain", "*/*"), false) { open(it) } }
    if (unreadable) Note(L.quizNotFound)
    if (problems.isNotEmpty()) {
        GHead(L.quizBad)
        Group {
            problems.take(6).forEachIndexed { i, it ->
                if (i > 0) Sep()
                Cell(L.quizIssue(it.code, it.path), null, "info", chevron = null)
            }
        }
    }
    Note(L.quizNote)
}

@Composable
private fun QuizPlay(c: Controller, ui: Ui, s: QuizSession.State) {
    val p = P.current
    val q = s.question
    if (s.finished || q == null) { QuizDone(c, ui, s); return }
    Column(verticalArrangement = Arrangement.spacedBy(T.gap)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Txt(L.quizQuestionN(s.index + 1, s.total), T.fSub, p.l2, modifier = Modifier.weight(1f))
            Tag(L.quizScoreOf(s.score.earned, s.score.max), false)
        }
        QuizTimeBar(s.endsAt, s.limitSec)
        Txt(q.text, T.fMark, p.ink, FontWeight.SemiBold)
        if (q.type == QuizTypes.MULTIPLE && s.result == null) Txt(L.quizPickMany, T.fSub, p.l2)
        QuizOptions(c, q, s)
        s.result?.let { r ->
            val head = when { s.timedOut -> L.quizTimeUp; r.correct -> L.quizRight; else -> L.quizWrong }
            Group {
                Cell(head, q.explanation.takeIf { it.isNotBlank() && c.quizShowExplanations(s) }, if (r.correct) "check" else "close", chevron = null)
            }
            Btn(if (s.index + 1 < s.total) L.quizNext else L.quizToResult, kind = BtnKind.TINT) { c.quiz.next() }
        } ?: Btn(L.quizAnswer, kind = BtnKind.TINT, enabled = s.picked.isNotEmpty()) { c.quiz.answer() }
    }
}

@Composable
private fun QuizOptions(c: Controller, q: QuizQuestion, s: QuizSession.State) {
    val p = P.current
    val opts = if (q.type == QuizTypes.TRUE_FALSE) listOf(QuizTypes.CHOICE_TRUE to L.quizTrue, QuizTypes.CHOICE_FALSE to L.quizFalse)
    else q.options.map { it.id to it.text }
    val right = s.result?.correctChoices.orEmpty()
    Group {
        opts.forEachIndexed { i, (id, text) ->
            if (i > 0) Sep()
            val picked = id in s.picked
            // Смысл не только цветом: буква варианта и значок «верно» или «неверно» после ответа.
            val mark = when {
                s.result == null -> null
                id in right -> p.ok
                picked -> p.red
                else -> null
            }
            Cell("${quizMark(i)}   $text", null, null, open = picked && s.result == null, mark = mark, chevron = null,
                onClick = if (s.result == null) ({ c.quiz.toggle(id) }) else null) {
                when {
                    s.result != null && id in right -> Icon("check", p.ok)
                    s.result != null && picked -> Icon("close", p.red)
                    picked -> Icon("check", p.tint)
                }
            }
        }
    }
}

/** Полоса оставшегося времени: идёт от часов пульта несколько раз в секунду. */
@Composable
private fun QuizTimeBar(endsAt: Long, limitSec: Int?) {
    if (endsAt == 0L || limitSec == null) return
    val p = P.current
    var now by remember(endsAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt) { while (System.currentTimeMillis() < endsAt) { now = System.currentTimeMillis(); delay(200) }; now = endsAt }
    val frac = ((endsAt - now).toFloat() / (limitSec * 1000f)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(T.seekTrack).clip(RoundedCornerShape(T.rPill)).background(p.fill2)) {
        Box(Modifier.fillMaxWidth(frac).height(T.seekTrack).clip(RoundedCornerShape(T.rPill)).background(p.tint))
    }
}

@Composable
private fun QuizDone(c: Controller, ui: Ui, s: QuizSession.State) {
    val p = P.current
    val sc = s.score
    Column(verticalArrangement = Arrangement.spacedBy(T.gap)) {
        Txt(L.quizScoreOf(sc.earned, sc.max), T.fTitle, p.ink, FontWeight.Bold, align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Txt("${sc.percent} %", T.fBody, p.l2, align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        sc.passed?.let { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Tag(if (it) L.quizPassed else L.quizNotPassed, !it) } }
        Note(L.quizNoSave)
        Btn(L.quizAgain, kind = BtnKind.TINT) { c.quiz.start(s.file) }
        Btn(L.quizClose) { c.quiz.close(); ui.closeAll() }
    }
}

/** Ведущий живой игры: код и адрес, игроки, ход ответов, команды. Игра идёт на экране, пульт её только ведёт. */
@Composable
private fun QuizHostPlay(c: Controller, ui: Ui, live: QuizHostSession.Live) {
    val p = P.current
    val st = live.state
    val file = live.file
    if (st == null) { Note(L.offlineWait); return }
    Column(verticalArrangement = Arrangement.spacedBy(T.gap)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Txt(file.title, T.fBody, p.ink, FontWeight.SemiBold, modifier = Modifier.weight(1f), lines = 1)
            Tag(L.quizLive, true)
        }
        when (st.phase) {
            app.deixis.core.quiz.GamePhase.LOBBY -> {
                GHead(L.quizJoinAt)
                Group {
                    Cell(st.joinUrl ?: "—", null, "phone", chevron = null)
                }
                Txt(L.quizPlayersN(st.players.size), T.fBody, p.ink, FontWeight.SemiBold)
                Txt(if (st.players.isEmpty()) L.quizNoPlayers else st.players.joinToString(", "), T.fSub, p.l2)
                Note(L.quizJoinNote)
                Btn(L.quizStart, kind = BtnKind.TINT, enabled = st.players.isNotEmpty()) { c.quizHost.command("start") }
            }
            app.deixis.core.quiz.GamePhase.QUESTION -> {
                val q = file.questions.getOrNull(st.index)
                Txt(L.quizQuestionN(st.index + 1, st.total), T.fSub, p.l2)
                QuizHostTime(st, live.fetchedAt)
                q?.let { Txt(it.text, T.fMark, p.ink, FontWeight.SemiBold) }
                Txt(L.quizAnsweredOf(st.answered.size, st.players.size), T.fSub, p.l2)
                q?.let { QuizCounts(it, st) }
                Btn(L.quizCloseIn, kind = BtnKind.TINT) { c.quizHost.command("close") }
            }
            app.deixis.core.quiz.GamePhase.REVEAL -> {
                val q = file.questions.getOrNull(st.index)
                Txt(L.quizQuestionN(st.index + 1, st.total), T.fSub, p.l2)
                q?.let { Txt(it.text, T.fMark, p.ink, FontWeight.SemiBold); QuizCounts(it, st, showRight = true) }
                QuizBoard(st)
                Btn(L.quizBoard) { c.quizHost.command("board") }
                Btn(if (st.index + 1 < st.total) L.quizNext else L.quizToResult, kind = BtnKind.TINT) { c.quizHost.command("next") }
            }
            app.deixis.core.quiz.GamePhase.FINISHED -> {
                QuizBoard(st)
                Btn(L.quizBoard) { c.quizHost.command("board") }
            }
        }
        Btn(L.quizEndGame, kind = BtnKind.RED) { c.quizHost.end(); ui.closeAll() }
    }
}

/** Оставшееся время вопроса у ведущего: от последнего ответа экрана, плавно между опросами. */
@Composable
private fun QuizHostTime(st: app.deixis.core.quiz.QuizHostState, fetchedAt: Long) {
    if (st.limitMs <= 0) return
    val p = P.current
    var now by remember(fetchedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(fetchedAt) { while (true) { now = System.currentTimeMillis(); delay(200) } }
    val left = (st.remainingMs - (now - fetchedAt)).coerceAtLeast(0)
    val frac = (left.toFloat() / st.limitMs).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(T.seekTrack).clip(RoundedCornerShape(T.rPill)).background(p.fill2)) {
        Box(Modifier.fillMaxWidth(frac).height(T.seekTrack).clip(RoundedCornerShape(T.rPill)).background(p.tint))
    }
}

/** Сколько игроков выбрали каждый вариант. Верные отмечаются только в результате: пока вопрос открыт, ведущий их не подсказывает залу, а себе — тем более. */
@Composable
private fun QuizCounts(q: QuizQuestion, st: app.deixis.core.quiz.QuizHostState, showRight: Boolean = false) {
    val p = P.current
    val opts = if (q.type == QuizTypes.TRUE_FALSE) listOf(QuizTypes.CHOICE_TRUE to L.quizTrue, QuizTypes.CHOICE_FALSE to L.quizFalse)
    else q.options.map { it.id to it.text }
    val right = if (showRight) app.deixis.core.quiz.grade(q, emptySet()).correctChoices else emptySet()
    val max = (st.counts.values.maxOrNull() ?: 0).coerceAtLeast(1)
    Group {
        opts.forEachIndexed { i, (id, text) ->
            if (i > 0) Sep()
            val n = st.counts[id] ?: 0
            Cell("${quizMark(i)}   $text", null, null, chevron = null, mark = if (id in right) p.ok else null) {
                Txt("$n", T.fSub, if (id in right) p.ok else p.l2, FontWeight.SemiBold)
            }
            Box(Modifier.fillMaxWidth(n.toFloat() / max).height(T.seekTrack).background(if (id in right) p.ok else p.tint))
        }
    }
}

@Composable
private fun QuizBoard(st: app.deixis.core.quiz.QuizHostState) {
    if (st.top.isEmpty()) return
    GHead(L.quizBoardHead)
    Group {
        st.top.forEachIndexed { i, r ->
            if (i > 0) Sep()
            Cell("${r.rank}.  ${r.name}", null, null, chevron = null) { Txt("${r.score}", T.fBody, P.current.ink, FontWeight.SemiBold) }
        }
    }
}
