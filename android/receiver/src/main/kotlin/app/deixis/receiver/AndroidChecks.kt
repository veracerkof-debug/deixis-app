package app.deixis.receiver

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import app.deixis.diag.Check
import app.deixis.diag.CheckResult
import app.deixis.diag.Status

/** Проверки Android для диагностики: Wi-Fi, экран, проигрыватель. Общие для ТВ, «Стать экраном» и пульта на телефоне. */
object AndroidChecks {
    fun wifi(context: Context) = Check("wifi", { it.t("Wi-Fi", "Wi-Fi", "Wi-Fi") }) { l ->
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        when {
            caps == null -> CheckResult("wifi", "Wi-Fi", Status.FAIL, l.t("нет сети", "tarmoq yo‘q", "no network"),
                l.t("Подключитесь к той же сети Wi-Fi, что и экраны.", "Ekranlar bilan bir xil Wi-Fi tarmog‘iga ulaning.", "Join the same Wi-Fi as the screens."))
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                CheckResult("wifi", "Wi-Fi", Status.OK, if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) "Ethernet" else "Wi-Fi")
            else -> CheckResult("wifi", "Wi-Fi", Status.WARN, l.t("мобильная сеть", "mobil tarmoq", "mobile data"),
                l.t("Экраны в зале видны только в той же сети Wi-Fi.", "Zaldagi ekranlar faqat bir xil Wi-Fi tarmog‘ida ko‘rinadi.", "Screens in the room are visible only on the same Wi-Fi."))
        }
    }

    fun device() = Check("device", { it.t("Устройство", "Qurilma", "Device") }) { l ->
        CheckResult("device", l.t("Устройство", "Qurilma", "Device"), Status.OK,
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
    }

    fun player(context: Context) = Check("vlc", { it.t("Проигрыватель видео", "Video pleyer", "Video player") }) { l ->
        val title = l.t("Проигрыватель видео", "Video pleyer", "Video player")
        val ok = runCatching { org.videolan.libvlc.LibVLC(context.applicationContext, arrayListOf("--no-audio")).release() }
        if (ok.isSuccess) CheckResult("vlc", title, Status.OK, "libVLC")
        else CheckResult("vlc", title, Status.FAIL, ok.exceptionOrNull()?.message.orEmpty(),
            l.t("Видео на этом экране не играет: поставьте сборку Deixis для этого процессора (arm64, armeabi-v7a, x86).", "Bu ekranda video o‘ynamaydi: shu protsessor uchun Deixis yig‘masini o‘rnating.", "Video does not play on this screen: install the Deixis build for this processor."))
    }

    fun screen(context: Context) = Check("display", { it.t("Экран", "Ekran", "Display") }) { l ->
        val m = context.resources.displayMetrics
        CheckResult("display", l.t("Размер экрана", "Ekran o‘lchami", "Display size"), Status.OK, "${m.widthPixels}×${m.heightPixels}")
    }

    fun notifications(app: Context) = Check("notifications", { it.t("Уведомление Deixis", "Deixis bildirishnomasi", "Deixis notification") }) { l ->
        val title = l.t("Уведомление Deixis", "Deixis bildirishnomasi", "Deixis notification")
        val on = app.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() != false
        if (on) CheckResult("notifications", title, Status.OK, l.t("включено", "yoqilgan", "on"))
        else CheckResult("notifications", title, Status.WARN, l.t("выключено", "o‘chirilgan", "off"),
            l.t("Без уведомления Android может оборвать связь с экраном в фоне: включите уведомления Deixis в настройках.", "Bildirishnomasiz Android fonda ekran bilan aloqani uzishi mumkin: sozlamalarda Deixis bildirishnomalarini yoqing.", "Without the notification Android may drop the link in the background: turn on Deixis notifications in settings."))
    }

    fun battery(app: Context) = Check("battery", { it.t("Экономия заряда", "Quvvatni tejash", "Battery saver") }) { l ->
        val title = l.t("Экономия заряда", "Quvvatni tejash", "Battery saver")
        val pm = app.getSystemService(android.os.PowerManager::class.java)
        val free = pm?.isIgnoringBatteryOptimizations(app.packageName) == true
        if (free) CheckResult("battery", title, Status.OK, l.t("Deixis не ограничен", "Deixis cheklanmagan", "Deixis is not restricted"))
        else CheckResult("battery", title, Status.WARN, l.t("Deixis ограничен", "Deixis cheklangan", "Deixis is restricted"),
            l.t("Если связь пропадает при выключенном экране телефона, снимите для Deixis ограничения расхода заряда.", "Telefon ekrani o‘chganda aloqa yo‘qolsa, Deixis uchun quvvat cheklovlarini olib tashlang.", "If the link drops when the phone screen is off, remove battery restrictions for Deixis."))
    }

}
