package app.deixis.core.airplay

import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import app.deixis.core.MirrorSink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Весь разговор «iPhone» с сервером по настоящему TCP: /info, сопряжение, FairPlay, SETUP, видео, TEARDOWN. */
class AirPlayServerTest {
    private val rnd = SecureRandom()

    private class Sink : MirrorSink {
        val events = LinkedBlockingQueue<String>()
        val frames = LinkedBlockingQueue<ByteArray>()
        override fun config(annexB: ByteArray, width: Int, height: Int) { events += "config ${width}x$height"; frames += annexB }
        override fun frame(annexB: ByteArray, key: Boolean, ptsUs: Long) { events += "frame key=$key"; frames += annexB }
        override fun stopped() { events += "stopped" }
    }

    private class Rtsp(port: Int) : AutoCloseable {
        val socket = Socket(InetAddress.getLoopbackAddress(), port)
        private val input = BufferedInputStream(socket.getInputStream())
        private var cseq = 0

        fun call(method: String, url: String, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()): Pair<Int, ByteArray> {
            val sb = StringBuilder("$method $url RTSP/1.0\r\nCSeq: ${++cseq}\r\nContent-Length: ${body.size}\r\n")
            headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("\r\n")
            socket.getOutputStream().apply { write(sb.toString().toByteArray()); write(body); flush() }
            val head = ByteArrayOutputStream()
            while (!head.toString().endsWith("\r\n\r\n")) head.write(input.read().also { check(it >= 0) })
            val text = head.toString()
            assertTrue("CSeq: $cseq" in text, text)
            val code = text.substringAfter(" ").substringBefore(" ").toInt()
            val len = Regex("Content-Length: (\\d+)").find(text)!!.groupValues[1].toInt()
            val out = ByteArray(len); var r = 0
            while (r < len) r += input.read(out, r, len - r)
            return code to out
        }

        override fun close() = socket.close()
    }

