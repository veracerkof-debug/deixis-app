package app.deixis.core

import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalAddressTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private fun c(name: String, addr: String, demoted: Boolean = false) = AddressCandidate(name, ip(addr), demoted)
    private fun pick(vararg c: AddressCandidate) = Discovery.pickLocalAddress(c.toList())?.hostAddress

    @Test
    fun siteLocalWins() {
        assertEquals("192.168.1.5", pick(c("wlan0", "8.8.8.8"), c("wlan0", "100.70.1.2"), c("wlan0", "192.168.1.5")))
    }

    @Test
    fun cgnatAcceptedWhenNoPrivate() {
        // Роутер или точка доступа раздаёт 100.64.0.0/10 — раньше приёмник считал, что сети нет.
        assertEquals("100.72.3.4", pick(c("lo", "127.0.0.1"), c("wlan0", "100.72.3.4")))
        assertEquals("100.127.255.1", pick(c("wlan0", "100.127.255.1"), c("wlan0", "100.128.0.1")))
    }

    @Test
    fun anyOtherAddressAsLastResort() {
        assertEquals("5.6.7.8", pick(c("wlan0", "5.6.7.8")))
    }

    @Test
    fun wifiInterfaceBeatsDirectAndMobile() {
        // Miracast поднимает p2p0 с 192.168.49.1 — в эту сеть телефоны не подключены.
        assertEquals("100.64.0.7", pick(c("p2p0", "192.168.49.1"), c("rmnet0", "10.20.30.40"), c("wlan0", "100.64.0.7")))
        assertEquals("192.168.0.10", pick(c("tun0", "10.8.0.2"), c("eth0", "192.168.0.10")))
        assertEquals("172.16.0.3", pick(c("p2p-wlan0-0", "192.168.49.1"), c("wlx00e04c", "172.16.0.3")))
    }

    @Test
    fun demotedFlagGoesLast() {
        assertEquals("192.168.1.2", pick(c("wlan0", "10.0.0.1", demoted = true), c("unknown0", "192.168.1.2")))
    }

    @Test
    fun unknownInterfaceBetweenPreferredAndDemoted() {
        assertEquals("192.168.5.5", pick(c("rmnet_data0", "10.1.1.1"), c("mlan0", "192.168.5.5")))
        assertEquals("192.168.1.9", pick(c("mlan0", "192.168.5.5"), c("wlan0", "192.168.1.9")))
    }

    @Test
    fun firstWinsOnTie() {
        assertEquals("192.168.1.2", pick(c("wlan0", "192.168.1.2"), c("wlan1", "192.168.1.3")))
    }

    @Test
    fun unusableAddressesIgnored() {
        assertNull(pick(c("lo", "127.0.0.1"), c("wlan0", "169.254.10.10"), c("wlan0", "0.0.0.0"), c("wlan0", "224.0.0.251")))
        assertNull(pick())
    }
}
