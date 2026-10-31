package app.deixis.receiver

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import app.deixis.core.Mode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * «Уйти с экранов» на экране Android (ТВ, «Стать экраном»): окно Deixis уходит назад, и на экране то, что
 * под ним — главный экран ТВ, другой вход, другое приложение. Пульт показал что-то снова — окно возвращается.
 * Android может не пустить окно вперёд из фона: тогда пульт получает подсказку открыть Deixis на экране.
 */
fun ComponentActivity.followAway(runtime: ReceiverRuntime) {
    lifecycleScope.launch {
        runtime.core.state.map { it.mode == Mode.AWAY }.distinctUntilChanged().drop(1).collect { away ->
            if (away) {
                moveTaskToBack(true)
                return@collect
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collect
            runCatching { startActivity(Intent(this@followAway, this@followAway.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            delay(RETURN_WAIT_MS)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                runtime.core.reportError("Экран не вернулся сам: Android не пускает окно из фона. Откройте Deixis на этом экране")
            }
        }
    }
}

private const val RETURN_WAIT_MS = 2_500L
