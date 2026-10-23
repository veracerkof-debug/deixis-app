package app.deixis.core

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** Постоянное имя приёмника в локальной сети без «.local», например deixis-ab12. */
fun localHostName(receiverId: String): String =
    "deixis-" + receiverId.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(4).ifEmpty { "tv" }

/** Приёмник, найденный в сети. */
data class FoundReceiver(val id: String, val name: String, val host: String, val port: Int, val platform: String)

/**
 * Поиск в локальной сети через mDNS (`_deixis._tcp`). На Android перед использованием
 * нужно взять `WifiManager.MulticastLock`, иначе система отбрасывает групповые пакеты.
 */
object Discovery {

    /** Объявляет приёмник в сети. Закрыть — вызвать `close()` у результата. */
    fun advertise(info: ReceiverInfo, port: Int, address: InetAddress? = localAddress()): AutoCloseable {
        // Имя в сети вида deixis-ab12.local: его понимают iPhone и компьютеры, и оно не меняется, если роутер выдал новый IP.
        val jmdns = JmDNS.create(address, localHostName(info.id))
        val props = mapOf("id" to info.id, "v" to PROTOCOL_VERSION.toString(), "platform" to info.platform)
        jmdns.registerService(ServiceInfo.create(SERVICE_TYPE, info.name, port, 0, 0, props))
        return AutoCloseable {
            jmdns.unregisterAllServices()
            jmdns.close()
        }
    }

    /** Поток найденных приёмников; при каждом изменении приходит полный список. */
    fun browse(address: InetAddress? = localAddress()): Flow<List<FoundReceiver>> = callbackFlow {
        val jmdns = JmDNS.create(address)
        val found = LinkedHashMap<String, FoundReceiver>()
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                jmdns.requestServiceInfo(event.type, event.name, 1_000)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                synchronized(found) {
                    found.remove(event.name)
                    trySend(found.values.toList())
                }
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: info.hostAddresses.firstOrNull() ?: return
                val receiver = FoundReceiver(
                    id = info.getPropertyString("id") ?: event.name,
                    name = info.name,
                    host = host,
                    port = info.port,
                    platform = info.getPropertyString("platform").orEmpty(),
                )
                synchronized(found) {
                    found[event.name] = receiver
                    trySend(found.values.toList())
                }
            }
        }
        jmdns.addServiceListener(SERVICE_TYPE, listener)
        awaitClose {
            jmdns.removeServiceListener(SERVICE_TYPE, listener)
            jmdns.close()
        }
    }

    /**
     * Адрес в локальной сети: Wi-Fi или проводная сеть, не петля. Порядок выбора — [pickLocalAddress].
     * Флаги интерфейсов на дешёвых прошивках бывают странными, поэтому VPN и точка-точка не отбрасываются, а уходят в конец.
     */
    fun localAddress(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp }.getOrDefault(false) }
            .flatMap { nif ->
                val demoted = runCatching { nif.isLoopback || nif.isVirtual || nif.isPointToPoint }.getOrDefault(false)
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { AddressCandidate(nif.name.orEmpty(), it, demoted) }
            }
    }.getOrNull()?.let(::pickLocalAddress)

    /**
     * Лучший адрес из кандидатов или null. Сначала интерфейс: wlan*, eth* и подобные, затем прочие,
     * в конце p2p*, rmnet*, tun* и помеченные [AddressCandidate.demoted]. Внутри — частная сеть
     * (10/8, 172.16/12, 192.168/16), затем 100.64.0.0/10 (CGNAT у некоторых роутеров и точек доступа),
     * затем любой другой. Петля, 169.254/16, 0.0.0.0 и групповые адреса не подходят.
     */
    fun pickLocalAddress(candidates: List<AddressCandidate>): Inet4Address? = candidates
        .filter { usable(it.address) }
        // При равенстве остаётся первый по порядку: minWithOrNull берёт первый минимальный.
        .minWithOrNull(compareBy<AddressCandidate>({ interfaceRank(it) }, { addressRank(it.address) }))
        ?.address

    private fun usable(a: Inet4Address) = !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.isAnyLocalAddress && !a.isMulticastAddress

    private val PREFERRED_INTERFACES = listOf("wlan", "eth", "en", "wl", "ap", "swlan")
    private val DEMOTED_INTERFACES = listOf("p2p", "rmnet", "tun", "ppp", "ccmni", "pdp", "v4-", "dummy", "docker", "veth", "br-", "virbr", "vmnet", "vboxnet", "utun", "wg", "lo")

    private fun interfaceRank(c: AddressCandidate): Int {
        val n = c.interfaceName.lowercase()
        return when {
            c.demoted || DEMOTED_INTERFACES.any { n.startsWith(it) } -> 2
            PREFERRED_INTERFACES.any { n.startsWith(it) } -> 0
            else -> 1
        }
    }

    private fun addressRank(a: Inet4Address): Int {
        val b = a.address
        return when {
            a.isSiteLocalAddress -> 0
            (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64 -> 1
            else -> 2
        }
    }
}

/** Адрес-кандидат: имя интерфейса, IPv4 и признак «интерфейс подозрительный» (VPN, точка-точка, виртуальный). */
data class AddressCandidate(val interfaceName: String, val address: Inet4Address, val demoted: Boolean = false)
