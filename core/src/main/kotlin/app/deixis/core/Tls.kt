package app.deixis.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/*
 * Шифрование связи пульт ↔ экран.
 *
 * Экран один раз создаёт себе ключ и сертификат (EC P-256, самоподписанный) и хранит их у себя. Пульт узнаёт
 * экран по отпечатку сертификата (SHA-256): из QR-кода на экране — сразу и надёжно, или при первом сопряжении
 * по PIN (уже внутри шифрованного канала, так что PIN и токен не видны в сети). Дальше пульт помнит отпечаток
 * и с другим сертификатом не соединяется — подменить экран в той же сети нельзя.
 *
 * Веб-сервер приёмника (Ktor CIO) шифрования не умеет, поэтому шифрованный вход — отдельный порт: стандартный
 * TLS из Java (есть и на компьютере, и на Android) принимает соединение и передаёт его внутрь, на отдельный
 * внутренний вход сервера (только 127.0.0.1). По этому входу сервер и знает, что запрос пришёл зашифрованным.
 *
 * Браузеры (веб-пульт на iPhone, страница зрителей) остаются на обычном HTTP: самоподписанный сертификат
 * браузер пугал бы предупреждением.
 */

/** Ключ и сертификат экрана. */
class TlsIdentity private constructor(val certificate: X509Certificate, private val key: PrivateKey) {

    /** Отпечаток сертификата: SHA-256, base64url без «=» (43 знака) — короче для QR-кода. */
    val fingerprint: String = fingerprintOf(certificate.encoded)

    fun sslContext(): SSLContext {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry(ALIAS, key, PASSWORD, arrayOf(certificate))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, PASSWORD) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    }

    companion object {
        private const val ALIAS = "deixis"
        private val PASSWORD = CharArray(0)

        /** Ключ экрана из папки [dir]; нет — создаётся один раз. */
        fun loadOrCreate(dir: File, name: String = "Deixis"): TlsIdentity {
            dir.mkdirs()
            val keyFile = File(dir, "tls-key.der")
            val certFile = File(dir, "tls-cert.der")
            if (keyFile.isFile && certFile.isFile) {
                runCatching {
                    val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    return TlsIdentity(certificateOf(certFile.readBytes()), key)
                }
            }
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }.generateKeyPair()
            val der = SelfSigned.certificate(pair.public.encoded, pair.private, name)
            writeDurably(keyFile, pair.private.encoded)
            writeDurably(certFile, der)
            // Ключ читает только владелец (на компьютере; на Android папка приложения и так закрыта).
            runCatching { keyFile.setReadable(false, false); keyFile.setReadable(true, true) }
            return TlsIdentity(certificateOf(der), pair.private)
        }

        private fun certificateOf(der: ByteArray) =
            CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

        private fun writeDurably(file: File, bytes: ByteArray) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            java.io.FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}

fun fingerprintOf(certDer: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(certDer))

/**
 * Проверка сертификата экрана по запомненному отпечатку — вместо удостоверяющих центров (их у экрана в локальной
 * сети нет). Чужой сертификат — отказ: это не тот экран, кто-то в сети выдаёт себя за него.
 */
