package app.deixis.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class UploadErrorTest {
    @Test
    fun timeoutReadsAsScreenNotAnswering() {
        val e = IOException("Connect timeout has expired [url=http://10.0.0.5:47820/files/abc, connect_timeout=5000 ms]")
        val text = uploadError("Maus.pdf", e)
        assertEquals("«Maus.pdf» не передан: экран не отвечает. Проверьте, что он включён и в той же сети; уже переданное не пропадёт", text)
        // Ведущему не нужны адреса и хэши.
        assertFalse("http" in text || "47820" in text)
    }

    @Test
    fun otherFailureKeepsWhatWasSent() {
        assertEquals("«a.mp4» не передан: связь оборвалась; уже переданное не пропадёт", uploadError("a.mp4", IOException("Broken pipe")))
        assertEquals("«a.mp4» не передан: связь оборвалась; уже переданное не пропадёт", uploadError("a.mp4", null))
    }
}
