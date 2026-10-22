package app.deixis.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveryTest {
    @Test
    fun receiverIsFoundInNetwork() = runBlocking {
        // Без сетевого интерфейса (в изолированной сборке) проверяем на петле.
        val address = Discovery.localAddress() ?: InetAddress.getLoopbackAddress()
        val info = ReceiverInfo("tv-test-${System.nanoTime()}", "ТВ для теста", "test", "0")
        Discovery.advertise(info, 47999, address).use {
            val found = withTimeout(15_000) {
                Discovery.browse(address).first { list -> list.any { it.id == info.id } }
            }.first { it.id == info.id }
            assertEquals("ТВ для теста", found.name)
            assertEquals(47999, found.port)
        }
    }
}
