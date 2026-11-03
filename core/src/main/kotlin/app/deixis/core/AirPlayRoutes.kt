package app.deixis.core

import app.deixis.core.airplay.AirPlayState
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

/** Пульт включает и выключает приём AirPlay на экране (`/api/airplay`). 501 — сборка без AirPlay. */
internal fun Route.airPlayRoutes(core: ReceiverCore, authorized: ApplicationCall.() -> Boolean) {
    suspend fun ApplicationCall.reply(enabled: Boolean) =
        respondText(DeixisJson.encodeToString(AirPlayState.serializer(), AirPlayState(enabled)), ContentType.Application.Json)
    get("/api/airplay") {
        if (!call.authorized()) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        val host = core.airPlay ?: return@get call.respondText("", status = HttpStatusCode.NotImplemented)
        call.reply(host.enabled)
    }
    post("/api/airplay") {
        if (!call.authorized()) return@post call.respondText("", status = HttpStatusCode.Unauthorized)
        val host = core.airPlay ?: return@post call.respondText("", status = HttpStatusCode.NotImplemented)
        val req = runCatching { DeixisJson.decodeFromString(AirPlayState.serializer(), call.receiveText()) }.getOrNull()
            ?: return@post call.respondText("", status = HttpStatusCode.BadRequest)
        // Запуск и остановка трогают сеть (mDNS): не в потоке ответа.
        withContext(Dispatchers.IO) { host.setEnabled(req.enabled) }
        call.reply(host.enabled)
    }
}
