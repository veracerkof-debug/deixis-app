package app.deixis.core

import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UploadLimitTest {
    private fun area(): UploadArea {
        val root = kotlin.io.path.createTempDirectory("uploads").toFile()
        return UploadArea(FileStore(File(root, "files")), File(root, "uploads"))
    }

    /** Поток, который не отдаёт данных, пока тест не отпустит его: имитирует медленную загрузку. */
    private class Gate : InputStream() {
        val latch = java.util.concurrent.CountDownLatch(1)
        override fun read(): Int { latch.await(); return -1 }
        override fun read(b: ByteArray, off: Int, len: Int): Int { latch.await(); return -1 }
    }

    @Test
    fun tooManyParallelUploadsAreRefused() {
        val area = area()
        val gate = Gate()
        val threads = (1..4).map { i ->
            Thread { runCatching { area.append("upload-id-$i", "a.bin", 10, 0, gate) } }.also { it.start() }
        }
        Thread.sleep(300)
        val e = assertFailsWith<StoreException> { area.append("upload-id-5", "a.bin", 10, 0, "x".byteInputStream()) }
        assertEquals("limit", e.code)
        gate.latch.countDown()
        threads.forEach { it.join() }
        // Место освободилось: пятая загрузка теперь проходит.
        area.append("upload-id-5", "a.bin", 1, 0, "x".byteInputStream())
    }

    @Test
    fun totalSizeOfPartialUploadsIsCapped() {
        val root = kotlin.io.path.createTempDirectory("uploads").toFile()
        val area = UploadArea(FileStore(File(root, "files")), File(root, "uploads"), limitBytes = 10)
        area.append("upload-big-1", "a.bin", 10, 0, "12345678".byteInputStream())
        // 8 байт уже лежит; ещё 100 заявлено — выше потолка в 10.
        val e = assertFailsWith<StoreException> { area.append("upload-big-2", "b.bin", 100, 0, "x".byteInputStream()) }
        assertEquals("limit", e.code)
    }
}
