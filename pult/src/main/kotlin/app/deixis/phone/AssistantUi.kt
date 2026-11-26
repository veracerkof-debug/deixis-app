package app.deixis.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.deixis.core.SceneBackground
import app.deixis.core.ai.AnthropicProvider
import app.deixis.core.ai.Assistant
import app.deixis.core.ai.AssistantPrompt
import app.deixis.core.ai.OpenAiProvider
import app.deixis.core.ai.Proposal
import app.deixis.core.ai.ProposalEngine
import app.deixis.core.ai.ProviderConfig
import app.deixis.core.ai.SceneEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Настройки помощника на этом устройстве. Ключ лежит в хранилище пульта этого устройства и никуда, кроме выбранного провайдера, не уходит. */
class AssistantSettings(private val prefs: Prefs, private val platform: Platform) {
    var url by mutableStateOf(prefs.getString("url", "") ?: "")
    var model by mutableStateOf(prefs.getString("model", "") ?: "")
    var key by mutableStateOf(readKey())
    /** Протокол Anthropic вместо OpenAI-совместимого. */
    var anthropic by mutableStateOf(prefs.getBoolean("anthropic", false))
    var auto by mutableStateOf(prefs.getBoolean("auto", false))
    var readOnly by mutableStateOf(prefs.getBoolean("readOnly", false))
    /** Оператор видел предупреждение, что тексты уйдут провайдеру. */
    var informed by mutableStateOf(prefs.getBoolean("informed", false))

    /** Ключ хранится под защитой системы (префикс p:), а если устройство так не умеет — как есть (t:) и это видно в [keyProtected]. */
    private fun readKey(): String {
        val v = prefs.getString("key", "") ?: ""
        return when {
            v.startsWith("p:") -> platform.unprotect(v.drop(2)).orEmpty()
            v.startsWith("t:") -> v.drop(2)
            else -> v // запись старого вида: при первом сохранении перейдёт на новый
        }
    }
    val keyProtected: Boolean get() = prefs.getString("key", "")?.startsWith("p:") == true

    fun save() = prefs.edit {
        val k = key.trim()
        val sealed = if (k.isEmpty()) "" else platform.protect(k)?.let { "p:$it" } ?: "t:$k"
        putString("url", url.trim()); putString("model", model.trim()); putString("key", sealed)
        putBoolean("auto", auto); putBoolean("anthropic", anthropic); putBoolean("readOnly", readOnly); putBoolean("informed", informed)
    }
    val ready get() = (url.isNotBlank() || anthropic) && model.isNotBlank()
}

private class Line(val mine: Boolean, val text: String)

/**
 * Помощник для живого фона: чат, предложения «было → стало» и журнал. Правит сцену фона этой темы теми же операциями, что и редактор слоёв.
 * Ничего не меняется без «Принять» (кроме включённого вручную автоподтверждения; удаление и замена всей сцены подтверждаются всегда).
 */
