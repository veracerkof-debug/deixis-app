package app.deixis.core

import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.TimeUnit

/** Перевод презентации или документа в PDF: дальше он показывается постранично, как любой PDF. */
fun interface DocConverter {
    /** Возвращает PDF в [workDir] или бросает исключение с понятным текстом. */
    fun toPdf(input: File, workDir: File): PdfResult
}

/** Готовый PDF и, если перевод неточен, предупреждение для пульта. */
data class PdfResult(val pdf: File, val warning: String? = null)

/** Результат перевода для пульта: PDF уже лежит на приёмнике. */
@Serializable
data class Converted(val file: FileRef, val pages: Int, val warning: String? = null)

/**
 * LibreOffice без окна. Его код в Deixis не входит: запускается установленная программа (лицензия MPL),
 * со своим отдельным профилем, чтобы не мешать открытому LibreOffice пользователя.
 */
class LibreOfficeConverter(private val soffice: File, private val timeoutSec: Long = 180) : DocConverter {
    override fun toPdf(input: File, workDir: File): PdfResult = PdfResult(convert(input, workDir))

    fun convert(input: File, workDir: File): File {
        workDir.mkdirs()
        val profile = File(workDir, "profile").toURI().toString()
        val out = File(workDir, "out").apply { mkdirs() }
        val p = ProcessBuilder(
            soffice.path, "--headless", "--norestore", "--nolockcheck", "-env:UserInstallation=$profile",
            // Без пережатия картинок, без страниц заметок и скрытых слайдов: в PDF ровно то, что идёт на показе.
            "--convert-to", "pdf:${filterFor(input)}:" + PDF_OPTIONS, "--outdir", out.path, input.path,
        ).redirectErrorStream(true).redirectOutput(File(workDir, "log.txt")).start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw StoreException("convert", "Презентация переводится слишком долго")
        }
        val pdf = File(out, input.nameWithoutExtension + ".pdf")
        if (p.exitValue() != 0 || !pdf.isFile || pdf.length() == 0L) throw StoreException("convert", "LibreOffice не смог открыть «${input.name}»")
        return pdf
    }

    companion object {
        private const val PDF_OPTIONS = """{"ReduceImageResolution":{"type":"boolean","value":"false"},""" +
            """"Quality":{"type":"long","value":"95"},"ExportNotesPages":{"type":"boolean","value":"false"},""" +
            """"ExportHiddenSlides":{"type":"boolean","value":"false"},"UseTaggedPDF":{"type":"boolean","value":"false"}}"""

        private fun filterFor(f: File) = when (mediaKindOf(f.name)) {
            MediaKind.PRESENTATION -> "impress_pdf_Export"
            else -> when (f.extension.lowercase()) {
                "xlsx", "xls", "ods" -> "calc_pdf_Export"
                else -> "writer_pdf_Export"
            }
        }

        /** Ищет LibreOffice на этом компьютере: в PATH и в обычных местах установки Windows и macOS. */
        fun find(): LibreOfficeConverter? {
            // Для проверки встроенного перевода: LibreOffice не ищется.
            if (System.getenv("DEIXIS_NO_LIBREOFFICE") == "1") return null
            val os = System.getProperty("os.name").lowercase()
            val candidates = buildList {
                System.getenv("DEIXIS_SOFFICE")?.let { add(File(it)) }
                if (os.contains("win")) {
                    listOf(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")).filterNotNull()
                        .forEach { add(File(it, "LibreOffice\\program\\soffice.exe")) }
                } else if (os.contains("mac")) {
                    add(File("/Applications/LibreOffice.app/Contents/MacOS/soffice"))
                }
                System.getenv("PATH")?.split(File.pathSeparatorChar)?.forEach { dir ->
                    add(File(dir, "soffice")); add(File(dir, "libreoffice")); add(File(dir, "soffice.exe"))
                }
            }
            return candidates.firstOrNull { it.isFile && it.canExecute() }?.let { LibreOfficeConverter(it) }
        }
    }
}

/** Переводы презентаций на приёмнике: один и тот же файл не переводится дважды. */
class Conversions(private val core: ReceiverCore, private val converter: DocConverter, private val work: File) {
    private val done = mutableMapOf<String, Converted>()

    @Synchronized
    fun convert(ref: FileRef): Converted {
        done[ref.sha256]?.let { c -> if (core.files.file(c.file.sha256) != null) return c }
        val src = core.files.file(ref.sha256) ?: throw StoreException("convert", "Файл «${ref.name}» не получен")
        val ext = ref.name.substringAfterLast('.', "pptx").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "pptx" }
        val dir = File(work, ref.sha256.take(16)).apply { deleteRecursively(); mkdirs() }
        try {
            // LibreOffice узнаёт формат по расширению, а в хранилище файл лежит под хэшем.
            val input = File(dir, "presentation.$ext")
            src.copyTo(input, overwrite = true)
            val result = converter.toPdf(input, dir)
            val pdf = result.pdf
            val sha = sha256Of(pdf)
            if (!core.files.status(sha).complete) pdf.inputStream().use { core.files.append(sha, pdf.length(), 0, it) }
            val name = ref.name.substringBeforeLast('.') + ".pdf"
            val pages = core.pageCounter?.invoke(pdf) ?: 0
            return Converted(FileRef(sha, name, pdf.length(), "application/pdf"), pages, result.warning).also { done[ref.sha256] = it }
        } finally {
            dir.deleteRecursively()
        }
    }
}
