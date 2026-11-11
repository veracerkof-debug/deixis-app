package app.deixis.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun Header(title: String, actions: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = T.topMin).padding(start = T.gutter, end = 8.dp, top = T.gap, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(title, T.fTitle, weight = FontWeight.Bold, modifier = Modifier.weight(1f), lines = 1)
        actions()
    }
}

@Composable
private fun Gear(c: Controller, ui: Ui) = IconBtn("gear", L.settings) { ui.sheet(L.settings) { SettingsSheet(c) } }

private val wide = Modifier.widthIn(max = 900.dp)

// ——— Библиотека ———

@Composable
fun LibraryScreen(c: Controller, @Suppress("UNUSED_PARAMETER") h: HolyricsSource, ui: Ui) {
    val data by c.data.collectAsState()
    val st by c.settings.collectAsState()
    val kinds = listOfNotNull(
        null, Kind.PRESENTATION, Kind.VIDEO, Kind.PHOTO, Kind.FILE, Kind.SONG, Kind.TEXT, if (st.church) Kind.BIBLE else null,
    )
    val segs = listOf<Kind?>(null) + kinds.filterNotNull()
    // Отбор пересчитывается, только когда изменились библиотека, вид или запрос — не на каждую перерисовку.
    val seg = ui.libSeg
    val query = ui.libQuery
    val groups = remember(data.items, seg, query) {
        val items = data.items.filter { (seg == null || it.kind == seg) && it.matches(query) }
        Kind.entries.mapNotNull { k -> items.filter { it.kind == k }.takeIf { it.isNotEmpty() }?.let { k to it } }
    }
    Column(Modifier.fillMaxSize()) {
        Header(L.tabLibrary) {
            IconBtn("plus", L.add) { ui.sheet(L.add) { AddSheet(c, ui) } }
            Gear(c, ui)
        }
        LazyColumn(wide.fillMaxSize()) {
            item("search") {
                Column(Modifier.padding(horizontal = T.gutter)) {
                    SearchField(ui.libQuery, L.libSearch, Modifier.padding(bottom = T.gap)) { ui.libQuery = it }
                    Seg(
                        segs.map { it?.plural ?: L.all }, segs.indexOf(ui.libSeg).coerceAtLeast(0),
                        Modifier.fillMaxWidth().padding(bottom = 18.dp), scroll = true,
                    ) { ui.libSeg = segs[it] }
                }
            }
            if (groups.isEmpty()) item("empty") {
                Note(
                    if (data.items.isEmpty()) L.libEmpty
                    else L.nothingFoundDot,
                    Modifier.padding(horizontal = T.gutter),
                )
            }
            groups.forEach { (k, list) ->
                item("h:$k") { Column(Modifier.padding(top = 4.dp)) { GHead(k.plural) } }
                list.forEachIndexed { i, it ->
                    item(it.id) {
                        Column(Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(T.mid, easing = T.eOut), fadeOutSpec = androidx.compose.animation.core.tween(T.fast, easing = T.eIn)).fillMaxWidth().groupCell(i == 0, i == list.lastIndex)) {
                            if (i > 0) Sep()
                            Cell(
                                it.title, subOf(c, it), it.kind.icon, item = it,
                                onLong = { ui.sheet(it.title) { ItemMenu(c, ui, it, inSet = false) } },
                                onClick = { ui.full = FullSpec.Local(Cue(c.sourceOf(it), 0)) },
                            ) { FileBadge(c, ui, it) }
                        }
                    }
                }
                item("g:$k") { Spacer(Modifier.height(20.dp)) }
            }
            item("note") { Note(L.libNote, Modifier.padding(horizontal = T.gutter)) }
        }
    }
}

/**
 * Файл на экранах — справа в строке библиотеки: «на экране», ход передачи, «в очереди» или кнопка
 * «отправить заранее». Видно только при подключённых экранах и только у фото, видео и PDF.
 */
