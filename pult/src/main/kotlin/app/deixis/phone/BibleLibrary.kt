package app.deixis.phone

import app.deixis.core.BibleModule
import app.deixis.core.DefaultBibles
import app.deixis.core.DeixisJson
import app.deixis.core.ScriptureOptions
import app.deixis.core.SqlSource
import java.io.File

/** Модули Библии на телефоне: файлы .SQLite3 копируются в память приложения и открываются только на чтение. */
class BibleLibrary(private val platform: Platform) {
    private val dir = File(platform.filesDir, "bible").apply { mkdirs(); DefaultBibles.install(this) }
    private val prefs = platform.prefs("bible")
    private val open = mutableMapOf<String, BibleModule>()

    fun modules(): List<BibleModule> = dir.listFiles { f -> f.isFile && f.name.endsWith(".SQLite3", ignoreCase = true) }
        .orEmpty().sortedBy { it.name.lowercase() }
        .mapNotNull { f -> open.getOrPut(f.name) { runCatching { BibleModule(platform.sql(f), f.name) }.getOrNull() ?: return@mapNotNull null } }

    /** Добавляет модуль. Если файл не модуль Библии MyBible, бросает исключение с понятным текстом. */
    fun import(uri: String): BibleModule {
        val name = platform.meta(uri).name.ifBlank { "module.SQLite3" }
        if (name.contains(".commentaries", true) || name.contains(".dictionary", true) || name.contains(".crossreferences", true)) {
            error(L.notBible(name))
        }
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").let { if (it.endsWith(".SQLite3", true)) it else "$it.SQLite3" }
        val target = File(dir, safe)
        val tmp = File(dir, "$safe.tmp")
        (platform.open(uri) ?: error(L.notMyBible(name))).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        try {
            BibleModule(platform.sql(tmp), safe).use { m ->
                if (m.books.isEmpty()) error(L.noBooks(name))
            }
        } catch (e: Exception) {
            tmp.delete()
            throw IllegalStateException(e.message ?: L.notMyBible(name))
        }
        open.remove(safe)?.close()
        target.delete()
        tmp.renameTo(target)
        return modules().first { it.fileName == safe }
    }

    fun remove(fileName: String) {
        open.remove(fileName)?.close()
        File(dir, fileName).delete()
    }

    var options: ScriptureOptions
        // По умолчанию два перевода рядом: русский и английский в две колонки.
        get() = prefs.getString("options", null)?.let { runCatching { DeixisJson.decodeFromString(ScriptureOptions.serializer(), it) }.getOrNull() } ?: ScriptureOptions(sideBySide = true)
        set(value) = prefs.edit { putString("options", DeixisJson.encodeToString(ScriptureOptions.serializer(), value)) }

    /** Выбранные переводы: первый — основной, второй — параллельный. Без выбора — Синодальный и King James вместе. */
    var selected: List<String>
        get() = prefs.getString("selected2", null)?.split('|')?.filter { it.isNotBlank() }.orEmpty().ifEmpty { DefaultBibles.FILES }
        set(value) = prefs.edit { putString("selected2", value.joinToString("|")) }

    /** Открытые места: «книга:глава:с:по», новые первыми. */
    var history: List<String>
        get() = prefs.getString("history", null)?.split('|')?.filter { it.isNotBlank() }.orEmpty()
        set(value) = prefs.edit { putString("history", value.take(30).joinToString("|")) }

    var lastRef: String
        get() = prefs.getString("ref", "") ?: ""
        set(value) = prefs.edit { putString("ref", value) }
}
