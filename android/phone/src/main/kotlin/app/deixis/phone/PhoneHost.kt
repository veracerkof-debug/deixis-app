package app.deixis.phone

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import app.deixis.core.UpdateHost
import app.deixis.receiver.ApkUpdates

/** Пульт на телефоне для экранов интерфейса: службы приложения и вызовы системы. */
class PhoneHost(private val app: PhoneApp) : PultHost {
    override val platform: Platform get() = app.platform
    override val holyrics: HolyricsSource get() = app.holyrics
    override val version: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
    override val updates: UpdateHost get() = app.updater
    override fun takeWhatsNew() = app.updater.takeWhatsNew()
    override val updatesFromStore: Boolean get() = ApkUpdates.fromPlay(app)
    override val canInstallUpdates: Boolean get() = ApkUpdates.canInstall(app)
    override var autoInstallTried: String?
        get() = app.autoInstallTried
        set(v) { app.autoInstallTried = v }

    @Composable override fun FeedbackSection() = PhoneFeedbackSection()

    override fun openCastSettings() {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        runCatching { app.startActivity(Intent(Settings.ACTION_CAST_SETTINGS).addFlags(flags)) }.onFailure {
            runCatching { app.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(flags)) }
        }
    }

    override fun becomeScreen() {
        app.startActivity(Intent(app, ScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
