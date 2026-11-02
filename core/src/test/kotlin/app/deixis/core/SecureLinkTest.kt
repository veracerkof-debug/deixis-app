package app.deixis.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Пульт и экран по шифрованному каналу: PIN и токен не идут открытым текстом, подменённый экран не принимается. */
class SecureLinkTest {
    private val dir = createTempDirectory("tls-e2e").toFile()
    private val id = TlsIdentity.loadOrCreate(File(dir, "tls"))
    private val core = ReceiverCore(ReceiverInfo("tv-1", "Зал", "test", "0"), FileStore(File(dir, "files")), Pairing(MemoryTokenStore()))
    private val server = ReceiverServer(core, port = 0, tls = id, tlsPort = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var port = 0

    @BeforeTest
    fun start() = runBlocking { port = server.start() }

    @AfterTest
    fun stop() { scope.cancel(); server.stop() }

    @Test
    fun receiverAnnouncesItsSecureEntrance() = runBlocking {
        val info = SenderClient("127.0.0.1", port, "Телефон", scope).info()
        assertTrue(info.tlsPort > 0)
        assertEquals(id.fingerprint, info.fingerprint)
    }

    @Test
    fun pairingMovesToTlsAndTokenNoLongerWorksInPlainText() = runBlocking {
        val c = SenderClient("127.0.0.1", port, "Телефон", scope)
        var pinned: String? = null
        c.onPinned = { _, fp -> pinned = fp }
        val token = c.pair(core.pairing.pin)
        assertEquals(id.fingerprint, pinned, "пульт узнал и запомнил отпечаток экрана")
        assertEquals(id.fingerprint, c.fingerprint)
        c.connect(token)
        withTimeout(10_000) { c.connection.first { it is Connection.Connected } }
        // Файл и показ — по шифрованному каналу.
        val bytes = Random(5).nextBytes(200_000)
        val ref = c.upload(object : UploadSource {
            override val name = "photo.jpg"; override val size = bytes.size.toLong(); override val mime = "image/jpeg"
            override fun open() = bytes.inputStream()
        })
        c.show(ImageContent(ref))
        withTimeout(5_000) { core.state.filterNotNull().first { it.content == ImageContent(ref) } }
        // Тот же токен открытым HTTP — не принимается: его нельзя подсмотреть и «откатить» пульт.
        val plain = HttpClient(CIO)
        val r = plain.get("http://127.0.0.1:$port/files/${ref.sha256}") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        plain.close()
        c.close()
    }

    @Test
    fun screenWithAnotherCertificateIsRefused() = runBlocking {
        val other = TlsIdentity.loadOrCreate(createTempDirectory("other").toFile())
        val tlsPort = core.tls!!.port
        // Отпечаток из QR-кода — не этого экрана: подмена.
        val c = SenderClient("127.0.0.1", port, "Телефон", scope, tlsPort, other.fingerprint)
        val e = assertFailsWith<PairingException> { c.pair(core.pairing.pin) }
        assertEquals(DenyReason.UNTRUSTED_SCREEN, e.reason)
        c.close()
    }

    @Test
    fun oldPlainPairingUpgradesOnReconnect() = runBlocking {
        // Сопряжение старой версии — по HTTP, без отпечатка.
        val token = (core.pairing.pair(core.pairing.pin, "старый пульт", "192.168.1.9") as Pairing.Result.Ok).token
        val c = SenderClient("127.0.0.1", port, "Телефон", scope)
        var pinned: String? = null
        c.onPinned = { _, fp -> pinned = fp }
        c.connect(token)
        withTimeout(10_000) { c.connection.first { it is Connection.Connected } }
        assertEquals(id.fingerprint, pinned, "при переподключении пульт перешёл на шифрование")
        assertTrue(!core.pairing.isValid(token, secure = false), "дальше этот токен — только по TLS")
        c.close()
    }
}
