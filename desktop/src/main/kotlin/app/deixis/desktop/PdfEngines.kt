package app.deixis.desktop

import app.deixis.core.LibreOfficeConverter
import app.deixis.core.MediaKind
import app.deixis.core.StoreException
import app.deixis.core.mediaKindOf
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Точный перевод в PDF установленной на компьютере программой. Код этих программ в Deixis не входит:
 * Deixis только запускает их так же, как это сделал бы человек, и забирает готовый PDF.
 */
interface PdfEngine {
    val name: String

    /** Берёт ли программа такой файл. */
    fun accepts(input: File): Boolean

    /** PDF в [workDir] или исключение с понятным текстом. */
    fun convert(input: File, workDir: File): File
}

private val SPREADSHEETS = setOf("xlsx", "xls", "ods")

class LibreOfficeEngine(private val lo: LibreOfficeConverter) : PdfEngine {
    override val name = "LibreOffice"
    override fun accepts(input: File) = true
    override fun convert(input: File, workDir: File) = lo.convert(input, workDir)
}

/**
 * Microsoft Office на Windows через его собственную автоматизацию (PowerShell и COM): PowerPoint для презентаций,
 * Word для документов, Excel для таблиц. Это самый точный перевод файлов PowerPoint — их рисует сам PowerPoint.
 * Если Office уже открыт у человека, Deixis не закрывает его.
 */
class MsOfficeEngine(private val apps: Set<String>, private val timeoutSec: Long = 180) : PdfEngine {
    override val name = "Microsoft Office"

    private fun appFor(input: File): String? = when {
        mediaKindOf(input.name) == MediaKind.PRESENTATION && input.extension.lowercase() !in setOf("odp", "key") -> "powerpoint"
        mediaKindOf(input.name) == MediaKind.DOCUMENT && input.extension.lowercase() in SPREADSHEETS -> "excel"
        mediaKindOf(input.name) == MediaKind.DOCUMENT && input.extension.lowercase() != "odt" -> "word"
        else -> null
    }?.takeIf { it in apps }

    override fun accepts(input: File) = appFor(input) != null

