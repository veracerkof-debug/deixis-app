package app.deixis.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Сопряжение: приёмник показывает PIN, источник вводит его один раз и получает постоянный токен.
 * После пяти неверных PIN код меняется, а новые попытки с того же адреса не принимаются какое-то время (30 с,
 * дальше вдвое дольше, до 10 мин) — так четырёхзначный PIN не подобрать перебором из той же сети. Пауза — для
 * каждого адреса своя: чужое устройство, перебирающее PIN, не закрывает сопряжение настоящему пульту.
 * Этот же компьютер (loopback) паузы не получает.
 */
class Pairing(
    private val tokens: TokenStore,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    var pin: String = newPin()
        private set

    /** Попытки одного адреса: сколько ошибок подряд, до какого времени закрыт, следующая пауза. */
    private class Attempts(var failures: Int = 0, var lockedUntil: Long = 0L, var lockMs: Long = LOCK_FIRST_MS)
    private val attempts = LinkedHashMap<String, Attempts>()

    /** Вызывается при смене PIN, чтобы экран приёмника показал новый. */
    var onPinChanged: (String) -> Unit = {}

    @Synchronized
    fun pair(pin: String, sender: String, from: String? = null, secure: Boolean = false): Result {
        val now = clock()
        val local = from == null || from in LOOPBACK_ADDRESSES
        val a = if (local) null else attempts.getOrPut(from!!) { Attempts() }.also { trimAttempts() }
        if (a != null && now < a.lockedUntil) return Result.Denied(DenyReason.TOO_MANY_ATTEMPTS)
        if (!constantTimeEquals(pin, this.pin)) {
            if (a == null) return Result.Denied(DenyReason.WRONG_PIN)
            a.failures++
            if (a.failures >= MAX_FAILURES) {
                rotate()
                a.failures = 0
                a.lockedUntil = now + a.lockMs
                a.lockMs = (a.lockMs * 2).coerceAtMost(LOCK_MAX_MS)
                return Result.Denied(DenyReason.TOO_MANY_ATTEMPTS)
            }
            return Result.Denied(DenyReason.WRONG_PIN)
        }
        val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        tokens.add(PairedSender(token, sender, now, secure))
        from?.let { attempts.remove(it) }
        rotate()
        return Result.Ok(token)
    }

    private fun trimAttempts() {
        while (attempts.size > MAX_TRACKED) attempts.remove(attempts.keys.first())
    }

    /**
     * Токен действителен. [secure] — запрос пришёл по шифрованному каналу. Токен, однажды прошедший по нему,
     * по открытому HTTP больше не принимается: его нельзя подсмотреть в сети и нельзя «откатить» пульт на HTTP.
     */
    fun isValid(token: String?, secure: Boolean = false): Boolean {
        if (token == null) return false
        val s = tokens.all().firstOrNull { constantTimeEquals(it.token, token) } ?: return false
        if (s.secure && !secure) return false
        if (secure && !s.secure) tokens.markSecure(s.token)
        return true
    }

    fun senders(): List<PairedSender> = tokens.all()

    fun forget(token: String) = tokens.remove(token)

    @Synchronized
    private fun rotate() {
        pin = newPin()
        onPinChanged(pin)
    }

    private fun newPin() = (random.nextInt(9000) + 1000).toString()

    sealed interface Result {
        data class Ok(val token: String) : Result
        data class Denied(val reason: DenyReason) : Result
    }

    companion object {
        const val MAX_FAILURES = 5
        const val LOCK_FIRST_MS = 30_000L
        const val LOCK_MAX_MS = 10 * 60_000L
        /** Сколько адресов помнится: старые забываются, память не растёт от перебора адресов. */
        private const val MAX_TRACKED = 256
        private val LOOPBACK_ADDRESSES = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost")
    }
}

@Serializable
data class PairedSender(
    val token: String,
    val sender: String,
    val pairedAt: Long,
    /** Токен получен или уже использован по шифрованному каналу: по открытому HTTP он больше не принимается. */
    val secure: Boolean = false,
)

interface TokenStore {
    fun all(): List<PairedSender>
    fun add(sender: PairedSender)
    fun remove(token: String)

    /** Токен ходит только по шифрованному каналу. */
    fun markSecure(token: String) {
        val s = all().firstOrNull { it.token == token } ?: return
        if (s.secure) return
        remove(token)
        add(s.copy(secure = true))
    }
}

class MemoryTokenStore : TokenStore {
    private val list = mutableListOf<PairedSender>()
    @Synchronized override fun all() = list.toList()
    @Synchronized override fun add(sender: PairedSender) { list += sender }
    @Synchronized override fun remove(token: String) { list.removeAll { it.token == token } }
}

/** Токены сопряжённых источников в JSON-файле. Запись через временный файл, чтобы сбой не испортил список. */
class FileTokenStore(private val file: File) : TokenStore {
    private val serializer = ListSerializer(PairedSender.serializer())
    private var cache: MutableList<PairedSender> = load()

    @Synchronized override fun all() = cache.toList()
    @Synchronized override fun add(sender: PairedSender) { cache += sender; save() }
    @Synchronized override fun remove(token: String) { cache.removeAll { it.token == token }; save() }

    private fun load(): MutableList<PairedSender> = try {
        if (file.isFile) DeixisJson.decodeFromString(serializer, file.readText()).toMutableList() else mutableListOf()
    } catch (e: Exception) {
        mutableListOf()
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(DeixisJson.encodeToString(serializer, cache))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        // Токены читает только владелец (на компьютере; на Android папка приложения и так закрыта), как ключ TLS.
        runCatching { file.setReadable(false, false); file.setReadable(true, true) }
    }
}

private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
