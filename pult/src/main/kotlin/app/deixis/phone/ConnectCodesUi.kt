package app.deixis.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * «QR и код для подключения»: когда в зале идёт показ, QR и PIN экрана уже не видно — новый телефон или
 * второй пульт подключить не к чему. Здесь они всегда под рукой у оператора, и одной кнопкой их можно
 * вывести на сам экран (заставка подключения поверх показа) и убрать обратно.
 */
@Composable
fun ConnectCodesSheet(c: Controller, ui: Ui) {
    val p = P.current
    val scope = rememberCoroutineScope()
    var run by remember { mutableIntStateOf(0) }
    var infos by remember { mutableStateOf<List<Controller.ConnectInfo>?>(null) }
    // PIN меняется после каждого подключения: список обновляется сам, пока шторка открыта.
    LaunchedEffect(run) {
        while (true) {
            infos = c.connectInfos()
            delay(T.refreshMs)
        }
    }
    Note(L.connectCodesNote, Modifier.padding(bottom = T.gap))
    val list = infos
    if (list == null) { Note(L.diagRunning); return }
    if (list.isEmpty()) { Group { Cell(L.connectCodesNone, chevron = null) }; return }
    for (info in list) {
        GHead(info.name)
        Group {
            Row(Modifier.padding(T.gutter), horizontalArrangement = Arrangement.spacedBy(T.gutter), verticalAlignment = Alignment.CenterVertically) {
                info.uri?.let { Qr(it) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(T.gapXs)) {
                    Txt(L.pinLabel, T.fSub, p.l2)
                    Txt(info.pin, T.fTitle, p.ink, FontWeight.Bold)
                    Txt(if (info.uri != null) L.connectCodesScan else L.connectCodesNoLan, T.fSub, p.l2)
                }
            }
        }
        Spacer(Modifier.height(T.gapS))
        Btn(
            if (info.shown) L.connectCardHide else L.connectCardShow,
            Modifier.fillMaxWidth(),
            icon = if (info.shown) "close" else "send",
            kind = if (info.shown) BtnKind.PLAIN else BtnKind.TINT,
        ) {
            scope.launch {
                if (!c.connectCard(info.target, !info.shown)) ui.toast(L.connectCardOld)
                run++
            }
        }
    }
    Note(L.connectCardNote)
}
