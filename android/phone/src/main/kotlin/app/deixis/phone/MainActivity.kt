package app.deixis.phone

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.deixis.core.HolyricsLink
import android.view.KeyEvent
import app.deixis.core.Mode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class PhoneApp : Application() {
    /** Пульт живёт дольше экрана: поворот телефона не рвёт связь и не прерывает передачу. */
    val controller by lazy { Controller(this) }
    val holyrics by lazy { HolyricsSource(this, controller) }
    /** Кабель и Миракаст: приёмник второго экрана на всё приложение. */
    val cast by lazy { CastOutput(this) { controller } }

    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /** Обновления из выпусков GitHub; общий для пульта и режима экрана. */
    val updater by lazy {
        // Перед каждым обновлением — резервная копия библиотеки.
        app.deixis.receiver.ApkUpdates.updater(this, app.deixis.core.UpdateTarget.PHONE, appScope, beforeInstall = { r ->
            runCatching { controller.backupNow("before-update-${r.version}") }
        }).also { u ->
            app.deixis.receiver.ApkUpdates.onResult = { err -> if (err != null) u.installCancelled(err) }
            u.start()
        }
    }

    /** Версия, которую «Автоматически» уже пробовало поставить в этот запуск. */
    var autoInstallTried: String? = null

    /** Падение в прошлый раз: при следующем запуске приложение показывает причину, а не закрывается молча. */
    var lastCrash: String? = null

    override fun onCreate() {
        super.onCreate()
        val file = java.io.File(filesDir, "last-crash.txt")
        lastCrash = file.takeIf { it.isFile }?.readText()?.also { file.delete() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
                file.writeText(
                    "Deixis $version · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · " +
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" + e.stackTraceToString().lines().take(16).joinToString("\n"),
                )
            }
            previous?.uncaughtException(t, e)
        }
    }
}

class MainActivity : ComponentActivity() {

    /** Запасной вывод на второй экран через пульт; null — пульт не открыт как пульт (сбой, режим ТВ). */
    private var castView: CastPresentation? = null
    private val ui = Ui()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PhoneApp
        app.lastCrash?.let { crash ->
            // Прошлый запуск упал: показываем причину; «Продолжить» открывает приложение как обычно.
            app.lastCrash = null
            setContent { CrashScreen(crash) { recreate() } }
            return
        }
        // На телевизоре и приставке универсальный Deixis сразу работает экраном; пульт — с телефона.
        if (savedInstanceState == null && intent?.data == null && ScreenActivity.isTelevision(this)) {
            startActivity(Intent(this, ScreenActivity::class.java))
            finish()
            return
        }
        val controller = (application as PhoneApp).controller
        // Проверка интерфейса на эмуляторе (только отладочная сборка): формат экрана в зале без самого экрана.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent?.getFloatExtra(TEST_RATIO, 0f)?.takeIf { it > 0f }?.let { controller.testRatio.value = it }
        }
        val cast = (application as PhoneApp).cast
        castView = CastPresentation(this, cast).also { v ->
            lifecycleScope.launch { cast.fallback.collect { v.update(it) } }
        }
        setContent { DeixisPhone(controller, (application as PhoneApp).holyrics, ui) }
        handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** Ссылки из QR-кодов, открытые камерой: экран Deixis (deixis://) или Holyrics (deixis-holyrics://). */
    private fun handleLink(intent: Intent?) {
        val uri = intent?.data?.toString() ?: return
        val app = application as PhoneApp
        HolyricsLink.parse(uri)?.let { app.holyrics.connect(it); return }
        if (uri.startsWith("deixis://")) app.controller.addManual(uri)
    }

    /**
     * Bluetooth-кликер и педаль: «Вперёд» и «Назад», B — чёрный экран. Стрелки и пробел работают,
     * только пока не открыта шторка с полем ввода. Кнопки громкости — если включены в настройках.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (castView == null) return super.dispatchKeyEvent(event)
        val c = (application as PhoneApp).controller
        val st = c.settings.value
        val free = ui.sheet == null
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> if (st.clicker) 1 else null
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (st.clicker) -1 else null
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_SPACE -> if (st.clicker && free) 1 else null
            KeyEvent.KEYCODE_DPAD_LEFT -> if (st.clicker && free) -1 else null
            KeyEvent.KEYCODE_B -> if (st.clicker && free) 0 else null
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (st.volumeKeys) 1 else null
            KeyEvent.KEYCODE_VOLUME_UP -> if (st.volumeKeys) -1 else null
            else -> null
        } ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (action == 0) c.mode(Mode.BLACK) else c.step(action)
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        if (castView == null) return
        val controller = (application as PhoneApp).controller
        controller.startDiscovery()
        // Служба связи не могла начаться в фоне — начинается, как только пульт снова открыт.
        controller.keepAlive()
        (application as PhoneApp).cast.start()
    }

    override fun onStop() {
        if (castView != null) (application as PhoneApp).controller.stopDiscovery()
        super.onStop()
    }

    override fun onDestroy() {
        // Приёмник второго экрана живёт дольше пульта: закрывается только запасной вывод через пульт.
        castView?.dismiss()
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
private fun CrashScreen(text: String, onContinue: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).safeDrawingPadding()
            .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp),
    ) {
        androidx.compose.foundation.text.BasicText("Deixis закрылся из-за ошибки", style = androidx.compose.ui.text.TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 22.sp))
        androidx.compose.foundation.text.BasicText(
            "Отчёт уже заполнен: его можно отправить одним касанием.",
            androidx.compose.ui.Modifier.padding(top = 8.dp), style = androidx.compose.ui.text.TextStyle(color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f), fontSize = 15.sp),
        )
        androidx.compose.foundation.text.BasicText(text, androidx.compose.ui.Modifier.padding(top = 16.dp), style = androidx.compose.ui.text.TextStyle(color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f), fontSize = 12.sp))
        val context = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.padding(top = 20.dp).fillMaxWidth().height(50.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .background(androidx.compose.ui.graphics.Color.White).clickable { Feedback.crash(context, text) },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { androidx.compose.foundation.text.BasicText("Отправить отчёт", style = androidx.compose.ui.text.TextStyle(color = androidx.compose.ui.graphics.Color.Black, fontSize = 17.sp)) }
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.padding(top = 12.dp).fillMaxWidth().height(50.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f)).clickable(onClick = onContinue),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { androidx.compose.foundation.text.BasicText("Продолжить", style = androidx.compose.ui.text.TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 17.sp)) }
    }
}

/** Отладочная сборка: `am start --ef deixis.test.ratio 0.5625` — превью как у вертикального экрана в зале. */
private const val TEST_RATIO = "deixis.test.ratio"
