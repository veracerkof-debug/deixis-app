package app.deixis.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import app.deixis.core.Mode
import java.awt.Rectangle
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.WindowConstants

/**
 * Окно пульта Deixis на компьютере — тот же пульт, что на телефоне (pult/), в широкой раскладке: показ,
 * Библия, библиотека, сетлисты, устройства, настройки. Свой экран компьютера — первый в «Устройствах»
 * (по 127.0.0.1, без PIN); экраны в сети подключаются так же, как с телефона.
 * Клавиши — как у кликера: →, пробел, PageDown — дальше; ←, PageUp — назад; B — чёрный экран. Esc закрывает шторку.
 */
class PultWindow(
    private val controller: Controller,
    private val host: PultHost,
    private val ui: Ui,
    private val title: String,
    private val onClosed: () -> Unit,
) {
    private var window: ComposeWindow? = null

    val showing: Boolean get() = window?.isVisible == true

    fun open(screen: Rectangle?) {
        window?.let { w -> w.isVisible = true; w.extendedState = w.extendedState and java.awt.Frame.ICONIFIED.inv(); raise(w); return }
        val w = ComposeWindow()
        w.title = title
        app.deixis.desktop.AppIcon.apply(w)
        w.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        val area = screen ?: java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val width = minOf(WIDTH, area.width - MARGIN * 2)
        val height = minOf(HEIGHT, area.height - MARGIN * 2)
        w.setBounds(area.x + (area.width - width) / 2, area.y + (area.height - height) / 2, width, height)
        w.minimumSize = java.awt.Dimension(MIN_WIDTH, MIN_HEIGHT)
        w.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = close(notify = true)
        })
        w.setContent {
            CompositionLocalProvider(LocalHost provides host) {
                Box(
                    Modifier.fillMaxSize()
                        .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key == Key.Escape && DesktopBack.back() }
                        // После полей ввода: стрелки и пробел в тексте остаются тексту.
                        .onKeyEvent { e -> e.type == KeyEventType.KeyDown && clicker(e.key) },
                ) {
                    DeixisPhone(controller, host.holyrics, ui)
                }
            }
        }
        pultFrame = w
        window = w
        w.isVisible = true
        raise(w)
        app.deixis.diag.Log.i("pult", "window ${w.bounds.x},${w.bounds.y} ${w.bounds.width}x${w.bounds.height}")
    }

    /**
     * Вперёд, над окнами зала: на Windows новое окно иначе может остаться под окном зала, растянутым на весь монитор.
     * Поверх всех — лишь на миг, дальше обычное окно.
     */
    private fun raise(w: ComposeWindow) {
        w.isAlwaysOnTop = true
        w.toFront()
        w.requestFocus()
        javax.swing.Timer(RAISE_MS) { w.isAlwaysOnTop = false }.apply { isRepeats = false }.start()
    }

    private fun clicker(key: Key): Boolean {
        if (!controller.settings.value.clicker) return false
        when (key) {
            Key.DirectionRight, Key.Spacebar, Key.PageDown, Key.MediaNext -> controller.step(1)
            Key.DirectionLeft, Key.PageUp, Key.MediaPrevious -> controller.step(-1)
            Key.B -> controller.mode(Mode.BLACK)
            else -> return false
        }
        return true
    }

    fun close(notify: Boolean) {
        val w = window ?: return
        window = null
        if (pultFrame === w) pultFrame = null
        w.isVisible = false
        w.dispose()
        // Несохранённое дописывается сразу: окно закрыли — программа может сейчас же выйти.
        StoreWriter.flush()
        if (notify) onClosed()
    }

    private companion object {
        const val WIDTH = 1400
        const val HEIGHT = 900
        const val MIN_WIDTH = 720
        const val MIN_HEIGHT = 520
        const val MARGIN = 40
        const val RAISE_MS = 600
    }
}
