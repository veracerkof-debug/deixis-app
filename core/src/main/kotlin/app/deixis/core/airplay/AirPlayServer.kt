package app.deixis.core.airplay

/*
 * Приёмник AirPlay: iPhone, iPad и Mac показывают свой экран на экране Deixis без отдельных программ.
 *
 * Происхождение: протокол переписан на Kotlin по UxPlay (https://github.com/FDH2/UxPlay, GPL-3.0):
 * lib/raop.c, lib/raop_handlers.h, lib/dns_sd/dns_sd.c, lib/dnssdint.h, lib/raop_ntp.c.
 * UxPlay вырос из RPiPlay (dsafa22, 2019, LGPL-2.1+) и shairplay (Juho Vähä-Herttua, 2011–2018, LGPL-2.1+).
 * Код не скопирован построчно: устройство своё (потоки Java, наш поток экрана), значения протокола — оттуда.
 * Подробности — в NOTICE.
 */

import app.deixis.core.MirrorSink
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Сервер AirPlay. Пока не вызван [start], он ничего не слушает и не объявляет себя в сети.
 * Одновременно показывает один iPhone; второй получает отказ, пока первый не отключится.
 * [password] — пароль, который iPhone спросит перед показом; null — без пароля.
 */
class AirPlayServer(
    private val name: String,
    private val identity: AirPlayIdentity,
    private val sink: MirrorSink,
    private val password: String? = null,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val fps: Int = 30,
) : Closeable {
    private var server: ServerSocket? = null
    private var mdns: JmDNS? = null
    private val random = SecureRandom()

    /** Соединение, которое сейчас показывает картинку. */
    private val showing = AtomicReference<Connection?>(null)

    @Volatile
    private var closed = false

    val port: Int get() = server?.localPort ?: 0

    /** Слушает [preferredPort] (обычный для AirPlay — 7000) или свободный и объявляет себя через mDNS на [address]. */
    fun start(address: InetAddress?, preferredPort: Int = 7000, advertise: Boolean = true) {
        val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(preferredPort)) } }
            .getOrElse { ServerSocket(0) }
        server = s
        thread("airplay-accept") {
            while (!closed) {
                val c = try { s.accept() } catch (_: SocketException) { break }
                c.tcpNoDelay = true
                thread("airplay-conn") { Connection(c).run() }
            }
        }
        if (advertise) runCatching {
            val j = JmDNS.create(address, "deixis-airplay")
            mdns = j
            j.registerService(ServiceInfo.create("_airplay._tcp.local.", name, s.localPort, 0, 0, airplayTxt()))
            j.registerService(ServiceInfo.create("_raop._tcp.local.", "${identity.deviceId.replace(":", "")}@$name", s.localPort, 0, 0, raopTxt()))
        }
    }

    override fun close() {
        closed = true
        runCatching { mdns?.unregisterAllServices(); mdns?.close() }
        runCatching { server?.close() }
        showing.getAndSet(null)?.close()
    }

    private val features = "0x5A7FFEE6,0x0"
    private val pkHex get() = identity.publicKey.joinToString("") { "%02x".format(it) }

    private fun airplayTxt(): Map<String, String> = linkedMapOf(
        "deviceid" to identity.deviceId, "features" to features,
        "pw" to (password != null).toString(), "flags" to "0x4", "model" to MODEL,
        "pk" to pkHex, "pi" to PI, "srcvers" to VERSION, "vv" to "2",
    )

    private fun raopTxt(): Map<String, String> = linkedMapOf(
        "ch" to "2", "cn" to "0,1,2,3", "da" to "true", "et" to "0,3,5", "vv" to "2", "ft" to features,
        "am" to MODEL, "md" to "0,1,2", "rhd" to "5.6.0.0",
        "pw" to (password != null).toString(), "sf" to if (password != null) "0x84" else "0x4",
        "sr" to "44100", "ss" to "16", "sv" to "false", "tp" to "UDP", "txtvers" to "1", "vs" to VERSION, "vn" to "65537", "pk" to pkHex,
    )

    /** TXT-запись в виде DNS (длина и строка), её iPhone просит в /info. */
    private fun txtBytes(map: Map<String, String>): ByteArray = ByteArrayOutputStream().apply {
        map.forEach { (k, v) -> val e = "$k=$v".toByteArray(); write(e.size); write(e) }
    }.toByteArray()

    private class Request(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String) = headers[name.lowercase()]
    }

    private class Response(var code: Int = 200, var reason: String = "OK") {
        val headers = LinkedHashMap<String, String>()
        var body: ByteArray = ByteArray(0)
        var close = false
        fun plist(v: Any?) { headers["Content-Type"] = "application/x-apple-binary-plist"; body = Bplist.write(v) }
        fun octets(b: ByteArray) { headers["Content-Type"] = "application/octet-stream"; body = b }
    }

    /** Одно TCP-соединение управления от iPhone: сопряжение, ключи, SETUP потоков. */
    private inner class Connection(private val socket: Socket) : Closeable {
        private val pairing = AirPlayPairSession(identity, random)
        private var fpMessage3: ByteArray? = null
        private var sessionKey: ByteArray? = null
        private var authenticated = password == null
        private var nonce: String? = null
        private val resources = ArrayList<Closeable>()
        private var timing: DatagramSocket? = null
        private var mirror: ServerSocket? = null

        fun run() {
            try {
                socket.soTimeout = IDLE_MS
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                while (!closed) {
                    val req = readRequest(input) ?: break
                    val res = Response()
                    handle(req, res)
                    write(output, req, res)
                    if (res.close) break
                }
            } catch (_: Exception) {
                // Оборванное соединение — обычный конец трансляции.
            } finally {
                close()
            }
        }

        override fun close() {
            runCatching { socket.close() }
            stopMirror()
            synchronized(resources) { resources.forEach { runCatching { it.close() } }; resources.clear() }
        }

        private fun stopMirror() {
            runCatching { mirror?.close() }
            mirror = null
            if (showing.compareAndSet(this, null)) sink.stopped()
        }

        private fun handle(req: Request, res: Response) {
            when {
                req.method == "GET" && req.url == "/info" -> info(req, res)
                req.method == "POST" && req.url == "/pair-setup" -> pairing.setup(req.body)?.let(res::octets) ?: res.fail(400, "Bad Request")
                req.method == "POST" && req.url == "/pair-verify" -> pairing.verify(req.body)?.let { if (it.isNotEmpty()) res.octets(it) } ?: res.fail(400, "Bad Request")
                req.method == "POST" && req.url == "/fp-setup" -> fpSetup(req, res)
                req.method == "POST" && req.url == "/feedback" -> Unit
                req.method == "OPTIONS" -> res.headers["Public"] = "SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
                req.method == "SETUP" -> setup(req, res)
                req.method == "GET_PARAMETER" -> {
                    res.headers["Content-Type"] = "text/parameters"
                    res.body = "volume: 0.000000\r\n".toByteArray()
                }
                req.method == "RECORD" -> {
                    res.headers["Audio-Latency"] = "11025"
                    res.headers["Audio-Jack-Status"] = "connected; type=analog"
                }
                req.method == "SET_PARAMETER" || req.method == "FLUSH" -> Unit
                req.method == "TEARDOWN" -> teardown(req, res)
                else -> res.fail(501, "Not Implemented")
            }
        }

        private fun Response.fail(c: Int, r: String) { code = c; reason = r }

        private fun info(req: Request, res: Response) {
            val qualifier = ((Bplist.parse(req.body) as? Map<*, *>)?.get("qualifier") as? List<*>)?.firstOrNull() as? String
            if (qualifier == "txtAirPlay") return res.plist(mapOf("txtAirPlay" to txtBytes(airplayTxt())))
            if (qualifier == "txtRAOP") return res.plist(mapOf("txtRAOP" to txtBytes(raopTxt())))
            res.plist(linkedMapOf(
                "deviceID" to identity.deviceId, "macAddress" to identity.deviceId, "pk" to identity.publicKey,
                "features" to 0x5A7FFEE6L, "name" to name, "pi" to PI, "vv" to 2, "statusFlags" to 68,
                "keepAliveLowPower" to true, "sourceVersion" to VERSION, "keepAliveSendStatsAsBody" to true, "model" to MODEL,
                "initialVolume" to 0.0,
                "audioLatencies" to listOf(100, 101).map { mapOf("type" to it, "inputLatencyMicros" to 0, "audioType" to "default", "outputLatencyMicros" to false) },
                "audioFormats" to listOf(100, 101).map { mapOf("type" to it, "audioInputFormats" to 0x3fffffc, "audioOutputFormats" to 0x3fffffc) },
                "displays" to listOf(linkedMapOf(
                    "uuid" to "e0ff8a27-6738-3d56-8a16-cc53aacee925", "widthPhysical" to 0, "heightPhysical" to 0,
                    "width" to width, "height" to height, "widthPixels" to width, "heightPixels" to height,
                    "rotation" to false, "refreshRate" to 1.0 / fps, "maxFPS" to fps, "overscanned" to false, "features" to 14,
                )),
            ))
        }

        private fun fpSetup(req: Request, res: Response) {
            val b = req.body
            when (b.size) {
                16 -> FairPlay.setupReply(b)?.let(res::octets) ?: res.fail(400, "Bad Request")
                164 -> { fpMessage3 = b.copyOf(); FairPlay.handshakeReply(b)?.let(res::octets) ?: res.fail(400, "Bad Request") }
                else -> res.fail(400, "Bad Request")
            }
        }

        private fun setup(req: Request, res: Response) {
            val root = Bplist.parse(req.body) as? Map<*, *> ?: return res.fail(400, "Bad Request")
            val out = LinkedHashMap<String, Any?>()
            val ekey = root["ekey"] as? ByteArray
            val eiv = root["eiv"] as? ByteArray
            if (ekey != null && eiv != null) {
                if (!authenticated && !checkPassword(req, res)) return
                if (ekey.size != 72 || eiv.size != 16) return res.fail(400, "Bad Request")
                val m3 = fpMessage3 ?: return res.fail(400, "Bad Request")
                val fpKey = runCatching { FairPlay.decryptKey(m3, ekey) }.getOrNull() ?: return res.fail(400, "Bad Request")
                sessionKey = airPlaySessionKey(fpKey, pairing.ecdhSecret)
                val remoteTiming = (root["timingPort"] as? Long)?.toInt() ?: 0
                out["timingPort"] = startTiming(remoteTiming)
                out["eventPort"] = 0
            }
            val streams = root["streams"] as? List<*>
            if (streams != null) {
                val list = ArrayList<Any?>()
                for (st in streams) {
                    val m = st as? Map<*, *> ?: continue
                    when ((m["type"] as? Long)?.toInt()) {
                        110 -> {
                            val key = sessionKey ?: return res.fail(400, "Bad Request")
                            val current = showing.get()
                            if (current != null && current !== this) return res.fail(409, "Conflict")
                            val id = m["streamConnectionID"] as? Long ?: 0L
                            list.add(mapOf("dataPort" to startMirror(MirrorCipher(key, id)), "type" to 110))
                        }
                        96 -> {
                            // Звук трансляции пока не играется: порты есть, чтобы iPhone начал показ.
                            val data = udpSink(); val control = udpSink()
                            list.add(mapOf("dataPort" to data, "controlPort" to control, "type" to 96))
                        }
                        else -> res.close = true
                    }
                }
                out["streams"] = list
            }
            res.plist(out)
        }

        private fun teardown(req: Request, res: Response) {
            val streams = (Bplist.parse(req.body) as? Map<*, *>)?.get("streams") as? List<*>
            val types = streams?.mapNotNull { ((it as? Map<*, *>)?.get("type") as? Long)?.toInt() }
            if (types == null || 110 in types) stopMirror()
            if (types == null) res.close = true
        }

        /** Пароль по RFC 2617 (Digest, MD5): так его спрашивает iPhone. Неверный — повторный запрос. */
        private fun checkPassword(req: Request, res: Response): Boolean {
            val pw = password ?: return true
            val auth = req.header("Authorization")
            val expected = nonce
            if (auth != null && expected != null && digestOk(req.method, auth, pw, expected)) {
                authenticated = true; nonce = null; return true
            }
            val n = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            nonce = n
            res.fail(401, "Unauthorized")
            res.headers["WWW-Authenticate"] = "Digest realm=\"raop\", nonce=\"$n\""
            return false
        }

        private fun startTiming(remotePort: Int): Int {
            val s = DatagramSocket(0)
            timing = s
            synchronized(resources) { resources.add(s) }
            val remote = socket.inetAddress
            if (remotePort > 0) thread("airplay-ntp") {
                // Приёмник раз в три секунды спрашивает время отправителя; без этого iPhone может прервать показ.
                val req = ByteArray(32).apply { this[0] = 0x80.toByte(); this[1] = 0xd2.toByte(); this[3] = 0x07 }
                while (!s.isClosed) {
                    putNtp(req, 24, System.currentTimeMillis())
                    runCatching { s.send(DatagramPacket(req, req.size, remote, remotePort)) }
                    runCatching { s.soTimeout = 3000; s.receive(DatagramPacket(ByteArray(128), 128)) }
                    Thread.sleep(3000)
                }
            }
            return s.localPort
        }

        private fun udpSink(): Int {
            val s = DatagramSocket(0)
            synchronized(resources) { resources.add(s) }
            thread("airplay-udp") {
                val buf = ByteArray(2048)
                while (!s.isClosed) runCatching { s.receive(DatagramPacket(buf, buf.size)) }.onFailure { if (s.isClosed) return@thread }
            }
            return s.localPort
        }

        private fun startMirror(cipher: MirrorCipher): Int {
            runCatching { mirror?.close() }
            val s = ServerSocket(0)
            mirror = s
            synchronized(resources) { resources.add(s) }
            thread("airplay-mirror") {
                val c = try { s.accept() } catch (_: SocketException) { return@thread }
                // Данные принимаются только от того же iPhone, что вёл переговоры.
                if (c.inetAddress != socket.inetAddress) { c.close(); return@thread }
                synchronized(resources) { resources.add(c) }
                c.soTimeout = IDLE_MS
                showing.set(this)
                try {
                    MirrorReader(cipher).run(c.getInputStream()) { e ->
                        if (showing.get() !== this) return@run
                        when (e) {
                            is MirrorEvent.Config -> sink.config(e.annexB, e.width, e.height)
                            is MirrorEvent.Frame -> sink.frame(e.annexB, e.key, e.ptsUs)
                            MirrorEvent.Pause -> Unit // последний кадр остаётся на экране
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { c.close() }
                    if (mirror === s) stopMirror()
                }
            }
            return s.localPort
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > MAX_HEADER) return null
        }
        val lines = head.toString(Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
        val first = lines.firstOrNull()?.split(" ") ?: return null
        if (first.size < 3) return null
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > MAX_BODY) return null
        val body = ByteArray(len)
        var read = 0
        while (read < len) { val n = input.read(body, read, len - read); if (n < 0) return null; read += n }
        return Request(first[0], first[1].substringBefore('?'), headers, body)
    }

    private fun write(out: OutputStream, req: Request, res: Response) {
        val sb = StringBuilder("RTSP/1.0 ${res.code} ${res.reason}\r\n")
        val cseq = req.header("CSeq")
        if (cseq != null && req.method != "RECORD") sb.append("Audio-Jack-Status: connected; type=digital\r\n")
        res.headers.forEach { (k, v) -> if (!(k == "Audio-Jack-Status" && cseq != null && req.method != "RECORD")) sb.append("$k: $v\r\n") }
        sb.append("Content-Length: ${res.body.size}\r\n")
        sb.append("Server: AirTunes/$VERSION\r\n")
        if (cseq != null) sb.append("CSeq: $cseq\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(res.body)
        out.flush()
    }

    companion object {
        const val MODEL = "AppleTV3,2"
        const val VERSION = "220.68"
        const val PI = "2e388006-13ba-4041-9a67-25dd4a43d536"
        private const val MAX_HEADER = 16 * 1024
        private const val MAX_BODY = 1 shl 20
        /** Без единого байта минуту — iPhone пропал. */
        private const val IDLE_MS = 60_000

        private fun thread(name: String, body: () -> Unit) = Thread(body, name).apply { isDaemon = true; start() }

        /** Время NTP: секунды с 1900 года и доли секунды. */
        internal fun putNtp(b: ByteArray, at: Int, millis: Long) {
            val sec = millis / 1000 + 2_208_988_800L
            val frac = ((millis % 1000) shl 32) / 1000
            for (i in 0 until 4) b[at + i] = (sec ushr (24 - 8 * i)).toByte()
            for (i in 0 until 4) b[at + 4 + i] = (frac ushr (24 - 8 * i)).toByte()
        }

        /** Проверка ответа Digest: MD5(MD5(user:realm:pw):nonce[:nc:cnonce:qop]:MD5(method:uri)). */
        internal fun digestOk(method: String, authorization: String, password: String, expectedNonce: String): Boolean {
            fun token(k: String) = Regex("""(?:^|[\s,])$k="?([^",]*)"?""").find(authorization.removePrefix("Digest").trim())?.groupValues?.get(1)
            val user = token("username") ?: return false
            val realm = token("realm") ?: return false
            val nonce = token("nonce") ?: return false
            val uri = token("uri") ?: return false
            val response = token("response") ?: return false
            if (nonce != expectedNonce) return false
            fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
            val h1 = md5("$user:$realm:$password")
            val h2 = md5("$method:$uri")
            val qop = token("qop")
            val mid = if (qop != null) "$nonce:${token("nc")}:${token("cnonce")}:$qop" else nonce
            return MessageDigest.isEqual(md5("$h1:$mid:$h2").toByteArray(), response.lowercase().toByteArray())
        }
    }
}
