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
        marker.writeText(done.joinToString("\n", postfix = "\n"))
        installed
    }.getOrDefault(emptyList())
}
