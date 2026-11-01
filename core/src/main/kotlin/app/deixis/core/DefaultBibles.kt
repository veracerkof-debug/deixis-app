package app.deixis.core

import java.io.File

/**
 * Встроенные переводы: Синодальный (RST) и King James Version (KJV), оба — общественное достояние.
 * Лежат в ресурсах ядра (/bible/…, собираются tools/bible/make_modules.py) и копируются в папку модулей
 * при первом запуске. Отметка в папке помнит, какие уже ставились, поэтому удалённый модуль не возвращается.
 */
object DefaultBibles {
    /** Первый — основной перевод по умолчанию, второй — параллельный. */
    val FILES = listOf("RST.SQLite3", "KJV.SQLite3")
    const val MARKER = ".defaults-installed"

    /**
     * Первые встроенные модули нумеровали послания по порядку KJV (Римлянам — 520), а в MyBible Римлянам — 590,
     * Иакова — 520. Нетронутая копия такого модуля заменяется новой: иначе второй перевод из настоящего модуля
     * MyBible и Holyrics получали бы не ту книгу.
     */
    private const val BOOKS_V2 = "books-v2"
    private val OUTDATED = mapOf(
        "RST.SQLite3" to "117b05de4889313f587d9dda7db7f390afbca401c0615b066bf77bb0c139024e",
        "KJV.SQLite3" to "72e8863c4f827986fe2c86e702f5c29bbac4042e92ffb4592f7bfd4316db307d",
    )

    /** Номера посланий в старых встроенных модулях → номера MyBible (для сохранённых мест из Писания). */
    val OLD_BOOKS = mapOf(
        520 to 590, 530 to 600, 540 to 610, 550 to 620, 560 to 630, 570 to 640, 580 to 650, 590 to 660, 600 to 670,
        610 to 680, 620 to 690, 630 to 700, 640 to 710, 650 to 720, 660 to 520, 670 to 530, 680 to 540, 690 to 550,
        700 to 560, 710 to 570, 720 to 580,
    )

    private fun sha256Of(f: File): String = f.inputStream().use { input ->
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Ставит ещё не ставившиеся встроенные модули в [dir]. Возвращает имена поставленных сейчас. Сбой — не ошибка: модули можно добавить вручную. */
    @Synchronized
    fun install(dir: File): List<String> = runCatching {
        dir.mkdirs()
        val marker = File(dir, MARKER)
        val done = if (marker.isFile) marker.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet() else mutableSetOf()
        val installed = mutableListOf<String>()
        for (name in FILES.filter { it !in done }) {
            val target = File(dir, name)
            // Свой файл с тем же именем не трогаем и считаем поставленным.
            if (!target.exists()) {
                val stream = DefaultBibles::class.java.getResourceAsStream("/bible/$name") ?: continue
                val tmp = File(dir, "$name.tmp")
                stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(target)) { tmp.delete(); continue }
                installed += name
            }
            done += name
        }
        // Старые встроенные модули (до номеров книг как в MyBible) — заменить, если файл не меняли.
        if (BOOKS_V2 !in done) for (name in FILES) {
            val target = File(dir, name)
            val old = OUTDATED[name] ?: continue
            if (!target.isFile || sha256Of(target) != old) continue
            val stream = DefaultBibles::class.java.getResourceAsStream("/bible/$name") ?: continue
            val tmp = File(dir, "$name.tmp")
            stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.renameTo(target)) installed += name else tmp.delete()
        }
        done += BOOKS_V2
        marker.writeText(done.joinToString("\n", postfix = "\n"))
        installed
    }.getOrDefault(emptyList())
}
