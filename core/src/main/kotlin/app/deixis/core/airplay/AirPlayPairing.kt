package app.deixis.core.airplay

/*
 * Сопряжение AirPlay (pair-setup, pair-verify) и ключи потока зеркала.
 *
 * Происхождение: переписано на Kotlin по lib/pairing.c, lib/raop_handlers.h и lib/mirror_buffer.c
 * проекта UxPlay (https://github.com/FDH2/UxPlay, GPL-3.0). Те файлы происходят из RPiPlay
 * (dsafa22, 2019, LGPL-2.1+) и shairplay (Juho Vähä-Herttua, 2011–2018, LGPL-2.1+);
 * доработки — Florian Draschbacher (2019), Jaslo Ziska (2020), F. Duncanh (UxPlay).
 * Подробности — в NOTICE.
 */

import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Постоянный ключ приёмника AirPlay (Ed25519) и его адрес-имя для mDNS.
 * Хранится в файле, чтобы iPhone узнавал этот экран после перезапуска.
 */
class AirPlayIdentity private constructor(private val seed: ByteArray, val deviceId: String) {
    val publicKey: ByteArray = ByteArray(Ed25519.PUBLIC_KEY_SIZE).also { Ed25519.generatePublicKey(seed, 0, it, 0) }

    fun sign(message: ByteArray): ByteArray =
        ByteArray(Ed25519.SIGNATURE_SIZE).also { Ed25519.sign(seed, 0, message, 0, message.size, it, 0) }

    companion object {
        /** Читает ключ из файла или создаёт новый: 32 байта ключа и 6 байт адреса. */
        fun load(file: File): AirPlayIdentity {
            val bytes = file.takeIf { it.isFile }?.readBytes()?.takeIf { it.size == 38 }
                ?: ByteArray(38).also { b ->
                    SecureRandom().nextBytes(b)
                    // Адрес «местный» и не групповой: так он не совпадёт с настоящим адресом сетевой карты.
                    b[32] = ((b[32].toInt() and 0xFC) or 0x02).toByte()
                    file.parentFile?.mkdirs()
                    file.writeBytes(b)
                }
            return of(bytes)
        }

        fun of(bytes: ByteArray): AirPlayIdentity {
            require(bytes.size == 38)
            val mac = (32 until 38).joinToString(":") { "%02X".format(bytes[it]) }
            return AirPlayIdentity(bytes.copyOfRange(0, 32), mac)
        }
    }
}

/** Одно соединение iPhone: проверка ключей и общий секрет, которым дополнительно закрыт ключ потока. */
class AirPlayPairSession(private val identity: AirPlayIdentity, private val random: SecureRandom = SecureRandom()) {
    private var setupDone = false
    private var theirEd: ByteArray? = null
    private var theirEcdh: ByteArray? = null
    private var ourEcdh: ByteArray? = null

    /** Общий секрет X25519; есть после первого шага pair-verify. */
    var ecdhSecret: ByteArray? = null
        private set

    var verified = false
        private set

    /** pair-setup: iPhone присылает свой ключ (32 байта), в ответ — наш постоянный открытый ключ. */
    fun setup(body: ByteArray): ByteArray? {
        if (body.size != 32) return null
        setupDone = true
        return identity.publicKey
    }

    /** pair-verify. Первый шаг возвращает наш ключ X25519 и зашифрованную подпись, второй — пустой ответ. */
    fun verify(body: ByteArray): ByteArray? {
        if (!setupDone || body.size < 4) return null
        return when (body[0].toInt()) {
            1 -> {
                if (body.size != 4 + 32 + 32) return null
                val theirX = body.copyOfRange(4, 36)
                val theirE = body.copyOfRange(36, 68)
                val priv = ByteArray(X25519.SCALAR_SIZE).also(random::nextBytes)
                val pub = ByteArray(X25519.POINT_SIZE).also { X25519.scalarMultBase(priv, 0, it, 0) }
                val secret = ByteArray(X25519.POINT_SIZE)
                if (!X25519.calculateAgreement(priv, 0, theirX, 0, secret, 0)) return null
                theirEcdh = theirX; theirEd = theirE; ourEcdh = pub; ecdhSecret = secret
                val signature = identity.sign(pub + theirX)
                pub + verifyCipher(secret).update(signature)
            }
            0 -> {
                val secret = ecdhSecret ?: return null
                if (body.size != 4 + 64) return null
                val cipher = verifyCipher(secret)
                cipher.update(ByteArray(64)) // поток ключа продолжается после нашей подписи
                val signature = cipher.update(body, 4, 64)
                val message = theirEcdh!! + ourEcdh!!
                if (!Ed25519.verify(signature, 0, theirEd!!, 0, message, 0, message.size)) return null
                verified = true
                ByteArray(0)
            }
            else -> null
        }
    }

    private fun verifyCipher(secret: ByteArray): Cipher {
        val key = sha512("Pair-Verify-AES-Key".toByteArray() + secret).copyOf(16)
        val iv = sha512("Pair-Verify-AES-IV".toByteArray() + secret).copyOf(16)
        return Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
    }
}

internal fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

/** Ключ из SETUP после FairPlay; при сопряжении он ещё смешивается с общим секретом. */
fun airPlaySessionKey(fairPlayKey: ByteArray, ecdhSecret: ByteArray?): ByteArray =
    if (ecdhSecret == null) fairPlayKey.copyOf(16) else sha512(fairPlayKey.copyOf(16) + ecdhSecret).copyOf(16)

/**
 * Расшифровка видео зеркала: AES-CTR, ключ и IV зависят от streamConnectionID.
 * Поток ключа непрерывен через все пакеты: так он устроен у отправителя.
 */
class MirrorCipher(sessionKey: ByteArray, streamConnectionId: Long) {
    private val cipher: Cipher

    init {
        val id = java.lang.Long.toUnsignedString(streamConnectionId)
        val key = sha512("AirPlayStreamKey$id".toByteArray() + sessionKey).copyOf(16)
        val iv = sha512("AirPlayStreamIV$id".toByteArray() + sessionKey).copyOf(16)
        cipher = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
    }

    fun decrypt(data: ByteArray): ByteArray = cipher.update(data) ?: ByteArray(0)
}
