package app.deixis.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.engine.embeddedServer
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket as serverWebSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TlsTest {
    private val dir = createTempDirectory("tls").toFile()

    @Test
    fun identityIsStableAndCertificateParses() {
        val a = TlsIdentity.loadOrCreate(dir, "Зал")
        val b = TlsIdentity.loadOrCreate(dir, "Зал")
        assertEquals(a.fingerprint, b.fingerprint, "ключ экрана один и тот же после перезапуска")
        assertEquals(43, a.fingerprint.length)
        assertTrue(a.certificate.subjectX500Principal.name.contains("Зал"))
        a.certificate.verify(a.certificate.publicKey)
    }

    @Test
    fun pinnedClientTalksThroughGatewayAndRejectsOtherScreen() = runBlocking {
        val id = TlsIdentity.loadOrCreate(dir)
        val server = embeddedServer(io.ktor.server.cio.CIO, port = 0, host = "127.0.0.1") {
            install(io.ktor.server.websocket.WebSockets)
            routing {
                get("/ping") { call.respondText("pong") }
                serverWebSocket("/echo") { for (f in incoming) if (f is Frame.Text) send(Frame.Text("echo:" + f.readText())) }
            }
        }.start(wait = false)
        val inner = server.engine.resolvedConnectors().first().port
        val gw = TlsGateway(id, "127.0.0.1", 0, { inner })
        val port = gw.start()
        try {
            val client = HttpClient(CIO) {
                install(WebSockets)
                engine { https { trustManager = PinnedTrustManager(id.fingerprint) } }
            }
            assertEquals("pong", client.get("https://127.0.0.1:$port/ping").bodyAsText())
            var got = ""
            client.webSocket(urlString = "wss://127.0.0.1:$port/echo") {
                send(Frame.Text("привет"))
                got = (incoming.receive() as Frame.Text).readText()
            }
            assertEquals("echo:привет", got)
            client.close()
            val other = TlsIdentity.loadOrCreate(createTempDirectory("tls2").toFile())
            val wrong = HttpClient(CIO) { engine { https { trustManager = PinnedTrustManager(other.fingerprint) } } }
            assertFailsWith<Exception> { wrong.get("https://127.0.0.1:$port/ping").bodyAsText() }
            wrong.close()
        } finally {
            gw.stop()
            server.stop(100, 500)
        }
    }
}
