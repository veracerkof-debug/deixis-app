package app.deixis.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DlnaTest {

    @Test
    fun ssdpAndXmlHelpers() {
        val resp = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.50:49152/description.xml\r\nST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n"
        assertEquals("http://192.168.1.50:49152/description.xml", Dlna.header(resp, "LOCATION"))
        assertEquals("PLAYING", Dlna.tag("<s:Body><CurrentTransportState>PLAYING</CurrentTransportState></s:Body>", "CurrentTransportState"))
        assertEquals("0:01:05", DlnaController.hms(65_400))
        assertEquals(3_723_000, DlnaController.parseHms("1:02:03.250"))
        assertEquals(0, DlnaController.parseHms("NOT_IMPLEMENTED"))
        assertTrue(DlnaController.didl("http://x/a.jpg", "Пасха & Рождество", "image/jpeg").contains("object.item.imageItem.photo"))
        assertTrue(DlnaController.didl("http://x/a.jpg", "Пасха & Рождество", "image/jpeg").contains("Пасха &amp; Рождество"))
    }

    @Test
    fun controlsAFakeTv() = runBlocking<Unit> {
        val actions = mutableListOf<Pair<String, String>>()
        val tv = embeddedServer(io.ktor.server.cio.CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/desc.xml") {
                    call.respondText(
                        """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
                        <friendlyName>Samsung &amp; Co TV</friendlyName><UDN>uuid:tv-1</UDN><serviceList>
                        <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service>
                        <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/upnp/control/AVTransport1</controlURL></service>
                        </serviceList></device></root>""",
                        ContentType.Text.Xml,
                    )
                }
                post("/upnp/control/AVTransport1") {
                    val action = call.request.header("SOAPACTION")!!.substringAfter('#').trim('"')
                    actions += action to call.receiveText()
                    val body = when (action) {
                        "GetTransportInfo" -> "<CurrentTransportState>PLAYING</CurrentTransportState>"
                        "GetPositionInfo" -> "<RelTime>0:00:12</RelTime><TrackDuration>0:03:00</TrackDuration>"
                        else -> ""
                    }
                    call.respondText("""<s:Envelope><s:Body><u:${action}Response>$body</u:${action}Response></s:Body></s:Envelope>""", ContentType.Text.Xml)
                }
            }
        }.start(wait = false)
        val port = tv.engine.resolvedConnectors().first().port
        try {
            val r = Dlna.describe("http://127.0.0.1:$port/desc.xml")!!
            assertEquals("Samsung & Co TV", r.name)
            assertEquals("http://127.0.0.1:$port/upnp/control/AVTransport1", r.controlUrl)
            DlnaController(r).use { c ->
                c.play("http://10.0.0.2:5000/dlna/ab/media.mp4", "Видео", "video/mp4")
                c.seek(90_000)
                c.pause()
                val st = c.state()
                assertTrue(st.playing)
                assertEquals(12_000, st.positionMs)
                assertEquals(180_000, st.durationMs)
            }
            assertEquals(listOf("Stop", "SetAVTransportURI", "Play", "Seek", "Pause", "GetTransportInfo", "GetPositionInfo"), actions.map { it.first })
            val set = actions[1].second
            assertTrue(set.contains("<CurrentURI>http://10.0.0.2:5000/dlna/ab/media.mp4</CurrentURI>"))
            assertTrue(set.contains("&lt;DIDL-Lite"), "метаданные экранированы внутри SOAP")
            assertTrue(actions[3].second.contains("<Target>0:01:30</Target>"))
        } finally {
            tv.stop(100, 500)
        }
    }

    @Test
    fun fileServerGivesRangesAndDlnaHeaders() = runBlocking<Unit> {
        val f = File(createTempDirectory("dlna").toFile(), "clip.mp4").apply { writeBytes(ByteArray(10_000) { (it % 251).toByte() }) }
        val server = DlnaFileServer("127.0.0.1")
        server.start()
        val http = HttpClient(CIO)
        try {
            val url = server.share(f, "video/mp4", "127.0.0.1")
            val r = http.get(url) { header("Range", "bytes=100-199") }
            assertEquals(HttpStatusCode.PartialContent, r.status)
            assertTrue(r.bodyAsBytes().contentEquals(f.readBytes().copyOfRange(100, 200)))
            assertEquals("Streaming", r.headers["transferMode.dlna.org"])
            // Старые ссылки перестают работать, когда показ сменился.
            repeat(3) { server.share(f, "video/mp4", "127.0.0.1") }
            server.forgetAllBut(2)
            assertEquals(HttpStatusCode.NotFound, http.get(url).status)
        } finally {
            http.close()
            server.stop()
        }
    }
}
