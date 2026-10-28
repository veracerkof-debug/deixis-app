package app.deixis.core

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/*
 * Обновления через выпуски GitHub: телефон, ТВ, Windows, Mac. Сеть — только по действию оператора:
 * «Проверить» или включённое им «Автоматически». Файл выпуска проверяется по SHA-256 из выпуска
 * до установки; оборванная загрузка ничего не трогает — работает прежняя версия.
 */

/** Стабильный — для служения и занятий; пробный — сборки для тестеров (пометка «pre-release» на GitHub). */
@Serializable
enum class UpdateChannel { STABLE, TEST }

/** По кнопке — сеть только когда оператор нажал «Проверить»; автоматически — проверка при запуске и раз в сутки. */
@Serializable
enum class UpdateMode { MANUAL, AUTO }

@Serializable
data class UpdateSettings(val mode: UpdateMode = UpdateMode.MANUAL, val channel: UpdateChannel = UpdateChannel.STABLE)

/** Что обновляется: от этого зависит, какой файл выпуска нужен. */
enum class UpdateTarget { PHONE, TV, WINDOWS, MAC, LINUX }

data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

data class Release(val version: String, val tag: String, val test: Boolean, val notes: String, val assets: List<ReleaseAsset>)

/** Версия «1.2.3» как числа; «v» в начале и хвост после «-» не учитываются. null — не версия. */
fun parseVersion(s: String): List<Int>? {
    val core = s.trim().removePrefix("v").substringBefore('-').substringBefore('+')
    val parts = core.split('.').map { it.toIntOrNull() ?: return null }
    return parts.takeIf { it.isNotEmpty() }
}

fun compareVersions(a: String, b: String): Int {
    val x = parseVersion(a) ?: emptyList()
    val y = parseVersion(b) ?: emptyList()
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

@Serializable
private data class GhAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String,
    /** «sha256:…» — GitHub считает сам для каждого файла выпуска. */
    val digest: String? = null,
)

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tag: String,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

/** Ответ GitHub `GET /repos/…/releases`. Черновики и метки не-версии пропускаются. */
fun parseReleases(json: String): List<Release> =
    DeixisJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GhRelease.serializer()), json)
        .filter { !it.draft && parseVersion(it.tag) != null }
        .map { r ->
            Release(
                version = r.tag.removePrefix("v"),
                tag = r.tag,
                test = r.prerelease,
                notes = r.body.orEmpty(),
                assets = r.assets.map { a ->
                    ReleaseAsset(a.name, a.url, a.size, a.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase())
                },
            )
        }

/** Стабильный канал видит только стабильные выпуски; пробный — все, самый новый. */
fun latestFor(releases: List<Release>, channel: UpdateChannel): Release? =
    releases.filter { channel == UpdateChannel.TEST || !it.test }.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }

/**
 * Файл выпуска для этого устройства. Android: сборка под первый подходящий процессор из [abis]
 * (как его называет система), иначе общая `all`. Имена — из release.yml.
 */
fun pickAsset(release: Release, target: UpdateTarget, abis: List<String> = emptyList()): ReleaseAsset? {
    val v = Regex.escape(release.version)
    fun apk(prefix: String): ReleaseAsset? {
        val re = Regex("^${Regex.escape(prefix)}-$v-(.+)\\.apk$")
        val byAbi = release.assets.mapNotNull { a -> re.find(a.name)?.let { it.groupValues[1] to a } }.toMap()
        return abis.firstNotNullOfOrNull { byAbi[it] } ?: byAbi["all"]
    }
    return when (target) {
        UpdateTarget.PHONE -> apk("Deixis")
        UpdateTarget.TV -> apk("Deixis-TV")
        UpdateTarget.WINDOWS -> release.assets.firstOrNull { it.name.endsWith(".msi", true) }
        UpdateTarget.MAC -> release.assets.firstOrNull { it.name.endsWith(".dmg", true) }
        UpdateTarget.LINUX -> release.assets.firstOrNull { it.name.endsWith(".deb", true) }
    }
}

/** `SHA256SUMS.txt` выпуска: «хэш  имя» в строке, как у sha256sum. */
fun parseSha256Sums(text: String): Map<String, String> = text.lines().mapNotNull { line ->
    val m = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$").find(line.trim()) ?: return@mapNotNull null
    m.groupValues[2].trim() to m.groupValues[1].lowercase()
}.toMap()

