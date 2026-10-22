package app.deixis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolTest {
    private val file = FileRef("a".repeat(64), "film.mkv", 1234, "video/x-matroska")

    @Test
    fun everyMessageSurvivesRoundTrip() {
        val messages = listOf(
            Hello(PROTOCOL_VERSION, "Телефон", "t"),
            PairRequest(PROTOCOL_VERSION, "Телефон", "1234"),
            Show(TextContent("Господь — Пастырь мой", ref = "Пс 22:1")),
            Show(ImageContent(file)),
            Show(VideoContent(file, loop = true)),
            Show(PdfContent(file, page = 3)),
            SetMode(Mode.BLACK),
            VideoCommand(VideoAction.SEEK, positionMs = 10_000),
            Ping(1), Pong(1),
            Welcome(PROTOCOL_VERSION, ReceiverInfo("id", "ТВ в холле", "android-tv", "1.0"), ReceiverState()),
            Paired("t"), Denied(DenyReason.WRONG_PIN),
            Status(ReceiverState(ImageContent(file), Mode.LOGO, VideoState(true, 1, 2))),
            ErrorMsg("missing_file", "нет"),
        )
        for (m in messages) assertEquals(m, decodeMsg(m.encode()))
    }

    @Test
    fun messageFromNewerVersionIsIgnored() {
        assertNull(decodeMsg("""{"type":"hologram","x":1}"""))
        assertNull(decodeMsg("not json"))
    }

    @Test
    fun unknownFieldsAreIgnored() {
        assertEquals(Ping(5), decodeMsg("""{"type":"ping","t":5,"future":"field"}"""))
    }
}
