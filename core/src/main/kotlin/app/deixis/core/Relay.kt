package app.deixis.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.InputStream

/** Экран в сети, к которому приёмник пересылает показ; токен получен по PIN один раз. */
@Serializable
data class RelayEntry(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val platform: String = "",
    val token: String? = null,
    /** Пересылка включена: после перезапуска связь восстанавливается сама. */
    val linked: Boolean = false,
)

interface RelayStore {
    fun load(): List<RelayEntry>
    fun save(entries: List<RelayEntry>)
}

class MemoryRelayStore : RelayStore {
    @Volatile private var list = emptyList<RelayEntry>()
    override fun load() = list
    override fun save(entries: List<RelayEntry>) { list = entries }
}

/** Экраны пересылки в JSON-файле. Запись через временный файл, чтобы сбой не испортил список. */
class FileRelayStore(private val file: File) : RelayStore {
    private val serializer = ListSerializer(RelayEntry.serializer())

    override fun load(): List<RelayEntry> = try {
        if (file.isFile) DeixisJson.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    override fun save(entries: List<RelayEntry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(DeixisJson.encodeToString(serializer, entries))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/**
 * Пересылка показа на экраны Deixis в сети (ТВ, проектор): этот приёмник становится для них источником.
 * Каждое изменение [ReceiverCore.state] повторяется на них: файлы передаются из хранилища этого приёмника,
 * затем идут `show`, режим и команды видео. После переподключения экран получает текущее состояние заново.
 * Сбой одного экрана не трогает ни другие экраны, ни свой вывод: у каждого своя задача и свои исключения.
 */
class ScreenRelay(
    private val core: ReceiverCore,
    private val store: RelayStore,
    parent: CoroutineScope,
    private val senderName: String,
    private val clientFactory: (host: String, port: Int, scope: CoroutineScope) -> SenderClient =
        { h, p, s -> SenderClient(h, p, senderName, s) },
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + CoroutineExceptionHandler { _, _ -> })
    private val lock = Any()
    private val entries = LinkedHashMap<String, RelayEntry>()
    private val found = LinkedHashMap<String, FoundReceiver>()
    private val links = HashMap<String, Link>()

    private val _outputs = MutableStateFlow<List<NetworkOutput>>(emptyList())
    val outputs: StateFlow<List<NetworkOutput>> = _outputs.asStateFlow()

    init {
        for (e in store.load()) entries[e.id] = e
        synchronized(lock) { entries.values.filter { it.linked && it.token != null }.forEach { startLink(it) } }
        publish()
    }

    /** Список экранов из поиска в сети (mDNS). Свой же приёмник в него не входит. */
    fun found(list: List<FoundReceiver>) {
        synchronized(lock) {
            found.clear()
            for (r in list) if (r.id != core.info.id) found[r.id] = r
            // Экран сменил адрес (роутер выдал новый IP): связь переходит на новый.
            for (r in found.values) {
                val e = entries[r.id] ?: continue
                if (e.host != r.host || e.port != r.port || e.name != r.name) {
                    val updated = e.copy(host = r.host, port = r.port, name = r.name, platform = r.platform)
                    entries[r.id] = updated
                    if (links[r.id]?.let { it.host != r.host || it.port != r.port } == true) {
                        links.remove(r.id)?.close()
                        if (updated.linked && updated.token != null) startLink(updated)
                    }
                }
            }
            save()
        }
        publish()
    }

    suspend fun link(request: LinkRequest): LinkResult {
        // Экран по адресу: сначала узнать его постоянный id, чтобы он не появился вторым рядом с найденным в сети.
        val req = if (request.host != null && synchronized(lock) { found[request.id] == null }) {
            val probe = clientFactory(request.host, request.port ?: ReceiverServer.DEFAULT_PORT, scope)
            try {
                val info = probe.info()
                if (info.id == core.info.id) return LinkResult(false, error = "Это адрес самого компьютера")
                synchronized(lock) {
                    if (entries[info.id] == null) entries[info.id] = RelayEntry(info.id, info.name, request.host, request.port ?: ReceiverServer.DEFAULT_PORT, info.platform)
                }
                request.copy(id = info.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                request
            } finally {
                probe.close()
            }
        } else request
        val target = synchronized(lock) {
            val known = entries[req.id]
            val f = found[req.id]
            val host = req.host ?: f?.host ?: known?.host
            val port = req.port ?: f?.port ?: known?.port ?: ReceiverServer.DEFAULT_PORT
            if (host == null) return LinkResult(false, error = "Экран не найден в сети")
            (known ?: RelayEntry(req.id, f?.name ?: host, host, port, f?.platform.orEmpty()))
                .copy(host = host, port = port, name = f?.name ?: known?.name ?: host, platform = f?.platform ?: known?.platform.orEmpty())
        }
        val pin = req.pin?.trim().orEmpty()
        val token = if (pin.isEmpty()) {
            val needPin = target.token == null || synchronized(lock) { links[target.id]?.denied == true }
            if (needPin) return LinkResult(false, needPin = true)
            target.token
        } else {
            val probe = clientFactory(target.host, target.port, scope)
            try {
                probe.pair(pin)
            } catch (e: PairingException) {
                return LinkResult(false, error = when (e.reason) {
                    DenyReason.WRONG_PIN -> "Неверный PIN"
                    DenyReason.TOO_MANY_ATTEMPTS -> "Слишком много попыток: на экране новый PIN"
                    DenyReason.INCOMPATIBLE_VERSION -> "На экране другая версия Deixis"
                    DenyReason.UNKNOWN_TOKEN -> "Экран не принял подключение"
                }, needPin = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return LinkResult(false, error = "Экран не отвечает")
            } finally {
                probe.close()
            }
        }
        synchronized(lock) {
            val entry = target.copy(token = token, linked = true)
            entries[entry.id] = entry
            save()
            links.remove(entry.id)?.close()
            startLink(entry)
        }
        publish()
        return LinkResult(true)
    }

    /** Отключает пересылку; токен остаётся, повторное подключение — без PIN. */
    fun unlink(id: String) {
        synchronized(lock) {
            links.remove(id)?.close()
            entries[id]?.let { entries[id] = it.copy(linked = false) }
            save()
        }
        publish()
    }

    fun close() {
        synchronized(lock) {
            links.values.forEach { it.close() }
            links.clear()
        }
        scope.cancel()
    }

    private fun save() = runCatching { store.save(entries.values.toList()) }

    private fun startLink(e: RelayEntry) {
        links[e.id] = Link(e.id, e.host, e.port, e.token!!)
    }

    // Список собирается и записывается под одним замком: иначе два одновременных вызова могут
    // записать устаревшее состояние («offline») поверх свежего.
    private fun publish() = synchronized(lock) {
        val list = run {
            val ids = LinkedHashSet<String>().apply { addAll(found.keys); addAll(entries.values.filter { it.linked }.map { it.id }) }
            ids.map { id ->
                val f = found[id]
                val e = entries[id]
                val link = links[id]
                NetworkOutput(
                    id = id,
                    name = f?.name ?: e?.name ?: id,
                    host = f?.host ?: e?.host.orEmpty(),
                    port = f?.port ?: e?.port ?: ReceiverServer.DEFAULT_PORT,
                    platform = f?.platform ?: e?.platform.orEmpty(),
                    state = link?.state ?: "idle",
                    linked = e?.linked == true,
                )
            }
        }
        _outputs.value = list
    }

    /** Что повторяется на экране. Ход видео сюда не входит: его экран ведёт сам. */
    private data class Mirror(
        val content: Content?,
        val next: Content?,
        val mode: Mode,
        val timer: CountdownContent? = null,
        val overlays: Overlays = Overlays(),
    )

    private inner class Link(val id: String, val host: String, val port: Int, token: String) {
        private val linkScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        private val client = clientFactory(host, port, linkScope)
        private val syncing = MutableStateFlow(false)
        @Volatile var denied = false
            private set

        val state: String
            get() = when (val c = client.connection.value) {
                is Connection.Connected -> if (syncing.value) "sync" else "online"
                is Connection.Denied -> if (c.reason == DenyReason.UNKNOWN_TOKEN) "pin" else "offline"
                else -> "offline"
            }

        // Что уже показано на экране в этой сессии связи; после обрыва — ничего.
        private var sent: Mirror? = null

        init {
            client.connect(token)
            linkScope.launch {
                client.connection.combine(syncing) { c, _ -> c }.collect { c ->
                    denied = c is Connection.Denied && c.reason == DenyReason.UNKNOWN_TOKEN
                    publish()
                }
            }
            linkScope.launch {
                client.connection.map { it is Connection.Connected }.distinctUntilChanged()
                    .combine(core.state.map { Mirror(it.content, it.next, it.mode, it.timer, it.overlays) }.distinctUntilChanged()) { online, m -> online to m }
                    .collectLatest { (online, m) ->
                        if (!online) { sent = null; syncing.value = false; return@collectLatest }
                        mirror(m)
                    }
            }
            linkScope.launch {
                core.videoCommands.collect { cmd -> if (core.state.value.content is VideoContent) client.video(cmd) }
            }
        }

        private suspend fun mirror(m: Mirror) {
            val prev = sent
            // Отсчёт для «Сцены» — сколько осталось на этот миг: экран привяжет его к своим часам.
            if (prev?.timer != m.timer && (m.content !is CountdownContent || m.timer?.id != (m.content as CountdownContent).id)) {
                client.send(SetTimer(m.timer?.forWire(System.currentTimeMillis())))
            }
            // Слой поверх показа: сначала логотип, потом сам слой. Не вышло — слайды идут и без него.
            if (prev == null || prev.overlays != m.overlays) {
                try {
                    for (ref in m.overlays.files) upload(ref)
                    client.send(SetOverlays(m.overlays))
                } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            val content = m.content
            if (content == null) {
                // Показа ещё не было: экран остаётся в ожидании, но режим повторяется.
                if (prev?.mode != m.mode && m.mode != Mode.NORMAL) client.mode(m.mode)
                sent = m
                return
            }
            if (prev != null && prev.content == content && prev.next == m.next) {
                if (prev.mode != m.mode) client.mode(m.mode)
                sent = m
                if (content is ScreenContent) forwardStream()
                return
            }
            // Файлы слайда — до показа: без них экран откажет и оставит прежний слайд.
            while (true) {
                try {
                    syncing.value = true
                    for (ref in content.files) upload(ref)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Нет места, файл не читается: повторить позже, не трогая остальное.
                    delay(RETRY_MS)
                } finally {
                    syncing.value = false
                }
            }
            val live = core.state.value
            // Видео подхватывается с того же места, где идёт здесь (экран подключился посреди фильма).
            val shown = when (content) {
                is VideoContent -> content.copy(startMs = live.video?.positionMs ?: content.startMs)
                is CountdownContent -> content.forWire(System.currentTimeMillis())
                else -> content
            }
            if (!client.show(shown, m.next)) return
            if (m.mode != Mode.NORMAL) client.mode(m.mode)
            if (content is VideoContent && live.video?.playing == false) client.video(VideoCommand(VideoAction.PAUSE))
            sent = m
            // Файлы следующего слайда — заранее, чтобы переход был мгновенным. Не вышло — не страшно.
            for (ref in m.next?.files.orEmpty()) {
                try { upload(ref) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            if (content is ScreenContent) forwardStream()
        }

        private suspend fun upload(ref: FileRef) {
            val file = core.files.file(ref.sha256) ?: throw java.io.IOException("Нет файла «${ref.name}»")
            client.upload(StoredFile(ref, file), sha256 = ref.sha256)
        }

        /** Демонстрация экрана: кадры от источника идут дальше, пока трансляция на экране. */
        private suspend fun forwardStream() {
            val stream = client.openStream(onKeyRequest = { core.stream.requestKey() })
            try {
                var sentConfig: ByteArray? = null
                core.stream.subscribe().collect { s ->
                    val cfg = s.config
                    if (s.key && cfg != null && !cfg.contentEquals(sentConfig)) {
                        stream.send(key = false, isConfig = true, ptsUs = s.ptsUs, data = cfg)
                        sentConfig = cfg
                    }
                    stream.send(s.key, false, s.ptsUs, s.data)
                }
            } finally {
                stream.close()
            }
        }

        fun close() {
            runCatching { client.close() }
            linkScope.cancel()
        }
    }

    private class StoredFile(ref: FileRef, private val file: File) : UploadSource {
        override val name = ref.name
        override val size = file.length()
        override val mime = ref.mime
        override fun open(): InputStream = file.inputStream()
    }

    private companion object {
        const val RETRY_MS = 3_000L
    }
}