/** Раздел журнала изменений: версия и её строки. */
data class ChangelogEntry(val version: String, val lines: List<String>)

/** `CHANGELOG.md`: разделы «## 0.9.2», под ними строки «- …». */
fun parseChangelog(text: String): List<ChangelogEntry> {
    val out = ArrayList<ChangelogEntry>()
    var version: String? = null
    var lines = ArrayList<String>()
    fun flush() { version?.let { out += ChangelogEntry(it, lines) }; lines = ArrayList() }
    for (raw in text.lines()) {
        val line = raw.trimEnd()
        val head = Regex("^##\\s+v?(\\d+(?:\\.\\d+)*)").find(line)
        if (head != null) { flush(); version = head.groupValues[1]; continue }
        if (version != null && line.isNotBlank() && !line.startsWith("#")) lines += line.trim().removePrefix("- ").removePrefix("* ")
    }
    flush()
    return out
}

/** «Что нового» между установленной раньше версией [from] (не включая) и текущей [to], новые сверху. */
fun whatsNew(changelog: String, from: String, to: String): List<ChangelogEntry> =
    parseChangelog(changelog)
        .filter { compareVersions(it.version, from) > 0 && compareVersions(it.version, to) <= 0 }
        .sortedWith { a, b -> compareVersions(b.version, a.version) }

/** Журнал изменений, вшитый в приложение: «Что нового» видно и после установки с флешки, без сети. */
fun bundledChangelog(): String =
    ReceiverCore::class.java.getResourceAsStream("/changelog.md")?.use { it.readBytes().decodeToString() }.orEmpty()

/** Состояние обновления для экрана настроек и для пульта (`/api/update`). */
@Serializable
data class UpdateState(
    val current: String,
    val settings: UpdateSettings = UpdateSettings(),
    /** idle, checking, uptodate, available, downloading, ready, installing, error. */
    val phase: String = "idle",
    val available: String? = null,
    val notes: List<String> = emptyList(),
    val progress: Float = 0f,
    val error: String? = null,
    /** Дата последней проверки (мс). */
    val checkedAt: Long = 0,
)

@Serializable
data class UpdateRequest(val action: String, val mode: UpdateMode? = null, val channel: UpdateChannel? = null)

/** Обновления приёмника, которыми управляет пульт: ТВ без своего экрана настроек, компьютер. */
interface UpdateHost {
    val state: StateFlow<UpdateState>
    fun check()
    fun install()
    fun setSettings(s: UpdateSettings)
}

internal fun Route.updateRoutes(core: ReceiverCore, authorized: ApplicationCall.() -> Boolean) {
    suspend fun ApplicationCall.reply(host: UpdateHost) =
        respondText(DeixisJson.encodeToString(UpdateState.serializer(), host.state.value), ContentType.Application.Json)
    get("/api/update") {
        if (!call.authorized()) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
        val host = core.updates ?: return@get call.respondText("", status = HttpStatusCode.NotImplemented)
        call.reply(host)
    }
    post("/api/update") {
        if (!call.authorized()) return@post call.respondText("", status = HttpStatusCode.Unauthorized)
        val host = core.updates ?: return@post call.respondText("", status = HttpStatusCode.NotImplemented)
        val req = DeixisJson.decodeFromString(UpdateRequest.serializer(), call.receiveText())
        if (req.mode != null || req.channel != null) {
            val s = host.state.value.settings
            host.setSettings(s.copy(mode = req.mode ?: s.mode, channel = req.channel ?: s.channel))
        }
        when (req.action) {
            "check" -> host.check()
            "install" -> host.install()
        }
        call.reply(host)
    }
}

/** Сеть для обновлений: список выпусков и загрузка файла. В проверках подменяется. */
interface ReleaseSource {
    suspend fun releases(): List<Release>
    suspend fun text(url: String): String
    suspend fun download(url: String, dest: File, onProgress: (Long) -> Unit)
}

/**
 * Выпуски проекта на GitHub. Без входа в учётную запись API даёт 60 запросов в час на адрес, а у мобильного
 * оператора один адрес на многих абонентов — лимит бывает исчерпан чужими запросами. Тогда описание берётся
 * из файла `release.json` последнего выпуска: это обычная загрузка файла, лимита API у неё нет.
 */
class GitHubReleases(private val repo: String = REPO) : ReleaseSource {
    override suspend fun releases(): List<Release> = releasesWithFallback(
        { parseReleases(text("https://api.github.com/repos/$repo/releases?per_page=30")) },
        { parseReleases(text("https://github.com/$repo/releases/latest/download/$RELEASE_FILE")) },
    )

