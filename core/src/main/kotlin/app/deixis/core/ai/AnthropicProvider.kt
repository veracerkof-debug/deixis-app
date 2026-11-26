package app.deixis.core.ai

import app.deixis.core.DeixisJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Поставщик с протоколом Anthropic Messages. Те же правила ответа, что у [OpenAiProvider]: один JSON-объект,
 * инструменты — вызовами по правилам инструкции. Адрес — без `/v1` (по умолчанию https://api.anthropic.com).
 */
class AnthropicProvider(private val config: ProviderConfig, private val client: HttpClient = HttpClient(CIO)) : ModelProvider {
    override fun next(system: String, transcript: List<Message>): ModelTurn = runBlocking {
        try {
            val base = config.baseUrl.ifBlank { "https://api.anthropic.com" }.trimEnd('/')
            val r = client.post("$base/v1/messages") {
                header("x-api-key", config.apiKey)
                header("anthropic-version", VERSION)
                contentType(ContentType.Application.Json)
                setBody(DeixisJson.encodeToString(JsonObject.serializer(), buildRequest(config.model, system, transcript)))
            }
            if (r.status != HttpStatusCode.OK) return@runBlocking ModelTurn.Say("Провайдер ответил ошибкой ${r.status.value}")
            val text = extractContent(r.bodyAsText()) ?: return@runBlocking ModelTurn.Say("Пустой ответ провайдера")
            OpenAiProvider.parseTurn(text)
        } catch (e: Exception) {
            ModelTurn.Say("Нет связи с провайдером: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
        }
    }

    fun close() = client.close()

    companion object {
        const val VERSION = "2023-06-01"

        /** Сообщения должны чередоваться «человек — модель»: подряд идущие одной роли склеиваются. Данные идут как слова человека с пометкой. */
        fun buildRequest(model: String, system: String, transcript: List<Message>): JsonObject {
            val merged = ArrayList<Pair<String, String>>()
            for (m in transcript) {
                val role = if (m.role == "assistant") "assistant" else "user"
                val text = if (m.role == "data") "ДАННЫЕ: ${m.text}" else m.text
                if (merged.isNotEmpty() && merged.last().first == role) merged[merged.lastIndex] = role to (merged.last().second + "\n\n" + text)
                else merged += role to text
            }
            return buildJsonObject {
                put("model", model)
                put("max_tokens", 4096)
                put("system", system + "\n\n" + OpenAiProvider.PROTOCOL)
                put("messages", buildJsonArray { for ((role, text) in merged) add(buildJsonObject { put("role", role); put("content", text) }) })
            }
        }

        fun extractContent(body: String): String? = try {
            DeixisJson.parseToJsonElement(body).jsonObject["content"]?.jsonArray
                ?.firstOrNull { (it.jsonObject["type"] as? JsonPrimitive)?.contentOrNull == "text" }?.jsonObject?.get("text")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
        } catch (e: Exception) { null }
    }
}
