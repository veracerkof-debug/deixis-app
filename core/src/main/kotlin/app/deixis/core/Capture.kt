package app.deixis.core

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Что можно транслировать с этого компьютера: монитор целиком (в том числе HDMI-заглушка или виртуальный экран,
 * куда Holyrics, PowerPoint и другие программы выводят свой «проектор»). [hall] — на нём уже зал Deixis:
 * его снимать нельзя, картинка ушла бы в бесконечное зеркало.
 */
@Serializable
data class CaptureSource(
    val id: String, val name: String, val width: Int, val height: Int, val hall: Boolean = false,
    /** monitor — монитор целиком; window — окно программы; virtual — виртуальный экран Deixis; newVirtual — создать его. */
    val kind: String = MONITOR,
) {
    companion object {
        const val MONITOR = "monitor"
        const val WINDOW = "window"
        const val VIRTUAL = "virtual"
        const val NEW_VIRTUAL = "newVirtual"
        /** Особые id для [CaptureRequest]: создать виртуальный экран и транслировать его; убрать его. */
        const val ID_NEW_VIRTUAL = "virtual:new"
        const val ID_REMOVE_VIRTUAL = "virtual:remove"
    }
}

/** Источники и тот, что транслируется сейчас ([active]); [error] — почему не удалось начать. */
@Serializable
data class CaptureState(
    val sources: List<CaptureSource>, val active: String? = null, val error: String? = null,
    /** Подсказка платформы: например, как получить виртуальный экран на Windows. */
    val note: String? = null,
)

@Serializable
data class CaptureRequest(val id: String? = null)

/** Трансляция экрана компьютера; есть только у Deixis для компьютера. */
interface CaptureHost {
    fun state(): CaptureState
    /** Начать трансляцию источника [id]; null — остановить. */
    fun select(id: String?)
}

/** Пульт выбирает, какой монитор компьютера транслировать (`/api/capture`); 501 — у этого экрана нет такой возможности. */
internal fun Route.captureRoutes(core: ReceiverCore, authorized: ApplicationCall.() -> Boolean) {
    suspend fun ApplicationCall.reply(st: CaptureState) =
        respondText(DeixisJson.encodeToString(CaptureState.serializer(), st), ContentType.Application.Json)
    get("/api/capture") {
        if (!call.authorized()) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        val host = core.capture ?: return@get call.respondText("", status = HttpStatusCode.NotImplemented)
        call.reply(withContext(Dispatchers.IO) { host.state() })
    }
    post("/api/capture") {
        if (!call.authorized()) return@post call.respondText("", status = HttpStatusCode.Unauthorized)
        val host = core.capture ?: return@post call.respondText("", status = HttpStatusCode.NotImplemented)
        val req = runCatching { DeixisJson.decodeFromString(CaptureRequest.serializer(), call.receiveText()) }.getOrNull()
            ?: return@post call.respondText("", status = HttpStatusCode.BadRequest)
        call.reply(withContext(Dispatchers.IO) { host.select(req.id); host.state() })
    }
}
