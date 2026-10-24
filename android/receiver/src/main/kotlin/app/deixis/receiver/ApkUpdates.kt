package app.deixis.receiver

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.deixis.core.UpdateTarget
import app.deixis.core.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Обновление APK из выпуска GitHub. Файл до установки сверяется с установленным приложением: то же имя пакета,
 * версия новее, тот же ключ подписи. Ставит системный установщик (PackageInstaller): Android спрашивает
 * подтверждение; на Android 12 и новее, если прошлую версию ставил сам Deixis, — без вопроса.
 * Версия из Google Play обновляется только магазином.
 */
object ApkUpdates {

    /** Итог установки для настроек: null — успех (процесс сейчас перезапустится), иначе причина. */
    @Volatile var onResult: ((String?) -> Unit)? = null

    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.0.0"

    fun abis(): List<String> = Build.SUPPORTED_ABIS.toList()

    /** Обновлятель приложения: телефон (универсальная версия) или Deixis TV. */
    fun updater(context: Context, target: UpdateTarget, scope: CoroutineScope, beforeInstall: (app.deixis.core.Release) -> Unit = {}): Updater {
        val ctx = context.applicationContext
        return Updater(currentVersion(ctx), target, File(ctx.filesDir, "updates"), scope, abis()) { file, release ->
            beforeInstall(release)
            install(ctx, file)
        }
    }

    private fun installerOf(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
    }.getOrNull()

    /** Поставлено из Google Play: обновляет магазин. */
    fun fromPlay(context: Context): Boolean = installerOf(context) == "com.android.vending"

    /** Обновление пройдёт без вопроса: Android 12+ и прошлую версию ставил сам Deixis. */
    fun silent(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && installerOf(context) == context.packageName

    /** Разрешено ли Deixis ставить приложения (Android 8+ спрашивает это один раз в настройках). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Экран настроек «Установка неизвестных приложений» для Deixis. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun signatures(pm: PackageManager, info: PackageInfo?): Set<String> {
        info ?: return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.let { si ->
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else info.signatures
        return sigs.orEmpty().map { it.toCharsString() }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    /** Файл — то же приложение, новее и подписан тем же ключом. Иначе установка не начинается. */
    @Suppress("DEPRECATION")
    fun verify(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val file = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("Файл обновления не читается как приложение")
        if (file.packageName != context.packageName) throw IOException("Файл обновления — другое приложение (${file.packageName})")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (versionCode(file) < versionCode(installed)) throw IOException("Файл обновления старше установленной версии")
        val mine = signatures(pm, installed)
        val theirs = signatures(pm, file)
        // Подпись архива читается не на всех версиях Android; тогда сверку делает сам установщик.
        if (theirs.isNotEmpty() && mine.isNotEmpty() && mine.intersect(theirs).isEmpty()) {
            throw IOException("Файл обновления подписан другим ключом: установка отменена")
        }
    }

    /** Сеанс установки. Ответ системы приходит в [InstallResultReceiver]. */
    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        if (fromPlay(context)) throw IOException("Эта версия из Google Play: обновляет магазин")
        if (!canInstall(context)) {
            context.startActivity(permissionIntent(context))
            throw IOException("Разрешите Deixis устанавливать приложения и нажмите «Установить» ещё раз")
        }
        verify(context, apk)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("deixis.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName), flags)
            session.commit(pi.intentSender)
        }
    }
}

/** Ответ установщика: попросить подтверждение у оператора или сообщить, почему не вышло. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> ApkUpdates.onResult?.invoke(null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> ApkUpdates.onResult?.invoke("Установка отменена")
            else -> ApkUpdates.onResult?.invoke(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { "Обновление не установилось: $it" } ?: "Обновление не установилось",
            )
        }
    }
}

/**
 * После обновления Android закрывает приложение. Экран (ТВ, проектор) сам открывается снова, чтобы зал
 * не остался на меню телевизора. На Android 10+ система может это запретить — тогда открыть вручную.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pm = context.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(context.packageName)?.takeIf { ScreenRole.wasScreen(context) }
            ?: pm.getLaunchIntentForPackage(context.packageName)?.takeIf { ScreenRole.wasScreen(context) }
            ?: return
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Устройство работало экраном перед обновлением: после обновления открыть его снова. Пульт не открывается сам. */
object ScreenRole {
    private fun file(context: Context) = File(context.filesDir, "screen-active")
    fun mark(context: Context, active: Boolean) { runCatching { if (active) file(context).writeText("1") else file(context).delete() } }
    fun wasScreen(context: Context): Boolean = file(context).isFile
}

/**
 * Обновления экрана (ТВ, проектор, телефон в режиме экрана), которыми управляет пульт через `/api/update`.
 * Установка открывает окно системы поверх зала, поэтому только когда на экране ничего не показывают.
 * «Автоматически» на экране — скачать и поставить молча, если Android это позволяет и экран
 * простаивает 10 минут; иначе ждать кнопки «Обновить» на пульте.
 */
class ScreenUpdateHost(
    private val context: Context,
    private val core: app.deixis.core.ReceiverCore,
    private val updater: Updater,
    scope: CoroutineScope,
) : app.deixis.core.UpdateHost by updater {
    @Volatile private var idleSince = System.currentTimeMillis()

    init {
        ApkUpdates.onResult = { err -> if (err != null) updater.installCancelled(err) }
        scope.launch(Dispatchers.Default) {
            core.state.collect { s -> if (s.content == null) { if (idleSince == 0L) idleSince = System.currentTimeMillis() } else idleSince = 0L }
        }
        scope.launch(Dispatchers.Default) {
            while (true) {
                kotlinx.coroutines.delay(AUTO_TICK_MS)
                val st = updater.state.value
                val idle = idleSince != 0L && System.currentTimeMillis() - idleSince >= AUTO_IDLE_MS
                if (st.phase == "ready" && st.settings.mode == app.deixis.core.UpdateMode.AUTO && idle && ApkUpdates.silent(context)) updater.install()
            }
        }
        updater.start()
    }

    private fun showing(): Boolean = core.state.value.content != null

    override fun install() {
        if (showing()) { updater.installCancelled("Сначала уберите показ с экрана: установка откроет окно системы поверх зала"); return }
        updater.install()
    }

    private companion object {
        const val AUTO_TICK_MS = 60_000L
        const val AUTO_IDLE_MS = 10 * 60_000L
    }
}
