package app.deixis.core

import app.deixis.core.quiz.PlayIn
import app.deixis.core.quiz.PlayOut
import app.deixis.core.quiz.QuizGame
import app.deixis.core.quiz.QuizHostState
import app.deixis.core.quiz.QuizIssue
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.atomic.AtomicInteger

/** Сколько страниц игроков может быть подключено сразу: игроков до [QuizGame.MAX_PLAYERS], остальное — запас на переподключения. */
private const val MAX_PLAY_CONNECTIONS = QuizGame.MAX_PLAYERS + 20

/** Самое длинное сообщение от игрока: вход по имени или ответ — это десятки байт. */
private const val PLAY_FRAME_BYTES = 2048L

/**
 * Живая игра. Страница игрока `/play` и её канал `/play/ws` — по тому же ключу, что у зрителей, но канал свой: игрок
 * шлёт ответы, зритель `/view` только смотрит. Команды ведущего — адреса `/api/quiz/…` с токеном оператора, как остальные
 * управляющие запросы. Игра живёт на приёмнике ([ReceiverCore.quiz]), поэтому сбой пульта её не прерывает.
 */
internal fun Route.quizRoutes(core: ReceiverCore, authorized: ApplicationCall.() -> Boolean) {
    val connections = AtomicInteger()

    get("/play") {
        call.response.header("Cache-Control", "no-store")
        call.response.header("Referrer-Policy", "no-referrer")
        call.respondText(ReceiverCore::class.java.getResourceAsStream("/web/play.html")?.use { it.readBytes() }?.decodeToString() ?: "", ContentType.Text.Html)
    }

    webSocket("/play/ws") {
        if (!viewKeyOk(call.request.queryParameters["k"], core.viewKey)) {
            close(CloseReason(VIEW_BAD_KEY, "bad key"))
            return@webSocket
        }
        if (connections.incrementAndGet() > MAX_PLAY_CONNECTIONS) {
            connections.decrementAndGet()
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "busy"))
            return@webSocket
        }
        maxFrameSize = PLAY_FRAME_BYTES
        val (id, out) = core.quiz.connect()
        val sender = launch { for (m in out) send(Frame.Text(DeixisJson.encodeToString(PlayOut.serializer(), m))) }
        try {
            // Игры нет — сказать об этом сразу, а не молчать.
            if (!core.quiz.active) core.quiz.handle(id, PlayIn.Rejoin(""))
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val msg = try { DeixisJson.decodeFromString(PlayIn.serializer(), frame.readText()) } catch (e: SerializationException) { null } catch (e: IllegalArgumentException) { null }
                if (msg != null) core.quiz.handle(id, msg)
            }
        } finally {
            sender.cancel()
            core.quiz.disconnect(id)
            connections.decrementAndGet()
        }
    }

    // Ведущий: открыть игру из файла. Тело — JSON викторины; ?join= — адрес для входа, который покажет зал.
    post("/api/quiz/open") {
        if (!call.authorized()) return@post call.respondText("", status = HttpStatusCode.Unauthorized)
        val report = core.quiz.open(call.receiveText(), call.request.queryParameters["join"]?.take(300))
        if (report.ok) call.respondText("{}", ContentType.Application.Json)
        else call.respondText(DeixisJson.encodeToString(ListSerializer(QuizIssue.serializer()), report.errors), ContentType.Application.Json, HttpStatusCode.UnprocessableEntity)
    }
    for ((path, action) in listOf<Pair<String, () -> Boolean>>(
        "start" to core.quiz::start, "close" to core.quiz::closeQuestion, "board" to core.quiz::board, "next" to core.quiz::next,
    )) post("/api/quiz/$path") {
        if (!call.authorized()) return@post call.respondText("", status = HttpStatusCode.Unauthorized)
        call.respondText("", status = if (action()) HttpStatusCode.NoContent else HttpStatusCode.Conflict)
    }
    post("/api/quiz/end") {
        if (!call.authorized()) return@post call.respondText("", status = HttpStatusCode.Unauthorized)
        core.quiz.end()
        call.respondText("", status = HttpStatusCode.NoContent)
    }
    get("/api/quiz/state") {
        if (!call.authorized()) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        val st = core.quiz.hostState() ?: return@get call.respondText("", status = HttpStatusCode.NoContent)
        call.response.header("Cache-Control", "no-store")
        call.respondText(DeixisJson.encodeToString(QuizHostState.serializer(), st), ContentType.Application.Json)
    }
}
