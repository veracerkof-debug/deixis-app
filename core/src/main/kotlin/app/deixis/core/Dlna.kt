package app.deixis.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.UUID

/*
 * DLNA/UPnP: открытый стандарт, который понимают почти все «умные» телевизоры. Запасной путь «на всякий случай»:
 * телевизор сам скачивает фото или видео с телефона и играет. Чёрный экран, логотип и слайды идут картинками,
 * поэтому смена слайда занимает доли секунды или пару секунд — как успеет телевизор. Основной путь — приёмник Deixis.
 */

/** Телевизор или приставка с DLNA, найденные в сети. */
data class DlnaRenderer(
    val id: String,
    val name: String,
    /** Адрес управления AVTransport (SOAP). */
    val controlUrl: String,
    val host: String,
)

/** Состояние воспроизведения по DLNA. */
data class DlnaState(val playing: Boolean, val positionMs: Long, val durationMs: Long)

object Dlna {
    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** Ищет DLNA-экраны: запрос SSDP M-SEARCH и описания найденных устройств. */
    suspend fun discover(timeoutMs: Int = 2_500, http: HttpClient = defaultHttp()): List<DlnaRenderer> = withContext(Dispatchers.IO) {
        val locations = linkedSetOf<String>()
        MulticastSocket(null).use { sock ->
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(0))
            sock.soTimeout = 300
            val msg = "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_ADDR:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $AV_TRANSPORT\r\n\r\n".toByteArray()
            val group = InetAddress.getByName(SSDP_ADDR)
            val until = System.currentTimeMillis() + timeoutMs
            var sent = 0
            while (System.currentTimeMillis() < until) {
                // Запрос повторяется: UDP теряется, а телевизоры отвечают не сразу.
                if (sent < 3) { sock.send(DatagramPacket(msg, msg.size, group, SSDP_PORT)); sent++ }
                val buf = ByteArray(4096)
                val p = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(p)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                header(String(p.data, 0, p.length), "LOCATION")?.let { locations += it }
            }
        }
        locations.mapNotNull { runCatching { describe(it, http) }.getOrNull() }.distinctBy { it.id }
    }

    /** Читает описание устройства и находит адрес управления AVTransport. */
    suspend fun describe(location: String, http: HttpClient = defaultHttp()): DlnaRenderer? {
        val xml = http.get(location).bodyAsText()
        val name = tag(xml, "friendlyName") ?: "DLNA"
        val udn = tag(xml, "UDN") ?: location
        val service = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { it.groupValues[1] }.firstOrNull { tag(it, "serviceType")?.startsWith("urn:schemas-upnp-org:service:AVTransport") == true }
            ?: return null
        val control = tag(service, "controlURL") ?: return null
        val base = tag(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: location
        val url = URI(base).resolve(control.trim()).toString()
        return DlnaRenderer(udn, unescape(name), url, URI(location).host)
    }

    internal fun header(response: String, name: String): String? =
        response.lineSequence().firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()

    internal fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    internal fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    internal fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    fun defaultHttp() = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 8_000
        }
    }
}

/** Управление одним DLNA-экраном по AVTransport. */
class DlnaController(val renderer: DlnaRenderer, private val http: HttpClient = Dlna.defaultHttp()) : AutoCloseable {

    /** Показывает файл по ссылке [url]: телевизор сам его скачивает. */
    suspend fun play(url: String, title: String, mime: String) {
        // Перед новой ссылкой — Stop: часть телевизоров иначе игнорирует SetAVTransportURI.
        runCatching { action("Stop") }
        action("SetAVTransportURI", "<CurrentURI>${Dlna.escape(url)}</CurrentURI><CurrentURIMetaData>${Dlna.escape(didl(url, title, mime))}</CurrentURIMetaData>")
        action("Play", "<Speed>1</Speed>")
    }

    suspend fun pause() { action("Pause") }
    suspend fun resume() { action("Play", "<Speed>1</Speed>") }
    suspend fun stop() { action("Stop") }

    suspend fun seek(positionMs: Long) {
        action("Seek", "<Unit>REL_TIME</Unit><Target>${hms(positionMs)}</Target>")
    }

