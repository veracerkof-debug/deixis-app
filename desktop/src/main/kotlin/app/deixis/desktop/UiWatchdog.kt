package app.deixis.desktop

import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/**
 * Сторож потока окон: если окна (и пульт) замерли дольше [STALL_MS], в журнал уходит «поток окон завис на N мс»
 * со стеком того, что его держало. Так «подлагивание» на чужом компьютере видно в отчёте диагностики:
 * без этого лаг есть, а причина неизвестна. Ничего не лечит и почти ничего не стоит: раз в [TICK_MS] — одна
 * пустая задача. Не больше [MAX_REPORTS] записей за запуск, чтобы журнал не рос.
 */
internal object UiWatchdog {
    private const val TICK_MS = 250L
    private const val STALL_MS = 400L
    private const val MAX_REPORTS = 30
    private const val STACK_LINES = 14

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start() {
        if (java.awt.GraphicsEnvironment.isHeadless() || !started.compareAndSet(false, true)) return
        val answered = AtomicLong(System.nanoTime())
        var reports = 0
        Thread {
            while (true) {
                runCatching { Thread.sleep(TICK_MS) }.onFailure { return@Thread }
                val sent = System.nanoTime()
                SwingUtilities.invokeLater { answered.set(System.nanoTime()) }
                runCatching { Thread.sleep(STALL_MS) }
                val late = (System.nanoTime() - answered.get()) / 1_000_000
                if (answered.get() < sent && reports < MAX_REPORTS) {
                    // Ещё не ответил: ждём, пока освободится, и считаем, сколько это длилось.
                    val edt = Thread.getAllStackTraces().keys.firstOrNull { it.name.startsWith("AWT-EventQueue") }
                    val stack = edt?.stackTrace?.take(STACK_LINES)?.joinToString("\n    at ", prefix = "\n    at ").orEmpty()
                    while (answered.get() < sent) runCatching { Thread.sleep(50) }
                    val total = (System.nanoTime() - sent) / 1_000_000
                    reports++
                    app.deixis.diag.Log.w("ui", "поток окон завис на $total мс (из них ждал сторож: $late)$stack")
                }
            }
        }.apply { isDaemon = true; name = "deixis-ui-watchdog"; start() }
    }
}
