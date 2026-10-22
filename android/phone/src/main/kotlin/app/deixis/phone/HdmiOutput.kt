package app.deixis.phone

import android.app.Presentation
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.deixis.core.Pairing
import app.deixis.receiver.ReceiverRuntime
import app.deixis.receiver.ReceiverScreen
import app.deixis.receiver.VlcPlayer
import kotlinx.coroutines.launch
import java.io.File

/**
 * Вывод по кабелю (USB-C → HDMI) и Miracast: второй экран, а не зеркало.
 * Внутри телефона запускается тот же приёмник, что на ТВ, но только для этого устройства,
 * поэтому кабель для пульта — обычный экран в списке.
 */
class HdmiOutput(private val activity: ComponentActivity, private val controller: Controller) : DisplayManager.DisplayListener {

    private val displays = activity.getSystemService(DisplayManager::class.java)
    private var runtime: ReceiverRuntime? = null
    private var player: VlcPlayer? = null
    private var presentation: Presentation? = null

    fun start() {
        displays.registerDisplayListener(this, null)
        check()
    }

    fun stop() {
        displays.unregisterDisplayListener(this)
        dismiss()
        runtime?.stop()
        runtime = null
    }

    override fun onDisplayAdded(displayId: Int) = check()
    override fun onDisplayRemoved(displayId: Int) = check()
    override fun onDisplayChanged(displayId: Int) = check()

    private fun check() {
        val display = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        when {
            display != null && presentation?.display?.displayId != display.displayId -> show(display)
            display == null && presentation != null -> dismiss()
        }
    }

    private fun show(display: Display) {
        dismiss()
        val rt = runtime ?: ReceiverRuntime(activity, "Кабель HDMI", "android-hdmi", File(activity.filesDir, "hdmi"), port = 0, loopbackOnly = true)
            .also { runtime = it }
        val vlc = player ?: VlcPlayer(activity) { rt.core.reportError(it) }.also { player = it }

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
        p.show()

        activity.lifecycleScope.launch {
            if (rt.actualPort == 0) rt.start()
            val saved = controller.cableToken()
            val token = if (saved != null && rt.core.pairing.isValid(saved)) saved
            else (rt.core.pairing.pair(rt.core.pairing.pin, "Этот телефон") as Pairing.Result.Ok).token
            controller.setCable(Target("cable", "Кабель HDMI", "127.0.0.1", rt.actualPort, Target.Kind.CABLE), token)
        }
    }

    private fun dismiss() {
        presentation?.dismiss()
        presentation = null
        player?.release()
        player = null
        controller.setCable(null, null)
    }
}
