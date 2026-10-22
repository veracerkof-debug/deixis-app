package app.deixis.core

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Кэш файлов приёмника. Файл лежит под именем своего SHA-256, недокачанный — с суффиксом `.part`.
 * Готовым файл становится только после проверки хэша. Старые файлы удаляются первыми, когда кэш переполнен.
 */
class FileStore(private val dir: File, private val limitBytes: Long = 10L * 1024 * 1024 * 1024) {

    private val writing = ConcurrentHashMap.newKeySet<String>()

    init {
        dir.mkdirs()
    }

    fun file(sha256: String): File? = complete(sha256).takeIf { it.isFile }

    fun status(sha256: String): FileStatus {
        requireSha(sha256)
        val done = complete(sha256)
        if (done.isFile) return FileStatus(sha256, done.length(), true)
        return FileStatus(sha256, part(sha256).takeIf { it.isFile }?.length() ?: 0, false)
    }

    /**
     * Дописывает байты, начиная с [offset]. Смещение должно совпадать с уже полученным объёмом —
     * так докачка после обрыва не портит файл. Когда собрано [size] байт, файл проверяется.
     */
    fun append(sha256: String, size: Long, offset: Long, input: InputStream): FileStatus {
        requireSha(sha256)
        require(size >= 0) { "size < 0" }
        if (complete(sha256).isFile) return status(sha256)
        if (!writing.add(sha256)) throw StoreException("busy", "Файл уже передаётся")
        try {
            val part = part(sha256)
            val have = if (part.isFile) part.length() else 0
            if (offset != have) throw StoreException("offset", "Ожидалось смещение $have, получено $offset")
            ensureSpace(size - have)
            FileOutputStream(part, true).use { out ->
                val buf = ByteArray(64 * 1024)
                var total = have
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > size) throw StoreException("size", "Получено больше заявленного размера")
                    out.write(buf, 0, n)
                }
            }
            if (part.length() == size) finish(sha256, part)
            return status(sha256)
        } finally {
            writing.remove(sha256)
        }
    }

    /**
     * Удаляет файлы, которые не показывались дольше [maxAgeMs], и брошенные недокачки.
     * [keep] — то, что сейчас на экране: его не трогаем, даже если показ идёт очень долго.
     * Возвращает число удалённых файлов.
     */
    fun purgeOlderThan(maxAgeMs: Long, keep: Set<String> = emptySet(), now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        dir.listFiles()?.forEach { f ->
            val sha = f.name.removeSuffix(".part")
            if (!f.isFile || sha in keep || sha in writing) return@forEach
            if (now - f.lastModified() > maxAgeMs && f.delete()) removed++
        }
        return removed
    }

    /** Отмечает использование файла, чтобы очистка кэша удаляла давно не нужные. */
    fun touch(sha256: String) {
        complete(sha256).takeIf { it.isFile }?.setLastModified(System.currentTimeMillis())
    }

    private fun finish(sha256: String, part: File) {
        val actual = sha256Of(part)
        if (!actual.equals(sha256, ignoreCase = true)) {
            part.delete()
            throw StoreException("hash", "Файл повреждён при передаче, нужно отправить заново")
        }
        if (!part.renameTo(complete(sha256))) throw StoreException("io", "Не удалось сохранить файл")
    }

    private fun ensureSpace(needed: Long) {
        if (needed <= 0) return
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }?.sortedBy { it.lastModified() } ?: return
        var used = dir.listFiles()?.sumOf { it.length() } ?: 0
        for (f in files) {
            if (used + needed <= limitBytes) break
            used -= f.length()
            f.delete()
        }
        if (used + needed > limitBytes) throw StoreException("space", "Не хватает места в кэше приёмника")
    }

    private fun complete(sha256: String) = File(dir, sha256.lowercase())
    private fun part(sha256: String) = File(dir, sha256.lowercase() + ".part")

    private fun requireSha(sha256: String) {
        if (!SHA_RE.matches(sha256)) throw StoreException("sha", "Неверный идентификатор файла")
    }

    companion object {
        private val SHA_RE = Regex("^[0-9a-fA-F]{64}$")
    }
}

class StoreException(val code: String, message: String) : Exception(message)

fun sha256Of(file: File): String = file.inputStream().use { sha256Of(it) }

fun sha256Of(input: InputStream): String {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