    @Test
    fun mirrorSessionEndToEnd() {
        val sink = Sink()
        val identity = AirPlayIdentity.of(ByteArray(38).also(rnd::nextBytes))
        val server = AirPlayServer("Зал", identity, sink)
        server.start(InetAddress.getLoopbackAddress(), preferredPort = 0, advertise = false)
        server.use {
            Rtsp(server.port).use { c ->
                val (_, txt) = c.call("GET", "/info", Bplist.write(mapOf("qualifier" to listOf("txtAirPlay"))))
                val txtData = (Bplist.parse(txt) as Map<*, *>)["txtAirPlay"] as ByteArray
                assertTrue(String(txtData).contains("deviceid=${identity.deviceId}"))
                val info = Bplist.parse(c.call("GET", "/info").second) as Map<*, *>
                assertEquals("Зал", info["name"])
                assertContentEquals(identity.publicKey, info["pk"] as ByteArray)

                // Сопряжение.
                val edSeed = ByteArray(32).also(rnd::nextBytes)
                val ed = ByteArray(32).also { Ed25519.generatePublicKey(edSeed, 0, it, 0) }
                assertContentEquals(identity.publicKey, c.call("POST", "/pair-setup", ed).second)
                val x = ByteArray(32).also(rnd::nextBytes)
                val xPub = ByteArray(32).also { X25519.scalarMultBase(x, 0, it, 0) }
                val r1 = c.call("POST", "/pair-verify", byteArrayOf(1, 0, 0, 0) + xPub + ed).second
                val serverX = r1.copyOfRange(0, 32)
                val secret = ByteArray(32).also { X25519.calculateAgreement(x, 0, serverX, 0, it, 0) }
                val ctr = verifyCtr(secret)
                ctr.update(r1.copyOfRange(32, 96))
                val sig = ByteArray(64).also { val m = xPub + serverX; Ed25519.sign(edSeed, 0, m, 0, m.size, it, 0) }
                assertEquals(200, c.call("POST", "/pair-verify", byteArrayOf(0, 0, 0, 0) + ctr.update(sig)).first)

                // FairPlay.
                val fp1 = ByteArray(16).also { it[4] = 3 }
                assertEquals(142, c.call("POST", "/fp-setup", fp1).second.size)
                val m3 = ByteArray(164).also(rnd::nextBytes).also { it[4] = 3; it[12] = 1 }
                assertEquals(32, c.call("POST", "/fp-setup", m3).second.size)

                // Ключи и поток.
                val ekey = ByteArray(72).also(rnd::nextBytes)
                val setup1 = Bplist.parse(c.call("SETUP", "rtsp://x/1", Bplist.write(mapOf("ekey" to ekey, "eiv" to ByteArray(16), "timingPort" to 0L, "deviceID" to "AA:BB:CC:DD:EE:FF"))).second) as Map<*, *>
                assertNotNull(setup1["timingPort"])
                val streamId = 1234567890123L
                val setup2 = Bplist.parse(c.call("SETUP", "rtsp://x/1", Bplist.write(mapOf("streams" to listOf(mapOf("type" to 110L, "streamConnectionID" to streamId))))).second) as Map<*, *>
                val dataPort = (((setup2["streams"] as List<*>)[0] as Map<*, *>)["dataPort"] as Long).toInt()

                val key = airPlaySessionKey(FairPlay.decryptKey(m3, ekey), secret)
                val enc = MirrorCipher(key, streamId) // AES-CTR: то же действие шифрует
                Socket(InetAddress.getLoopbackAddress(), dataPort).use { d ->
                    val out = d.getOutputStream()
                    val sps = byteArrayOf(0x67, 0x42, 0, 0x1f); val pps = byteArrayOf(0x68, 1)
                    val avcc = byteArrayOf(1, 0x42, 0, 0x1f, -1, -31, 0, 4) + sps + byteArrayOf(1, 0, 2) + pps
                    out.write(packet(1, 0x16, avcc, 1280f, 720f))
                    val idr = byteArrayOf(0x65) + ByteArray(40) { it.toByte() }
                    out.write(packet(0, 0, enc.decrypt(ByteBuffer.allocate(4).putInt(idr.size).array() + idr)))
                    out.flush()
                    assertEquals("config 1280x720", sink.events.poll(5, TimeUnit.SECONDS))
                    sink.frames.poll(1, TimeUnit.SECONDS)
                    assertEquals("frame key=true", sink.events.poll(5, TimeUnit.SECONDS))
                    assertContentEquals(byteArrayOf(0, 0, 0, 1) + idr, sink.frames.poll(1, TimeUnit.SECONDS))

                    // Второй iPhone, пока первый показывает, получает отказ.
                    Rtsp(server.port).use { other ->
                        other.call("POST", "/pair-setup", ByteArray(32))
                        val o = other.call("SETUP", "rtsp://y/1", Bplist.write(mapOf("streams" to listOf(mapOf("type" to 110L)))))
                        assertEquals(400, o.first) // без ключей FairPlay поток не открыть
                    }

                    assertEquals(200, c.call("TEARDOWN", "rtsp://x/1", Bplist.write(mapOf("streams" to listOf(mapOf("type" to 110L))))).first)
                    assertEquals("stopped", sink.events.poll(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test
    fun passwordAsked() {
        val server = AirPlayServer("Зал", AirPlayIdentity.of(ByteArray(38).also(rnd::nextBytes)), Sink(), password = "1234")
        server.start(InetAddress.getLoopbackAddress(), preferredPort = 0, advertise = false)
        server.use {
            Rtsp(server.port).use { c ->
                c.call("POST", "/fp-setup", ByteArray(16).also { it[4] = 3 })
                c.call("POST", "/fp-setup", ByteArray(164).also { it[4] = 3 })
                val body = Bplist.write(mapOf("ekey" to ByteArray(72), "eiv" to ByteArray(16)))
                assertEquals(401, c.call("SETUP", "rtsp://x/1", body).first)
                assertEquals(401, c.call("SETUP", "rtsp://x/1", body, mapOf("Authorization" to "Digest username=\"a\", realm=\"raop\", nonce=\"x\", uri=\"rtsp://x/1\", response=\"0\"")).first)
            }
        }
    }

    private fun verifyCtr(secret: ByteArray): Cipher {
        val sha = MessageDigest.getInstance("SHA-512")
        val k = sha.digest("Pair-Verify-AES-Key".toByteArray() + secret).copyOf(16)
        val iv = sha.digest("Pair-Verify-AES-IV".toByteArray() + secret).copyOf(16)
        return Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(iv)) }
    }

    private fun packet(type: Int, option: Int, payload: ByteArray, w: Float = 0f, h: Float = 0f): ByteArray {
        val hdr = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(0, payload.size); hdr.put(4, type.toByte()); hdr.put(6, option.toByte())
        hdr.putFloat(56, w); hdr.putFloat(60, h)
        return hdr.array() + payload
    }
}
