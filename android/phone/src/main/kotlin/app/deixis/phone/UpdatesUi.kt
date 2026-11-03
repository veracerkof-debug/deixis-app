package app.deixis.phone

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.deixis.core.ChangelogEntry
import app.deixis.core.UpdateMode
import app.deixis.core.UpdateRequest
import app.deixis.core.UpdateState
import app.deixis.receiver.ApkUpdates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Строка состояния обновления для пульта: и для этого телефона, и для экрана в «Устройствах». */
fun updateStatus(s: UpdateState): String = when (s.phase) {
    "checking" -> L.checking
    "uptodate" -> L.uptodate
    "available" -> L.versionAvailable(s.available)
    "downloading" -> L.downloadingPct(s.available, (s.progress * 100).toInt())
    "ready" -> L.versionDownloaded(s.available)
    "installing" -> L.installingV(s.available)
    "error" -> s.error ?: L.checkFailed
    else -> if (s.settings.mode == UpdateMode.AUTO) L.autoChecked else L.manualCheck
}

/** Кнопка действия по состоянию: проверить, скачать и установить, установить. null — действие идёт. */
fun updateAction(s: UpdateState): Pair<String, String>? = when (s.phase) {
    "checking", "downloading", "installing" -> null
    "available" -> L.downloadInstall(s.available) to "install"
    "ready" -> L.installV(s.available) to "install"
    else -> L.checkUpdates to "check"
}

/** Раздел «Обновления» в настройках телефона. */
@Composable
fun UpdatesSection() {
    val context = LocalContext.current
    val app = context.applicationContext as PhoneApp
    val u = app.updater
    val s by u.state.collectAsState()
    GHead(L.updates)
    if (ApkUpdates.fromPlay(context)) {
        Note(L.playVersion)
        return
    }
    Group {
        Cell("Deixis ${s.current}", updateStatus(s), chevron = null)
    }
    Seg(listOf(L.byButton, L.automatically), if (s.settings.mode == UpdateMode.AUTO) 1 else 0, Modifier.fillMaxWidth().padding(top = T.gap, bottom = T.gap)) { i ->
        u.setSettings(s.settings.copy(mode = if (i == 1) UpdateMode.AUTO else UpdateMode.MANUAL))
    }
    if (s.notes.isNotEmpty() && s.phase in setOf("available", "downloading", "ready")) {
        Group { s.notes.take(MAX_NOTES).forEachIndexed { i, line -> if (i > 0) Sep(T.gutter); Cell(line, chevron = null) } }
    }
    updateAction(s)?.let { (label, action) ->
        Btn(label, Modifier.fillMaxWidth().padding(top = T.gap), kind = if (action == "install") BtnKind.TINT else BtnKind.PLAIN) {
            if (action == "install") u.install() else u.check()
        }
    }
    if (!ApkUpdates.canInstall(context)) {
        Note(L.installPermission)
    }
    Note(
        if (s.settings.mode == UpdateMode.AUTO) L.autoNote
        else L.manualNoteUpd,
    )
}

/** Окно «Что нового» один раз после обновления. */
@Composable
fun WhatsNewSheet(entries: List<ChangelogEntry>, ui: Ui) {
    entries.forEach { e ->
        GHead(L.versionN(e.version))
        Group { e.lines.forEachIndexed { i, line -> if (i > 0) Sep(T.gutter); Cell(line, chevron = null) } }
    }
    Btn(L.gotIt, Modifier.fillMaxWidth().padding(top = T.gutter), kind = BtnKind.TINT) { ui.close() }
}

/**
 * При запуске: «Что нового» после обновления; в режиме «Автоматически» — установка скачанной версии,
 * когда в зале ничего не показывается (окно установки — на телефоне, не в зале).
 */
@Composable
fun UpdatesEffects(c: Controller, ui: Ui) {
    val context = LocalContext.current
    val app = context.applicationContext as PhoneApp
    LaunchedEffect(Unit) {
        val news = app.updater.takeWhatsNew()
        if (news.isNotEmpty()) ui.sheet(L.whatsNew) { WhatsNewSheet(news, ui) }
    }
    val s by app.updater.state.collectAsState()
    val live by c.live.collectAsState()
    LaunchedEffect(s.phase, live) {
        // Один раз на версию: если оператор отменил окно установки, не спрашивать снова до кнопки.
        if (s.phase == "ready" && s.settings.mode == UpdateMode.AUTO && live == null && app.autoInstallTried != s.available) {
            app.autoInstallTried = s.available
            app.updater.install()
        }
    }
}

private const val MAX_NOTES = 8

/** Обновление экрана в сети с пульта: у ТВ и компьютера в зале своих настроек на экране нет. */
@Composable
fun ScreenUpdateSheet(c: Controller, t: Target) {
    var s by remember { mutableStateOf<UpdateState?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(t.id) {
        while (true) {
            s = c.screenUpdate(t)
            loaded = true
            delay(if (s?.phase in setOf("checking", "downloading", "installing")) 1_000L else 4_000L)
        }
    }
    fun send(req: UpdateRequest) { scope.launch { c.screenUpdate(t, req)?.let { s = it } } }
    var airPlay by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(t.id) { airPlay = c.screenAirPlay(t) }
    airPlay?.let { on ->
        GHead("AirPlay")
        Group { SwitchRow(L.airPlayReceive, on) { v -> airPlay = v; scope.launch { airPlay = c.screenAirPlay(t, v) ?: on } } }
        Note(L.airPlayNote)
    }
    val st = s
    if (st == null) {
        Note(if (loaded) L.screenNoUpdate else L.linkToScreen)
        return
    }
    GHead(L.screenUpdates)
    Group { Cell("Deixis ${st.current}", updateStatus(st), chevron = null) }
    Seg(listOf(L.byButton, L.automatically), if (st.settings.mode == UpdateMode.AUTO) 1 else 0, Modifier.fillMaxWidth().padding(top = T.gap, bottom = T.gap)) { i ->
        send(UpdateRequest("settings", mode = if (i == 1) UpdateMode.AUTO else UpdateMode.MANUAL))
    }
    if (st.notes.isNotEmpty() && st.phase in setOf("available", "downloading", "ready")) {
        Group { st.notes.take(MAX_NOTES).forEachIndexed { i, line -> if (i > 0) Sep(T.gutter); Cell(line, chevron = null) } }
    }
    updateAction(st)?.let { (label, action) ->
        Btn(label, Modifier.fillMaxWidth().padding(top = T.gap), kind = if (action == "install") BtnKind.TINT else BtnKind.PLAIN) { send(UpdateRequest(action)) }
    }
    Note(L.screenUpdateNote)
}
