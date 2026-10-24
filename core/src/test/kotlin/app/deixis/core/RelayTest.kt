package app.deixis.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Пересылка показа: компьютер (источник) и экран в сети — два приёмника на 127.0.0.1. */
class RelayTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stops = mutableListOf<() -> Unit>()

    private fun core(id: String) = ReceiverCore(
        ReceiverInfo(id, "Приёмник $id", "test", "0"),
        FileStore(File(createTempDirectory("relay-$id").toFile(), "files")),
        Pairing(MemoryTokenStore()),
    )

    private suspend fun serve(core: ReceiverCore, port: Int = 0): Pair<ReceiverServer, Int> {
        val s = ReceiverServer(core, port = port)
        val p = s.start()
        stops += { s.stop() }
        return s to p
    }

    @AfterTest
    fun stop() {
        stops.reversed().forEach { runCatching { it() } }
        scope.cancel()
    }

    private fun put(core: ReceiverCore, bytes: ByteArray, name: String, mime: String): FileRef {
        val sha = sha256Of(bytes.inputStream())
        core.files.append(sha, bytes.size.toLong(), 0, bytes.inputStream())
        return FileRef(sha, name, bytes.size.toLong(), mime)
    }

    private suspend fun ScreenRelay.waitState(id: String, state: String) = withTimeout(15_000) {
        outputs.first { list -> list.any { it.id == id && it.state == state } }
    }

    private suspend fun ReceiverCore.waitFor(what: String, check: (ReceiverState) -> Boolean) = try {
        withTimeout(15_000) { state.first(check) }
    } catch (e: Exception) {
        throw AssertionError("не дождались: $what; состояние ${state.value}", e)
    }

    @Test
    fun mirrorsFilesShowModeAndResendsAfterReconnect() = runBlocking<Unit> {
        val pc = core("pc")
        val tv = core("tv")
        val (tvServer, tvPort) = serve(tv)
        val store = MemoryRelayStore()
        val relay = ScreenRelay(pc, store, scope, "Компьютер")
        stops += { relay.close() }
        relay.found(listOf(FoundReceiver("pc", "Сам себе", "127.0.0.1", 1, "test"), FoundReceiver("tv", "ТВ", "127.0.0.1", tvPort, "test")))
        assertEquals(listOf("tv"), relay.outputs.value.map { it.id }, "свой приёмник в списке не появляется")
        assertEquals("idle", relay.outputs.value.single().state)

        assertTrue(relay.link(LinkRequest("tv")).needPin, "без токена и PIN нужен PIN")
        val wrong = if (tv.pairing.pin == "1000") "1001" else "1000"
        val denied = relay.link(LinkRequest("tv", pin = wrong))
        assertFalse(denied.ok)
        assertEquals("Неверный PIN", denied.error)
        assertTrue(relay.link(LinkRequest("tv", pin = tv.pairing.pin)).ok)
        relay.waitState("tv", "online")
        assertNotNull(store.load().single().token, "токен сохранён")

        // Фото с фоном в оформлении следующего слайда: передаются оба файла.
        val photo = put(pc, Random(1).nextBytes(400_000), "photo.jpg", "image/jpeg")
        val bg = put(pc, Random(2).nextBytes(50_000), "bg.jpg", "image/jpeg")
        val next = TextContent("Дальше", style = TextStyle(backgroundImage = bg))
        assertEquals(null, pc.apply(Show(ImageContent(photo), next)))
        tv.waitFor("фото на экране") { it.content == ImageContent(photo) && it.next == next }
        assertNotNull(tv.files.file(photo.sha256))
        withTimeout(10_000) { while (tv.files.file(bg.sha256) == null) delay(50) }

        pc.apply(SetMode(Mode.BLACK))
        tv.waitFor("чёрный экран") { it.mode == Mode.BLACK }

        // Экран пропал из сети: пока его нет, показ идёт дальше; после возврата он получает текущее состояние.
        tvServer.stop()
        relay.waitState("tv", "offline")
        pc.apply(Show(TextContent("Пока экрана не было")))
        pc.apply(SetMode(Mode.LOGO))
        serve(tv, tvPort)
        tv.waitFor("состояние после переподключения") { it.content == TextContent("Пока экрана не было") && it.mode == Mode.LOGO }
        relay.waitState("tv", "online")

        // Отключение пересылки: экран больше не следует за компьютером, токен остаётся.
        relay.unlink("tv")
        pc.apply(Show(TextContent("Только здесь")))
        delay(500)
        assertEquals(TextContent("Пока экрана не было"), tv.state.value.content)
        assertTrue(relay.link(LinkRequest("tv")).ok, "повторное подключение без PIN")
        tv.waitFor("снова следует") { it.content == TextContent("Только здесь") }
    }

    @Test
    fun oneDeadScreenDoesNotStopTheOthersAndLinksSurviveRestart() = runBlocking<Unit> {
        val pc = core("pc")
        val tv = core("tv")
        val (_, tvPort) = serve(tv)
        val dead = ServerSocket(0).use { it.localPort } // здесь никто не слушает
        val store = MemoryRelayStore()
        store.save(listOf(RelayEntry("ghost", "Выключенный проектор", "127.0.0.1", dead, token = "0".repeat(64), linked = true)))
        val relay = ScreenRelay(pc, store, scope, "Компьютер")
        relay.found(listOf(FoundReceiver("tv", "ТВ", "127.0.0.1", tvPort, "test")))
        assertTrue(relay.link(LinkRequest("tv", pin = tv.pairing.pin)).ok)
        relay.waitState("tv", "online")
        pc.apply(Show(TextContent("Для всех")))
        tv.waitFor("слайд на живом экране") { it.content == TextContent("Для всех") }
        assertEquals("offline", relay.outputs.value.first { it.id == "ghost" }.state)
        relay.close()

        // Перезапуск компьютера: подключённые экраны возвращаются сами, без PIN.
        val again = ScreenRelay(pc, store, scope, "Компьютер")
        stops += { again.close() }
        again.waitState("tv", "online")
        pc.apply(Show(TextContent("После перезапуска")))
        tv.waitFor("после перезапуска") { it.content == TextContent("После перезапуска") }
    }

    @Test
    fun videoCommandsFollowAndLateScreenJoinsAtCurrentPosition() = runBlocking<Unit> {
        val pc = core("pc")
        val tv = core("tv")
        val (_, tvPort) = serve(tv)
        val film = put(pc, Random(5).nextBytes(200_000), "film.mp4", "video/mp4")
        pc.apply(Show(VideoContent(film)))
        pc.reportVideo(VideoState(playing = true, positionMs = 42_000, durationMs = 100_000))
        val relay = ScreenRelay(pc, MemoryRelayStore(), scope, "Компьютер")
        stops += { relay.close() }
        relay.found(listOf(FoundReceiver("tv", "ТВ", "127.0.0.1", tvPort, "test")))
        assertTrue(relay.link(LinkRequest("tv", pin = tv.pairing.pin)).ok)
        val shown = tv.waitFor("видео на экране") { it.content is VideoContent }
        assertEquals(42_000, (shown.content as VideoContent).startMs, "экран подхватывает фильм с того же места")
        relay.waitState("tv", "online")
        val got = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { tv.videoCommands.first() } }
        pc.apply(VideoCommand(VideoAction.SEEK, positionMs = 5_000))
        assertEquals(VideoCommand(VideoAction.SEEK, positionMs = 5_000), got.await())
    }

    @Test
    fun forgottenTokenAsksForPinAgain() = runBlocking<Unit> {
        val pc = core("pc")
        val tv = core("tv")
        val (_, tvPort) = serve(tv)
        val store = MemoryRelayStore()
        store.save(listOf(RelayEntry("tv", "ТВ", "127.0.0.1", tvPort, token = "f".repeat(64), linked = true)))
        val relay = ScreenRelay(pc, store, scope, "Компьютер")
        stops += { relay.close() }
        relay.waitState("tv", "pin")
        assertTrue(relay.link(LinkRequest("tv")).needPin)
        assertTrue(relay.link(LinkRequest("tv", pin = tv.pairing.pin)).ok)
        relay.waitState("tv", "online")
    }

    /** Экран, подключённый по IP, и он же, найденный в сети, — одно устройство с настоящим именем. */
    @Test
    fun screenByAddressAndFoundInNetworkIsOneDevice() = runBlocking<Unit> {
        val pc = core("pc")
        val tv = core("tv")
        val (_, tvPort) = serve(tv)
        val relay = ScreenRelay(pc, MemoryRelayStore(), scope, "Компьютер")
        stops += { relay.close() }
        assertTrue(relay.link(LinkRequest("127.0.0.1:$tvPort", pin = tv.pairing.pin, host = "127.0.0.1", port = tvPort)).ok)
        relay.waitState("tv", "online")
        relay.found(listOf(FoundReceiver("tv", "Приёмник tv", "127.0.0.1", tvPort, "test")))
        val list = relay.outputs.value
        assertEquals(listOf("tv"), list.map { it.id }, "одно устройство, а не два: $list")
        assertEquals("Приёмник tv", list.single().name, "настоящее имя, а не адрес")
        assertTrue(list.single().linked)
        assertFalse(relay.link(LinkRequest("x", host = "127.0.0.1", port = serve(pc).second)).ok, "свой адрес не подключается")
    }
}
