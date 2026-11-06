package app.deixis.diag

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Итог проверки: всё в порядке, стоит обратить внимание, не работает. */
@Serializable
enum class Status { OK, WARN, FAIL }

/**
 * Одна проверка: что проверяли ([title]), что нашли ([detail]) и что сделать, если не в порядке ([advice]).
 * Тексты — на языке пульта: проверки получают [Lang] при запуске.
 */
@Serializable
data class CheckResult(val id: String, val title: String, val status: Status, val detail: String = "", val advice: String? = null)

/** Отчёт диагностики устройства: кто, где, что проверено, последние ошибки журнала. */
@Serializable
data class DiagReport(
    val device: String,
    val app: String,
    val system: String,
    val at: Long,
    val checks: List<CheckResult>,
    val problems: List<String> = emptyList(),
) {
    val worst: Status get() = checks.maxOfOrNull { it.status } ?: Status.OK

    /** Текст для автора: в письмо или сообщение. */
    fun toText(): String = buildString {
        appendLine("Deixis — диагностика · $device")
        appendLine("$app · $system · ${REPORT_STAMP.format(Instant.ofEpochMilli(at))}")
        appendLine()
        for (c in checks) {
            appendLine("${MARKS.getValue(c.status)} ${c.title}${if (c.detail.isNotBlank()) ": ${c.detail}" else ""}")
            c.advice?.let { appendLine("   → $it") }
        }
        if (problems.isNotEmpty()) {
            appendLine()
            appendLine("Журнал (ошибки и предупреждения):")
            problems.forEach { appendLine(it) }
        }
    }
}

private val MARKS = mapOf(Status.OK to "✓", Status.WARN to "!", Status.FAIL to "✗")
private val REPORT_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** Язык текстов проверок — тот же выбор, что у пульта: ru, uz, en. */
class Lang(code: String?) {
    private val i = when (code) { "uz" -> 1; "en" -> 2; else -> 0 }
    fun t(ru: String, uz: String, en: String) = arrayOf(ru, uz, en)[i]
}

/** Проверка: получает язык, возвращает итог. Упавшая или зависшая проверка сама становится итогом FAIL. */
class Check(val id: String, val title: (Lang) -> String, val run: suspend (Lang) -> CheckResult)

/**
 * Диагностика: по очереди проходит проверки площадки (общие [Checks] плюс свои: мониторы и VLC на компьютере,
 * Wi-Fi и разрешения на телефоне, проигрыватель на ТВ) и собирает отчёт с последними ошибками журнала.
 */
object Diagnostics {
    suspend fun run(checks: List<Check>, lang: Lang, device: String, app: String, timeoutMs: Long = CHECK_TIMEOUT_MS): DiagReport {
        val results = checks.map { c ->
            val r = runCatching { withTimeoutOrNull(timeoutMs) { c.run(lang) } }
            r.getOrNull() ?: CheckResult(
                c.id, c.title(lang), Status.FAIL,
                r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" } ?: lang.t("нет ответа", "javob yo‘q", "no answer"),
            )
        }
        Log.i("diag", "Диагностика: " + results.joinToString { "${it.id}=${it.status}" })
        return DiagReport(device, app, system(), System.currentTimeMillis(), results, Log.problems().map { it.toString() })
    }

    fun system(): String = listOfNotNull(
        System.getProperty("os.name"), System.getProperty("os.version"),
        System.getProperty("java.vm.name")?.takeIf { !it.contains("Dalvik", true) }?.let { "Java ${System.getProperty("java.version")}" },
    ).joinToString(" ")

    private const val CHECK_TIMEOUT_MS = 8_000L
}

