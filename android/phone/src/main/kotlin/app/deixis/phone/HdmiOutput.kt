package app.deixis.phone

import android.app.ActivityOptions
import android.app.Application
import android.app.Presentation
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.deixis.core.Pairing
import app.deixis.receiver.ReceiverRuntime
import app.deixis.receiver.ReceiverScreen
import app.deixis.receiver.VlcPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Вывод по кабелю (USB-C → HDMI) и Миракаст: второй экран, а не зеркало.
 * Внутри телефона работает тот же приёмник, что на ТВ, только для этого устройства, поэтому кабель
 * для пульта — обычный экран в списке.
 *
 * Приёмник один на всё приложение: пульт свернули, повернули или пересоздали — в зале остаётся то,
 * что было, и связь не рвётся. Картинку на второй экран выводит своё окно на нём ([CastActivity]):
 * у него своя жизнь, и оно не гаснет, когда пульт уходит в фон. Если Android не дал открыть окно
 * на втором экране, выводит открытый пульт ([CastPresentation]), как раньше.
 */
class CastOutput(private val app: Application, private val controller: () -> Controller) : DisplayManager.DisplayListener {

    private val displays = app.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    /** Приёмник второго экрана; живёт, пока живёт приложение. */
    var runtime: ReceiverRuntime? = null
        private set
    private var wireless: Boolean? = null
    private var runtimeSize: Pair<Int, Int>? = null

    /** Второй экран, на который сейчас идёт вывод. */
    private var shown: Display? = null

    /** Окно на втором экране; null — его нет или Android не дал его открыть. */
    var activity: CastActivity? = null
        internal set(value) { field = value; if (value != null) _fallback.value = null }

    /** Окно на втором экране не открылось: вывести через открытый пульт. */
    val fallback: StateFlow<Display?> get() = _fallback
    private val _fallback = MutableStateFlow<Display?>(null)

    fun start() {
        if (!started) {
            started = true
            displays.registerDisplayListener(this, main)
        }
        check(retry = true)
    }

    override fun onDisplayAdded(displayId: Int) = check()
    override fun onDisplayRemoved(displayId: Int) = check()
    override fun onDisplayChanged(displayId: Int) = check()

    /** [retry] — пульт снова открыт: окно на втором экране, которого нет, открывается ещё раз. */
    private fun check(retry: Boolean = false) {
        val display = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        when {
            display == null -> if (shown != null) hide()
            display.displayId != shown?.displayId -> show(display)
            // Пульт вернулся, а окна на втором экране нет: открыть ещё раз.
            retry && activity == null -> open(display)
        }
    }

    private fun show(display: Display) {
        shown = display
        val isWireless = isWireless(display)
        // Пульт рисует превью в пропорциях этого экрана, а не телефона.
        val size = android.graphics.Point().also { @Suppress("DEPRECATION") display.getRealSize(it) }.let { it.x to it.y }
        if (runtime == null || wireless != isWireless || runtimeSize != size) {
            runtime?.stop()
            wireless = isWireless
            runtimeSize = size
            runtime = ReceiverRuntime(app, if (isWireless) "Беспроводной дисплей" else "Кабель HDMI", "android-hdmi", File(app.filesDir, "hdmi"), port = 0, loopbackOnly = true, screen = size)
        }
        val rt = runtime!!
        scope.launch {
            if (rt.actualPort == 0) rt.start()
            val c = controller()
            val saved = c.cableToken()
            val token = if (saved != null && rt.core.pairing.isValid(saved)) saved
            else (rt.core.pairing.pair(rt.core.pairing.pin, "Этот телефон") as Pairing.Result.Ok).token
            // Миракаст — тот же второй экран, но без провода: так и подписывается.
            val target = if (isWireless) Target("cable", display.name?.takeIf { it.isNotBlank() } ?: "Беспроводной дисплей", "127.0.0.1", rt.actualPort, Target.Kind.MIRACAST)
            else Target("cable", "Кабель HDMI", "127.0.0.1", rt.actualPort, Target.Kind.CABLE)
            c.setCable(target, token)
        }
        open(display)
    }

