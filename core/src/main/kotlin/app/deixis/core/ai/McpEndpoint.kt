package app.deixis.core.ai

import app.deixis.core.DeixisJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * Способ Б (docs/dev/AI.md, раздел 3): внешняя нейросеть (агент с поддержкой MCP) управляет тем же каталогом через локальный адрес.
 * Только `127.0.0.1`, обязательный токен, включается вручную и живёт, пока открыта панель помощника. Инструменты — чтение цели
 * и `propose_change`: запись по-прежнему только предложением с «Принять» в приложении. Показа и залов в каталоге нет.
 */
class McpEndpoint<S : Any>(
    private val engine: ProposalEngine<S>,
    private val target: OpTarget<S>,
    private val instructions: String,
    /** Что-то изменилось (пришло предложение или применено): панель перерисовывается. */
    private val onChange: () -> Unit = {},
) {
    val token: String = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var server: EmbeddedServer<*, *>? = null
    var port: Int = 0
        private set

    val url: String get() = "http://127.0.0.1:$port/mcp"

    @Synchronized
    fun start() {
        if (server != null) return
        val s = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                post("/mcp") {
                    if (call.request.header("Authorization") != "Bearer $token") { call.respond(HttpStatusCode.Unauthorized); return@post }
                    val reply = handle(call.receiveText())
                    if (reply == null) call.respond(HttpStatusCode.Accepted)
                    else call.respondText(DeixisJson.encodeToString(JsonObject.serializer(), reply), ContentType.Application.Json)
                }
            }
        }.start(wait = false)
        port = runBlocking { s.engine.resolvedConnectors().first().port }
        server = s
    }

    @Synchronized
    fun stop() { server?.stop(100, 300); server = null }

    /** Разбор одного запроса JSON-RPC; null — уведомление без ответа. */
    internal fun handle(body: String): JsonObject? {
        val req = try { DeixisJson.parseToJsonElement(body).jsonObject } catch (e: Exception) { return error(JsonNull, -32700, "Не JSON") }
        val id = req["id"] ?: return null
        val method = (req["method"] as? JsonPrimitive)?.contentOrNull ?: return error(id, -32600, "Нет method")
        val params = req["params"] as? JsonObject
        return when (method) {
            "initialize" -> ok(id, buildJsonObject {
                put("protocolVersion", "2025-03-26")
                put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                put("serverInfo", buildJsonObject { put("name", "deixis"); put("version", "1") })
                put("instructions", instructions)
            })
            "ping" -> ok(id, buildJsonObject {})
            "tools/list" -> ok(id, buildJsonObject { put("tools", tools()) })
            "tools/call" -> {
                val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull ?: return error(id, -32602, "Нет name")
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val (text, failed) = call(name, args)
                ok(id, buildJsonObject {
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
                    put("isError", failed)
                })
            }
            else -> error(id, -32601, "Неизвестный метод $method")
        }
    }

    private fun call(name: String, args: JsonObject): Pair<String, Boolean> {
        if (name == "propose_change") {
            val r = proposeTool(engine, args)
            if (r.proposal != null || !r.failed) onChange()
            return r.text to r.failed
        }
        target.read(name, engine.state)?.let { return it to false }
        return "Нет такого инструмента «$name». Есть: ${(target.readTools + "propose_change").joinToString()}" to true
    }

    private fun tools(): JsonArray = buildJsonArray {
        for (t in target.readTools) add(tool(t, "Прочитать: $t (только чтение)", buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) }))
        add(tool("propose_change", "Предложить пакет правок. Ничего не меняется, пока человек не нажмёт «Принять» в приложении.", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("summary", buildJsonObject { put("type", "string"); put("description", "Коротко: что и зачем") })
                put("ops", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "object"); put("description", "Операция: поле op и её аргументы") }) })
            })
            put("required", buildJsonArray { add(JsonPrimitive("ops")) })
        }))
    }

    private fun tool(name: String, description: String, schema: JsonObject): JsonElement =
        buildJsonObject { put("name", name); put("description", description); put("inputSchema", schema) }

    private fun ok(id: JsonElement, result: JsonObject) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
    private fun error(id: JsonElement, code: Int, message: String) =
        buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("code", code); put("message", message) }) }
}
