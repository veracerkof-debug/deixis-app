package app.deixis.phone

import app.deixis.core.DeixisJson
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Резервная копия: файл, когда сделана, почему, сколько в ней материалов. */
data class BackupInfo(val name: String, val at: String, val reason: String, val items: Int, val setlists: Int, val external: Boolean, val uri: String? = null)

/**
 * Резервные копии библиотеки: сетлисты, материалы и тексты песен, темы, настройки (`library.json`).
 * Сами копии — в памяти приложения (последние [KEEP]): раз в сутки при запуске и перед каждым обновлением.
 * Ещё одна копия — в папке, которую оператор выбрал сам (флешка, Google Диск, «Документы»): она переживает
 * потерю или сброс телефона. Большие видео и фото не копируются: в копии — ссылки на них.
 */
class Backups(
    private val platform: Platform,
    root: File = platform.filesDir,
    /** Начало имени копии: у каждого профиля своё, копии профилей не путаются и в общей папке. */
    private val prefix: String = "deixis-",
    private val lastKey: String = "last",
) {
    private val dir = File(root, "backups").apply { mkdirs() }
    private val nameRe = Regex("^" + Regex.escape(prefix) + "(\\d{8}-\\d{6})-(.+)\\.json$")
    private val prefs = platform.prefs("backups")

    /** Папка вне приложения, выбранная оператором (на телефоне — через систему, на компьютере — любая). */
    var folder: String?
        get() = prefs.getString("folder", null)
        set(v) { prefs.edit { putString("folder", v) } }

    fun lastAt(): Long = prefs.getLong(lastKey, 0)

    /** Копия, если последней больше суток. */
    fun daily(data: StoreData) {
        if (System.currentTimeMillis() - lastAt() >= DAY_MS) save(data, "daily")
    }

    /** Копия сейчас: «before-update-0.10.0», «manual», «before-restore». Возвращает имя файла. */
    fun save(data: StoreData, reason: String): String {
        val stamp = LocalDateTime.now().format(STAMP)
        val name = "$prefix$stamp-$reason.json"
        val json = DeixisJson.encodeToString(StoreData.serializer(), data)
        if (!writeDurably(File(dir, name), json)) throw java.io.IOException(L.backupNoSpace)
        val local = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.sortedBy { it.name }
        // Одна копия постарше (не новее трёх дней до последней) не вытесняется: семь копий подряд после
        // случайного удаления не стирают последнюю хорошую.
        val anchor = local.lastOrNull()?.let { newest -> local.lastOrNull { newest.lastModified() - it.lastModified() >= ANCHOR_AGE_MS } }
        prune(local.filter { it != anchor }) { it.delete() }
        runCatching { folder?.let { writeExternal(it, name, json) } }
        prefs.edit { putLong(lastKey, System.currentTimeMillis()) }
        return name
    }

    private fun <T> prune(sortedOldFirst: List<T>, delete: (T) -> Unit) {
        if (sortedOldFirst.size > KEEP) sortedOldFirst.take(sortedOldFirst.size - KEEP).forEach { runCatching { delete(it) } }
    }

    private fun writeExternal(tree: String, name: String, json: String) {
        platform.folderWrite(tree, name, json.toByteArray())
        prune(externalFiles(tree).filter { it.second.startsWith(prefix) }.sortedBy { it.second }) { (uri, _) -> platform.folderDelete(uri) }
    }

    private fun externalFiles(tree: String): List<Pair<String, String>> = platform.folderFiles(tree).filter { it.second.endsWith(".json") }

    /** Копии, новые сверху: в памяти приложения и в выбранной папке. */
    fun list(): List<BackupInfo> {
        val local = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.mapNotNull { f -> info(f.name, false, null) { f.readText() } }
        val ext = runCatching { folder?.let { t -> externalFiles(t).mapNotNull { (u, n) -> info(n, true, u) { read(u) } } } }.getOrNull().orEmpty()
        return (local + ext).sortedByDescending { it.name }
    }

    private fun info(name: String, external: Boolean, uri: String?, text: () -> String): BackupInfo? {
        val m = nameRe.find(name) ?: return null
        val d = runCatching { StoreRead.read(text())?.data }.getOrNull() ?: return null
        val at = runCatching { LocalDateTime.parse(m.groupValues[1], STAMP).format(SHOW) }.getOrDefault(m.groupValues[1])
        return BackupInfo(name, at, m.groupValues[2], d.items.size, d.setlists.size, external, uri)
    }

    private fun read(uri: String): String = platform.open(uri)?.use { input ->
        // Случайно выбранный огромный файл не роняет приложение нехваткой памяти.
        val bytes = input.readNBytesCompat(MAX_BACKUP_BYTES + 1)
        if (bytes.size > MAX_BACKUP_BYTES) error(L.notBackupTooBig)
        bytes.decodeToString()
    } ?: error(L.backupUnreadable)

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Данные копии; бросает, если файл не читается. */
    fun load(b: BackupInfo): StoreData {
        val text = if (b.uri != null) read(b.uri) else File(dir, b.name).readText()
        // Копия с более новой версии читается по частям, а не отвергается целиком.
        return StoreRead.read(text)?.data ?: error(L.notBackup)
    }

    /** Данные из любого файла копии (перенос на новый телефон). */
    fun loadFile(uri: String): StoreData = StoreRead.read(read(uri))?.data ?: error(L.notBackup)

    /** Самая свежая читаемая копия — на случай, если основной файл испорчен. */
    fun newest(): StoreData? = list().firstNotNullOfOrNull { runCatching { load(it) }.getOrNull() }

    fun takeFolder(uri: String) {
        runCatching { platform.keepFolder(uri) }
        folder = uri
    }

    companion object {
        const val KEEP = 7
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val ANCHOR_AGE_MS = 3 * DAY_MS
        private const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val SHOW: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMMM, HH:mm", I18n.locale)

        fun reasonText(r: String): String = when {
            r == "daily" -> L.bkDaily
            r == "manual" -> L.bkManual
            r == "before-restore" -> L.bkBeforeRestore
            r == "broken" -> L.bkBroken
            r.startsWith("before-update-") -> L.beforeUpdate(r.removePrefix("before-update-"))
            else -> r
        }
    }
}
