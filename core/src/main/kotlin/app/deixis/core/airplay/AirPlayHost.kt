package app.deixis.core.airplay

import app.deixis.core.MirrorOutput
import app.deixis.core.ReceiverCore
import java.io.File
import java.net.InetAddress

/**
 * Включение приёма AirPlay на экране Deixis. По умолчанию выключен: экран зала не принимает
 * картинку от любого iPhone в сети, пока оператор сам этого не разрешил.
 * Выбор хранится в [dataDir]; ключ AirPlay — там же, чтобы iPhone узнавал этот экран.
 */
class AirPlayHost(
    private val core: ReceiverCore,
    private val dataDir: File,
    private val address: () -> InetAddress?,
) {
    private val flag = File(dataDir, "airplay-on")
    private var server: AirPlayServer? = null
    private var output: MirrorOutput? = null

    val enabled: Boolean get() = flag.isFile

    /** Запуск приёмника: AirPlay начинает слушать, только если его включали. */
    @Synchronized
    fun start() {
        if (enabled && server == null) runCatching { open() }.onFailure { System.err.println("AirPlay: ${it.message}"); close() }
    }

    @Synchronized
    fun setEnabled(on: Boolean) {
        if (on) { dataDir.mkdirs(); flag.writeText("1") } else flag.delete()
        if (on) start() else close()
    }

    private fun open() {
        val out = MirrorOutput(core)
        output = out
        val s = AirPlayServer(core.info.name, AirPlayIdentity.load(File(dataDir, "airplay-key")), out,
            width = core.info.screenWidth.takeIf { it > 0 } ?: 1920, height = core.info.screenHeight.takeIf { it > 0 } ?: 1080)
        server = s
        s.start(address())
    }

    @Synchronized
    fun close() {
        runCatching { server?.close() }
        runCatching { output?.close() }
        server = null; output = null
    }
}

/** Состояние приёма AirPlay для пульта. */
@kotlinx.serialization.Serializable
data class AirPlayState(val enabled: Boolean)
