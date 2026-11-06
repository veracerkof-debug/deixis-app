package app.deixis.desktop

import java.io.File
import java.io.OutputStream
import java.io.PrintStream

/**
 * Журнал программы на компьютере — файл log.txt в папке данных Deixis (никуда не отправляется).
 * На Windows и Маке у программы нет консоли: без файла ошибка (например, окно пульта не открылось)
 * не видна ни оператору, ни автору. Пишется всё, что программа выводит в консоль, и необработанные
 * исключения; при запуске прежний журнал становится log.old.txt.
 */
object DiskLog {
    @Volatile private var file: File? = null

    fun start(dir: File) {
        if (file != null) return
        runCatching {
            dir.mkdirs()
            val f = File(dir, "log.txt")
            if (f.isFile) f.renameTo(File(dir, "log.old.txt").also { it.delete() })
            file = f
            val out = f.outputStream().buffered()
            val sink = object : OutputStream() {
                private var size = 0L
                override fun write(b: Int) = synchronized(this) { if (size++ < MAX_BYTES) out.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) = synchronized(this) {
                    if (size < MAX_BYTES) out.write(b, off, minOf(len.toLong(), MAX_BYTES - size).toInt())
                    size += len
                }
                override fun flush() = synchronized(this) { out.flush() }
            }
            System.setErr(PrintStream(Tee(System.err, sink), true, "UTF-8"))
            System.setOut(PrintStream(Tee(System.out, sink), true, "UTF-8"))
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                System.err.println("Необработанная ошибка в потоке ${t.name}:")
                e.printStackTrace()
                previous?.uncaughtException(t, e)
            }
            println("Deixis ${ReceiverApp.APP_VERSION} · ${System.getProperty("os.name")} ${System.getProperty("os.version")} · Java ${System.getProperty("java.version")}")
        }
    }

    private class Tee(private val a: OutputStream, private val b: OutputStream) : OutputStream() {
        override fun write(x: Int) { runCatching { a.write(x) }; runCatching { b.write(x) } }
        override fun write(x: ByteArray, off: Int, len: Int) { runCatching { a.write(x, off, len) }; runCatching { b.write(x, off, len) } }
        override fun flush() { runCatching { a.flush() }; runCatching { b.flush() } }
    }

    private const val MAX_BYTES = 4L * 1024 * 1024
}
