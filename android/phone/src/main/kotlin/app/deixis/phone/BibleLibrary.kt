package app.deixis.phone

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import app.deixis.core.BibleModule
import app.deixis.core.DefaultBibles
import app.deixis.core.DeixisJson
import app.deixis.core.ScriptureOptions
import app.deixis.core.SqlSource
import app.deixis.receiver.AndroidSql
import java.io.File

/** Модули Библии на телефоне: файлы .SQLite3 копируются в память приложения и открываются только на чтение. */
class BibleLibrary(private val context: Context) {
    private val dir = File(context.filesDir, "bible").apply { mkdirs(); DefaultBibles.install(this) }
    private val prefs = context.getSharedPreferences("bible", Context.MODE_PRIVATE)
    private val open = mutableMapOf<String, BibleModule>()

    fun modules(): List<BibleModule> = dir.listFiles { f -> f.isFile && f.name.endsWith(".SQLite3", ignoreCase = true) }
        .orEmpty().sortedBy { it.name.lowercase() }
        .mapNotNull { f -> open.getOrPut(f.name) { runCatching { BibleModule(AndroidSql(f), f.name) }.getOrNull() ?: return@mapNotNull null } }

    /** Добавляет модуль. Если файл не модуль Библии MyBible, бросает исключение с понятным текстом. */
    fun import(uri: Uri): BibleModule {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "module.SQLite3"
        if (name.contains(".commentaries", true) || name.contains(".dictionary", true) || name.contains(".crossreferences", true)) {
            error("«$name» — не перевод Библии. Нужен основной файл модуля, например RST.SQLite3")
        }
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").let { if (it.endsWith(".SQLite3", true)) it else "$it.SQLite3" }
        val target = File(dir, safe)
        val tmp = File(dir, "$safe.tmp")
        context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        try {
            BibleModule(AndroidSql(tmp), safe).use { m ->
                if (m.books.isEmpty()) error("В «$name» нет книг: это не модуль Библии MyBible")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw IllegalStateException(e.message ?: "«$name» не открывается как модуль MyBible")
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
        get() = prefs.getString("options", null)?.let { runCatching { DeixisJson.decodeFromString(ScriptureOptions.serializer(), it) }.getOrNull() } ?: ScriptureOptions()
        set(value) = prefs.edit().putString("options", DeixisJson.encodeToString(ScriptureOptions.serializer(), value)).apply()

    /** Выбранные переводы: первый — основной, второй — параллельный (может не быть). Без выбора — Синодальный. */
    var selected: List<String>
        get() = prefs.getString("selected", null)?.split('|')?.filter { it.isNotBlank() }.orEmpty().ifEmpty { DefaultBibles.FILES.take(1) }
        set(value) = prefs.edit().putString("selected", value.joinToString("|")).apply()

    var lastRef: String
        get() = prefs.getString("ref", "") ?: ""
        set(value) = prefs.edit().putString("ref", value).apply()
}
