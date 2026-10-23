package app.deixis.core

import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.http.ContentType
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Holyrics для тестов, по документации API Server: вход по токену и по хешу
 * (sha256(nonce:rid:token:data), rid растёт), ответы в формате примеров из документации.
 */
class FakeHolyrics(private val token: String = "secret") {
    var slide = 1
    val calls = mutableListOf<String>()
    private val sessions = mutableMapOf<String, Pair<String, Long>>() // sid -> nonce, последний rid
    private var server: EmbeddedServer<*, *>? = null

    fun start(): Int {
        val s = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                post("/api/{action}") {
                    val action = call.parameters["action"]!!
                    val body = call.receiveText()
                    val q = call.request.queryParameters
                    if (action == "Auth" && q["sid"] == null) {
                        val sid = "s" + sessions.size
                        val nonce = "n" + System.nanoTime()
                        sessions[sid] = nonce to -1
                        return@post call.ok("""{"sid":"$sid","nonce":"$nonce"}""")
                    }
                    val authorized = when {
                        q["token"] != null -> q["token"] == token
                        else -> {
                            val sess = sessions[q["sid"]]
                            val rid = q["rid"]?.toLongOrNull() ?: -1
                            val data = if (action == "Auth") "auth" else body
                            sess != null && rid > sess.second && q["dtoken"] == sha256Hex("${sess.first}:$rid:$token:$data")
                                .also { if (it == q["dtoken"]) sessions[q["sid"]!!] = sess.first to rid }
                        }
                    }
                    if (!authorized) return@post call.respondText("""{"status":"error","error":"invalid token"}""", ContentType.Application.Json)
                    calls += action
                    when (action) {
                        "Auth" -> call.ok(null)
                        "GetVersion" -> call.ok("""{"version":"2.26.0","platform":"win","baseDir":"C:\\Holyrics\\Holyrics"}""")
                        "GetSongs" -> call.ok("""[{"id":"1","title":"Великий Бог","artist":"","author":"","key":"C","bpm":"72","archived":false},
                            {"id":"2","title":"Старая","archived":true}]""")
                        "GetSong" -> call.ok(
                            """{"id":"1","title":"Великий Бог","order":"1,2,1","theme":"t1","slides":[
                            {"text":"Куплет строка 1\nКуплет строка 2","slide_description":"Verse 1","background_id":null},
                            {"text":"Припев","slide_description":"Chorus"}],"formatting_type":"basic","new_field_from_future":1}""",
                        )
                        "GetSongPlaylist" -> call.ok("""[{"id":"1","title":"Великий Бог"}]""")
                        "GetTexts" -> call.ok("""[{"id":"x","title":"Объявления","folder":"Служение","theme":null}]""")
                        "GetText" -> call.ok("""{"id":"x","title":"Объявления","slides":[{"text":"Добро пожаловать"},{"text":"Молитва в 18:00"}]}""")
                        "GetThemes" -> call.ok(
                            """[{"id":"t1","name":"Тёмная","background":{"type":"color","id":"212121","opacity":100},
                            "font":{"name":"CMG Sans","bold":true,"italic":false,"size":10.0,"color":"F5F5F5","line_spacing":0.3,"char_spacing":0},
                            "align":{"horizontal":"left","vertical":"bottom","margin":{"top":3.0,"right":4.0,"bottom":5.0,"left":6.0}},
                            "effect":{"outline_color":"000000","outline_weight":20.0,"shadow_color":"101010","shadow_x_weight":10.0,"shadow_y_weight":10.0,"blur":true},
                            "shape_fill":{"type":"box","enabled":true,"color":"80000000","margin":{"top":5.0,"right":30.0,"bottom":10.0,"left":30.0},"corner":0},
                            "settings":{"uppercase":true,"line_break":"system"}}]""",
                        )
                        "GetImages" -> call.ok("""[{"name":"фото","isDir":true},{"name":"пасха.jpg","isDir":false,"length":"204800","width":1920,"height":1080,"thumbnail":"AAAA"}]""")
                        "GetCurrentPresentation" -> call.ok("""{"id":"1","type":"song","name":"Великий Бог","slide_number":$slide,"total_slides":3,"slide_type":"default",
                            "slides":[{"number":1,"text":"Куплет","preview":"QUJD"},{"number":2,"text":"Припев"},{"number":3,"text":"Куплет"}]}""")
                        "ActionNext" -> { slide = (slide + 1).coerceAtMost(3); call.ok(null) }
                        "ActionPrevious" -> { slide = (slide - 1).coerceAtLeast(1); call.ok(null) }
                        "CloseCurrentPresentation" -> call.ok(null)
                        else -> call.respondText("""{"status":"error","error":"unknown action $action"}""", ContentType.Application.Json)
                    }
                }
            }
        }
        s.start(wait = false)
        server = s
        return runBlocking { s.engine.resolvedConnectors().first().port }
    }

    /** Holyrics перезапустился: старые сеансы недействительны. */
    fun forgetSessions() = sessions.clear()

    fun stop() = server?.stop(100, 500)

    private suspend fun ApplicationCall.ok(data: String?) =
        respondText(if (data == null) """{"status":"ok"}""" else """{"status":"ok","data":$data}""", ContentType.Application.Json)
}

