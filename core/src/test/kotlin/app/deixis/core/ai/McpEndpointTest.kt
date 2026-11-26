package app.deixis.core.ai

import app.deixis.core.effects.SceneLooks
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpEndpointTest {
    private fun rpc(id: Int, method: String, params: String = "{}") = """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

    @Test
    fun externalAgentReadsAndProposesButCannotApplyOrShow() = runBlocking {
        val engine = ProposalEngine(SceneLooks.load("evening"))
        var changes = 0
        val mcp = McpEndpoint(engine, SceneTarget, "инструкция") { changes++ }
        mcp.start()
        val client = HttpClient(CIO)
        try {
            suspend fun post(body: String, token: String? = mcp.token) = client.post(mcp.url) { if (token != null) header("Authorization", "Bearer $token"); setBody(body) }
            assertEquals(HttpStatusCode.Unauthorized, post(rpc(1, "ping"), token = null).status)
            assertEquals(HttpStatusCode.Unauthorized, post(rpc(1, "ping"), token = "чужой").status)
            val init = post(rpc(1, "initialize")).bodyAsText()
            assertTrue(init.contains("deixis") && init.contains("инструкция"), init)
            assertEquals(HttpStatusCode.Accepted, post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)
            val list = post(rpc(2, "tools/list")).bodyAsText()
            assertTrue(list.contains("propose_change") && list.contains("scene.get") && !list.contains("show"), list)
            val get = post(rpc(3, "tools/call", """{"name":"scene.get","arguments":{}}""")).bodyAsText()
            assertTrue(get.contains("evening") && get.contains("\"isError\":false"), get.take(200))
            val prop = post(rpc(4, "tools/call", """{"name":"propose_change","arguments":{"summary":"Тише","ops":[{"op":"scene.set","key":"quiet","value":0.9}]}}""")).bodyAsText()
            assertTrue(prop.contains("ждёт ответа человека"), prop)
            assertEquals(1, engine.pending().size)
            assertEquals(SceneLooks.load("evening").quiet, engine.scene.quiet, 1e-9, "агент сам применить не может")
            assertTrue(changes > 0)
            val bad = post(rpc(5, "tools/call", """{"name":"show.slide","arguments":{}}""")).bodyAsText()
            assertTrue(bad.contains("\"isError\":true") && bad.contains("Нет такого инструмента"), bad)
            assertTrue(post("не json").bodyAsText().contains("-32700"))
        } finally { client.close(); mcp.stop() }
    }
}
