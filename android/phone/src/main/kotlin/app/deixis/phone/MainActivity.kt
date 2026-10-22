package app.deixis.phone

import android.app.Application
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class PhoneApp : Application() {
    /** Пульт живёт дольше экрана: поворот телефона не рвёт связь и не прерывает передачу. */
    val controller by lazy { Controller(this) }
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
        setContent { DeixisPhone(controller) }
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
