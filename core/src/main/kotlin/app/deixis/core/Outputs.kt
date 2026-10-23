package app.deixis.core

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * Выводы приёмника, у которого их несколько (Deixis для компьютера): мониторы со своей раскладкой
 * и экраны Deixis в сети, на которые он пересылает показ. Пульт (веб-пульт на этом компьютере
 * или в браузере телефона) управляет ими через /api/outputs.
 */
@Serializable
enum class OutputLayout {
    /** Зал: слайд целиком. */
    HALL,
    /** Сцена и суфлёр: текущий слайд, следующий, часы. */
    STAGE,
    /** Титры: текст нижней третью на чёрном (для проектора поверх сцены). */
    LOWER_THIRD,
    /** Хромакей: текст на зелёном — для трансляции (OBS, vMix). */
    CHROMA,
}

@Serializable
data class MonitorOutput(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    /** Монитор, на котором стоит окно пульта: на него по умолчанию не выводится. */
    val primary: Boolean,
    val layout: OutputLayout = OutputLayout.HALL,
    val enabled: Boolean = false,
)

@Serializable
data class NetworkOutput(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val platform: String = "",
    /** online, sync (передаются файлы), offline, pin (нужен PIN), idle (не подключён). */
    val state: String = "idle",
    val linked: Boolean = false,
)

@Serializable
data class OutputsState(
    val monitors: List<MonitorOutput> = emptyList(),
    val network: List<NetworkOutput> = emptyList(),
    /** Ссылка «экран в браузере»: любой браузер становится экраном вывода. */
    val webScreenUrl: String? = null,
)

@Serializable data class MonitorRequest(val id: String, val enabled: Boolean, val layout: OutputLayout = OutputLayout.HALL)
@Serializable data class LinkRequest(val id: String, val pin: String? = null, val host: String? = null, val port: Int? = null)
@Serializable data class UnlinkRequest(val id: String)
@Serializable data class LinkResult(val ok: Boolean, val error: String? = null, val needPin: Boolean = false)

/** Платформа с несколькими выводами. На ТВ и телефоне её нет: там /api/outputs отвечает 501. */
interface OutputsHost {
    fun state(): OutputsState
    fun setMonitor(req: MonitorRequest)
    /** Подключить экран в сети к пересылке показа; при первом подключении нужен PIN. */
    suspend fun link(req: LinkRequest): LinkResult
    fun unlink(id: String)
}

internal fun Route.outputsRoutes(core: ReceiverCore, authorized: ApplicationCall.() -> Boolean) {
    get("/api/outputs") {
        if (!call.authorized()) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        val host = core.outputs ?: return@get call.respondText("", status = HttpStatusCode.NotImplemented)
        call.respondText(DeixisJson.encodeToString(OutputsState.serializer(), host.state()), ContentType.Application.Json)
    }
    post("/api/outputs/monitor") {
        if (!call.authorized()) return@post call.respondText("", status = HttpStatusCode.Unauthorized)
        val host = core.outputs ?: return@post call.respondText("", status = HttpStatusCode.NotImplemented)
        host.setMonitor(DeixisJson.decodeFromString(MonitorRequest.serializer(), call.receiveText()))
        call.respondText(DeixisJson.encodeToString(OutputsState.serializer(), host.state()), ContentType.Application.Json)
    }
    post("/api/outputs/link") {
        if (!call.authorized()) return@post call.respondText("", status = HttpStatusCode.Unauthorized)
        val host = core.outputs ?: return@post call.respondText("", status = HttpStatusCode.NotImplemented)
        val r = host.link(DeixisJson.decodeFromString(LinkRequest.serializer(), call.receiveText()))
        call.respondText(DeixisJson.encodeToString(LinkResult.serializer(), r), ContentType.Application.Json)
    }
    post("/api/outputs/unlink") {
        if (!call.authorized()) return@post call.respondText("", status = HttpStatusCode.Unauthorized)
        val host = core.outputs ?: return@post call.respondText("", status = HttpStatusCode.NotImplemented)
        host.unlink(DeixisJson.decodeFromString(UnlinkRequest.serializer(), call.receiveText()).id)
        call.respondText(DeixisJson.encodeToString(OutputsState.serializer(), host.state()), ContentType.Application.Json)
    }
}