class HolyricsTest {
    private val fake = FakeHolyrics()
    private var port = 0

    @BeforeTest
    fun start() { port = fake.start() }

    @AfterTest
    fun stop() { fake.stop() }

    @Test
    fun readsLibraryWithHashLogin() = runBlocking<Unit> {
        HolyricsClient("127.0.0.1", port, "secret").use { h ->
            assertEquals("2.26.0", h.version().version)
            val songs = h.songs()
            assertEquals(listOf("Великий Бог"), songs.map { it.title }, "архивные песни скрыты")
            val song = h.song("1")!!
            assertEquals(listOf("Куплет строка 1\nКуплет строка 2", "Припев", "Куплет строка 1\nКуплет строка 2"), song.orderedSlides().map { it.text })
            assertEquals("Verse 1", song.slides[0].description)
            assertEquals(listOf("Добро пожаловать", "Молитва в 18:00"), h.text("x")!!.slides.map { it.text })
            assertEquals("Служение", h.texts().single().folder)
            assertEquals("1", h.songPlaylist().single().id)
            val img = h.files(HolyricsMedia.IMAGE)
            assertEquals(204800L, img.first { !it.isDir }.length)
            assertTrue(img.first().isDir)
        }
    }

    @Test
    fun themeFieldsAreRead() = runBlocking<Unit> {
        HolyricsClient("127.0.0.1", port, "secret").use { h ->
            val t = h.themes().single()
            assertEquals("212121", t.background.id)
            assertEquals("left", t.align.horizontal)
            assertEquals(6.0, t.align.margin.left)
            assertTrue(t.font.bold && t.settings.uppercase && t.shapeFill.enabled)
            assertEquals(20.0, t.effect.outlineWeight)
        }
    }

    @Test
    fun remoteControlsThePresentation() = runBlocking<Unit> {
        HolyricsClient("127.0.0.1", port, "secret").use { h ->
            assertEquals(1, h.current("320x180")!!.slideNumber)
            h.next()
            h.next()
            assertEquals(3, h.current()!!.slideNumber)
            h.previous()
            val cur = h.current()!!
            assertEquals(2, cur.slideNumber)
            assertEquals("QUJD", cur.slides!!.first().preview)
        }
    }

    @Test
    fun wrongTokenIsAnError() = runBlocking<Unit> {
        HolyricsClient("127.0.0.1", port, "wrong").use { h ->
            assertFailsWith<HolyricsException> { h.version() }
        }
        HolyricsClient("127.0.0.1", port, "wrong", useHash = false).use { h ->
            assertFailsWith<HolyricsException> { h.version() }
        }
        HolyricsClient("127.0.0.1", port, "secret", useHash = false).use { h -> assertEquals("win", h.version().platform) }
    }

    @Test
    fun reconnectsAfterHolyricsRestart() = runBlocking<Unit> {
        HolyricsClient("127.0.0.1", port, "secret").use { h ->
            h.version()
            fake.forgetSessions()
            assertEquals("2.26.0", h.version().version)
        }
    }

    @Test
    fun linkRoundTripsThroughQr() {
        val link = HolyricsLink("192.168.1.10", 8091, "a b&c")
        assertEquals(link, HolyricsLink.parse(link.toUri()))
        assertNull(HolyricsLink.parse("deixis://1.2.3.4"))
        assertEquals(8091, HolyricsLink.parse("deixis-holyrics://10.0.0.5?token=x")!!.port)
    }
}
