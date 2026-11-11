package app.deixis.desktop

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.User32
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Свой виртуальный монитор Windows — драйвер Deixis Display (папка windows-display, ставится вместе с Deixis).
 * «Создать» один раз спрашивает права администратора: помощник deixis-display.exe ставит сертификат драйвера
 * и заводит устройство, Windows подключает монитор «Deixis» 1920×1080. Монитор остаётся, пока его не убрали
 * («Убрать» — тоже с правами администратора): так Holyrics и другие программы помнят его между запусками.
 */
object WindowsVirtualScreen {
    /** DISPLAY_DEVICEW из WinUser.h: в jna-platform его нет. */
    @Structure.FieldOrder("cb", "DeviceName", "DeviceString", "StateFlags", "DeviceID", "DeviceKey")
    class DisplayDevice : Structure() {
        @JvmField var cb: Int = 0
        @JvmField var DeviceName = CharArray(32)
        @JvmField var DeviceString = CharArray(128)
        @JvmField var StateFlags: Int = 0
        @JvmField var DeviceID = CharArray(128)
        @JvmField var DeviceKey = CharArray(128)
        init { cb = size() }
    }

    private interface Display : StdCallLibrary {
        fun EnumDisplayDevices(lpDevice: String?, iDevNum: Int, lpDisplayDevice: DisplayDevice, dwFlags: Int): Boolean
    }
    private val display by lazy { Native.load("user32", Display::class.java, W32APIOptions.DEFAULT_OPTIONS) }

    private val dir: File? by lazy {
        val explicit = System.getProperty("deixis.display")?.let(::File)
        val nearApp = System.getProperty("jpackage.app-path")?.let { File(it).parentFile?.resolve("deixis-display") }
        listOfNotNull(explicit, nearApp).firstOrNull { File(it, "deixis-display.exe").isFile && File(it, "DeixisDisplay.inf").isFile }
    }
    private val helper get() = dir?.resolve("deixis-display.exe")

    val supported: Boolean get() = System.getProperty("os.name").orEmpty().lowercase().contains("win") && dir != null

    /** Монитор Deixis уже подключён (без прав администратора). */
    val exists: Boolean get() = supported && run(listOf(helper!!.path, "status")) == 0

    /** Включает монитор (с запросом прав) и возвращает его место на рабочем столе в точках экрана. */
    fun create(): Result<CaptureArea> = runCatching {
        val d = dir ?: error("драйвер не входит в эту установку Deixis")
        find()?.let { return@runCatching it }
        if (!exists) {
            app.deixis.diag.Log.i("virtual", "Windows: ставлю драйвер Deixis Display (окно прав администратора)")
            // Помощник идёт в скрытом окне с правами администратора: свои шаги и ошибки Windows он пишет в файл.
            val steps = File.createTempFile("deixis-display-", ".log")
            val code = try {
                elevated("install", File(d, "DeixisDisplay.inf").path, File(d, "DeixisDisplay.cer").path, "--log", steps.path)
            } finally {
                runCatching { steps.readText(Charsets.UTF_8).lines().map { it.trim('\uFEFF', ' ') }.filter { it.isNotEmpty() } }.getOrDefault(emptyList())
                    .forEach { app.deixis.diag.Log.i("virtual", "Windows: $it") }
                steps.delete()
            }
            app.deixis.diag.Log.i("virtual", "Windows: помощник установки завершился, код $code")
            when (code) {
                0 -> Unit
                // Окно прав могло остаться под другими окнами: ответ пришёл позже — монитор ещё может появиться.
                -1 -> if (waitFor(LATE_MS) == null) throw Refused("окно прав администратора осталось без ответа — оно могло открыться под другими окнами")
                1223 -> throw Refused(DECLINED)
                2 -> error("Windows не доверила драйверу Deixis Display: сертификат не принят (часто — антивирус) и установка без него не прошла. Подробности — в журнале, строки «virtual»")
                3 -> error("Windows не завела устройство виртуального монитора (подробности — в журнале)")
                4 -> error("драйвер виртуального монитора не установился (подробности — в журнале)")
                else -> error("установка драйвера: код $code")
            }
        }
        waitFor(APPEAR_MS) ?: error("драйвер стоит, но Windows не подключила монитор: перезагрузите компьютер и нажмите «Создать» ещё раз")
    }

    /** Отказ оператора (или окно прав без ответа) — не ошибка драйвера. */
    class Refused(message: String) : Exception(message)
    const val DECLINED = "права администратора не даны"

    private fun waitFor(ms: Long): CaptureArea? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { find()?.let { return it }; Thread.sleep(POLL_MS) }
        return null
    }

    private const val APPEAR_MS = 10_000L
    private const val LATE_MS = 60_000L
    private const val POLL_MS = 250L

    fun remove() {
        if (supported && exists) elevated("remove")
    }

    /** Место монитора с EDID «DXS0001» (так его назвал драйвер) — в настоящих точках экрана. */
    fun find(): CaptureArea? = runCatching {
        val u = User32.INSTANCE
        var adapterName: String? = null
        var i = 0
        while (adapterName == null) {
            val adapter = DisplayDevice()
            if (!display.EnumDisplayDevices(null, i++, adapter, 0)) break
            val name = String(adapter.DeviceName).trimEnd('\u0000')
            var j = 0
            while (true) {
                val mon = DisplayDevice()
                if (!display.EnumDisplayDevices(name, j++, mon, 0)) break
                if (String(mon.DeviceID).contains("DXS0001", ignoreCase = true)) { adapterName = name; break }
            }
        }
        val target = adapterName ?: return@runCatching null
        var found: CaptureArea? = null
        u.EnumDisplayMonitors(null, null, { hMonitor, _, _, _ ->
            val info = WinUser.MONITORINFOEX()
            if (u.GetMonitorInfo(hMonitor, info).booleanValue()) {
                val dev = String(info.szDevice).trimEnd('\u0000')
                if (dev.equals(target, ignoreCase = true)) {
                    val r = info.rcMonitor
                    found = CaptureArea(r.left, r.top, r.right - r.left, r.bottom - r.top)
                }
            }
            1
        }, WinDef.LPARAM(0))
        found
    }.getOrNull()

    /** Запуск помощника с правами администратора (окно UAC); код выхода, -1 — не запустился или отказ. */
    private fun elevated(vararg args: String): Int {
        val exe = helper ?: return -1
        fun ps(s: String) = "'" + s.replace("'", "''") + "'"
        val list = args.joinToString(",") { ps("\"$it\"") }
        // Отказ в окне прав — исключение PowerShell: код 1223, как у Windows (ERROR_CANCELLED).
        val cmd = "try { \$p = Start-Process -FilePath ${ps(exe.path)} -ArgumentList $list -Verb RunAs -Wait -PassThru -WindowStyle Hidden -ErrorAction Stop; exit \$p.ExitCode } catch { exit 1223 }"
        return run(listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", cmd), 120)
    }

    private fun run(cmd: List<String>, seconds: Long = 15): Int = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        // Вывод читается в фоне: иначе ожидание не ограничено временем, если программа молчит и не выходит.
        val out = StringBuilder()
        val reader = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }.apply { isDaemon = true; start() }
        val code = if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.destroyForcibly(); -1 } else p.exitValue()
        reader.join(READER_MS)
        if (code != 0 && cmd.getOrNull(1) != "status") out.toString().trim().takeIf { it.isNotEmpty() }?.let { app.deixis.diag.Log.w("virtual", "Windows: ${it.take(LOG_CHARS)}") }
        code
    }.getOrDefault(-1)

    private const val READER_MS = 1_000L
    private const val LOG_CHARS = 600
}
