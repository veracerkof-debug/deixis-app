package app.deixis.phone

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight

/** Настройки → Управление (компьютер): клавиши показа и кнопки мыши. Всё переназначается, «Сбросить» возвращает умолчания. */
@Composable
fun SettingsControlsPage(c: Controller) {
    val st by c.settings.collectAsState()
    val ui = LocalUi.current
    GHead(L.ctlKeys)
    Group {
        KeyAction.entries.forEachIndexed { i, a ->
            if (i > 0) Sep(T.gutter)
            Cell(L.keyActionName(a), Hotkeys.combosOf(a, st.keys).joinToString("  ·  ").ifEmpty { L.ctlNoKey }, chevron = null,
                onClick = { ui.push(L.keyActionName(a)) { KeyCapture(c, ui, a) } })
        }
    }
    Btn(L.ctlResetKeys, Modifier.padding(top = T.gap)) { c.setSettings { it.copy(keys = emptyMap()) } }
    GHead(L.ctlMouse)
    Note(L.ctlMouseNote)
    Txt(L.ctlDouble, T.fBody, modifier = Modifier.padding(top = T.gap, bottom = T.gapS))
    Seg(listOf(L.toHall, L.toPreview), if (st.mouseDouble == "preview") 1 else 0, Modifier.fillMaxWidth()) { i -> c.setSettings { it.copy(mouseDouble = if (i == 1) "preview" else "live") } }
    Txt(L.ctlMiddle, T.fBody, modifier = Modifier.padding(top = T.gap, bottom = T.gapS))
    val mids = listOf("black" to L.black, "next" to L.ctlNext, "none" to L.ctlNothing)
    Seg(mids.map { it.second }, mids.indexOfFirst { it.first == st.mouseMiddle }.coerceAtLeast(0), Modifier.fillMaxWidth()) { i -> c.setSettings { it.copy(mouseMiddle = mids[i].first) } }
}

/** Ждёт сочетание клавиш для действия [a]: «Заменить» оставляет только его, «Добавить» — к прежним, «Умолчание» — как было. */
@Composable
private fun KeyCapture(c: Controller, ui: Ui, a: KeyAction) {
    val st by c.settings.collectAsState()
    val focus = remember { FocusRequester() }
    var got by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val p = P.current
    Box(
        Modifier.fillMaxWidth().focusRequester(focus).focusable()
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown) { Hotkeys.comboOf(e)?.let { got = it }; true } else false },
        contentAlignment = Alignment.Center,
    ) {
        Txt(got ?: L.ctlPress, T.fSheet, if (got != null) p.tintText else p.l2, FontWeight.SemiBold, Modifier.padding(vertical = T.gutter))
    }
    Note(L.ctlNow(Hotkeys.combosOf(a, st.keys).joinToString(", ")))
    Row(Modifier.padding(top = T.gap), horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
        Btn(L.ctlReplace, Modifier.weight(1f), kind = BtnKind.TINT, enabled = got != null) { c.setSettings { it.copy(keys = Hotkeys.assign(it.keys, a, listOf(got!!))) }; ui.close() }
        Btn(L.ctlAdd, Modifier.weight(1f), enabled = got != null) { c.setSettings { it.copy(keys = Hotkeys.assign(it.keys, a, (Hotkeys.combosOf(a, it.keys) + got!!).distinct())) }; ui.close() }
    }
    Btn(L.ctlDefault, Modifier.padding(top = T.gapS)) { c.setSettings { it.copy(keys = Hotkeys.assign(it.keys, a, null)) }; ui.close() }
}
