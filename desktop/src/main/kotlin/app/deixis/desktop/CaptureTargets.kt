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

/** Выходы видеокарты из `xrandr`: имя, подключён ли монитор, где он на рабочем столе (null — выключен). */
data class XOutput(val name: String, val connected: Boolean, val primary: Boolean, val x: Int?, val y: Int?, val width: Int?, val height: Int?)

/** Разбор `xrandr` (без ключей): размер рабочего стола и выходы. */
object Xrandr {
    private val screenRe = Regex("current (\\d+) x (\\d+)")
    private val outputRe = Regex("^(\\S+) (connected|disconnected)( primary)?(?: (\\d+)x(\\d+)\\+(\\d+)\\+(\\d+))?")

    fun screen(text: String): Pair<Int, Int>? = screenRe.find(text)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    fun outputs(text: String): List<XOutput> = text.lines().mapNotNull { line ->
        val m = outputRe.find(line) ?: return@mapNotNull null
        val g = m.groupValues
        XOutput(g[1], g[2] == "connected", g[3].isNotEmpty(), g[6].toIntOrNull(), g[7].toIntOrNull(), g[4].toIntOrNull(), g[5].toIntOrNull())
    }

    /**
     * Свободный выход под виртуальный монитор: сначала VIRTUAL (драйвер Intel заводит их нарочно), потом
     * любой выход без монитора — HDMI, DisplayPort. Встроенный экран ноутбука (eDP, LVDS) не берётся.
     */
    fun freeOutput(outs: List<XOutput>): XOutput? {
        val free = outs.filter { !it.connected && it.width == null && !it.name.startsWith("eDP") && !it.name.startsWith("LVDS") }
        return free.firstOrNull { it.name.startsWith("VIRTUAL", ignoreCase = true) } ?: free.firstOrNull()
    }
}

/**
 * Свой виртуальный экран на Linux (X11) без драйвера, двумя путями:
 * 1) свободный выход видеокарты (VIRTUAL, HDMI или DisplayPort без монитора) включается в режиме 1920×1080
 *    справа от основного — такой монитор видят система, настройки экранов и все программы;
 * 2) если видеодрайвер выход не включил — рабочий стол расширяется вправо и эта область объявляется монитором
 *    (xrandr --setmonitor): его видят программы, но не настройки экранов.
 * Каждый шаг пишется в журнал. Убирается при выходе из Deixis.
 */
object LinuxVirtualScreen {
    const val NAME = "DEIXIS"
    const val WIDTH = 1920
    const val HEIGHT = 1080
    /** Режим 1920×1080 60 Гц (cvt 1920 1080 60). */
    private const val MODE = "DEIXIS1080"
    private val MODELINE = listOf("173.00", "1920", "2048", "2248", "2576", "1080", "1083", "1088", "1120", "-hsync", "+vsync")

    /** Почему виртуального экрана здесь нет; null — может быть. */
    val unsupported: String? get() = when {
        !System.getProperty("os.name").orEmpty().lowercase().contains("linux") -> "не Linux"
        System.getenv("WAYLAND_DISPLAY") != null -> "сеанс Wayland: виртуальный монитор создаётся только в сеансе X11 (на экране входа — «Cinnamon» или «Xorg», не «Wayland»)"
        AppWindows.run("xrandr", "--version") == null -> "нет программы xrandr: установите пакет x11-xserver-utils"
        else -> null
    }
    val supported: Boolean get() = unsupported == null

    /** Где теперь виртуальный экран (x — его левый край). */
    @Volatile var created: Triple<Int, Int, Int>? = null
        private set

    /** Включённый выход (путь 1) или null (путь 2 — область рабочего стола). */
    private var output: String? = null
    /** Размер рабочего стола до расширения (путь 2): к нему возвращаемся. */
    private var original: Pair<Int, Int>? = null

    private fun x(vararg args: String): String? {
        val r = AppWindows.run("xrandr", *args)
        if (r == null) app.deixis.diag.Log.w("virtual", "xrandr ${args.joinToString(" ")}: не выполнено")
        return r
    }

    @Synchronized
    fun create(): Result<Int> = runCatching {
        created?.let { return@runCatching it.first }
        val info = x() ?: error("xrandr недоступен")
        val (w, h) = Xrandr.screen(info) ?: error("не узнать размер рабочего стола")
        val outs = Xrandr.outputs(info)
        val left = outs.filter { it.width != null }.maxOfOrNull { it.x!! + it.width!! } ?: w
        viaOutput(outs, left)?.let { return@runCatching it }
        // Путь 2: область рабочего стола справа от всего.
        x("--fb", "${w + WIDTH}x${maxOf(h, HEIGHT)}") ?: error("видеодрайвер не дал расширить рабочий стол")
        original = w to h
        // Размер в миллиметрах — примерно как у монитора 24″: программам он нужен только для масштаба.
        if (x("--setmonitor", NAME, "$WIDTH/531x$HEIGHT/299+$w+0", "none") == null) {
            x("--fb", "${w}x$h")
            error("не удалось объявить виртуальный монитор")
        }
        app.deixis.diag.Log.i("virtual", "Linux: область рабочего стола $w,0 ${WIDTH}x$HEIGHT (--setmonitor $NAME)")
        created = Triple(w, WIDTH, HEIGHT)
        w
    }

