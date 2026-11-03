package app.deixis.core.airplay

import app.deixis.core.ReceiverCore
import app.deixis.core.ReceiverState
import app.deixis.core.ScreenContent
import app.deixis.core.Show
import app.deixis.core.StreamFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * Картинка с iPhone идёт в тот же поток экрана, что и демонстрация с телефона-пульта:
 * зал, второй монитор и зрители по QR показывают её одинаково. Когда iPhone отключается,
 * возвращается то, что было на экране до него.
 */
class AirPlayOutput(private val core: ReceiverCore) : MirrorSink, Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var owner = -1
    private var seq = 0L
    private var before: ReceiverState? = null
    private var shown: ScreenContent? = null
    private var config: ByteArray? = null
    /** Последний ключевой кадр: iPhone шлёт их редко, а новый подписчик (зритель, второй монитор) без него чёрный. */
    private var lastKey: ByteArray? = null
    private var lastPts = 0L

    init {
        scope.launch {
            core.stream.keyRequests.collect {
                synchronized(this@AirPlayOutput) {
                    val k = lastKey ?: return@synchronized
                    if (core.stream.owns(owner)) core.stream.publish(StreamFrame(true, false, seq++, lastPts, k))
                }
            }
        }
    }

    @Synchronized
    override fun config(annexB: ByteArray, width: Int, height: Int) {
        if (shown == null || !core.stream.owns(owner)) {
            before = core.state.value
            owner = core.stream.claim()
            lastKey = null
        }
        config = annexB
        core.stream.publish(StreamFrame(false, true, seq, lastPts, annexB))
        val content = ScreenContent(width, height)
        if (content != shown || core.state.value.content != content) {
            shown = content
            core.apply(Show(content))
        }
    }

    @Synchronized
    override fun frame(annexB: ByteArray, key: Boolean, ptsUs: Long) {
        if (shown == null || !core.stream.owns(owner)) return
        lastPts = ptsUs
        if (key) lastKey = annexB
        core.stream.publish(StreamFrame(key, false, seq++, ptsUs, annexB))
    }

    @Synchronized
    override fun stopped() {
        val s = shown ?: return
        core.stream.release(owner)
        before?.let { core.endMirror(s, it) }
        shown = null; before = null; lastKey = null; config = null
    }

    override fun close() {
        stopped()
        scope.cancel()
    }
}
