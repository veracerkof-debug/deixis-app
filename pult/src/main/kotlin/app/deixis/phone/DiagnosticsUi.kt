package app.deixis.phone

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.deixis.diag.DiagReport
import app.deixis.diag.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Диагностика»: проверки этого устройства и каждого подключённого экрана — что не так и что сделать.
 * Отчёт с журналами (этого устройства и экранов) копируется или уходит файлом — в сообщение или письмо автору.
 */
@Composable
fun DiagnosticsSheet(c: Controller, ui: Ui) {
    val host = LocalHost.current
    val scope = rememberCoroutineScope()
    var run by remember { mutableIntStateOf(0) }
    var reports by remember { mutableStateOf<List<Pair<String, DiagReport?>>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(run) {
        busy = true
        reports = c.diagnoseAll()
        busy = false
    }
    Note(L.diagNote, Modifier.padding(bottom = T.gap))
    if (busy && reports.isEmpty()) Note(L.diagRunning)
    for ((name, r) in reports) {
        GHead(name)
        if (r == null) {
            Group { Cell(L.diagNoAnswer, chevron = null) }
            continue
        }
        Group {
            r.checks.forEachIndexed { i, ch ->
                if (i > 0) Sep(T.gutter)
                Cell(
                    ch.title,
                    listOfNotNull(ch.detail.takeIf { it.isNotBlank() }, ch.advice).joinToString("\n").ifEmpty { null },
                    kind = when (ch.status) { Status.OK -> "check"; Status.WARN -> "info"; Status.FAIL -> "close" },
                    chevron = null,
                )
            }
        }
    }
    Spacer(Modifier.height(T.gap))
    Btn(L.diagAgain, Modifier.fillMaxWidth(), icon = "refresh", enabled = !busy) { run++ }
    Spacer(Modifier.height(T.gapS))
    Btn(L.diagCopy, Modifier.fillMaxWidth(), icon = "copy", enabled = reports.isNotEmpty()) {
        scope.launch {
            val text = withContext(Dispatchers.IO) { c.diagnosticsText(reports) }
            host.platform.copyText(text)
            ui.toast(L.copied)
        }
    }
    Spacer(Modifier.height(T.gapS))
    Btn(L.diagSendFile, Modifier.fillMaxWidth(), icon = "send", kind = BtnKind.TINT, enabled = reports.isNotEmpty()) {
        scope.launch {
            val text = withContext(Dispatchers.IO) { c.diagnosticsText(reports) }
            runCatching { host.platform.shareFile(L.diagSubject, "deixis-diagnostics.txt", text) }.onFailure { ui.toast(it.message ?: L.failedShort) }
        }
    }
    Note(L.diagPrivacy)
}
