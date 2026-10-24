package app.deixis.phone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Раздел «Резервные копии» в настройках: копия сейчас, папка вне телефона, восстановление. */
@Composable
fun BackupsSection(c: Controller, ui: Ui) {
    var tick by remember { mutableIntStateOf(0) }
    val folderPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            c.backups.takeFolder(it)
            runCatching { c.backupNow() }.onSuccess { ui.toast("Копия сохранена и в выбранную папку") }.onFailure { e -> ui.toast(e.message ?: "Папка недоступна") }
            tick++
        }
    }
    val filePick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u ->
            runCatching { c.backups.loadFile(u) }
                .onSuccess { d -> ui.push("Восстановить из файла") { RestoreConfirm(c, ui, d, "файл") } }
                .onFailure { ui.toast("Это не копия Deixis или файл испорчен") }
        }
    }
    // Перечитать после копии или выбора папки.
    val last = remember(tick) { c.backups.lastAt() }
    val folder = remember(tick) { c.backups.folder }
    GHead("Резервные копии")
    Group {
        Cell(
            if (last == 0L) "Копий ещё нет" else "Последняя: " + Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).format(SHOWN),
            "Сами раз в сутки и перед каждым обновлением, хранятся последние ${Backups.KEEP}",
            "file", chevron = null,
        )
        Sep(T.gutter)
        Cell(
            if (folder == null) "Копия вне телефона" else "Копия вне телефона: включена",
            if (folder == null) "Папка на флешке, в Google Диске или «Документах»: переживёт потерю телефона" else "Нажмите, чтобы выбрать другую папку",
            onClick = { folderPick.launch(null) },
        )
        Sep(T.gutter)
        Cell("Восстановить из копии", onClick = { ui.push("Резервные копии") { BackupsList(c, ui) } })
        Sep(T.gutter)
        Cell("Восстановить из файла…", "С другого телефона или из папки", onClick = { filePick.launch(arrayOf("application/json", "application/octet-stream", "*/*")) })
    }
    Btn("Сделать копию сейчас", Modifier.fillMaxWidth().padding(top = T.gap)) {
        runCatching { c.backupNow() }.onSuccess { ui.toast("Копия сохранена") }.onFailure { e -> ui.toast(e.message ?: "Копия не сохранилась") }
        tick++
    }
    Note("В копии — библиотека, сетлисты, тексты песен, темы и настройки. Видео и фото не копируются: после восстановления на новом телефоне их нужно выбрать заново.")
}

@Composable
private fun BackupsList(c: Controller, ui: Ui) {
    val list = remember { runCatching { c.backups.list() }.getOrDefault(emptyList()) }
    if (list.isEmpty()) { Note("Копий пока нет."); return }
    Group {
        list.forEachIndexed { i, b ->
            if (i > 0) Sep(T.gutter)
            Cell(
                b.at + if (b.external) " · в папке" else "",
                "${Backups.reasonText(b.reason)} · материалов ${b.items}, сетлистов ${b.setlists}",
                onClick = {
                    runCatching { c.backups.load(b) }
                        .onSuccess { d -> ui.push("Восстановить") { RestoreConfirm(c, ui, d, b.at) } }
                        .onFailure { ui.toast("Копия не читается") }
                },
            )
        }
    }
}

@Composable
private fun RestoreConfirm(c: Controller, ui: Ui, d: StoreData, from: String) {
    var done by remember { mutableStateOf(false) }
    Note("Библиотека заменится копией «$from»: материалов ${d.items.size}, сетлистов ${d.setlists.size}. Нынешняя библиотека сначала сохранится копией — её можно будет вернуть.")
    Btn("Восстановить", Modifier.fillMaxWidth().padding(top = T.gap), kind = BtnKind.TINT, enabled = !done) {
        done = true
        c.restore(d)
        ui.closeAll()
        ui.toast("Библиотека восстановлена")
    }
}

private val SHOWN = DateTimeFormatter.ofPattern("d MMMM, HH:mm", java.util.Locale.forLanguageTag("ru"))