    /** Путь 1: свободный выход в режиме 1920×1080 справа от занятых. null — не вышло (всё возвращено как было). */
    private fun viaOutput(outs: List<XOutput>, left: Int): Int? {
        val free = Xrandr.freeOutput(outs) ?: run { app.deixis.diag.Log.i("virtual", "Linux: свободного выхода видеокарты нет"); return null }
        x("--newmode", MODE, *MODELINE.toTypedArray())  // уже есть — не страшно
        if (x("--addmode", free.name, MODE) == null) return null
        if (x("--output", free.name, "--mode", MODE, "--pos", "${left}x0") == null) {
            x("--delmode", free.name, MODE)
            return null
        }
        val on = x()?.let { Xrandr.outputs(it).firstOrNull { o -> o.name == free.name } }
        if (on?.width == null) {
            x("--output", free.name, "--off"); x("--delmode", free.name, MODE)
            app.deixis.diag.Log.w("virtual", "Linux: выход ${free.name} не включился")
            return null
        }
        output = free.name
        created = Triple(on.x ?: left, WIDTH, HEIGHT)
        app.deixis.diag.Log.i("virtual", "Linux: выход ${free.name} включён как монитор ${WIDTH}x$HEIGHT на ${on.x},${on.y}")
        return on.x ?: left
    }

    @Synchronized
    fun remove() {
        if (created == null) return
        val out = output
        if (out != null) {
            x("--output", out, "--off"); x("--delmode", out, MODE); x("--rmmode", MODE)
        } else {
            x("--delmonitor", NAME)
            original?.let { (w, h) -> x("--fb", "${w}x$h") }
        }
        output = null
        created = null
    }
}

/** Виртуальный экран Deixis своей системы: Linux (xrandr), Мак (CGVirtualDisplay), Windows (драйвер Deixis Display). */
object VirtualScreen {
    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val mac = os.contains("mac")
    private val win = os.contains("win")
    const val WIDTH = 1920
    const val HEIGHT = 1080

    val supported: Boolean get() = when { mac -> MacVirtualScreen.supported; win -> WindowsVirtualScreen.supported; else -> LinuxVirtualScreen.supported }

    /** Почему виртуального экрана на этом компьютере нет — для пульта и диагностики; null — он возможен. */
    val unsupported: String? get() = when {
        mac -> if (MacVirtualScreen.supported) null else "macOS не даёт создать виртуальный дисплей (нужна более новая версия macOS)"
        win -> if (WindowsVirtualScreen.supported) null else "драйвер Deixis Display не входит в эту установку: поставьте Deixis из установщика .msi"
        else -> LinuxVirtualScreen.unsupported
    }

    /** Последняя ошибка создания (для пульта и диагностики); null — не было или создан. */
    @Volatile var lastError: String? = null

    @Volatile private var winExists: Pair<Long, Boolean>? = null

    val exists: Boolean get() = when {
        mac -> MacVirtualScreen.displayId != null
        // Проверка на Windows — отдельная программа: результат помнится несколько секунд.
        win -> winExists?.takeIf { System.currentTimeMillis() - it.first < 5_000 }?.second
            ?: WindowsVirtualScreen.exists.also { winExists = System.currentTimeMillis() to it }
        else -> LinuxVirtualScreen.created != null
    }

    /** Создаёт (или берёт уже созданный) и возвращает, что снимать. */
    fun create(): Result<CaptureArea> = when {
        mac -> MacVirtualScreen.create().map { id ->
            // Новый дисплей появляется в списке съёмки не сразу.
            Thread.sleep(1_000)
            CaptureArea(0, 0, WIDTH, HEIGHT, screenIndex = MacVirtualScreen.captureIndex(id))
        }
        win -> WindowsVirtualScreen.create().also { winExists = null }
        else -> LinuxVirtualScreen.create().map { x -> CaptureArea(x, 0, WIDTH, HEIGHT) }
    }.also { r ->
        lastError = r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        r.onFailure { app.deixis.diag.Log.w("virtual", "не создан", it) }.onSuccess { app.deixis.diag.Log.i("virtual", "создан: ${it.x},${it.y} ${it.width}x${it.height}") }
    }

    /** Этот монитор из списка Java — виртуальный экран Deixis. */
    fun isVirtual(screenId: String, x: Int): Boolean = when {
        mac -> MacVirtualScreen.displayId?.let { id -> screenId.endsWith(" $id") || screenId == id.toString() } == true
        win -> runCatching {
            val area = WindowsVirtualScreen.find() ?: return@runCatching false
            val d = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.first { it.iDstring == screenId }
            val b = d.defaultConfiguration.bounds
            val k = d.displayMode.width.toDouble() / b.width
            kotlin.math.abs(b.x * k - area.x) < 2 && kotlin.math.abs(b.y * k - area.y) < 2
        }.getOrDefault(false)
        else -> LinuxVirtualScreen.created?.first == x
    }

    /** При выходе: Мак и Linux убирают экран сами (без прав); на Windows монитор остаётся до «Убрать». */
    fun releaseOnExit() { if (mac) MacVirtualScreen.remove() else if (!win) LinuxVirtualScreen.remove() }

    fun remove() { when { mac -> MacVirtualScreen.remove(); win -> { WindowsVirtualScreen.remove(); winExists = null }; else -> LinuxVirtualScreen.remove() } }
}
