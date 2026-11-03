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
import android.widget.RemoteViews
import app.deixis.core.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
        val first = snapshot(controller)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification(first), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification(first))
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
        // Шторка показывает то же, что «Сейчас» на пульте: экран, что в зале, какой режим включён.
        scope.launch {
            combine(controller.outputs, controller.live, controller.mode) { _, _, _ -> snapshot(controller) }
                .distinctUntilChanged()
                .collect { st ->
                    if (st.names.isEmpty()) stopSelf()
                    else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(st))
                }
        }
    }

    /** Кнопки шторки: те же действия, что полоса под «Сейчас» на пульте. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = (application as PhoneApp).controller
        when (intent?.action) {
            ACTION_PREV -> c.step(-1)
            ACTION_NEXT -> c.step(1)
            ACTION_BLACK -> c.mode(Mode.BLACK)
            ACTION_LOGO -> c.mode(Mode.LOGO)
            ACTION_AWAY -> c.mode(Mode.AWAY)
        }
        return START_NOT_STICKY
    }

    /** Что показать в шторке. */
    private data class Snapshot(val names: List<String>, val live: String, val mode: Mode)

    private fun snapshot(c: Controller): Snapshot {
        val mode = c.mode.value
        val live = c.live.value
        val text = when (mode) {
            Mode.BLACK -> L.blackScreen
            Mode.LOGO -> L.logo
            Mode.CLEAR -> L.cleared
            Mode.AWAY -> L.awayNow
            Mode.NORMAL -> live?.let { l -> if (l.source.slides.size > 1) "${l.source.title} · ${l.index + 1}/${l.source.slides.size}" else l.source.title } ?: L.empty
        }
        return Snapshot(c.outputs.value.map { it.target.name }, text, mode)
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wifi?.release() }
        runCatching { cpu?.release() }
        super.onDestroy()
    }

    private fun notification(st: Snapshot): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, L.linkChannel, NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = if (st.names.size == 1) L.screenConnected(st.names[0]) else L.screensConnected(st.names.size)
        val small = RemoteViews(packageName, R.layout.notif_show)
        val big = RemoteViews(packageName, R.layout.notif_show_big)
        big.setTextViewText(R.id.n_title, title)
        big.setTextViewText(R.id.n_text, "${L.now}: ${st.live}")
        // Кнопка, которая сейчас включена («Чёрный», «Логотип», «Уйти»), — бронзовая, как на пульте.
        for (b in BUTTONS) {
            val on = b.mode != null && st.mode == b.mode
            val pi = PendingIntent.getService(this, b.code, Intent(this, ShowService::class.java).setAction(b.action), PendingIntent.FLAG_IMMUTABLE)
            val label = b.label()
            for ((rv, id) in listOf(small to b.small, big to b.big)) {
                rv.setOnClickPendingIntent(id, pi)
                rv.setInt(id, "setBackgroundResource", if (on) R.drawable.n_btn_on else R.drawable.n_btn)
                rv.setContentDescription(id, label)
            }
            big.setTextViewText(b.bigLabel, label)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_deixis)
            .setContentTitle(title)
            .setContentText("${L.now}: ${st.live}")
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            // Кнопки нужны и на экране блокировки: показ ведут, не разблокируя телефон.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    /** Кнопка шторки: действие, место в свёрнутом и развёрнутом виде, режим, который она включает. */
    private class Btn(val action: String, val code: Int, val small: Int, val big: Int, val bigLabel: Int, val mode: Mode?, val label: () -> String)

    companion object {
        private const val CHANNEL = "show"
        private const val NOTIFICATION_ID = 8
        private const val ACTION_PREV = "app.deixis.phone.show.PREV"
        private const val ACTION_NEXT = "app.deixis.phone.show.NEXT"
        private const val ACTION_BLACK = "app.deixis.phone.show.BLACK"
        private const val ACTION_LOGO = "app.deixis.phone.show.LOGO"
        private const val ACTION_AWAY = "app.deixis.phone.show.AWAY"

        private val BUTTONS = listOf(
            Btn(ACTION_PREV, 1, R.id.n_prev, R.id.nb_prev, R.id.nb_prev_t, null) { L.prev },
            Btn(ACTION_NEXT, 2, R.id.n_next, R.id.nb_next, R.id.nb_next_t, null) { L.next },
            Btn(ACTION_BLACK, 3, R.id.n_black, R.id.nb_black, R.id.nb_black_t, Mode.BLACK) { L.black },
            Btn(ACTION_LOGO, 4, R.id.n_logo, R.id.nb_logo, R.id.nb_logo_t, Mode.LOGO) { L.logo },
            Btn(ACTION_AWAY, 5, R.id.n_away, R.id.nb_away, R.id.nb_away_t, Mode.AWAY) { L.away },
        )

        /** Из фона Android не даёт начать такую службу: тогда она начнётся при следующем открытии пульта. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ShowService::class.java)) }
        }
    }
}
