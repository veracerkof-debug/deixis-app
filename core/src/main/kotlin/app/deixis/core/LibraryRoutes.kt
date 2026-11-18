package app.deixis.core

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Библиотека и сетлисты пульта этого компьютера для пульта на телефоне: GET /api/library — файл `library.json`
 * как есть (формат пульта, [DeixisJson]). Только сопряжённым пультам. 404 — на этом экране библиотеки нет.
 */
internal fun Route.libraryRoutes(core: ReceiverCore, authorized: ApplicationCall.() -> Boolean) {
    get("/api/library") {
        if (!call.authorized()) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        val text = runCatching { core.library() }.getOrNull()
            ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
        call.respondText(text, ContentType.Application.Json)
    }
}