@Composable
fun FileBadge(c: Controller, ui: Ui, item: LibItem) {
    val screens by c.outputs.collectAsState()
    val transfers by c.transfers.collectAsState()
    val stored by c.stored.collectAsState()
    // Хэш элемента появляется после первой передачи: от него зависит, найдётся ли файл на экране.
    @Suppress("UNUSED_VARIABLE") val data by c.data.collectAsState()
    val st = c.onScreens(item, screens, transfers, stored) ?: return
    val p = P.current
    val t = st.transfer
    when {
        t != null -> Txt(if (t.queued) L.queued else if (t.preparing) L.preparingLower else "${t.percent} %", T.fTag, p.tintText, FontWeight.SemiBold, lines = 1)
        st.done == st.screens -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon("onscreen", p.tintText, T.iconS)
            Txt(if (st.screens == 1) L.onScreen else L.onAll, T.fTag, p.tintText, FontWeight.SemiBold, lines = 1)
        }
        else -> Row(
            Modifier.press(label = L.sendAheadOf(item.title)) {
                ui.toast(if (c.sendAhead(item) > 0) L.sendingBackground else L.noScreens)
            }.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Просто значок: название файла важнее. Частично отправленный — с подписью.
            Icon("send", p.l2, T.icon)
            if (st.done > 0) Txt(L.nOfM(st.done, st.screens), T.fTag, p.l2, FontWeight.SemiBold, lines = 1)
            else if (st.partial) Txt(L.resend, T.fTag, p.l2, FontWeight.SemiBold, lines = 1)
        }
    }
}

// ——— Сетлисты ———

@Composable
fun SetlistsScreen(c: Controller, ui: Ui) {
    val data by c.data.collectAsState()
    val up = data.setlists.filter { !it.past }.sortedBy { it.date }
    val past = data.setlists.filter { it.past }.sortedByDescending { it.date }
    Column(Modifier.fillMaxSize()) {
        Header(L.tabSets) {
            IconBtn("plus", L.newSet) { ui.sheet(L.newSet) { NewSetSheet(c, ui) } }
            Gear(c, ui)
        }
        Column(wide.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = T.gutter)) {
            if (up.isNotEmpty()) { GHead(L.upcoming); SetGroup(c, ui, up, data) }
            if (past.isNotEmpty()) { GHead(L.past); SetGroup(c, ui, past, data) }
            Note(L.dupHint)
        }
    }
}

@Composable
private fun SetGroup(c: Controller, ui: Ui, sets: List<Setlist>, data: StoreData) {
    Group {
        sets.forEachIndexed { i, s ->
            if (i > 0) Sep(T.gutter)
            val names = s.items.take(3).mapNotNull { id -> data.items.firstOrNull { it.id == id }?.title }.joinToString(", ")
            Cell("${s.title}, ${s.dateText}", L.itemsShort(s.items.size) + if (names.isNotEmpty()) " · $names" else "", chevron = null, onClick = {
                c.pickSet(s.id); ui.open = s.items.firstOrNull(); ui.listSeg = 0; ui.tab = AppTab.SHOW
                ui.toast(L.onMainSet("${s.title}, ${s.dateText}"))
            }) {
                if (s.id == data.active) Tag(L.onMain, false)
                IconBtn("more", L.actions) { ui.sheet("${s.title}, ${s.dateText}") { SetMenu(c, ui, s) } }
            }
        }
    }
}

