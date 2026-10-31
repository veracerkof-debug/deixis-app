package app.deixis.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL

/**
 * Поиск Holyrics в сети. Holyrics себя не объявляет, но его API Server отвечает на «Auth» без токена:
 * выдаёт сеанс и nonce. Поэтому телефон по кнопке «Найти» опрашивает свою сеть /24 на порту API Server.
 * Токен так не узнать — его по-прежнему создают в Holyrics (это его защита).
 */
object HolyricsDiscovery {
    /** Отвечает ли по адресу API Server Holyrics. */
    suspend fun probe(host: String, port: Int = HolyricsClient.DEFAULT_PORT, timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("http://$host:$port/api/Auth").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs * 2
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write("{}".toByteArray()) }
                val text = c.inputStream.use { it.readBytes().decodeToString() }
                val o = DeixisJson.parseToJsonElement(text).jsonObject
                val data = o["data"]?.jsonObject
                o["status"]?.jsonPrimitive?.contentOrNull == "ok" && data?.get("nonce") != null && data["sid"] != null
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
    }

    /** Адреса своей сети /24 вокруг [local], кроме его самого и широковещательного. */
    fun neighbours(local: Inet4Address): List<String> {
        val b = local.address.map { it.toInt() and 0xFF }
        val prefix = "${b[0]}.${b[1]}.${b[2]}."
        return (1..254).filter { it != b[3] }.map { prefix + it }
    }

    /** Все Holyrics среди [hosts]: опрос сразу по [parallel] адресов, весь /24 — за пару секунд. */
    suspend fun scan(hosts: List<String>, port: Int = HolyricsClient.DEFAULT_PORT, parallel: Int = PARALLEL): List<String> = coroutineScope {
        val gate = Semaphore(parallel)
        hosts.map { h -> async { gate.withPermit { h.takeIf { probe(it, port) } } } }.awaitAll().filterNotNull()
    }

    private const val PROBE_TIMEOUT_MS = 600
    private const val PARALLEL = 64
}
