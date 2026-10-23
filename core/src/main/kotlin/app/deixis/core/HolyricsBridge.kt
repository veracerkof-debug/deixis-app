package app.deixis.core

import kotlinx.serialization.Serializable
import java.io.File

/** Запрос источника: взять файл из библиотеки Holyrics на компьютере приёмника. */
@Serializable
data class HolyricsMediaRequest(
    /** Порт API Server Holyrics на этом компьютере. */
    val port: Int = HolyricsClient.DEFAULT_PORT,
    val token: String,
    val kind: HolyricsMedia,
    /** Имя файла, как его отдаёт Holyrics (с подпапкой): «пасха.jpg», «фоны/море.mp4». */
    val name: String,
)

/**
 * Мост к файлам Holyrics для Deixis на том же компьютере. API Holyrics отдаёт только список файлов
 * и миниатюры, а сами фото и видео лежат обычными файлами в его папке. Приёмник спрашивает у Holyrics
 * на 127.0.0.1, где эта папка: так нельзя подсунуть чужую папку, и мост работает, только если Holyrics
 * с этим токеном запущен здесь же. Читаются только фото, видео и аудио внутри папки Holyrics.
 */
class HolyricsBridge(
    private val files: FileStore,
    private val client: (port: Int, token: String) -> HolyricsClient = { port, token -> HolyricsClient("127.0.0.1", port, token) },
) {
    private var index: Pair<String, Map<String, List<File>>>? = null
    private var indexedAt = 0L

    /** Находит файл, кладёт его в хранилище приёмника и возвращает ссылку для показа. */
    suspend fun import(req: HolyricsMediaRequest): FileRef {
        val base = client(req.port, req.token).use { it.version().baseDir }
            ?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory }
            ?: throw HolyricsException("Holyrics на этом компьютере не сообщил свою папку: фото и видео взять нельзя")
        val file = find(base, req.kind, req.name) ?: throw HolyricsException("В папке Holyrics нет «${req.name}»")
        val sha = sha256Of(file)
        if (!files.status(sha).complete) file.inputStream().use { files.append(sha, file.length(), 0, it) }
        return FileRef(sha, file.name, file.length(), mimeOf(file.name))
    }

    /** Файл [name] внутри [base] с расширением этого вида; при нескольких — тот, что в папке вида (image, video…). */
    internal fun find(base: File, kind: HolyricsMedia, name: String): File? {
        val rel = name.replace('\\', '/').trim('/')
        if (rel.isEmpty() || rel.split('/').any { it == ".." || it == "." }) return null
        if (!allowed(kind, rel)) return null
        val root = base.canonicalFile
        val candidates = indexOf(root)[rel.substringAfterLast('/').lowercase()].orEmpty()
            .filter { it.invariantSeparatorsPath.lowercase().endsWith("/" + rel.lowercase()) }
            .filter { it.canonicalFile.startsWith(root) }
        val hint = kind.name.lowercase()
        return candidates.firstOrNull { it.invariantSeparatorsPath.lowercase().contains("/$hint") } ?: candidates.firstOrNull()
    }

    private fun allowed(kind: HolyricsMedia, rel: String): Boolean {
        val ext = rel.substringAfterLast('.', "").lowercase()
        return when (kind) {
            HolyricsMedia.IMAGE -> mediaKindOf(rel) == MediaKind.IMAGE
            HolyricsMedia.VIDEO -> mediaKindOf(rel) == MediaKind.VIDEO
            HolyricsMedia.AUDIO -> ext in setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "wma")
            HolyricsMedia.FILE -> mediaKindOf(rel) in setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.PDF)
        }
    }

    /** Указатель файлов папки Holyrics по имени; обновляется не чаще раза в минуту. */
    @Synchronized
    private fun indexOf(root: File): Map<String, List<File>> {
        val now = System.currentTimeMillis()
        index?.let { (path, map) -> if (path == root.path && now - indexedAt < 60_000) return map }
        val map = HashMap<String, MutableList<File>>()
        var seen = 0
        root.walkTopDown().maxDepth(8).onEnter { !it.name.startsWith(".") }.forEach { f ->
            if (++seen > 300_000) return@forEach
            if (f.isFile) map.getOrPut(f.name.lowercase()) { mutableListOf() } += f
        }
        index = root.path to map
        indexedAt = now
        return map
    }
}
