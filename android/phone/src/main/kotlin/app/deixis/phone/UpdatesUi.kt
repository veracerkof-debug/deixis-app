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
import app.deixis.core.UpdateChannel
import app.deixis.core.UpdateMode
import app.deixis.core.UpdateRequest
import app.deixis.core.UpdateState
import app.deixis.receiver.ApkUpdates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Строка состояния обновления для пульта: и для этого телефона, и для экрана в «Устройствах». */
fun updateStatus(s: UpdateState): String = when (s.phase) {
    "checking" -> "Проверка…"
    "uptodate" -> "Установлена последняя версия"
    "available" -> "Есть версия ${s.available}"
    "downloading" -> "Загрузка ${s.available}: ${(s.progress * 100).toInt()} %"
    "ready" -> "Версия ${s.available} скачана"
    "installing" -> "Установка ${s.available}…"
    "error" -> s.error ?: "Не удалось проверить"
    else -> if (s.settings.mode == UpdateMode.AUTO) "Проверяется автоматически" else "Проверка по кнопке"
}

/** Кнопка действия по состоянию: проверить, скачать и установить, установить. null — действие идёт. */
fun updateAction(s: UpdateState): Pair<String, String>? = when (s.phase) {
    "checking", "downloading", "installing" -> null
    "available" -> "Скачать и установить ${s.available}" to "install"
    "ready" -> "Установить ${s.available}" to "install"
    else -> "Проверить обновления" to "check"
}

/** Раздел «Обновления» в настройках телефона. */
@Composable
fun UpdatesSection() {
    val context = LocalContext.current
    val app = context.applicationContext as PhoneApp
    val u = app.updater
    val s by u.state.collectAsState()
    GHead("Обновления")
    if (ApkUpdates.fromPlay(context)) {
        Note("Эта версия из Google Play: обновления приходят через магазин.")
        return
    }
    Group {
        Cell("Deixis ${s.current}", updateStatus(s), chevron = null)
    }
    Seg(listOf("По кнопке", "Автоматически"), if (s.settings.mode == UpdateMode.AUTO) 1 else 0, Modifier.fillMaxWidth().padding(top = T.gap)) { i ->
        u.setSettings(s.settings.copy(mode = if (i == 1) UpdateMode.AUTO else UpdateMode.MANUAL))
    }
    Seg(listOf("Стабильные", "Пробные"), if (s.settings.channel == UpdateChannel.TEST) 1 else 0, Modifier.fillMaxWidth().padding(top = T.gap, bottom = T.gap)) { i ->
        u.setSettings(s.settings.copy(channel = if (i == 1) UpdateChannel.TEST else UpdateChannel.STABLE))
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
        Note("Перед первой установкой Android попросит разрешить Deixis устанавливать приложения.")
    }
    Note(
        if (s.settings.mode == UpdateMode.AUTO) "Автоматически: проверка на GitHub при запуске и раз в сутки, установка — когда ничего не показывается."
        else "По кнопке: Deixis выходит в сеть только когда нажата «Проверить обновления». Пробные — сборки для тестеров, раньше стабильных.",
    )
}

/** Окно «Что нового» один раз после обновления. */
@Composable
fun WhatsNewSheet(entries: List<ChangelogEntry>, ui: Ui) {
    entries.forEach { e ->
        GHead("Версия ${e.version}")
        Group { e.lines.forEachIndexed { i, line -> if (i > 0) Sep(T.gutter); Cell(line, chevron = null) } }
    }
    Btn("Понятно", Modifier.fillMaxWidth().padding(top = T.gutter), kind = BtnKind.TINT) { ui.close() }
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
        if (news.isNotEmpty()) ui.sheet("Что нового") { WhatsNewSheet(news, ui) }
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
    val st = s
    if (st == null) {
        Note(if (loaded) "Этот экран не обновляется с пульта: старая версия Deixis или версия из Google Play. Новую версию можно поставить на нём вручную." else "Связь с экраном…")
        return
    }
    GHead("Обновления экрана")
    Group { Cell("Deixis ${st.current}", updateStatus(st), chevron = null) }
    Seg(listOf("По кнопке", "Автоматически"), if (st.settings.mode == UpdateMode.AUTO) 1 else 0, Modifier.fillMaxWidth().padding(top = T.gap)) { i ->
        send(UpdateRequest("settings", mode = if (i == 1) UpdateMode.AUTO else UpdateMode.MANUAL))
    }
    Seg(listOf("Стабильные", "Пробные"), if (st.settings.channel == UpdateChannel.TEST) 1 else 0, Modifier.fillMaxWidth().padding(top = T.gap, bottom = T.gap)) { i ->
        send(UpdateRequest("settings", channel = if (i == 1) UpdateChannel.TEST else UpdateChannel.STABLE))
    }
    if (st.notes.isNotEmpty() && st.phase in setOf("available", "downloading", "ready")) {
        Group { st.notes.take(MAX_NOTES).forEachIndexed { i, line -> if (i > 0) Sep(T.gutter); Cell(line, chevron = null) } }
    }
    updateAction(st)?.let { (label, action) ->
        Btn(label, Modifier.fillMaxWidth().padding(top = T.gap), kind = if (action == "install") BtnKind.TINT else BtnKind.PLAIN) { send(UpdateRequest(action)) }
    }
    Note("Установка идёт, только когда на экране ничего не показывается: окно системы появится на экране зала. На телевизоре его подтверждают пультом телевизора. После обновления экран откроется сам.")
}