@Composable
fun AssistantSheet(c: Controller, ui: Ui, start: SceneBackground, onChange: (SceneBackground) -> Unit) {
    val settings = remember { AssistantSettings(c.platform.prefs("assistant"), c.platform) }
    val scope = rememberCoroutineScope()
    val scene = remember { SceneEditor.sceneOf(start) }
    if (scene == null) { Note(L.assistantNoScene); return }
    val engine = remember { ProposalEngine(scene) }
    var bg by remember { mutableStateOf(start) }
    val chat = remember { mutableStateListOf<Line>() }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(!settings.ready) }

    fun publish() {
        val next = SceneEditor.withScene(bg, engine.scene)
        bg = next; onChange(next); tick++
    }

    // Читаем состояние движка при каждой перерисовке: tick меняется после любой правки.
    @Suppress("UNUSED_VARIABLE") val t = tick
    engine.autoConfirm = settings.auto
    engine.readOnly = settings.readOnly

    Note(L.assistantPrivacy, Modifier.padding(bottom = T.gap))
    Cell(L.assistantConnection, if (settings.ready) settings.model else L.assistantNotSet, open = settingsOpen, chevron = if (settingsOpen) "up" else "chev", onClick = { settingsOpen = !settingsOpen })
    if (settingsOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(T.gapS), modifier = Modifier.padding(vertical = T.gapS)) {
            Field(settings.url, L.assistantUrlHint, keyboard = KeyboardType.Uri) { settings.url = it }
            Field(settings.model, L.assistantModelHint) { settings.model = it }
            Field(settings.key, L.assistantKeyHint, keyboard = KeyboardType.Password) { settings.key = it }
            if (settings.key.isNotEmpty()) Note(if (settings.keyProtected) L.assistantKeySafe else L.assistantKeyPlain)
        }
        Group {
            SwitchRow(L.assistantAnthropic, settings.anthropic, L.assistantAnthropicSub) { settings.anthropic = it; settings.save() }
            Sep(T.gutter)
            SwitchRow(L.assistantAuto, settings.auto, L.assistantAutoSub) { settings.auto = it; settings.save() }
            Sep(T.gutter)
            SwitchRow(L.assistantReadOnly, settings.readOnly, L.assistantReadOnlySub) { settings.readOnly = it; settings.save() }
        }
        Btn(L.save, kind = BtnKind.TINT, enabled = settings.ready) { settings.informed = true; settings.save(); settingsOpen = false }
    }

    GHead(L.assistantChat)
    for (l in chat) Txt((if (l.mine) "› " else "") + l.text, T.fBody, if (l.mine) P.current.l2 else P.current.ink, modifier = Modifier.padding(vertical = 4.dp))

    // Предложения: что изменится, и три ответа.
    for (p in engine.pending()) ProposalCard(p, engine, ::publish, ui)

    Field(input, L.assistantAskHint, single = false, minHeight = T.btn * 2) { input = it }
    Btn(if (busy) L.assistantBusy else L.assistantSend, kind = BtnKind.TINT, enabled = !busy && settings.ready && settings.informed && input.isNotBlank()) {
        val q = input.trim(); input = ""; chat += Line(true, q); busy = true
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                val cfg = ProviderConfig(settings.url, settings.model, settings.key)
                if (settings.anthropic) {
                    val prov = AnthropicProvider(cfg)
                    try { Assistant(engine, prov, AssistantPrompt.text).ask(q) } finally { prov.close() }
                } else {
                    val prov = OpenAiProvider(cfg)
                    try { Assistant(engine, prov, AssistantPrompt.text).ask(q) } finally { prov.close() }
                }
            }
            chat += Line(false, reply.text)
            busy = false
            // Автоподтверждение уже изменило сцену: показать в оформлении.
            if (engine.journal.any { it.auto && !it.undone }) publish() else tick++
        }
    }
    if (!settings.informed && settings.ready) Note(L.assistantPressSave)

    if (engine.journal.isNotEmpty()) {
        GHead(L.assistantJournal)
        Group {
            engine.journal.reversed().forEachIndexed { i, e ->
                if (i > 0) Sep(T.gutter)
                Cell(e.proposal.summary, (if (e.auto) L.assistantAutoApplied else L.assistantApplied) + if (e.undone) " · ${L.assistantUndone}" else "", chevron = null) {
                    if (!e.undone && e === engine.journal.last { !it.undone }) Pill(L.assistantUndo) { if (engine.undoLast()) publish() }
                }
            }
        }
    }
}

@Composable
private fun ProposalCard(p: Proposal, engine: ProposalEngine, publish: () -> Unit, ui: Ui) {
    // Какие операции отмечены (по умолчанию все).
    val chosen = remember(p.id) { mutableStateListOf<Boolean>().also { l -> repeat(p.ops.size) { l += true } } }
    GHead(p.summary)
    if (p.dangerous) Note(L.assistantDangerous)
    Group {
        p.lines.forEachIndexed { i, line ->
            if (i > 0) Sep(T.gutter)
            Cell(line, chevron = null) { if (p.lines.size > 1) Switch(chosen.getOrElse(i) { true }, line) { v -> if (i < chosen.size) chosen[i] = v } }
        }
    }
    p.warnings.forEach { Note(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
        Btn(L.assistantAccept, Modifier.weight(1f), kind = BtnKind.TINT) {
            val only = chosen.withIndex().filter { it.value }.map { it.index }.toSet().takeIf { it.size != chosen.size }
            val errors = engine.accept(p.id, only)
            if (errors.isEmpty()) publish() else ui.toast(errors.first())
        }
        Btn(L.assistantReject, Modifier.weight(1f)) { engine.reject(p.id); publish() }
    }
}
