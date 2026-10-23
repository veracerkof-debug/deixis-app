package app.deixis.receiver

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import app.deixis.core.AddressCandidate
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
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private var advertised: InetAddress? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val connectivity: ConnectivityManager? = appContext.getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    // Свой поток: JmDNS открывается и закрывается долго, поток событий сети занимать нельзя.
    private val net = Executors.newSingleThreadScheduledExecutor { Thread(it, "deixis-net").apply { isDaemon = true } }
    @Volatile private var stopped = false

    init {
        dataDir.mkdirs()
        val idFile = File(dataDir, "id")
        val id = idFile.takeIf { it.isFile }?.readText()?.trim() ?: UUID.randomUUID().toString().also { idFile.writeText(it) }
        val metrics = appContext.resources.displayMetrics
        // Презентации PPTX экран переводит сам (встроенный перевод), без компьютера.
        val info = ReceiverInfo(id, name, platform, BuildInfo.VERSION, metrics.widthPixels, metrics.heightPixels, convert = true)
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
        // Для веб-пульта (iPhone): модули Библии хранятся на ТВ, страницы PDF считаются здесь.
        core.bible = app.deixis.core.BibleStore(File(dataDir, "bible")) { AndroidSql(it) }
        core.conversions = app.deixis.core.Conversions(core, PptxPdf.converter(appContext), File(dataDir, "convert"))
        core.pageCounter = { f ->
            android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> android.graphics.pdf.PdfRenderer(fd).use { it.pageCount } }
        }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        actualPort = server.start()
        if (loopbackOnly) return@withContext
        refresh()
        // Проектор мог запуститься раньше, чем подключился Wi-Fi, а роутер — сменить адрес: следим за сетью.
        watchNetwork()
    }

    private fun watchNetwork() {
        val cm = connectivity
        if (cm != null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = scheduleRefresh()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = scheduleRefresh()
                override fun onLost(network: Network) = scheduleRefresh()
            }
            // Сеть без выхода в интернет (роутер в зале без провайдера) тоже годится.
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching { cm.registerNetworkCallback(request, callback) }.onSuccess { networkCallback = callback }
        }
        // Запас на прошивки, где события сети не приходят, и на раздачу Wi-Fi с самого приёмника.
        runCatching { net.scheduleWithFixedDelay(Runnable { runCatching { refresh() } }, 15, 15, TimeUnit.SECONDS) }
    }

    private fun scheduleRefresh() {
        runCatching { net.schedule(Runnable { runCatching { refresh() } }, 300, TimeUnit.MILLISECONDS) }
    }

    /** Сверяет адрес с текущей сетью; при смене переобъявляет приёмник, без сети — снимает объявление. */
    @Synchronized
    private fun refresh() {
        if (stopped) return
        val addr = currentAddress()
        _address.value = addr?.hostAddress?.let { "$it:$actualPort" }
        if (addr == advertised && (addr == null || advertisement != null)) return
        runCatching { advertisement?.close() }
        advertisement = null
        advertised = addr
        runCatching { multicastLock?.release() }
        if (addr == null) return
        // Без этой блокировки Android отбрасывает групповые пакеты, и телефон не найдёт приёмник.
        val lock = multicastLock ?: (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("deixis")?.apply { setReferenceCounted(false) }?.also { multicastLock = it }
        runCatching { lock?.acquire() }
        advertisement = runCatching { Discovery.advertise(core.info, actualPort, addr) }.getOrNull()
    }

    /** Адрес из ConnectivityManager (Wi-Fi или провод, без VPN), иначе — перебор интерфейсов. */
    private fun currentAddress(): InetAddress? = runCatching {
        val cm = connectivity ?: return@runCatching null
        @Suppress("DEPRECATION")
        val networks = (listOfNotNull(cm.activeNetwork) + cm.allNetworks).distinct()
        val candidates = networks.flatMap { n ->
            val caps = cm.getNetworkCapabilities(n)
            val local = caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            val lp = if (local) cm.getLinkProperties(n) else null
            lp?.linkAddresses.orEmpty().mapNotNull { it.address as? Inet4Address }.map { AddressCandidate(lp?.interfaceName.orEmpty(), it) }
        }
        Discovery.pickLocalAddress(candidates)
    }.getOrNull() ?: Discovery.localAddress()

    fun pairingUri(): String? {
        val addr = _address.value ?: return null
        return PairingCode(addr.substringBefore(':'), actualPort, _pin.value, core.info.id, core.info.name).toUri()
    }

    fun stop() {
        stopped = true
        networkCallback?.let { cb -> runCatching { connectivity?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        net.shutdownNow()
        synchronized(this) {
            runCatching { advertisement?.close() }
            advertisement = null
            advertised = null
            runCatching { multicastLock?.release() }
        }
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