    suspend fun state(): DlnaState {
        val t = action("GetTransportInfo")
        val p = action("GetPositionInfo")
        return DlnaState(
            playing = Dlna.tag(t, "CurrentTransportState") == "PLAYING",
            positionMs = parseHms(Dlna.tag(p, "RelTime")),
            durationMs = parseHms(Dlna.tag(p, "TrackDuration")),
        )
    }

    private suspend fun action(name: String, args: String = ""): String {
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><u:$name xmlns:u="${Dlna.AV_TRANSPORT}"><InstanceID>0</InstanceID>$args</u:$name></s:Body></s:Envelope>"""
        val r = http.post(renderer.controlUrl) {
            header("SOAPACTION", "\"${Dlna.AV_TRANSPORT}#$name\"")
            setBody(TextContent(body, ContentType.parse("text/xml; charset=\"utf-8\"")))
        }
        val text = r.bodyAsText()
        if (!r.status.isSuccess()) throw IOException("«${renderer.name}» отказал ($name): ${Dlna.tag(text, "errorDescription") ?: r.status.value}")
        return text
    }

    override fun close() = http.close()

    companion object {
        internal fun hms(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        }

        internal fun parseHms(s: String?): Long {
            val parts = s?.substringBefore('.')?.split(':')?.mapNotNull { it.toLongOrNull() } ?: return 0
            if (parts.size != 3) return 0
            return ((parts[0] * 60 + parts[1]) * 60 + parts[2]) * 1000
        }

        /** Описание файла DIDL-Lite: без него часть телевизоров не знает, фото это или видео. */
        internal fun didl(url: String, title: String, mime: String): String {
            val cls = when {
                mime.startsWith("image/") -> "object.item.imageItem.photo"
                mime.startsWith("audio/") -> "object.item.audioItem.musicTrack"
                else -> "object.item.videoItem"
            }
            return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
                """<item id="0" parentID="-1" restricted="1"><dc:title>${Dlna.escape(title)}</dc:title><upnp:class>$cls</upnp:class>""" +
                """<res protocolInfo="http-get:*:$mime:*">${Dlna.escape(url)}</res></item></DIDL-Lite>"""
        }
    }
}

/**
 * Раздача файлов телевизору по DLNA: телефон или компьютер отдаёт по HTTP только то, что сейчас показано.
 * Ссылки случайные и действуют, пока файл зарегистрирован.
 */
class DlnaFileServer(private val bindHost: String = "0.0.0.0") {
    private val files: MutableMap<String, Pair<File, String>> = java.util.Collections.synchronizedMap(LinkedHashMap())
    private var server: EmbeddedServer<*, *>? = null
    var port = 0
        private set

    suspend fun start(): Int {
        val s = embeddedServer(io.ktor.server.cio.CIO, port = 0, host = bindHost) {
            install(io.ktor.server.plugins.partialcontent.PartialContent)
            install(io.ktor.server.plugins.autohead.AutoHeadResponse)
            routing {
                get("/dlna/{id}/{name}") {
                    val (file, mime) = files[call.parameters["id"]] ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
                    // Заголовки DLNA: без них часть телевизоров не начинает воспроизведение.
                    call.response.header("transferMode.dlna.org", if (mime.startsWith("image/")) "Interactive" else "Streaming")
                    call.response.header("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
                    call.respond(LocalFileContent(file, ContentType.parse(mime)))
                }
            }
        }
        s.start(wait = false)
        server = s
        port = s.engine.resolvedConnectors().first().port
        return port
    }

    /** Ссылка на файл для телевизора; [address] — адрес этого устройства в той же сети. */
    fun share(file: File, mime: String, address: String): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        files[id] = file to mime
        val ext = file.name.substringAfterLast('.', "bin")
        return "http://$address:$port/dlna/$id/media.$ext"
    }

    /** Убирает старые ссылки, оставляя последние [keep]. */
    fun forgetAllBut(keep: Int = 2) {
        synchronized(files) {
            if (files.size <= keep) return
            files.keys.toList().dropLast(keep).forEach { files.remove(it) }
        }
    }

    fun stop() {
        server?.stop(200, 1_000)
        server = null
        files.clear()
    }
}
