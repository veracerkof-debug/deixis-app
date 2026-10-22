package app.deixis.receiver

import android.content.Context
import android.net.wifi.WifiManager
import app.deixis.core.Discovery
import app.deixis.core.FileStore
import app.deixis.core.FileTokenStore
import app.deixis.core.PageRenderer
import app.deixis.core.loadViewKey
import app.deixis.core.Pairing
import app.deixis.core.PairingCode
import app.deixis.core.ReceiverCore
import app.deixis.core.ReceiverInfo
import app.deixis.core.ReceiverServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Приёмник на Android: ядро, сервер и объявление в сети. Его используют Deixis TV
 * и телефон для вывода по кабелю (тогда [loopbackOnly] = true и в сети он не виден).
 */
class ReceiverRuntime(
    context: Context,
    name: String,
    platform: String,
    dataDir: File,
    private val port: Int = ReceiverServer.DEFAULT_PORT,
    private val loopbackOnly: Boolean = false,
) {
    private val appContext = context.applicationContext
    val core: ReceiverCore
    private val server: ReceiverServer

    private val _pin: MutableStateFlow<String>
    val pin: StateFlow<String>

    private val _address = MutableStateFlow<String?>(null)
    /** Адрес в локальной сети для экрана ожидания; null, если сети нет. */
    val address: StateFlow<String?> = _address.asStateFlow()

    var actualPort: Int = 0
        private set

    private var advertisement: AutoCloseable? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    init {
        dataDir.mkdirs()
        val idFile = File(dataDir, "id")
        val id = idFile.takeIf { it.isFile }?.readText()?.trim() ?: UUID.randomUUID().toString().also { idFile.writeText(it) }
        val metrics = appContext.resources.displayMetrics
        val info = ReceiverInfo(id, name, platform, BuildInfo.VERSION, metrics.widthPixels, metrics.heightPixels)
        val pairing = Pairing(FileTokenStore(File(dataDir, "tokens.json")))
        core = ReceiverCore(info, FileStore(File(dataDir, "files"), limitBytes = cacheLimit(dataDir)), pairing, loadViewKey(File(dataDir, "view-key")))
        _pin = MutableStateFlow(pairing.pin)
        pin = _pin.asStateFlow()
        pairing.onPinChanged = { _pin.value = it }
        server = ReceiverServer(core, port, if (loopbackOnly) "127.0.0.1" else "0.0.0.0")
        core.pageRenderer = PageRenderer { file, page, width ->
            val bmp = renderPdfPage(file, page, width, width * 4)
            java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
        }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        actualPort = server.start()
        if (loopbackOnly) return@withContext
        val addr = Discovery.localAddress()
        _address.value = addr?.hostAddress?.let { "$it:$actualPort" }
        if (addr != null) {
            // Без этой блокировки Android отбрасывает групповые пакеты, и телефон не найдёт приёмник.
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("deixis")?.apply { setReferenceCounted(false); acquire() }
            advertisement = runCatching { Discovery.advertise(core.info, actualPort, addr) }.getOrNull()
        }
    }

    fun pairingUri(): String? {
        val addr = _address.value ?: return null
        return PairingCode(addr.substringBefore(':'), actualPort, _pin.value, core.info.id, core.info.name).toUri()
    }

    fun stop() {
        runCatching { advertisement?.close() }
        runCatching { multicastLock?.release() }
        runCatching { server.stop() }
    }

    private fun cacheLimit(dir: File): Long {
        // Не больше 10 ГБ и не больше половины свободного места.
        val free = dir.usableSpace
        return minOf(10L * 1024 * 1024 * 1024, free / 2).coerceAtLeast(256L * 1024 * 1024)
    }
}

object BuildInfo {
    const val VERSION = "0.1.0"
}
