package app.deixis.desktop

import app.deixis.diag.Check
import app.deixis.diag.CheckResult
import app.deixis.diag.Status
import java.awt.GraphicsEnvironment

/** Проверки компьютера для диагностики: мониторы и зал, проигрыватель, FFmpeg, перевод презентаций, окно пульта. */
object DesktopChecks {
    fun all(monitors: () -> List<app.deixis.core.MonitorOutput>, engines: List<PdfEngine>, pultShowing: () -> Boolean?): List<Check> = listOf(
        Check("monitors", { it.t("Мониторы и зал", "Monitorlar va zal", "Monitors and hall") }) { l ->
            val title = l.t("Мониторы и зал", "Monitorlar va zal", "Monitors and hall")
            if (GraphicsEnvironment.isHeadless()) return@Check CheckResult("monitors", title, Status.WARN, l.t("нет экрана", "ekran yo‘q", "no display"))
            val list = monitors()
            val hall = list.filter { it.enabled }
            val detail = list.joinToString { "${it.name} ${it.width}×${it.height}" + if (it.enabled) " — ${it.layout.title}" else "" }
            if (hall.isEmpty()) CheckResult("monitors", title, Status.WARN, detail,
                l.t("Зал не выведен ни на один монитор: включите монитор в «Устройства» → «Выходы».", "Zal hech bir monitorga chiqarilmagan: «Qurilmalar» → «Chiqishlar»da monitorni yoqing.", "The hall is on no monitor: turn one on in Devices → Outputs."))
            else CheckResult("monitors", title, Status.OK, detail)
        },
        Check("virtual", { it.t("Виртуальный монитор Deixis", "Deixis virtual monitori", "Deixis virtual monitor") }) { l ->
            val title = l.t("Виртуальный монитор Deixis", "Deixis virtual monitori", "Deixis virtual monitor")
            val why = runCatching { VirtualScreen.unsupported }.getOrElse { it.message }
            val err = VirtualScreen.lastError
            when {
                runCatching { VirtualScreen.exists }.getOrDefault(false) -> CheckResult("virtual", title, Status.OK, l.t("есть", "bor", "present"))
                why != null -> CheckResult("virtual", title, Status.WARN, why,
                    l.t("Без него зал на единственном мониторе идёт в окне. Зрители по QR и экраны в сети видят его всё равно.", "Usiz yagona monitordagi zal oynada ko‘rsatiladi. QR orqali tomoshabinlar va tarmoqdagi ekranlar baribir ko‘radi.", "Without it, the hall on a single monitor runs in a window. QR viewers and network screens still see it."))
                err != null -> CheckResult("virtual", title, Status.WARN, err,
                    l.t("«Устройства» → «Входы» → «Создать виртуальный экран» — попробовать снова. Причина — в журнале (строки «virtual»).", "«Qurilmalar» → «Kirishlar» → «Virtual ekran yaratish» — qayta urinish. Sababi jurnalda («virtual» qatorlari).", "Devices → Inputs → Create virtual screen to try again. The reason is in the log (lines “virtual”)."))
                else -> CheckResult("virtual", title, Status.OK, l.t("не создан — создаётся в «Устройства» → «Входы»", "yaratilmagan — «Qurilmalar» → «Kirishlar»da yaratiladi", "not created — create it in Devices → Inputs"))
            }
        },
        Check("vlc", { it.t("Проигрыватель видео (VLC)", "Video pleyer (VLC)", "Video player (VLC)") }) { l ->
            val title = l.t("Проигрыватель видео (VLC)", "Video pleyer (VLC)", "Video player (VLC)")
            if (VideoPlayer.vlcFound) CheckResult("vlc", title, Status.OK, l.t("найден", "topildi", "found"))
            else CheckResult("vlc", title, Status.FAIL, l.t("не найден: видео в зале не играет", "topilmadi: zalda video o‘ynamaydi", "not found: videos do not play in the hall"),
                l.t("Переустановите Deixis; на Linux поставьте пакет vlc.", "Deixisni qayta o‘rnating; Linuxda vlc paketini o‘rnating.", "Reinstall Deixis; on Linux install the vlc package."))
        },
        Check("ffmpeg", { "FFmpeg" }) { l ->
            val v = runCatching { org.bytedeco.ffmpeg.global.avutil.av_version_info().string }
            if (v.isSuccess) CheckResult("ffmpeg", "FFmpeg", Status.OK, v.getOrThrow())
            else CheckResult("ffmpeg", "FFmpeg", Status.FAIL, v.exceptionOrNull()?.message.orEmpty(),
                l.t("Без FFmpeg не работают трансляция экрана и кадры видео в пульте. Переустановите Deixis.", "FFmpegsiz ekran translyatsiyasi va pultdagi video kadrlari ishlamaydi. Deixisni qayta o‘rnating.", "Without FFmpeg, screen broadcast and video frames in the remote do not work. Reinstall Deixis."))
        },
        Check("office", { it.t("Перевод презентаций", "Taqdimotlarni o‘girish", "Presentation conversion") }) { l ->
            val title = l.t("Перевод презентаций", "Taqdimotlarni o‘girish", "Presentation conversion")
            if (engines.isNotEmpty()) CheckResult("office", title, Status.OK, engines.joinToString { it.name })
            else CheckResult("office", title, Status.WARN, l.t("встроенный (приблизительно)", "ichki (taxminan)", "built-in (approximate)"),
                l.t("Точнее переводят PowerPoint или LibreOffice: поставьте один из них.", "PowerPoint yoki LibreOffice aniqroq o‘giradi: birini o‘rnating.", "PowerPoint or LibreOffice converts more exactly: install one of them."))
        },
        Check("pult", { it.t("Окно пульта", "Pult oynasi", "Remote window") }) { l ->
            val title = l.t("Окно пульта", "Pult oynasi", "Remote window")
            when (pultShowing()) {
                true -> CheckResult("pult", title, Status.OK, l.t("открыто", "ochiq", "open"))
                false -> CheckResult("pult", title, Status.OK, l.t("закрыто (открывается из значка Deixis)", "yopiq (Deixis belgisidan ochiladi)", "closed (opens from the Deixis icon)"))
                null -> CheckResult("pult", title, Status.WARN, l.t("ещё не открывалось", "hali ochilmagan", "not opened yet"))
            }
        },
    )
}