    override fun convert(input: File, workDir: File): File {
        val app = appFor(input) ?: throw StoreException("convert", "Microsoft Office не открывает «${input.name}»")
        workDir.mkdirs()
        val script = File(workDir, "office.ps1").apply { writeText(SCRIPT) }
        val out = File(workDir, "office.pdf").apply { delete() }
        val pb = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.path)
            .redirectErrorStream(true).redirectOutput(File(workDir, "office-log.txt"))
        // Пути передаются через окружение: так в них не важны пробелы, кавычки и кириллица.
        pb.environment().putAll(mapOf("DEIXIS_APP" to app, "DEIXIS_IN" to input.absolutePath, "DEIXIS_OUT" to out.absolutePath))
        val p = pb.start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw StoreException("convert", "Microsoft Office переводит файл слишком долго")
        }
        if (p.exitValue() != 0 || !out.isFile || out.length() == 0L) throw StoreException("convert", "Microsoft Office не смог открыть «${input.name}»")
        return out
    }

    companion object {
        // PowerPoint: Open(файл, только чтение, без имени, без окна), SaveAs 32 — PDF; скрытые слайды PowerPoint в PDF не кладёт.
        // Word: ExportAsFixedFormat 17 — PDF. Excel: ExportAsFixedFormat 0 — PDF.
        private val SCRIPT = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}in = ${'$'}env:DEIXIS_IN
            ${'$'}out = ${'$'}env:DEIXIS_OUT
            switch (${'$'}env:DEIXIS_APP) {
              'powerpoint' {
                ${'$'}app = New-Object -ComObject PowerPoint.Application
                ${'$'}before = ${'$'}app.Presentations.Count
                ${'$'}p = ${'$'}app.Presentations.Open(${'$'}in, -1, 0, 0)
                try { ${'$'}p.SaveAs(${'$'}out, 32) } finally { ${'$'}p.Close() }
                if (${'$'}before -eq 0) { ${'$'}app.Quit() }
              }
              'word' {
                ${'$'}app = New-Object -ComObject Word.Application
                ${'$'}app.Visible = ${'$'}false
                ${'$'}app.DisplayAlerts = 0
                try {
                  ${'$'}d = ${'$'}app.Documents.Open(${'$'}in, ${'$'}false, ${'$'}true, ${'$'}false)
                  try { ${'$'}d.ExportAsFixedFormat(${'$'}out, 17) } finally { ${'$'}d.Close(0) }
                } finally { ${'$'}app.Quit() }
              }
              'excel' {
                ${'$'}app = New-Object -ComObject Excel.Application
                ${'$'}app.Visible = ${'$'}false
                ${'$'}app.DisplayAlerts = ${'$'}false
                try {
                  ${'$'}w = ${'$'}app.Workbooks.Open(${'$'}in, 0, ${'$'}true)
                  try { ${'$'}w.ExportAsFixedFormat(0, ${'$'}out) } finally { ${'$'}w.Close(${'$'}false) }
                } finally { ${'$'}app.Quit() }
              }
            }
        """.trimIndent()

        /** Установленные программы Office: по их записи COM в реестре Windows. */
        fun find(): MsOfficeEngine? {
            if (!System.getProperty("os.name").lowercase().contains("win")) return null
            val apps = mapOf("powerpoint" to "PowerPoint.Application", "word" to "Word.Application", "excel" to "Excel.Application")
                .filterValues { registered(it) }.keys
            return apps.takeIf { it.isNotEmpty() }?.let { MsOfficeEngine(it) }
        }

        private fun registered(progId: String): Boolean = runCatching {
            val p = ProcessBuilder("reg", "query", "HKEY_CLASSES_ROOT\\$progId\\CLSID").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }
}

/**
 * Keynote на Mac (бесплатный, есть почти на каждом Mac) через AppleScript: открывает PPTX, PPT и KEY.
 * В первый раз macOS спросит, можно ли Deixis управлять Keynote.
 */
class KeynoteEngine(private val timeoutSec: Long = 180) : PdfEngine {
    override val name = "Keynote"

    override fun accepts(input: File) =
        mediaKindOf(input.name) == MediaKind.PRESENTATION && input.extension.lowercase() in setOf("pptx", "ppt", "pps", "ppsx", "pptm", "key")

    override fun convert(input: File, workDir: File): File {
        workDir.mkdirs()
        val script = File(workDir, "keynote.applescript").apply { writeText(SCRIPT) }
        val out = File(workDir, "keynote.pdf").apply { delete() }
        val p = ProcessBuilder("osascript", script.path, input.absolutePath, out.absolutePath)
            .redirectErrorStream(true).redirectOutput(File(workDir, "keynote-log.txt")).start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw StoreException("convert", "Keynote переводит файл слишком долго")
        }
        if (p.exitValue() != 0 || !out.isFile || out.length() == 0L) throw StoreException("convert", "Keynote не смог открыть «${input.name}»")
        return out
    }

    companion object {
        private val SCRIPT = """
            on run argv
              set src to POSIX file (item 1 of argv)
              set dst to POSIX file (item 2 of argv)
              set wasRunning to application "Keynote" is running
              tell application "Keynote"
                set d to open src
                try
                  export d to dst as PDF with properties {PDF image quality:Best, skipped slides:false}
                on error e
                  close d saving no
                  error e
                end try
                close d saving no
                if not wasRunning then quit
              end tell
            end run
        """.trimIndent()

        fun find(): KeynoteEngine? {
            if (!System.getProperty("os.name").lowercase().contains("mac")) return null
            return KeynoteEngine().takeIf { File("/Applications/Keynote.app").isDirectory }
        }
    }
}

/**
 * Точные переводчики этого компьютера по порядку: родная программа презентации, если она есть,
 * потом LibreOffice, потом Keynote. Встроенный перевод остаётся последним запасным путём.
 */
fun findPdfEngines(): List<PdfEngine> = listOfNotNull(
    MsOfficeEngine.find(),
    LibreOfficeConverter.find()?.let { LibreOfficeEngine(it) },
    KeynoteEngine.find(),
)
