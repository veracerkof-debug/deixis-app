package app.deixis.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.deixis.core.DeixisJson
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Резервная копия: файл, когда сделана, почему, сколько в ней материалов. */
data class BackupInfo(val name: String, val at: String, val reason: String, val items: Int, val setlists: Int, val external: Boolean, val uri: Uri? = null)

/**
 * Резервные копии библиотеки: сетлисты, материалы и тексты песен, темы, настройки (`library.json`).
 * Сами копии — в памяти приложения (последние [KEEP]): раз в сутки при запуске и перед каждым обновлением.
 * Ещё одна копия — в папке, которую оператор выбрал сам (флешка, Google Диск, «Документы»): она переживает
 * потерю или сброс телефона. Большие видео и фото не копируются: в копии — ссылки на них.
 */
class Backups(private val context: Context) {
    private val dir = File(context.filesDir, "backups").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("backups", Context.MODE_PRIVATE)

    /** Папка вне приложения, выбранная оператором (SAF). */
    var folder: Uri?
        get() = prefs.getString("folder", null)?.let(Uri::parse)
        set(v) { prefs.edit().putString("folder", v?.toString()).apply() }

    fun lastAt(): Long = prefs.getLong("last", 0)

    /** Копия, если последней больше суток. */
    fun daily(data: StoreData) {
        if (System.currentTimeMillis() - lastAt() >= DAY_MS) save(data, "daily")
    }

    /** Копия сейчас: «before-update-0.10.0», «manual», «before-restore». Возвращает имя файла. */
    fun save(data: StoreData, reason: String): String {
        val stamp = LocalDateTime.now().format(STAMP)
        val name = "deixis-$stamp-$reason.json"
        val json = DeixisJson.encodeToString(StoreData.serializer(), data)
        File(dir, name).writeText(json)
        prune(dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.sortedBy { it.name }) { it.delete() }
        runCatching { folder?.let { writeExternal(it, name, json) } }
        prefs.edit().putLong("last", System.currentTimeMillis()).apply()
        return name
    }

    private fun <T> prune(sortedOldFirst: List<T>, delete: (T) -> Unit) {
        if (sortedOldFirst.size > KEEP) sortedOldFirst.take(sortedOldFirst.size - KEEP).forEach { runCatching { delete(it) } }
    }

    private fun writeExternal(tree: Uri, name: String, json: String) {
        val cr = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(cr, parent, "application/json", name) ?: return
        cr.openOutputStream(doc, "w")?.use { it.write(json.toByteArray()) }
        prune(externalFiles(tree).filter { it.second.startsWith("deixis-") }.sortedBy { it.second }) { (uri, _) -> DocumentsContract.deleteDocument(cr, uri) }
    }

    private fun externalFiles(tree: Uri): List<Pair<Uri, String>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<Pair<Uri, String>>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.endsWith(".json")) out += DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)) to name
            }
        }
        return out
    }

    /** Копии, новые сверху: в памяти приложения и в выбранной папке. */
    fun list(): List<BackupInfo> {
        val local = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.mapNotNull { f -> info(f.name, false, null) { f.readText() } }
        val ext = runCatching { folder?.let { t -> externalFiles(t).mapNotNull { (u, n) -> info(n, true, u) { read(u) } } } }.getOrNull().orEmpty()
        return (local + ext).sortedByDescending { it.name }
    }

    private fun info(name: String, external: Boolean, uri: Uri?, text: () -> String): BackupInfo? {
        val m = NAME.find(name) ?: return null
        val d = runCatching { DeixisJson.decodeFromString(StoreData.serializer(), text()) }.getOrNull() ?: return null
        val at = runCatching { LocalDateTime.parse(m.groupValues[1], STAMP).format(SHOW) }.getOrDefault(m.groupValues[1])
        return BackupInfo(name, at, m.groupValues[2], d.items.size, d.setlists.size, external, uri)
    }

    private fun read(uri: Uri): String = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Копия не читается")

    /** Данные копии; бросает, если файл не читается. */
    fun load(b: BackupInfo): StoreData {
        val text = if (b.uri != null) read(b.uri) else File(dir, b.name).readText()
        return DeixisJson.decodeFromString(StoreData.serializer(), text)
    }

    /** Данные из любого файла копии (перенос на новый телефон). */
    fun loadFile(uri: Uri): StoreData = DeixisJson.decodeFromString(StoreData.serializer(), read(uri))

    /** Самая свежая читаемая копия — на случай, если основной файл испорчен. */
    fun newest(): StoreData? = list().firstNotNullOfOrNull { runCatching { load(it) }.getOrNull() }

    fun takeFolder(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        folder = uri
    }

    companion object {
        const val KEEP = 7
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val SHOW = DateTimeFormatter.ofPattern("d MMMM, HH:mm", java.util.Locale.forLanguageTag("ru"))
        private val NAME = Regex("^deixis-(\\d{8}-\\d{6})-(.+)\\.json$")

        fun reasonText(r: String): String = when {
            r == "daily" -> "ежедневная"
            r == "manual" -> "вручную"
            r == "before-restore" -> "перед восстановлением"
            r == "broken" -> "испорченный файл"
            r.startsWith("before-update-") -> "перед обновлением до ${r.removePrefix("before-update-")}"
            else -> r
        }
    }
}