class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val cert = chain?.firstOrNull() ?: throw CertificateException("Экран не прислал сертификат")
        if (!MessageDigest.isEqual(fingerprintOf(cert.encoded).toByteArray(), fingerprint.toByteArray())) {
            throw UntrustedScreenException()
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Клиентские сертификаты не принимаются")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Сертификат экрана не совпал с запомненным: по этому адресу отвечает другое устройство. */
class UntrustedScreenException : CertificateException("Экран по этому адресу — не тот, с которым было сопряжение")

/**
 * Шифрованный вход приёмника: принимает TLS на [port] и передаёт расшифрованный поток на внутренний вход
 * сервера `127.0.0.1:[target]`. Соединений одновременно — не больше [maxConnections]: лишние ждать не будут,
 * их закроют сразу, а работающие пульты и трансляция не пострадают.
 */
class TlsGateway(
    private val identity: TlsIdentity,
    private val host: String,
    private val port: Int,
    private val target: () -> Int,
    private val maxConnections: Int = 64,
) {
    @Volatile private var server: ServerSocket? = null

    /**
     * Настоящий адрес пульта для каждого соединения внутрь (по его локальному порту): сервер видит все
     * шифрованные запросы с 127.0.0.1, а паузе PIN нужен адрес того, кто перебирает.
     */
    val origins = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "deixis-tls").apply { isDaemon = true } }
    private val slots = Semaphore(maxConnections)

    /** Запуск; возвращает фактический порт. */
    fun start(): Int {
        val ss = identity.sslContext().serverSocketFactory.createServerSocket() as SSLServerSocket
        ss.reuseAddress = true
        ss.enabledProtocols = ss.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        ss.bind(InetSocketAddress(InetAddress.getByName(host), port))
        server = ss
        pool.execute { acceptLoop(ss) }
        return ss.localPort
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val client = runCatching { ss.accept() }.getOrNull() ?: continue
            if (!slots.tryAcquire()) { runCatching { client.close() }; continue }
            pool.execute {
                try { serve(client as SSLSocket) } finally { slots.release() }
            }
        }
    }

    private fun serve(client: SSLSocket) {
        val inner = Socket()
        try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            client.startHandshake()
            client.soTimeout = 0
            client.tcpNoDelay = true
            inner.tcpNoDelay = true
            inner.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), target()), CONNECT_TIMEOUT_MS)
            origins[inner.localPort] = client.inetAddress.hostAddress ?: "tls"
            val back = pool.submit { pump(inner.getInputStream(), client.getOutputStream()); runCatching { client.close() } }
            pump(client.getInputStream(), inner.getOutputStream())
            runCatching { inner.shutdownOutput() }
            runCatching { back.get() }
        } catch (_: Exception) {
        } finally {
            origins.remove(inner.localPort)
            runCatching { client.close() }
            runCatching { inner.close() }
        }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(BUFFER)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val CONNECT_TIMEOUT_MS = 5_000
        const val BUFFER = 64 * 1024
    }
}

/** Самоподписанный сертификат X.509 v3 в DER — без сторонних библиотек (их API на Android и JVM разные). */
internal object SelfSigned {
    private val ECDSA_SHA256 = oid(1, 2, 840, 10045, 4, 3, 2)
    private val CN = oid(2, 5, 4, 3)

    fun certificate(publicKeyInfo: ByteArray, key: PrivateKey, name: String): ByteArray {
        val algorithm = seq(ECDSA_SHA256)
        val subject = seq(set(seq(CN, tlv(0x0C, name.take(64).toByteArray(Charsets.UTF_8)))))
        val now = System.currentTimeMillis()
        val validity = seq(
            tlv(0x17, utc(now - DAY_MS)),
            tlv(0x18, generalized(now + YEARS_100_MS)),
        )
        val serial = BigInteger(1, ByteArray(16).also(SecureRandom()::nextBytes).also { it[0] = (it[0].toInt() and 0x7F).toByte() })
        val tbs = seq(
            tlv(0xA0, int(BigInteger.valueOf(2))),
            int(serial),
            algorithm,
            subject,
            validity,
            subject,
            publicKeyInfo,
        )
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(tbs) }.sign()
        return seq(tbs, algorithm, tlv(0x03, byteArrayOf(0) + signature))
    }

    private fun utc(ms: Long) = fmt("yyMMddHHmmss'Z'", ms)
    private fun generalized(ms: Long) = fmt("yyyyMMddHHmmss'Z'", ms)
    private fun fmt(p: String, ms: Long) = SimpleDateFormat(p, java.util.Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms)).toByteArray(Charsets.US_ASCII)

    private fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    private fun seq(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    private fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))
    private fun concat(parts: Array<out ByteArray>) = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun oid(vararg arcs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (a in arcs.drop(2)) {
            val bytes = ArrayList<Int>()
            var v = a
            do { bytes.add(0, (v and 0x7F).toInt()); v = v shr 7 } while (v > 0)
            bytes.forEachIndexed { i, b -> out.write(if (i < bytes.size - 1) b or 0x80 else b) }
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val YEARS_100_MS = 100L * 365 * DAY_MS
}