@Composable
private fun SetMenu(c: Controller, ui: Ui, s: Setlist) {
    Column(verticalArrangement = Arrangement.spacedBy(T.gap)) {
        Btn(L.toMain) { c.pickSet(s.id); ui.closeAll(); ui.tab = AppTab.SHOW; ui.toast(L.onMainSet("${s.title}, ${s.dateText}")) }
        Btn(L.duplicate) { ui.push(L.duplicate) { NewSetSheet(c, ui, copyOf = s) } }
        Btn(L.rename) {
            ui.push(L.rename) {
                var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(s.title) }
                Field(name, L.title) { name = it }
                Spacer(Modifier.height(12.dp))
                Btn(L.save, kind = BtnKind.TINT, enabled = name.isNotBlank()) { c.saveSet(s.copy(title = name.trim())); ui.closeAll() }
            }
        }
        Btn(L.delete, kind = BtnKind.RED) { ui.push(L.deleteSet) { DeleteSetSheet(c, ui, s) } }
    }
}

// ——— Устройства ———

@Composable
fun DevicesScreen(c: Controller, h: HolyricsSource, ui: Ui) {
    val outputs by c.outputs.collectAsState()
    val targets by c.targets.collectAsState()
    val st by c.settings.collectAsState()
    val holyrics by h.status.collectAsState()
    val host = LocalHost.current
    Column(Modifier.fillMaxSize()) {
        Header(L.tabDevices) { Gear(c, ui) }
        Column(wide.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = T.gutter)) {
            if (st.church) WorkModeSwitch(c, ui, h)
            GHead(L.screens)
            Group {
                outputs.forEachIndexed { i, o ->
                    if (i > 0) Sep()
                    OutputCell(c, o)
                }
                if (outputs.isNotEmpty()) Sep()
                Cell(L.connectScreen, kind = "plus", onClick = { ui.sheet(L.connect) { ConnectSheet(c, ui) } })
                Sep()
                Cell(L.preflight, kind = "show", onClick = { ui.toast(c.check()) })
                if (outputs.any { it.state == OutState.ONLINE }) {
                    Sep()
                    Cell(L.connectCodes, L.connectCodesSub, kind = "qr", onClick = { ui.push(L.connectCodes) { ConnectCodesSheet(c, ui) } })
                }
            }
            // Компьютер среди экранов: его входы (монитор, окно программы, виртуальный экран, AirPlay) и выходы (мониторы).
            outputs.firstOrNull { it.state == OutState.ONLINE && it.info?.platform?.startsWith("desktop") == true }?.let { pc ->
                Txt(L.inputs, T.fSheet, P.current.ink, FontWeight.Bold, Modifier.padding(top = T.gutter, start = T.gapXs))
                Note(L.inputsNote)
                ScreenInputs(c, pc.target)
                Txt(L.outputs, T.fSheet, P.current.ink, FontWeight.Bold, Modifier.padding(top = T.gutter, start = T.gapXs))
                Note(L.outputsNote)
                ScreenOutputs(c, pc.target)
            }
            GHead(L.viewers)
            ViewersBlock(c)
            val free = targets.filter { t -> outputs.none { c.sameScreen(t, it) } }
            if (free.isNotEmpty()) {
                GHead(L.nearby)
                Group {
                    free.forEachIndexed { i, t ->
                        if (i > 0) Sep()
                        Cell(t.name, t.via + if (t.kind == Target.Kind.NETWORK) " · ${t.host}" else "", t.icon, chevron = null) {
                            Pill(L.connect, tint = true) { c.connect(t) }
                        }
                    }
                }
            }
            val features = host.platform.features
            if (features.becomeScreen || st.church) {
                GHead(if (features.desktop) L.thisComputerHead else L.thisPhone)
                Group {
                    if (features.becomeScreen) Cell(L.becomeScreen, L.becomeScreenSub, "tv", onClick = { host.becomeScreen() })
                    if (st.church) {
                        if (features.becomeScreen) Sep()
                        Cell("Holyrics", if (holyrics is HolyricsStatus.On) L.connectedState else L.hlAbout, "song", onClick = { ui.holyrics = true })
                        Sep()
                        Cell(L.modules, L.modulesAbout, "bible", onClick = { ui.sheet(L.modules) { BibleModulesSheet(c) } })
                    }
                }
            }
            if (features.cable) Note(L.cableNote)
        }
    }
}
