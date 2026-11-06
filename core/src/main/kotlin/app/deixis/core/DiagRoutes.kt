package app.deixis.core

import app.deixis.diag.Check
import app.deixis.diag.CheckResult
import app.deixis.diag.Checks
import app.deixis.diag.DiagReport
import app.deixis.diag.Diagnostics
import app.deixis.diag.Lang
import app.deixis.diag.Log
import app.deixis.diag.Status
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Диагностика экрана для пульта: GET /api/diagnostics?lang=ru — отчёт (общие проверки, проверки экрана и площадки),
 * GET /api/diagnostics/log — журнал экрана файлом. Только сопряжённым пультам.
 */
internal fun Route.diagRoutes(core: ReceiverCore, authorized: ApplicationCall.() -> Boolean) {
    get("/api/diagnostics") {
        if (!call.authorized()) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        val report = core.diagnose(call.request.queryParameters["lang"])
        call.respondText(DeixisJson.encodeToString(DiagReport.serializer(), report), ContentType.Application.Json)
    }
    get("/api/diagnostics/log") {
        if (!call.authorized()) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        call.response.header("Content-Disposition", "attachment; filename=\"deixis-${core.info.platform}-log.txt\"")
        call.respondText(core.logText(), ContentType.Text.Plain.withParameter("charset", "utf-8"))
    }
}

/** Отчёт диагностики этого экрана на языке [lang]. */
suspend fun ReceiverCore.diagnose(lang: String?): DiagReport =
    Diagnostics.run(screenChecks() + diagChecks(), Lang(lang), info.name, "Deixis ${info.appVersion} · ${info.platform}")

/** Журнал этого экрана целиком: файл, если пишется, иначе последние записи из памяти. */
fun ReceiverCore.logText(): String =
    Log.logFile?.takeIf { it.isFile }?.let { f -> runCatching { f.readText() }.getOrNull() }
        ?: Log.recent().joinToString("\n")

/** Проверки самого экрана: принимает ли, кто на связи, что в зале. */
private fun ReceiverCore.screenChecks(): List<Check> = listOf(
    Check("screen", { it.t("Экран", "Ekran", "Screen") }) { l ->
        val peers = peers.state.value.connected.size
        CheckResult("screen", l.t("Экран", "Ekran", "Screen"), Status.OK,
            l.t("принимает показ; пультов на связи: $peers; зрителей: ${state.value.viewers}", "ko‘rsatuvni qabul qiladi; ulangan pultlar: $peers; tomoshabinlar: ${state.value.viewers}", "accepting shows; remotes connected: $peers; viewers: ${state.value.viewers}"))
    },
    Check("pairing", { it.t("Сопряжённые пульты", "Ulangan pultlar", "Paired remotes") }) { l ->
        val n = pairing.senders().size
        CheckResult("pairing", l.t("Сопряжённые пульты", "Ulangan pultlar", "Paired remotes"), Status.OK, l.t("запомнено: $n", "eslab qolingan: $n", "remembered: $n"))
    },
    Check("error", { it.t("Последняя ошибка показа", "Ko‘rsatuvdagi oxirgi xato", "Last show error") }) { l ->
        val err = state.value.error
        if (err == null) CheckResult("error", l.t("Ошибки показа", "Ko‘rsatuv xatolari", "Show errors"), Status.OK, l.t("нет", "yo‘q", "none"))
        else CheckResult("error", l.t("Ошибка показа", "Ko‘rsatuv xatosi", "Show error"), Status.WARN, err)
    },
)
