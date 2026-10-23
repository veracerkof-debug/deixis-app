package app.deixis.core

import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.TimeUnit

/** Перевод презентации (PPTX, PPT, ODP, KEY) в PDF: дальше она показывается постранично, как любой PDF. */
fun interface DocConverter {
    /** Возвращает PDF в [workDir] или бросает исключение с понятным текстом. */
    fun toPdf(input: File, workDir: File): File
}

/** Результат перевода для пульта: PDF уже лежит на приёмнике. */
@Serializable
data class Converted(val file: FileRef, val pages: Int)

/**
 * LibreOffice без окна. Его код в Deixis не входит: запускается установленная программа (лицензия MPL),
 * со своим отдельным профилем, чтобы не мешать открытому LibreOffice пользователя.
 */
class LibreOfficeConverter(private val soffice: File, private val timeoutSec: Long = 180) : DocConverter {
    override fun toPdf(input: File, workDir: File): File {
        workDir.mkdirs()
        val profile = File(workDir, "profile").toURI().toString()
        val out = File(workDir, "out").apply { mkdirs() }
        val p = ProcessBuilder(
            soffice.path, "--headless", "--norestore", "--nolockcheck", "-env:UserInstallation=$profile",
            "--convert-to", "pdf", "--outdir", out.path, input.path,
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
        /** Ищет LibreOffice на этом компьютере: в PATH и в обычных местах установки Windows и macOS. */
        fun find(): LibreOfficeConverter? {
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
            val pdf = converter.toPdf(input, dir)
            val sha = sha256Of(pdf)
            if (!core.files.status(sha).complete) pdf.inputStream().use { core.files.append(sha, pdf.length(), 0, it) }
            val name = ref.name.substringBeforeLast('.') + ".pdf"
            val pages = core.pageCounter?.invoke(pdf) ?: 0
            return Converted(FileRef(sha, name, pdf.length(), "application/pdf"), pages).also { done[ref.sha256] = it }
        } finally {
            dir.deleteRecursively()
        }
    }
}
