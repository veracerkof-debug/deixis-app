package app.deixis.tv

import android.app.Application
import android.os.Build
import android.provider.Settings
import app.deixis.receiver.ReceiverRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class DeixisTvApp : Application() {

    /** Приёмник живёт, пока жив процесс, а не экран: поворот или пауза экрана не рвут связь с телефоном. */
    lateinit var runtime: ReceiverRuntime
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
        runtime = ReceiverRuntime(this, name, "android-tv", File(filesDir, "receiver"))
        scope.launch { runtime.start() }
    }
}
