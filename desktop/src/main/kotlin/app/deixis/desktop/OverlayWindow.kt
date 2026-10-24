package app.deixis.desktop

import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JPanel
import javax.swing.JWindow

/**
 * Прозрачное окно над окном вывода: VLC на Windows и Linux рисует видео в своё системное окно, и
 * обычная отрисовка Swing оказывается под ним. Окно принадлежит окну вывода — держится над ним, не
 * берёт фокус; прозрачные точки пропускают мышь к окну под ним.
 */
class OverlayWindow(private val owner: Window, private val paint: (Graphics2D, Int, Int) -> Unit) {
    private val window = JWindow(owner)
    private val layer = object : JPanel() {
        init { isOpaque = false }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            paint(g as Graphics2D, width, height)
        }
    }
    private val follow = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent?) = place()
        override fun componentResized(e: ComponentEvent?) = place()
    }

    init {
        window.background = Color(0, 0, 0, 0)
        window.contentPane = layer
        window.focusableWindowState = false
        window.type = Window.Type.UTILITY
        owner.addComponentListener(follow)
    }

    var showing: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            if (v) place()
            window.isVisible = v
        }

    fun repaintLayer() { if (showing) layer.repaint() }

    private fun place() {
        if (!owner.isShowing) return
        val p = owner.locationOnScreen
        val insets = owner.insets
        window.setBounds(p.x + insets.left, p.y + insets.top, owner.width - insets.left - insets.right, owner.height - insets.top - insets.bottom)
    }

    fun dispose() {
        owner.removeComponentListener(follow)
        window.dispose()
    }

    companion object {
        /**
         * Умеет ли система окна с прозрачными точками на мониторе этого окна. Только Windows: там окна
         * всегда сводит композитор. В Linux без композитора Java тоже отвечает «умею», а окно выходит
         * сплошным чёрным и закрывает видео в зале — там слоя над видео нет.
         */
        fun supported(w: Window): Boolean = isWindows && runCatching {
            val d: GraphicsDevice = w.graphicsConfiguration?.device ?: return false
            d.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
        }.getOrDefault(false)

        private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    }
}
