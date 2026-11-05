package app.deixis.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Новое устройство ввело верный PIN и ждёт, пока оператор его пустит. */
@Serializable
data class ConnectRequest(val id: String, val sender: String, val address: String, val at: Long)

/** Пульт, который сейчас на связи. [here] — окно пульта на самом этом компьютере. */
@Serializable
data class ConnectedRemote(val id: String, val sender: String, val address: String, val since: Long, val here: Boolean)

/** Кто подключён и кто ждёт подтверждения — для пульта оператора. */
@Serializable
data class PeersState(val requests: List<ConnectRequest> = emptyList(), val connected: List<ConnectedRemote> = emptyList())

/**
 * Подключения к экрану. PIN виден всем в зале, поэтому новое устройство с верным PIN пускается только после
 * «Разрешить»: на самом экране (пульт или окно подтверждения на мониторе оператора компьютера) или на уже
 * подключённом пульте оператора — так и компьютер, где все мониторы показывают зал, не выводит запрос зрителям.
 * Подтверждать некому (первое устройство у ТВ или компьютера-экрана) — достаточно PIN, как прежде.
 * Оператор видит, кто на связи, и может отключить любого: тот забывается и подключается заново по PIN.
 */
class PeerDesk(private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow(PeersState())
    val state: StateFlow<PeersState> = _state.asStateFlow()

    private val seq = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val sessions = ConcurrentHashMap<String, Session>()

    private class Session(val token: String?, val close: () -> Unit)

    /** Сколько пультов оператора на связи готовы подтверждать подключения (этот компьютер и подключённые). */
    private val approvers = java.util.concurrent.atomic.AtomicInteger()

    fun approverJoined() = approvers.incrementAndGet()
    fun approverLeft() = approvers.decrementAndGet()

    /** Окно подтверждения самого экрана: компьютер показывает его на мониторе оператора, где нет зала. */
    @Volatile var screenApprover: Boolean = false

    /** Нужно ли подтверждение: есть ли кому его дать. */
    val approvalNeeded: Boolean get() = screenApprover || approvers.get() > 0

    /**
     * Спрашивает оператора. null — спрашивать некого: хватает PIN.
     * Без ответа за [timeoutMs] — отказ. Ждущих одновременно не больше [MAX_PENDING]: остальным отказ сразу.
     */
    suspend fun ask(sender: String, address: String, timeoutMs: Long = APPROVAL_TIMEOUT_MS): Boolean? {
        if (!approvalNeeded) return null
        if (pending.size >= MAX_PENDING) return false
        val id = "r" + seq.incrementAndGet()
        val answer = CompletableDeferred<Boolean>()
        pending[id] = answer
        _state.update { it.copy(requests = it.requests + ConnectRequest(id, sender.take(MAX_NAME), address, clock())) }
        try {
            return withTimeoutOrNull(timeoutMs) { answer.await() } ?: false
        } finally {
            pending.remove(id)
            _state.update { s -> s.copy(requests = s.requests.filter { it.id != id }) }
        }
    }

    fun answer(id: String, allow: Boolean) {
        pending[id]?.complete(allow)
    }

    /** Пульт вошёл. Возвращает его номер для [left]. */
    fun joined(sender: String, address: String, here: Boolean, token: String?, close: () -> Unit): String {
        val id = "c" + seq.incrementAndGet()
        sessions[id] = Session(token, close)
        _state.update { it.copy(connected = it.connected + ConnectedRemote(id, sender.take(MAX_NAME), address, clock(), here)) }
        return id
    }

    fun left(id: String) {
        sessions.remove(id)
        _state.update { s -> s.copy(connected = s.connected.filter { it.id != id }) }
    }

    /**
     * Отключить пульт: связь рвётся, токен забывается через [forget] — вернуться можно только заново по PIN
     * (и с подтверждением). Все его соединения с тем же токеном рвутся тоже. Пульт этого компьютера не отключается.
     */
    fun kick(id: String, forget: (String) -> Unit) {
        val me = _state.value.connected.firstOrNull { it.id == id } ?: return
        if (me.here) return
        val token = sessions[id]?.token
        token?.let(forget)
        sessions.filter { (k, s) -> k == id || (token != null && s.token == token) }.forEach { (_, s) -> runCatching { s.close() } }
    }

    companion object {
        const val APPROVAL_TIMEOUT_MS = 60_000L
        const val MAX_PENDING = 8
        const val MAX_NAME = 80
    }
}
