package app.deixis.phone

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.deixis.receiver.ReceiverRuntime
import app.deixis.receiver.ReceiverScreen
import app.deixis.receiver.VlcPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Универсальный Deixis как экран: тот же приёмник, что в Deixis TV. На телевизоре, приставке
 * и устройстве без сенсорного экрана приложение открывается сразу так; на телефоне, планшете,
 * доске — по плитке «Стать экраном». Сеть открывается только здесь, по действию человека.
 */
class ScreenActivity : ComponentActivity() {

    private lateinit var player: VlcPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        val runtime = ScreenRuntime.get(this)
        player = VlcPlayer(this) { runtime.core.reportError(it) }
        setContent { ReceiverScreen(runtime, player, overscan = isTelevision(this)) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        player.release()
        // Выход из режима экрана закрывает приёмник: телефон больше не ждёт подключений.
        if (isFinishing) ScreenRuntime.stop()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    companion object {
        /** Телевизор, приставка или устройство без сенсорного экрана: пультом здесь не пользуются. */
        fun isTelevision(context: Context): Boolean {
            val ui = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        }
    }
}

/** Приёмник живёт, пока открыт режим экрана: поворот или пауза не рвут связь с пультом. */
private object ScreenRuntime {
    private var runtime: ReceiverRuntime? = null
    private var started: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    fun get(context: Context): ReceiverRuntime = runtime ?: run {
        val app = context.applicationContext
        val name = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
        val platform = if (ScreenActivity.isTelevision(app)) "android-tv" else "android-screen"
        ReceiverRuntime(app, name, platform, File(app.filesDir, "screen")).also { rt ->
            runtime = rt
            started = scope.launch { rt.start() }
        }
    }

    @Synchronized
    fun stop() {
        val rt = runtime ?: return
        val job = started
        // Закрыть можно только запущенный сервер: если запуск ещё идёт, закрытие ждёт его.
        scope.launch { job?.join(); rt.stop() }
        runtime = null
        started = null
    }
}
