package app.deixis.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Window

/**
 * Окно «Наложения» пропускает мышь к программе под ним: щёлкать и листать можно там, будто окна нет.
 * Windows — WS_EX_TRANSPARENT (окно «прозрачно» для мыши) и WS_EX_NOACTIVATE (не забирает фокус);
 * Linux (X11) — пустая область ввода окна (XFixes, ShapeInput); macOS — ignoresMouseEvents у NSWindow.
 * Не вышло — окно остаётся обычным (мышь на нём останавливается), показ это не трогает.
 */
object ClickThrough {
    private val os = System.getProperty("os.name").orEmpty().lowercase()

    /** Окно уже на экране (у него есть системное окно). true — мышь проходит. */
    fun apply(w: Window): Boolean = runCatching {
        when {
            os.contains("win") -> windows(w)
            os.contains("mac") -> mac(w)
            else -> x11(w)
        }
    }.onFailure { app.deixis.diag.Log.w("overlay", "мышь сквозь окно не пропускается", it) }.getOrDefault(false)

    private fun windows(w: Window): Boolean {
        val hwnd = WinDef.HWND(Native.getComponentPointer(w) ?: return false)
        val u = User32.INSTANCE
        val ex = u.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        u.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, ex or WinUser.WS_EX_LAYERED or WS_EX_TRANSPARENT or WS_EX_NOACTIVATE or WS_EX_TOOLWINDOW)
        return true
    }

    private interface X11 : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XFlush(d: Pointer): Int
        fun XCloseDisplay(d: Pointer): Int
    }
    private interface Xfixes : Library {
        fun XFixesCreateRegion(d: Pointer, rects: Pointer?, n: Int): Long
        fun XFixesSetWindowShapeRegion(d: Pointer, win: Long, kind: Int, x: Int, y: Int, region: Long)
        fun XFixesDestroyRegion(d: Pointer, region: Long)
    }

    private fun x11(w: Window): Boolean {
        val win = Native.getComponentID(w).takeIf { it != 0L } ?: return false
        val x = Native.load("X11", X11::class.java)
        val f = Native.load("Xfixes", Xfixes::class.java)
        val d = x.XOpenDisplay(null) ?: return false
        try {
            val empty = f.XFixesCreateRegion(d, null, 0)
            f.XFixesSetWindowShapeRegion(d, win, SHAPE_INPUT, 0, 0, empty)
            f.XFixesDestroyRegion(d, empty)
            x.XFlush(d)
        } finally {
            x.XCloseDisplay(d)
        }
        return true
    }

    /**
     * macOS: NSWindow окна Java ищется среди окон программы по временному уникальному заголовку
     * (указатель от JAWT у окон на слоях Core Animation бывает пустым), затем ignoresMouseEvents = YES.
     */
    private fun mac(w: Window): Boolean {
        val objc = NativeLibrary.getInstance("objc")
        val send = objc.getFunction("objc_msgSend")
        fun sel(n: String): Pointer = objc.getFunction("sel_registerName").invokePointer(arrayOf(n))
        fun cls(n: String): Pointer = objc.getFunction("objc_getClass").invokePointer(arrayOf(n))
        fun str(p: Pointer?): String? = p?.let { send.invokePointer(arrayOf(it, sel("UTF8String")))?.getString(0, "UTF-8") }
        val frame = w as? java.awt.Frame ?: return false
        val old = frame.title
        val mark = "deixis-overlay-" + System.nanoTime()
        frame.title = mark
        try {
            val app = send.invokePointer(arrayOf(cls("NSApplication"), sel("sharedApplication"))) ?: return false
            // Заголовок доходит до NSWindow не сразу: несколько попыток.
            repeat(MAC_TRIES) {
                val list = send.invokePointer(arrayOf(app, sel("windows"))) ?: return false
                val n = send.invokeLong(arrayOf(list, sel("count")))
                for (i in 0 until n) {
                    val ns = send.invokePointer(arrayOf(list, sel("objectAtIndex:"), i)) ?: continue
                    if (str(send.invokePointer(arrayOf(ns, sel("title")))) == mark) {
                        send.invokeVoid(arrayOf(ns, sel("setIgnoresMouseEvents:"), true))
                        return true
                    }
                }
                Thread.sleep(MAC_WAIT_MS)
            }
            return false
        } finally {
            frame.title = old
        }
    }

    private const val MAC_TRIES = 10
    private const val MAC_WAIT_MS = 50L
    private const val WS_EX_TRANSPARENT = 0x00000020
    private const val WS_EX_NOACTIVATE = 0x08000000
    private const val WS_EX_TOOLWINDOW = 0x00000080
    /** ShapeInput из X11/extensions/shape.h. */
    private const val SHAPE_INPUT = 2
}
