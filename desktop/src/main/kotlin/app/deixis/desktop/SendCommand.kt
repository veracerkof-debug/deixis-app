package app.deixis.desktop

import app.deixis.core.Connection
import app.deixis.core.Content
import app.deixis.core.DeixisJson
import app.deixis.core.Discovery
import app.deixis.core.ImageContent
import app.deixis.core.MediaKind
import app.deixis.core.Mode
import app.deixis.core.PairingException
import app.deixis.core.PdfContent
import app.deixis.core.ReceiverServer
import app.deixis.core.SenderClient
import app.deixis.core.TextContent
import app.deixis.core.UploadSource
import app.deixis.core.VideoAction
import app.deixis.core.VideoCommand
import app.deixis.core.VideoContent
import app.deixis.core.mediaKindOf
import app.deixis.core.mimeOf
import app.deixis.core.splitIntoSlides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.InputStream
import java.net.InetAddress

/** `deixis send`: показать с компьютера на приёмник. Возвращает код выхода. */
class SendCommand(private val opts: Map<String, String>, private val tokensFile: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun run(): Int = runBlocking {
        val to = opts["to"] ?: return@runBlocking fail("Укажите приёмник: --to АДРЕС[:ПОРТ]")
        val host = to.substringBefore(':')
        val port = to.substringAfter(':', "").toIntOrNull() ?: ReceiverServer.DEFAULT_PORT
        val key = "$host:$port"
        val client = SenderClient(host, port, senderName(), scope)
        try {
            val token = tokens()[key] ?: run {
                val pin = opts["pin"] ?: return@runBlocking fail("Первое подключение: укажите --pin с экрана приёмника")
                val t = try { client.pair(pin) } catch (e: PairingException) { return@runBlocking fail("Приёмник отклонил PIN: ${e.reason}") }
                saveToken(key, t)
                t
            }
            client.connect(token)
            val conn = withTimeoutOrNull(10_000) { client.connection.first { it is Connection.Connected || it is Connection.Denied } }
            when (conn) {
                is Connection.Connected -> {
                    println("Подключено: «${conn.receiver.name}»")
                    client.viewUrl()?.let { println("Для зрителей: $it") }
                    client.viewLocalUrl()?.let { println("Или по имени в сети: $it") }
                }
                is Connection.Denied -> { saveToken(key, null); return@runBlocking fail("Приёмник не узнал этот компьютер, подключитесь заново с --pin") }
                else -> return@runBlocking fail("Нет связи с $key: ${(client.connection.value as? Connection.Lost)?.reason ?: "таймаут"}")
            }
            scope.launch { client.errors.collect { System.err.println("Приёмник: ${it.message}") } }

            when {
                "mode" in opts -> client.mode(Mode.valueOf(opts.getValue("mode").uppercase()))
                "video" in opts -> client.video(parseVideo(opts.getValue("video")) ?: return@runBlocking fail("Неизвестная команда видео"))
                else -> {
                    val content = prepare(client) ?: return@runBlocking 2
                    client.show(content)
                    val shown = withTimeoutOrNull(10_000) { client.state.filterNotNull().first { it.content == content } }
                    if (shown == null) return@runBlocking fail("Приёмник не подтвердил показ")
                    println("Показано")
                }
            }
            delay(300)
            opts["watch"]?.toLongOrNull()?.let { seconds ->
                repeat(seconds.toInt()) {
                    val s = client.state.value
                    println(buildString {
                        append("режим ${s?.mode}")
                        s?.video?.let { v -> append(", видео ${if (v.playing) "идёт" else "пауза"} ${v.positionMs / 1000}/${v.durationMs / 1000} с") }
                        s?.error?.let { append(", ошибка: $it") }
                    })
                    delay(1_000)
                }
            }
            0
        } finally {
            client.close()
        }
    }

    private suspend fun prepare(client: SenderClient): Content? {
        opts["text"]?.let { text ->
            val slides = splitIntoSlides(text)
            if (slides.isEmpty()) { fail("Пустой текст"); return null }
            val n = (opts["slide"]?.toIntOrNull() ?: 1).coerceIn(1, slides.size)
            return TextContent(slides[n - 1])
        }
        val path = opts["file"] ?: run { fail("Укажите файл или --text"); return null }
        val file = File(path)
        if (!file.isFile) { fail("Нет файла $path"); return null }
        val kind = mediaKindOf(file.name)
        if (kind == null) { fail("Формат «${file.name}» пока не поддерживается"); return null }
        if (kind == MediaKind.PRESENTATION) { fail("PPTX, PPT и ODP пока нужно сохранить как PDF"); return null }
        if (kind == MediaKind.TEXT) return TextContent(splitIntoSlides(file.readText()).firstOrNull() ?: "")
        val source = object : UploadSource {
            override val name = file.name
            override val size = file.length()
            override val mime = mimeOf(file.name)
            override fun open(): InputStream = file.inputStream()
        }
        var lastPercent = -1
        val ref = client.upload(source) { sent, total ->
            val p = if (total == 0L) 100 else (sent * 100 / total).toInt()
            if (p / 10 != lastPercent / 10) { lastPercent = p; println("Передано $p%") }
        }
        return when (kind) {
            MediaKind.IMAGE -> ImageContent(ref)
            MediaKind.VIDEO -> VideoContent(ref, loop = "loop" in opts)
            MediaKind.PDF -> PdfContent(ref, page = (opts["page"]?.toIntOrNull() ?: 1) - 1)
            MediaKind.PRESENTATION, MediaKind.TEXT -> null
        }
    }

    private fun parseVideo(s: String): VideoCommand? = when {
        s == "play" -> VideoCommand(VideoAction.PLAY)
        s == "pause" -> VideoCommand(VideoAction.PAUSE)
        s.startsWith("seek=") -> s.removePrefix("seek=").toLongOrNull()?.let { VideoCommand(VideoAction.SEEK, positionMs = it) }
        s.startsWith("volume=") -> s.removePrefix("volume=").toIntOrNull()?.let { VideoCommand(VideoAction.VOLUME, volume = it) }
        else -> null
    }

    private val tokenMap = MapSerializer(String.serializer(), String.serializer())

    private fun tokens(): Map<String, String> =
        runCatching { DeixisJson.decodeFromString(tokenMap, tokensFile.readText()) }.getOrDefault(emptyMap())

    private fun saveToken(key: String, token: String?) {
        val map = tokens().toMutableMap()
        if (token == null) map.remove(key) else map[key] = token
        tokensFile.parentFile.mkdirs()
        tokensFile.writeText(DeixisJson.encodeToString(tokenMap, map))
    }

    private fun senderName() = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Компьютер")

    private fun fail(msg: String): Int {
        System.err.println(msg)
        return 1
    }
}

/** `deixis list`: приёмники в сети. */
fun listReceivers(seconds: Long): Int = runBlocking {
    println("Поиск приёмников $seconds с…")
    val seen = mutableSetOf<String>()
    withTimeoutOrNull(seconds * 1000) {
        Discovery.browse().collect { list ->
            list.filter { seen.add(it.id) }.forEach { println("${it.name}  ${it.host}:${it.port}  ${it.platform}") }
        }
    }
    if (seen.isEmpty()) println("Ничего не найдено. Можно указать адрес с экрана приёмника: deixis send --to АДРЕС")
    0
}