/** Проверки, общие для всех площадок. */
object Checks {
    /** Есть адрес в локальной сети: без него экраны и пульты друг друга не найдут. */
    fun network() = Check("network", { it.t("Сеть", "Tarmoq", "Network") }) { l ->
        val addrs = withContext(Dispatchers.IO) {
            NetworkInterface.getNetworkInterfaces().toList().filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { n -> n.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${n.displayName}: ${it.hostAddress}" } }
        }
        if (addrs.isEmpty()) CheckResult(
            "network", l.t("Сеть", "Tarmoq", "Network"), Status.FAIL, l.t("нет подключения к сети", "tarmoqqa ulanish yo‘q", "no network connection"),
            l.t("Подключите устройство к той же сети Wi-Fi, что и остальные экраны и пульты.", "Qurilmani boshqa ekranlar va pultlar bilan bir xil Wi-Fi tarmog‘iga ulang.", "Connect the device to the same Wi-Fi as the other screens and remotes."),
        ) else CheckResult("network", l.t("Сеть", "Tarmoq", "Network"), Status.OK, addrs.take(3).joinToString(", "))
    }

    /** Место для файлов показа: видео и презентации копятся на экране. */
    fun disk(dir: File) = Check("disk", { it.t("Место на диске", "Diskdagi joy", "Disk space") }) { l ->
        val free = dir.apply { mkdirs() }.usableSpace
        val mb = free / 1_048_576
        val title = l.t("Место на диске", "Diskdagi joy", "Disk space")
        when {
            mb < LOW_MB -> CheckResult("disk", title, Status.FAIL, l.t("свободно $mb МБ", "bo‘sh $mb MB", "$mb MB free"),
                l.t("Освободите место: без него не сохраняются библиотека и файлы показа.", "Joy bo‘shating: busiz kutubxona va ko‘rsatuv fayllari saqlanmaydi.", "Free up space: without it the library and show files are not saved."))
            mb < SHORT_MB -> CheckResult("disk", title, Status.WARN, l.t("свободно $mb МБ", "bo‘sh $mb MB", "$mb MB free"),
                l.t("Большие видео могут не поместиться.", "Katta videolar sig‘masligi mumkin.", "Large videos may not fit."))
            else -> CheckResult("disk", title, Status.OK, l.t("свободно ${mb / 1024} ГБ", "bo‘sh ${mb / 1024} GB", "${mb / 1024} GB free"))
        }
    }

    /** Папка данных записывается: иначе ничего не сохраняется между запусками. */
    fun writable(dir: File) = Check("storage", { it.t("Папка данных", "Ma’lumotlar jildi", "Data folder") }) { l ->
        val title = l.t("Папка данных", "Ma’lumotlar jildi", "Data folder")
        val probe = File(dir.apply { mkdirs() }, ".write-check")
        val ok = runCatching { probe.writeText("ok"); probe.readText() == "ok" }.getOrDefault(false).also { probe.delete() }
        if (ok) CheckResult("storage", title, Status.OK, dir.absolutePath)
        else CheckResult("storage", title, Status.FAIL, l.t("не записывается: ${dir.absolutePath}", "yozilmaydi: ${dir.absolutePath}", "not writable: ${dir.absolutePath}"),
            l.t("Проверьте права на папку или место на диске.", "Jild huquqlarini yoki diskdagi joyni tekshiring.", "Check folder permissions or free space."))
    }

    /** Память программы: на слабых устройствах большие PDF и фото упираются в неё. */
    fun memory() = Check("memory", { it.t("Память", "Xotira", "Memory") }) { l ->
        val rt = Runtime.getRuntime()
        val max = rt.maxMemory() / 1_048_576
        val used = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        val title = l.t("Память", "Xotira", "Memory")
        val detail = l.t("занято $used из $max МБ", "$max MB dan $used band", "$used of $max MB used")
        if (max > 0 && used * 100 / max > TIGHT_PERCENT) CheckResult("memory", title, Status.WARN, detail,
            l.t("Памяти почти нет: закройте лишние программы или перезапустите Deixis.", "Xotira deyarli qolmagan: ortiqcha dasturlarni yoping yoki Deixisni qayta ishga tushiring.", "Memory is nearly full: close other apps or restart Deixis."))
        else CheckResult("memory", title, Status.OK, detail)
    }

    /** Ошибки в журнале с запуска: сколько и последняя. */
    fun journal() = Check("journal", { it.t("Журнал ошибок", "Xatolar jurnali", "Error log") }) { l ->
        val errs = Log.recent().filter { it.level == Log.Level.ERROR }
        val title = l.t("Журнал ошибок", "Xatolar jurnali", "Error log")
        if (errs.isEmpty()) CheckResult("journal", title, Status.OK, l.t("ошибок нет", "xato yo‘q", "no errors"))
        else CheckResult("journal", title, Status.WARN, l.t("ошибок: ${errs.size}; последняя: ", "xatolar: ${errs.size}; oxirgisi: ", "errors: ${errs.size}; last: ") + errs.last().message.lines().first().take(LINE),
            l.t("Если что-то не работает, отправьте отчёт автору.", "Agar biror narsa ishlamasa, hisobotni muallifga yuboring.", "If something does not work, send the report to the author."))
    }

    /** Общие проверки для папки данных [dataDir]. */
    fun common(dataDir: File) = listOf(network(), writable(dataDir), disk(dataDir), memory(), journal())

    private const val LOW_MB = 200L
    private const val SHORT_MB = 2_048L
    private const val TIGHT_PERCENT = 90
    private const val LINE = 140
}