    /** Своё окно на втором экране; не открылось за пару секунд — вывод через пульт. */
    private fun open(display: Display) {
        _fallback.value = null
        val opened = runCatching {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId)
            app.startActivity(
                Intent(app, CastActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                options.toBundle(),
            )
        }.isSuccess
        if (!opened) { _fallback.value = display; return }
        // Из фона Android молча не открывает окна: тогда выручает пульт, когда он открыт.
        main.postDelayed({ if (activity == null && shown?.displayId == display.displayId) _fallback.value = display }, OPEN_WAIT_MS)
    }

    private fun hide() {
        shown = null
        _fallback.value = null
        activity?.finish()
        activity = null
        // Приёмник остаётся: экран вернётся — в зале то же, что было.
        controller().setCable(null, null)
    }

    /**
     * Беспроводной дисплей (Миракаст, Wi-Fi Display) или кабель. Тип экрана Android отдаёт скрытым
     * Display.getType() (TYPE_WIFI = 3); если его не прочитать, подсказывает имя экрана.
     */
    private fun isWireless(display: Display): Boolean {
        val type = runCatching { Display::class.java.getMethod("getType").invoke(display) as Int }.getOrNull()
        if (type != null) return type == TYPE_WIFI
        val name = display.name.orEmpty().lowercase()
        return listOf("wifi", "wi-fi", "wireless", "miracast", "cast", "беспровод").any { it in name }
    }

    private companion object {
        const val TYPE_WIFI = 3
        const val OPEN_WAIT_MS = 2_000L
    }
}

/** Окно приёмника на втором экране: живёт отдельно от пульта и не гаснет, когда пульт свёрнут. */
class CastActivity : ComponentActivity() {
    private var player: VlcPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cast = (application as PhoneApp).cast
        val rt = cast.runtime
        // Второй экран отключили, и Android перенёс окно на телефон: здесь ему не место.
        if (rt == null || onPhoneScreen()) { finish(); return }
        // Фокус остаётся у пульта: кликер и педаль листают слайды, а не нажимают в окне зала.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        hideSystemBars()
        val vlc = VlcPlayer(this) { rt.core.reportError(it) }.also { player = it }
        setContent { ReceiverScreen(rt, vlc, showIdleInfo = false) }
        cast.activity = this
    }

    override fun onStart() {
        super.onStart()
        if (onPhoneScreen()) finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        val cast = (application as PhoneApp).cast
        if (cast.activity === this) cast.activity = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun onPhoneScreen(): Boolean {
        val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        return d == null || d.displayId == Display.DEFAULT_DISPLAY
    }
}

/** Запасной вывод на второй экран через открытый пульт: если своё окно там открыть не дали. */
class CastPresentation(private val activity: ComponentActivity, private val cast: CastOutput) {
    private var presentation: Presentation? = null
    private var player: VlcPlayer? = null

    fun update(display: Display?) {
        if (display?.displayId == presentation?.display?.displayId) return
        dismiss()
        val rt = cast.runtime ?: return
        display ?: return
        val vlc = VlcPlayer(activity) { rt.core.reportError(it) }.also { player = it }
        val p = object : Presentation(activity, display) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window?.decorView?.let {
                    it.setViewTreeLifecycleOwner(activity)
                    it.setViewTreeSavedStateRegistryOwner(activity)
                }
                setContentView(ComposeView(context).apply { setContent { ReceiverScreen(rt, vlc, showIdleInfo = false) } })
            }
        }
        presentation = p
        runCatching { p.show() }.onFailure { dismiss() }
    }

    fun dismiss() {
        runCatching { presentation?.dismiss() }
        presentation = null
        player?.release()
        player = null
    }
}
