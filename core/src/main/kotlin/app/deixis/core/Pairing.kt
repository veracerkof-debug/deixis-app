package app.deixis.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Сопряжение: приёмник показывает PIN, источник вводит его один раз и получает постоянный токен.
 * После пяти неверных PIN код меняется, а новые попытки не принимаются какое-то время (30 с, дальше вдвое
 * дольше, до 10 мин) — так четырёхзначный PIN не подобрать перебором из той же сети.
 */
class Pairing(
    private val tokens: TokenStore,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    var pin: String = newPin()
        private set

    private var failures = 0
    private var lockedUntil = 0L
    private var lockMs = LOCK_FIRST_MS

    /** Вызывается при смене PIN, чтобы экран приёмника показал новый. */
    var onPinChanged: (String) -> Unit = {}

    @Synchronized
    fun pair(pin: String, sender: String): Result {
        val now = clock()
        if (now < lockedUntil) return Result.Denied(DenyReason.TOO_MANY_ATTEMPTS)
        if (!constantTimeEquals(pin, this.pin)) {
            failures++
            if (failures >= MAX_FAILURES) {
                rotate()
                lockedUntil = now + lockMs
                lockMs = (lockMs * 2).coerceAtMost(LOCK_MAX_MS)
                return Result.Denied(DenyReason.TOO_MANY_ATTEMPTS)
            }
            return Result.Denied(DenyReason.WRONG_PIN)
        }
        val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        tokens.add(PairedSender(token, sender, now))
        lockMs = LOCK_FIRST_MS
        rotate()
        return Result.Ok(token)
    }

    fun isValid(token: String?): Boolean = token != null && tokens.all().any { constantTimeEquals(it.token, token) }

    fun senders(): List<PairedSender> = tokens.all()

    fun forget(token: String) = tokens.remove(token)

    @Synchronized
    private fun rotate() {
        failures = 0
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
    }
}

@Serializable
data class PairedSender(val token: String, val sender: String, val pairedAt: Long)

interface TokenStore {
    fun all(): List<PairedSender>
    fun add(sender: PairedSender)
    fun remove(token: String)
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
    }
}

private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
