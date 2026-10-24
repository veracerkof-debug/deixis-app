package app.deixis.tv

import android.app.Application
import android.os.Build
import android.provider.Settings
import app.deixis.core.UpdateTarget
import app.deixis.receiver.ApkUpdates
import app.deixis.receiver.ReceiverRuntime
import app.deixis.receiver.ScreenUpdateHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class DeixisTvApp : Application() {

    /** Приёмник живёт, пока жив процесс, а не экран: поворот или пауза экрана не рвут связь с телефоном. */
    var runtime: ReceiverRuntime? = null
        private set

    /** Почему приёмник не запустился (или упал в прошлый раз): показывается вместо пустого закрытия. */
    var startError: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Падение записывается в файл: при следующем запуске экран покажет причину, а не закроется молча.
        val crashFile = File(filesDir, "last-crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashFile.writeText(describe(e)) }
            previous?.uncaughtException(t, e)
        }
        startError = crashFile.takeIf { it.isFile }?.readText()?.also { crashFile.delete() }
        try {
            val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
            val rt = ReceiverRuntime(this, name, "android-tv", File(filesDir, "receiver"))
            runtime = rt
            // Обновления ТВ — с пульта (`/api/update`): на экране зала нет своих настроек.
            if (!ApkUpdates.fromPlay(this)) rt.core.updates = ScreenUpdateHost(this, rt.core, ApkUpdates.updater(this, UpdateTarget.TV, scope), scope)
            scope.launch {
                try { rt.start() } catch (e: Throwable) { startError = describe(e) }
            }
        } catch (e: Throwable) {
            startError = describe(e)
        }
    }

    private fun describe(e: Throwable): String {
        android.util.Log.e("DeixisTV", "Приёмник не запустился", e)
        return "Deixis TV ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL} · " +
            Build.SUPPORTED_ABIS.joinToString() + "\n" + e.stackTraceToString().lines().take(14).joinToString("\n")
    }
}