    override suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        open(url).let { c -> try { c.inputStream.use { it.readBytes().decodeToString() } } finally { c.disconnect() } }
    }

    override suspend fun download(url: String, dest: File, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val c = open(url)
        try {
            dest.outputStream().use { out ->
                c.inputStream.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onProgress(total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", if (url.contains("api.github.com")) "application/vnd.github+json" else "*/*")
        c.setRequestProperty("User-Agent", "Deixis")
        if (c.responseCode !in 200..299) {
            val code = c.responseCode
            c.disconnect()
            throw java.io.IOException(if (code == 403 || code == 429) "GitHub временно ограничил проверки, попробуйте позже" else "GitHub ответил $code")
        }
        return c
    }

    companion object {
        const val REPO = "veracerkof-debug/deixis-app"
        /** Описание выпуска в том же виде, что ответ API (release.yml кладёт его в каждый выпуск). */
        const val RELEASE_FILE = "release.json"
        private const val TIMEOUT_MS = 20_000
    }
}

/**
 * Список выпусков: сначала [primary] (API GitHub со всеми выпусками, и пробными), при ошибке — [fallback]
 * (последний стабильный выпуск). Не вышло и так — видна ошибка основного пути: она понятнее.
 */
suspend fun releasesWithFallback(primary: suspend () -> List<Release>, fallback: suspend () -> List<Release>): List<Release> =
    try {
        primary()
    } catch (e: java.io.IOException) {
        try { fallback() } catch (_: Exception) { throw e }
    }

fun sha256File(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { inp ->
        val buf = ByteArray(64 * 1024)
        while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Обновление одного приложения: проверка, загрузка с проверкой SHA-256, передача файла установщику
 * платформы. Когда ставить — решает платформа ([install] вызывает она или оператор), чтобы обновление
 * не началось посреди показа.
 */
class Updater(
    private val current: String,
    private val target: UpdateTarget,
    private val dir: File,
    private val scope: CoroutineScope,
    private val abis: List<String> = emptyList(),
    private val source: ReleaseSource = GitHubReleases(),
    /** Ставит проверенный файл. Ошибку бросает — она видна в настройках. */
    private val installer: suspend (File, Release) -> Unit,
) : UpdateHost {
    private val settingsFile = File(dir, "update-settings.json")
    private val seenFile = File(dir, "seen-version")
    private val _state = MutableStateFlow(UpdateState(current, loadSettings()))
    override val state: StateFlow<UpdateState> = _state
    private val lock = Mutex()
    private var release: Release? = null
    private var file: File? = null
    private var auto: Job? = null

    init {
        dir.mkdirs()
        // Файлы прошлых обновлений не копятся.
        dir.listFiles()?.filter { it.name.startsWith("update-") && !it.name.endsWith(".json") }?.forEach { it.delete() }
    }

    private fun loadSettings(): UpdateSettings = runCatching {
        DeixisJson.decodeFromString(UpdateSettings.serializer(), settingsFile.readText())
    }.getOrDefault(UpdateSettings())

    /** Автоматически: проверка сейчас и раз в сутки, пока приложение открыто. По кнопке — ничего. */
    fun start() {
        auto?.cancel()
        if (_state.value.settings.mode != UpdateMode.AUTO) return
        auto = scope.launch {
            while (isActive) {
                runCheck(download = true)
                delay(AUTO_PERIOD_MS)
            }
        }
    }

    override fun setSettings(s: UpdateSettings) {
        val old = _state.value.settings
        runCatching { dir.mkdirs(); settingsFile.writeText(DeixisJson.encodeToString(UpdateSettings.serializer(), s)) }
        _state.value = _state.value.copy(settings = s)
        if (old.channel != s.channel) { release = null; file = null; _state.value = _state.value.copy(phase = "idle", available = null, notes = emptyList()) }
        if (old.mode != s.mode) start()
    }

    override fun check() {
        scope.launch { runCheck(download = _state.value.settings.mode == UpdateMode.AUTO) }
    }

    override fun install() {
        scope.launch {
            lock.withLock {
                if (release == null) checkLocked()
                val r = release ?: return@withLock
                val f = file ?: downloadLocked(r) ?: return@withLock
                _state.value = _state.value.copy(phase = "installing", error = null)
                try {
                    installer(f, r)
                } catch (e: Throwable) {
                    _state.value = _state.value.copy(phase = "error", error = e.message ?: "Обновление не установилось")
                }
            }
        }
    }

    /** Установщик вернул управление, а приложение продолжает работать (оператор отменил установку). */
    fun installCancelled(reason: String? = null) {
        _state.value = _state.value.copy(phase = if (file != null) "ready" else "available", error = reason)
    }

    private suspend fun runCheck(download: Boolean) = lock.withLock {
        checkLocked()
        val r = release
        if (download && r != null && file == null) downloadLocked(r)
    }

    private suspend fun checkLocked() {
        _state.value = _state.value.copy(phase = "checking", error = null)
        try {
            val all = source.releases()
            val latest = latestFor(all, _state.value.settings.channel)
            val now = System.currentTimeMillis()
            if (latest == null || compareVersions(latest.version, current) <= 0 || pickAsset(latest, target, abis) == null) {
                release = null
                file = null
                _state.value = _state.value.copy(phase = "uptodate", available = null, notes = emptyList(), checkedAt = now)
                return
            }
            if (release?.version != latest.version) file = null
            release = latest
            val notes = whatsNewOf(all, latest)
            _state.value = _state.value.copy(phase = if (file != null) "ready" else "available", available = latest.version, notes = notes, checkedAt = now)
        } catch (e: Throwable) {
            _state.value = _state.value.copy(phase = "error", error = netError(e))
        }
    }

    /** Строки «Что нового» из выпусков между текущей версией и новой. */
    private fun whatsNewOf(all: List<Release>, latest: Release): List<String> =
        all.filter { compareVersions(it.version, current) > 0 && compareVersions(it.version, latest.version) <= 0 }
            .sortedWith { a, b -> compareVersions(b.version, a.version) }
            .flatMap { r -> notesLines(r.notes) }

    private suspend fun downloadLocked(r: Release): File? {
        val asset = pickAsset(r, target, abis) ?: return null
        val dest = File(dir, "update-${r.version}-${asset.name}")
        try {
            val expected = asset.sha256 ?: r.assets.firstOrNull { it.name == SUMS }?.let { parseSha256Sums(source.text(it.url))[asset.name] }
                ?: throw java.io.IOException("В выпуске нет контрольной суммы файла: установка отменена")
            _state.value = _state.value.copy(phase = "downloading", progress = 0f, error = null)
            val part = File(dest.path + ".part")
            source.download(asset.url, part) { n ->
                if (asset.size > 0) _state.value = _state.value.copy(progress = (n.toFloat() / asset.size).coerceIn(0f, 1f))
            }
            if (asset.size > 0 && part.length() != asset.size) { part.delete(); throw java.io.IOException("Файл скачался не полностью") }
            if (sha256File(part) != expected) { part.delete(); throw java.io.IOException("Файл повреждён при загрузке (контрольная сумма не совпала)") }
            dest.delete()
            if (!part.renameTo(dest)) throw java.io.IOException("Не удалось сохранить файл обновления")
            file = dest
            _state.value = _state.value.copy(phase = "ready", progress = 1f)
            return dest
        } catch (e: Throwable) {
            _state.value = _state.value.copy(phase = "error", error = netError(e))
            return null
        }
    }

    private fun netError(e: Throwable): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "Нет связи с GitHub"
        else -> e.message ?: "Не удалось проверить обновления"
    }

    /**
     * «Что нового» один раз после обновления: разделы журнала между прошлой запущенной версией и этой.
     * Первый запуск после установки с нуля — пусто. Отмечает версию просмотренной.
     */
    fun takeWhatsNew(changelog: String = bundledChangelog()): List<ChangelogEntry> {
        val seen = runCatching { seenFile.readText().trim() }.getOrNull()
        runCatching { dir.mkdirs(); seenFile.writeText(current) }
        if (seen.isNullOrBlank() || compareVersions(seen, current) >= 0) return emptyList()
        return whatsNew(changelog, seen, current)
    }

    companion object {
        const val SUMS = "SHA256SUMS.txt"
        private const val AUTO_PERIOD_MS = 24L * 60 * 60 * 1000
    }
}

/** Строки списка из описания выпуска (Markdown): «- …» без разметки. */
fun notesLines(body: String): List<String> =
    body.lines().map { it.trim() }.filter { it.startsWith("- ") || it.startsWith("* ") }
        .map { it.drop(2).replace("**", "").replace("`", "") }
