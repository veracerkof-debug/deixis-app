package app.deixis.diag

import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Журнал Deixis — один на всех площадках (телефон, ТВ, компьютер). Последние записи держатся в памяти
 * (для отчёта диагностики), всё — ещё и в файле log.txt в папке данных, если площадка его указала.
 * Журнал никуда не отправляется: оператор сам решает, переслать ли отчёт.
 */
object Log {
    enum class Level { INFO, WARN, ERROR }

    data class Entry(val at: Long, val level: Level, val tag: String, val message: String) {
        override fun toString(): String = "${TIME.format(Instant.ofEpochMilli(at))} ${level.name.first()} $tag: $message"
    }

    private val ring = ArrayDeque<Entry>()
    @Volatile private var file: File? = null
    private var out: OutputStream? = null
    private var written = 0L

    /** Писать ещё и в файл [dir]/log.txt; прежний журнал — log.old.txt. */
    @Synchronized
    fun toFile(dir: File) {
        if (file != null) return
        runCatching {
            dir.mkdirs()
            val f = File(dir, NAME)
            if (f.isFile) File(dir, OLD).let { old -> old.delete(); f.renameTo(old) }
            out = f.outputStream().buffered()
            file = f
            written = 0
            // То, что записано до файла (запуск, первые ошибки), — в начало файла.
            ring.forEach { writeLine(it.toString()) }
        }
    }

    /** Файл журнала (если пишется). */
    val logFile: File? get() = file

    fun i(tag: String, message: String) = add(Level.INFO, tag, message)
    fun w(tag: String, message: String, e: Throwable? = null) = add(Level.WARN, tag, message + (e?.let { ": " + it.brief() } ?: ""))
    fun e(tag: String, message: String, e: Throwable? = null) =
        add(Level.ERROR, tag, message + (e?.let { "\n" + it.stackTraceToString().lines().take(STACK_LINES).joinToString("\n") } ?: ""))

    @Synchronized
    private fun add(level: Level, tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        ring.addLast(entry)
        while (ring.size > KEEP) ring.removeFirst()
        writeLine(entry.toString())
    }

    @Synchronized
    private fun writeLine(line: String) {
        val o = out ?: return
        if (written > MAX_FILE_BYTES) return
        runCatching {
            val bytes = (line + "\n").toByteArray()
            o.write(bytes); o.flush()
            written += bytes.size
        }
    }

    /** Последние записи, старые первыми. */
    @Synchronized
    fun recent(n: Int = KEEP): List<Entry> = ring.toList().takeLast(n)

    /** Ошибки и предупреждения последнего времени — для диагностики. */
    fun problems(n: Int = PROBLEMS): List<Entry> = recent().filter { it.level != Level.INFO }.takeLast(n)

    /**
     * Компьютер: всё, что программа печатает в консоль, — тоже в журнал (у программы из установщика консоли нет),
     * и необработанные ошибки — с причиной.
     */
    fun captureConsole() {
        System.setErr(PrintStream(LineSink(System.err, Level.WARN), true, "UTF-8"))
        System.setOut(PrintStream(LineSink(System.out, Level.INFO), true, "UTF-8"))
        catchUncaught()
    }

    /** Необработанная ошибка — в журнал до того, как процесс упадёт. */
    fun catchUncaught() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, ex ->
            e("crash", "Необработанная ошибка в потоке ${t.name}", ex)
            previous?.uncaughtException(t, ex)
        }
    }

    /** Поток консоли: печатает как раньше и складывает строки в журнал. */
    private class LineSink(private val pass: OutputStream, private val level: Level) : OutputStream() {
        private val line = java.io.ByteArrayOutputStream()
        override fun write(b: Int) {
            runCatching { pass.write(b) }
            if (b == '\n'.code) flushLine() else line.write(b)
        }
        override fun flush() { runCatching { pass.flush() } }
        @Synchronized private fun flushLine() {
            val s = line.toString(Charsets.UTF_8).trimEnd()
            line.reset()
            if (s.isNotEmpty()) add(level, "console", s)
        }
    }

    private fun Throwable.brief() = "${javaClass.simpleName}: ${message.orEmpty()}"

    private const val NAME = "log.txt"
    private const val OLD = "log.old.txt"
    private const val KEEP = 400
    private const val PROBLEMS = 20
    private const val STACK_LINES = 14
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
}
