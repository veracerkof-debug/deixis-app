package app.deixis.phone

import android.app.Application
import android.content.Intent
import android.os.Bundle
import app.deixis.core.HolyricsLink
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class PhoneApp : Application() {
    /** Пульт живёт дольше экрана: поворот телефона не рвёт связь и не прерывает передачу. */
    val controller by lazy { Controller(this) }
    val holyrics by lazy { HolyricsSource(this, controller) }
}

class MainActivity : ComponentActivity() {

    private lateinit var hdmi: HdmiOutput

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as PhoneApp).controller
        hdmi = HdmiOutput(this, controller)
        lifecycleScope.launch {
            controller.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
        }
        setContent { DeixisPhone(controller, (application as PhoneApp).holyrics) }
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

    override fun onStart() {
        super.onStart()
        val controller = (application as PhoneApp).controller
        controller.startDiscovery()
        hdmi.start()
    }

    override fun onStop() {
        (application as PhoneApp).controller.stopDiscovery()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) hdmi.stop()
        super.onDestroy()
    }
}
