package app.deixis.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Показ идёт, пока телефон в кармане: видимая служба с уведомлением держит связь с экранами,
 * Wi-Fi без сна и процессор для проверки связи. Без неё Android через пару минут в фоне
 * усыпляет сеть приложения, и экраны отключаются. Работает, пока подключён хотя бы один экран.
 */
class ShowService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifi: WifiManager.WifiLock? = null
    private var cpu: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val controller = (application as PhoneApp).controller
        val names = controller.outputs.value.map { it.target.name }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification(names), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification(names))
        }
        // В фоне режим «малой задержки» не действует: держим полный режим Wi-Fi.
        @Suppress("DEPRECATION")
        wifi = runCatching {
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "deixis-show").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        cpu = runCatching {
            getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "deixis:show").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        scope.launch {
            controller.outputs.collect { list ->
                if (list.isEmpty()) stopSelf()
                else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(list.map { it.target.name }))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        runCatching { wifi?.release() }
        runCatching { cpu?.release() }
        super.onDestroy()
    }

    private fun notification(names: List<String>): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Связь с экранами", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle(if (names.size == 1) "Подключён экран «${names[0]}»" else "Подключено экранов: ${names.size}")
            .setContentText("Связь держится и в фоне. Отключить — в «Устройствах»")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "show"
        private const val NOTIFICATION_ID = 8

        /** Из фона Android не даёт начать такую службу: тогда она начнётся при следующем открытии пульта. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ShowService::class.java)) }
        }
    }
}
