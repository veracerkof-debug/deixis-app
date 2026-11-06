package app.deixis.phone

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
    val folderPick = rememberFolderPicker { uri ->
        uri.let {
            c.backups.takeFolder(it)
            runCatching { c.backupNow() }.onSuccess { ui.toast(L.backupFolderSaved) }.onFailure { e -> ui.toast(e.message ?: L.folderUnavailable) }
            tick++
        }
    }
    val filePick = rememberFilePicker { uris ->
        uris.firstOrNull()?.let { u ->
            runCatching { c.backups.loadFile(u) }
                .onSuccess { d -> ui.push(L.restoreFromFile) { RestoreConfirm(c, ui, d, L.fileLower) } }
                .onFailure { ui.toast(L.notBackupOrBroken) }
        }
    }
    // Перечитать после копии или выбора папки.
    val last = remember(tick) { c.backups.lastAt() }
    val folder = remember(tick) { c.backups.folder }
    GHead(L.backups)
    Group {
        Cell(
            if (last == 0L) L.noBackupsYet else L.lastBackup + Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).format(SHOWN),
            L.backupsAuto(Backups.KEEP),
            "file", chevron = null,
        )
        Sep(T.gutter)
        Cell(
            if (folder == null) L.offPhone else L.offPhoneOn,
            if (folder == null) L.offPhoneSub else L.pickOtherFolder,
            onClick = { folderPick.launch(null) },
        )
        Sep(T.gutter)
        Cell(L.restoreFromBackup, onClick = { ui.push(L.backups) { BackupsList(c, ui) } })
        Sep(T.gutter)
        Cell(L.restoreFromFileDots, L.restoreFromFileSub, onClick = { filePick.launch(arrayOf("application/json", "application/octet-stream", "*/*")) })
    }
    Btn(L.backupNow, Modifier.fillMaxWidth().padding(top = T.gap)) {
        runCatching { c.backupNow() }.onSuccess { ui.toast(L.backupSaved) }.onFailure { e -> ui.toast(e.message ?: L.backupNotSaved) }
        tick++
    }
    Note(L.backupNote)
}

@Composable
private fun BackupsList(c: Controller, ui: Ui) {
    val list = remember { runCatching { c.backups.list() }.getOrDefault(emptyList()) }
    if (list.isEmpty()) { Note(L.noBackups); return }
    Group {
        list.forEachIndexed { i, b ->
            if (i > 0) Sep(T.gutter)
            Cell(
                b.at + if (b.external) L.inFolderSuffix else "",
                Backups.reasonText(b.reason) + " · " + L.itemsSetlists(b.items, b.setlists),
                onClick = {
                    runCatching { c.backups.load(b) }
                        .onSuccess { d -> ui.push(L.restore) { RestoreConfirm(c, ui, d, b.at) } }
                        .onFailure { ui.toast(L.backupUnreadable) }
                },
            )
        }
    }
}

@Composable
private fun RestoreConfirm(c: Controller, ui: Ui, d: StoreData, from: String) {
    var done by remember { mutableStateOf(false) }
    Note(L.restoreNote(from, d.items.size, d.setlists.size))
    Btn(L.restore, Modifier.fillMaxWidth().padding(top = T.gap), kind = BtnKind.TINT, enabled = !done) {
        done = true
        c.restore(d)
        ui.closeAll()
        ui.toast(L.libraryRestored)
    }
}

private val SHOWN: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMMM, HH:mm", I18n.locale)
