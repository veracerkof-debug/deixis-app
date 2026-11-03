package app.deixis.core.airplay

import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AirPlayTest {

    @Test
    fun bplistRoundTrip() {
        val v = linkedMapOf(
            "name" to "Зал", "ascii" to "AppleTV3,2", "n" to 7L, "big" to 0x5A7FFEE6L, "neg" to -5L, "pi" to 0.5,
            "yes" to true, "no" to false, "data" to byteArrayOf(1, 2, 3),
            "list" to listOf(1L, "x", mapOf("k" to "v")),
            "long" to "a".repeat(300),
        )
        val back = Bplist.parse(Bplist.write(v)) as Map<*, *>
        assertEquals("Зал", back["name"])
        assertEquals("AppleTV3,2", back["ascii"])
        assertEquals(7L, back["n"])
        assertEquals(0x5A7FFEE6L, back["big"])
        assertEquals(-5L, back["neg"])
        assertEquals(0.5, back["pi"])
        assertEquals(true, back["yes"]); assertEquals(false, back["no"])
        assertContentEquals(byteArrayOf(1, 2, 3), back["data"] as ByteArray)
        assertEquals(listOf(1L, "x", mapOf("k" to "v")), back["list"])
        assertEquals(300, (back["long"] as String).length)
    }

    @Test
    fun bplistRejectsGarbage() {
        assertNull(Bplist.parse(ByteArray(0)))
        assertNull(Bplist.parse("bplist00".toByteArray() + ByteArray(40) { 0x7F }))
        val ok = Bplist.write(mapOf("a" to 1L))
        assertNull(Bplist.parse(ok.copyOf(ok.size - 3)))
    }

    @Test
    fun identityPersists() {
        val f = kotlin.io.path.createTempFile().toFile().apply { delete(); deleteOnExit() }
        val a = AirPlayIdentity.load(f)
        val b = AirPlayIdentity.load(f)
        assertContentEquals(a.publicKey, b.publicKey)
        assertEquals(a.deviceId, b.deviceId)
        assertTrue(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(a.deviceId))
        // Адрес местный (второй бит первого байта) и не групповой.
        assertEquals(2, a.deviceId.substring(0, 2).toInt(16) and 3)
    }

    /** Сторона iPhone написана по тому же описанию: ключи, подписи и шифрование сходятся в обе стороны. */
    @Test
    fun pairVerifyBothWays() {
        val rnd = SecureRandom()
        val id = AirPlayIdentity.of(ByteArray(38).also(rnd::nextBytes))
        val s = AirPlayPairSession(id)
        val clientEdSeed = ByteArray(32).also(rnd::nextBytes)
        val clientEd = ByteArray(32).also { Ed25519.generatePublicKey(clientEdSeed, 0, it, 0) }
        assertContentEquals(id.publicKey, s.setup(clientEd))

        val clientX = ByteArray(32).also(rnd::nextBytes)
        val clientXPub = ByteArray(32).also { X25519.scalarMultBase(clientX, 0, it, 0) }
        val r1 = assertNotNull(s.verify(byteArrayOf(1, 0, 0, 0) + clientXPub + clientEd))
        assertEquals(96, r1.size)
        val serverXPub = r1.copyOfRange(0, 32)
        val secret = ByteArray(32).also { X25519.calculateAgreement(clientX, 0, serverXPub, 0, it, 0) }
        assertContentEquals(secret, s.ecdhSecret)

        val ctr = ctr(secret)
        val serverSig = ctr.update(r1.copyOfRange(32, 96))
        assertTrue(Ed25519.verify(serverSig, 0, id.publicKey, 0, serverXPub + clientXPub, 0, 64))

        val clientSig = ByteArray(64).also { val m = clientXPub + serverXPub; Ed25519.sign(clientEdSeed, 0, m, 0, m.size, it, 0) }
        val r2 = s.verify(byteArrayOf(0, 0, 0, 0) + ctr.update(clientSig))
        assertNotNull(r2)
        assertTrue(s.verified)
    }

    @Test
    fun pairVerifyRejectsForgedSignature() {
        val rnd = SecureRandom()
        val s = AirPlayPairSession(AirPlayIdentity.of(ByteArray(38).also(rnd::nextBytes)))
        assertNull(s.verify(byteArrayOf(1, 0, 0, 0) + ByteArray(64)), "без pair-setup")
        s.setup(ByteArray(32))
        val clientX = ByteArray(32).also(rnd::nextBytes)
        val clientXPub = ByteArray(32).also { X25519.scalarMultBase(clientX, 0, it, 0) }
        val edSeed = ByteArray(32).also(rnd::nextBytes)
        val ed = ByteArray(32).also { Ed25519.generatePublicKey(edSeed, 0, it, 0) }
        assertNotNull(s.verify(byteArrayOf(1, 0, 0, 0) + clientXPub + ed))
        assertNull(s.verify(byteArrayOf(0, 0, 0, 0) + ByteArray(64) { 7 }))
        assertTrue(!s.verified)
    }

    @Test
    fun sessionKeyMixesSecret() {
        val k = ByteArray(16) { it.toByte() }
        assertContentEquals(k, airPlaySessionKey(k, null))
        val secret = ByteArray(32) { 9 }
        val expect = MessageDigest.getInstance("SHA-512").digest(k + secret).copyOf(16)
        assertContentEquals(expect, airPlaySessionKey(k, secret))
    }

    @Test
    fun mirrorStreamDecodes() {
        val key = ByteArray(16) { (it * 3).toByte() }
        val id = -2L // streamConnectionID беззнаковый: 18446744073709551614
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1f, 0x11)
        val pps = byteArrayOf(0x68, 0x01, 0x02)
        val idr = byteArrayOf(0x65, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17)
        val p = byteArrayOf(0x41, 9, 9, 9, 9, 9, 9)

        // Шифрует «отправитель»: тот же ключ из SHA-512 и непрерывный поток AES-CTR через пакеты.
        val k = MessageDigest.getInstance("SHA-512").digest("AirPlayStreamKey18446744073709551614".toByteArray() + key).copyOf(16)
        val iv = MessageDigest.getInstance("SHA-512").digest("AirPlayStreamIV18446744073709551614".toByteArray() + key).copyOf(16)
        val enc = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(iv)) }

        val avcc = byteArrayOf(1, 0x42, 0, 0x1f, 0xff.toByte(), 0xe1.toByte(), 0, sps.size.toByte()) + sps +
            byteArrayOf(1, 0, pps.size.toByte()) + pps
        val out = ByteArrayOutputStream()
        out.write(packet(1, 0x16, avcc, w = 1170f, h = 2532f))
        out.write(packet(0, 0, enc.update(lengthPrefixed(idr)), ntp = (5L shl 32) or (1L shl 31)))
        out.write(packet(5, 0, byteArrayOf(1, 2, 3)))
        out.write(packet(0, 0, enc.update(lengthPrefixed(p))))

        val events = ArrayList<MirrorEvent>()
        MirrorReader(MirrorCipher(key, id)).run(ByteArrayInputStream(out.toByteArray())) { events += it }
        assertEquals(3, events.size)
        val c = events[0] as MirrorEvent.Config
        assertEquals(1170, c.width); assertEquals(2532, c.height)
        assertContentEquals(byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps, c.annexB)
        val f1 = events[1] as MirrorEvent.Frame
        assertTrue(f1.key)
        assertEquals(5_500_000L, f1.ptsUs)
        assertContentEquals(byteArrayOf(0, 0, 0, 1) + idr, f1.annexB)
        val f2 = events[2] as MirrorEvent.Frame
        assertTrue(!f2.key)
        assertContentEquals(byteArrayOf(0, 0, 0, 1) + p, f2.annexB)
    }

    @Test
    fun mirrorStopsOnHugeOrBrokenPacket() {
        val events = ArrayList<MirrorEvent>()
        val huge = ByteArray(128).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0, Int.MAX_VALUE) }
        MirrorReader(MirrorCipher(ByteArray(16), 1)).run(ByteArrayInputStream(huge)) { events += it }
        assertTrue(events.isEmpty())
        assertNull(MirrorReader.lengthsToStartCodes(byteArrayOf(0, 0, 0, 9, 1)))
        assertNull(MirrorReader.avcConfig(byteArrayOf(1, 2, 3, 4, 5, 6, 0x7f, 0x7f)))
    }

    @Test
    fun digestPassword() {
        // Пример из RFC 2617, раздел 3.5.
        val auth = "Digest username=\"Mufasa\", realm=\"testrealm@host.com\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", " +
            "uri=\"/dir/index.html\", qop=auth, nc=00000001, cnonce=\"0a4f113b\", response=\"6629fae49393a05397450978507c4ef1\", " +
            "opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""
        assertTrue(AirPlayServer.digestOk("GET", auth, "Circle Of Life", "dcd98b7102dd2f0e8b11d0f600bfb0c093"))
        assertTrue(!AirPlayServer.digestOk("GET", auth, "wrong", "dcd98b7102dd2f0e8b11d0f600bfb0c093"))
        assertTrue(!AirPlayServer.digestOk("GET", auth, "Circle Of Life", "other-nonce"))
    }

    @Test
    fun ntpTime() {
        val b = ByteArray(8)
        AirPlayServer.putNtp(b, 0, 1500)
        val sec = ByteBuffer.wrap(b).getInt(0).toLong() and 0xFFFFFFFFL
        assertEquals(2_208_988_801L, sec)
        assertEquals(500_000L, MirrorReader.ntpToUs(1L shl 31))
    }

    private fun ctr(secret: ByteArray): Cipher {
        val sha = MessageDigest.getInstance("SHA-512")
        val k = sha.digest("Pair-Verify-AES-Key".toByteArray() + secret).copyOf(16)
        val iv = sha.digest("Pair-Verify-AES-IV".toByteArray() + secret).copyOf(16)
        return Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(iv)) }
    }

    private fun lengthPrefixed(nal: ByteArray) = ByteBuffer.allocate(4).putInt(nal.size).array() + nal

    private fun packet(type: Int, option: Int, payload: ByteArray, ntp: Long = 0, w: Float = 0f, h: Float = 0f): ByteArray {
        val hdr = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(0, payload.size)
        hdr.put(4, type.toByte()); hdr.put(6, option.toByte())
        hdr.putLong(8, ntp)
        hdr.putFloat(16, w); hdr.putFloat(20, h); hdr.putFloat(40, w); hdr.putFloat(44, h); hdr.putFloat(56, w); hdr.putFloat(60, h)
        return hdr.array() + payload
    }
}
