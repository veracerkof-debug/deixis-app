package app.deixis.tv

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.deixis.receiver.ReceiverScreen
import app.deixis.receiver.VlcPlayer

class MainActivity : ComponentActivity() {

    private var player: VlcPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        val app = application as DeixisTvApp
        val runtime = app.runtime
        val error = app.startError
        if (runtime == null || error != null) {
            // Приёмник не запустился: вместо мгновенного закрытия — причина, её можно сфотографировать.
            setContent { StartError(error ?: "Приёмник не создан") }
            return
        }
        val vlc = VlcPlayer(this) { runtime.core.reportError(it) }
        player = vlc
        setContent { ReceiverScreen(runtime, vlc, overscan = true) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }
}

@Composable
private fun StartError(text: String) {
    Column(Modifier.fillMaxSize().background(Color.Black).padding(48.dp)) {
        BasicText("Deixis TV не запустился", style = TextStyle(color = Color.White, fontSize = 28.sp))
        BasicText(
            "Сфотографируйте этот экран и отправьте разработчику. Перезапуск приложения покажет экран ожидания, если ошибка разовая.",
            Modifier.padding(top = 12.dp), style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp),
        )
        BasicText(text, Modifier.padding(top = 24.dp), style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp))
    }
}
