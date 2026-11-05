package app.deixis.desktop

import java.util.concurrent.TimeUnit

/** Окно программы, которое можно транслировать. */
data class AppWindow(val id: Long, val title: String, val width: Int, val height: Int)

/**
 * Окна программ на этом компьютере для трансляции: Linux — через xprop (список окон рабочего стола),
 * Windows — через User32 (видимые окна с заголовком). На Маке окна по отдельности пока не снимаются.
 * Окна самого Deixis в список не попадают: снимать себя бессмысленно.
 */
object AppWindows {
    private val os = System.getProperty("os.name").orEmpty().lowercase()

    fun list(): List<AppWindow> = runCatching {
        when {
            os.contains("win") -> windows()
            os.contains("linux") -> x11()
            else -> emptyList()
        }
    }.getOrDefault(emptyList()).filter { it.title.isNotBlank() && !it.title.startsWith("Deixis") && it.width >= MIN_SIDE && it.height >= MIN_SIDE }

    private fun x11(): List<AppWindow> {
        val ids = run("xprop", "-root", "_NET_CLIENT_LIST") ?: return emptyList()
        return Regex("0x[0-9a-fA-F]+").findAll(ids.substringAfter("#", "")).mapNotNull { m ->
            val id = m.value.removePrefix("0x").toLongOrNull(16) ?: return@mapNotNull null
            val name = run("xprop", "-id", m.value, "_NET_WM_NAME")?.substringAfter("= ", "")?.trim()?.removeSurrounding("\"") ?: ""
            val geo = run("xwininfo", "-id", m.value).orEmpty()
            val w = Regex("Width: (\\d+)").find(geo)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val h = Regex("Height: (\\d+)").find(geo)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            AppWindow(id, name, w, h)
        }.toList()
    }

    private fun windows(): List<AppWindow> {
        val u = com.sun.jna.platform.win32.User32.INSTANCE
        val out = ArrayList<AppWindow>()
        u.EnumWindows({ hwnd, _ ->
            if (u.IsWindowVisible(hwnd)) {
                val buf = CharArray(512)
                val n = u.GetWindowText(hwnd, buf, buf.size)
                val r = com.sun.jna.platform.win32.WinDef.RECT()
                u.GetWindowRect(hwnd, r)
                if (n > 0) out += AppWindow(com.sun.jna.Pointer.nativeValue(hwnd.pointer), String(buf, 0, n), r.right - r.left, r.bottom - r.top)
            }
            true
        }, null)
        return out
    }

    internal fun run(vararg cmd: String): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        text.takeIf { p.exitValue() == 0 }
    }.getOrNull()

    private const val MIN_SIDE = 120
}

/**
 * Свой виртуальный экран на Linux (X11) без драйвера: рабочий стол расширяется вправо на [WIDTH]×[HEIGHT],
 * и эта область объявляется отдельным монитором (xrandr --setmonitor). Программы (Holyrics, LibreOffice)
 * видят его как второй монитор и выводят туда свой «проектор»; Deixis снимает его и раздаёт.
 * Пробно: часть видеодрайверов и оболочек не даёт расширить рабочий стол — тогда [create] вернёт ошибку.
 * Убирается при выходе из Deixis.
 */
object LinuxVirtualScreen {
    const val NAME = "DEIXIS"
    const val WIDTH = 1920
    const val HEIGHT = 1080

    val supported: Boolean get() = System.getProperty("os.name").orEmpty().lowercase().contains("linux") &&
        System.getenv("WAYLAND_DISPLAY") == null && AppWindows.run("xrandr", "--version") != null

    /** Где теперь виртуальный экран (x — его левый край) или текст ошибки. */
    @Volatile var created: Triple<Int, Int, Int>? = null
        private set

    /** Размер рабочего стола до расширения: к нему возвращаемся. */
    private var original: Pair<Int, Int>? = null

    @Synchronized
    fun create(): Result<Int> = runCatching {
        created?.let { return@runCatching it.first }
        val info = AppWindows.run("xrandr") ?: error("xrandr недоступен")
        val m = Regex("current (\\d+) x (\\d+)").find(info) ?: error("не узнать размер рабочего стола")
        val w = m.groupValues[1].toInt(); val h = m.groupValues[2].toInt()
        val newH = maxOf(h, HEIGHT)
        AppWindows.run("xrandr", "--fb", "${w + WIDTH}x$newH") ?: error("видеодрайвер не дал расширить рабочий стол")
        original = w to h
        // Размер в миллиметрах — примерно как у монитора 24″: программам он нужен только для масштаба.
        if (AppWindows.run("xrandr", "--setmonitor", NAME, "$WIDTH/531x$HEIGHT/299+$w+0", "none") == null) {
            AppWindows.run("xrandr", "--fb", "${w}x$h")
            error("не удалось объявить виртуальный монитор")
        }
        created = Triple(w, WIDTH, HEIGHT)
        w
    }

    @Synchronized
    fun remove() {
        if (created == null) return
        AppWindows.run("xrandr", "--delmonitor", NAME)
        original?.let { (w, h) -> AppWindows.run("xrandr", "--fb", "${w}x$h") }
        created = null
    }
}
