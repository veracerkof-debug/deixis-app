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

/** Приёмник, найденный в сети. */
data class FoundReceiver(val id: String, val name: String, val host: String, val port: Int, val platform: String)

/**
 * Поиск в локальной сети через mDNS (`_deixis._tcp`). На Android перед использованием
 * нужно взять `WifiManager.MulticastLock`, иначе система отбрасывает групповые пакеты.
 */
object Discovery {

    /** Объявляет приёмник в сети. Закрыть — вызвать `close()` у результата. */
    fun advertise(info: ReceiverInfo, port: Int, address: InetAddress? = localAddress()): AutoCloseable {
        val jmdns = JmDNS.create(address, info.id)
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

    /** Адрес в локальной сети: Wi-Fi или проводная сеть, не петля и не VPN. */
    fun localAddress(): InetAddress? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
}
