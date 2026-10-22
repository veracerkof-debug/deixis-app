package app.deixis.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileStoreTest {
    private val dir = createTempDirectory("store").toFile()
    private val data = Random(1).nextBytes(300_000)
    private val sha = sha256Of(data.inputStream())

    @Test
    fun resumesAfterInterruptedTransfer() {
        val store = FileStore(dir)
        val first = store.append(sha, data.size.toLong(), 0, data.copyOfRange(0, 100_000).inputStream())
        assertEquals(FileStatus(sha, 100_000, false), first)
        assertNull(store.file(sha))
        val done = store.append(sha, data.size.toLong(), 100_000, data.copyOfRange(100_000, data.size).inputStream())
        assertTrue(done.complete)
        assertTrue(store.file(sha)!!.readBytes().contentEquals(data))
    }

    @Test
    fun wrongOffsetIsRejectedWithoutDamage() {
        val store = FileStore(dir)
        store.append(sha, data.size.toLong(), 0, data.copyOfRange(0, 1000).inputStream())
        val e = assertFailsWith<StoreException> { store.append(sha, data.size.toLong(), 5000, data.inputStream()) }
        assertEquals("offset", e.code)
        assertEquals(1000, store.status(sha).bytes)
    }

    @Test
    fun corruptedFileIsDiscarded() {
        val store = FileStore(dir)
        val bad = data.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val e = assertFailsWith<StoreException> { store.append(sha, data.size.toLong(), 0, bad.inputStream()) }
        assertEquals("hash", e.code)
        assertEquals(FileStatus(sha, 0, false), store.status(sha))
    }

    @Test
    fun oversizedUploadIsRejected() {
        val store = FileStore(dir)
        assertFailsWith<StoreException> { store.append(sha, 10, 0, data.inputStream()) }
    }

    @Test
    fun oldestFilesAreEvictedWhenFull() {
        val store = FileStore(dir, limitBytes = 700_000)
        val files = (1..3).map { Random(it).nextBytes(300_000) }
        val shas = files.map { sha256Of(it.inputStream()) }
        store.append(shas[0], 300_000, 0, files[0].inputStream())
        File(dir, shas[0]).setLastModified(1_000)
        store.append(shas[1], 300_000, 0, files[1].inputStream())
        store.append(shas[2], 300_000, 0, files[2].inputStream())
        assertNull(store.file(shas[0]))
        assertNotNull(store.file(shas[1]))
        assertNotNull(store.file(shas[2]))
    }

    @Test
    fun invalidIdIsRejected() {
        val store = FileStore(dir)
        assertFailsWith<StoreException> { store.status("../etc/passwd") }
        assertFailsWith<StoreException> { store.append("../x", 1, 0, byteArrayOf(1).inputStream()) }
        assertFalse(File(dir.parentFile, "x.part").exists())
    }
}
