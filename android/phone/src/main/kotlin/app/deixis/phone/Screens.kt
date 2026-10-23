package app.deixis.phone

import android.content.Intent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
private fun Gear(c: Controller, ui: Ui) = IconBtn("gear", "Настройки") { ui.sheet("Настройки") { SettingsSheet(c) } }

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
    val items = data.items.filter { (ui.libSeg == null || it.kind == ui.libSeg) && it.matches(ui.libQuery) }
    val groups = Kind.entries.mapNotNull { k -> items.filter { it.kind == k }.takeIf { it.isNotEmpty() }?.let { k to it } }
    Column(Modifier.fillMaxSize()) {
        Header("Библиотека") {
            IconBtn("plus", "Добавить") { ui.sheet("Добавить") { AddSheet(c, ui) } }
            Gear(c, ui)
        }
        LazyColumn(wide.fillMaxSize()) {
            item("search") {
                Column(Modifier.padding(horizontal = T.gutter)) {
                    SearchField(ui.libQuery, "Поиск по названию и тексту", Modifier.padding(bottom = T.gap)) { ui.libQuery = it }
                    Seg(
                        segs.map { it?.plural ?: "Все" }, segs.indexOf(ui.libSeg).coerceAtLeast(0),
                        Modifier.fillMaxWidth().padding(bottom = 18.dp), scroll = true,
                    ) { ui.libSeg = segs[it] }
                }
            }
            if (groups.isEmpty()) item("empty") {
                Note(
                    if (data.items.isEmpty()) "Библиотека пуста. «+» добавляет песни, тексты, места из Библии и файлы телефона: презентации, видео, фото."
                    else "Ничего не найдено.",
                    Modifier.padding(horizontal = T.gutter),
                )
            }
            groups.forEach { (k, list) ->
                item("h:$k") { Column(Modifier.padding(top = 4.dp)) { GHead(k.plural) } }
                list.forEachIndexed { i, it ->
                    item(it.id) {
                        Column(Modifier.fillMaxWidth().groupCell(i == 0, i == list.lastIndex)) {
                            if (i > 0) Sep()
                            Cell(
                                it.title, subOf(c, it), it.kind.icon,
                                onLong = { ui.sheet(it.title) { ItemMenu(c, ui, it, inSet = false) } },
                                onClick = { ui.full = FullSpec.Local(Cue(c.sourceOf(it), 0)) },
                            )
                        }
                    }
                }
                item("g:$k") { Spacer(Modifier.height(20.dp)) }
            }
            item("note") { Note("Удержание элемента — править, удалить, в сетлист.", Modifier.padding(horizontal = T.gutter)) }
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
        Header("Сетлисты") {
            IconBtn("plus", "Новый сетлист") { ui.sheet("Новый сетлист") { NewSetSheet(c, ui) } }
            Gear(c, ui)
        }
        Column(wide.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = T.gutter)) {
            if (up.isNotEmpty()) { GHead("Ближайшие"); SetGroup(c, ui, up, data) }
            if (past.isNotEmpty()) { GHead("Прошедшие"); SetGroup(c, ui, past, data) }
            Note("Прошлое служение удобно дублировать: оно становится основой следующего.")
        }
    }
}

@Composable
private fun SetGroup(c: Controller, ui: Ui, sets: List<Setlist>, data: StoreData) {
    Group {
        sets.forEachIndexed { i, s ->
            if (i > 0) Sep(T.gutter)
            val names = s.items.take(3).mapNotNull { id -> data.items.firstOrNull { it.id == id }?.title }.joinToString(", ")
            Cell("${s.title}, ${s.dateText}", "${s.items.size} элем." + if (names.isNotEmpty()) " · $names" else "", chevron = null, onClick = {
                c.pickSet(s.id); ui.open = s.items.firstOrNull(); ui.listSeg = 0; ui.tab = Tab.SHOW
                ui.toast("На главном: ${s.title}, ${s.dateText}")
            }) {
                if (s.id == data.active) Tag("На главном", false)
                IconBtn("more", "Действия") { ui.sheet("${s.title}, ${s.dateText}") { SetMenu(c, ui, s) } }
            }
        }
    }
}

@Composable
private fun SetMenu(c: Controller, ui: Ui, s: Setlist) {
    Column(verticalArrangement = Arrangement.spacedBy(T.gap)) {
        Btn("На главный") { c.pickSet(s.id); ui.closeAll(); ui.tab = Tab.SHOW; ui.toast("На главном: ${s.title}, ${s.dateText}") }
        Btn("Дублировать") { ui.push("Дублировать") { NewSetSheet(c, ui, copyOf = s) } }
        Btn("Переименовать") {
            ui.push("Переименовать") {
                var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(s.title) }
                Field(name, "Название") { name = it }
                Spacer(Modifier.height(12.dp))
                Btn("Сохранить", kind = BtnKind.TINT, enabled = name.isNotBlank()) { c.saveSet(s.copy(title = name.trim())); ui.closeAll() }
            }
        }
        Btn("Удалить", kind = BtnKind.RED) {
            if (c.deleteSet(s.id)) { ui.closeAll(); ui.toast("Сетлист удалён") } else { ui.closeAll(); ui.toast("Сетлист на главном удалить нельзя") }
        }
    }
}

// ——— Устройства ———

@Composable
fun DevicesScreen(c: Controller, h: HolyricsSource, ui: Ui) {
    val outputs by c.outputs.collectAsState()
    val targets by c.targets.collectAsState()
    val st by c.settings.collectAsState()
    val holyrics by h.status.collectAsState()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Header("Устройства") { Gear(c, ui) }
        Column(wide.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = T.gutter)) {
            GHead("Экраны")
            Group {
                outputs.forEachIndexed { i, o ->
                    if (i > 0) Sep()
                    OutputCell(c, o)
                }
                if (outputs.isNotEmpty()) Sep()
                Cell("Подключить экран", kind = "plus", onClick = { ui.sheet("Подключить") { ConnectSheet(c, ui) } })
                Sep()
                Cell("Проверка перед началом", kind = "show", onClick = { ui.toast(c.check()) })
            }
            GHead("Зрители")
            ViewersBlock(c)
            val free = targets.filter { t -> outputs.none { it.target.id == t.id } }
            if (free.isNotEmpty()) {
                GHead("Рядом в сети")
                Group {
                    free.forEachIndexed { i, t ->
                        if (i > 0) Sep()
                        Cell(t.name, t.via + if (t.kind == Target.Kind.NETWORK) " · ${t.host}" else "", t.icon, chevron = null) {
                            Pill("Подключить", tint = true) { c.connect(t) }
                        }
                    }
                }
            }
            GHead("Этот телефон")
            Group {
                Cell("Стать экраном", "Телефон или планшет показывает сам: для проектора, доски, запасного экрана", "tv", onClick = {
                    context.startActivity(Intent(context, ScreenActivity::class.java))
                })
                if (st.church) {
                    Sep()
                    Cell("Holyrics", if (holyrics is HolyricsStatus.On) "Подключено" else "Песни, темы и показ из Holyrics на компьютере", "song", onClick = { ui.holyrics = true })
                    Sep()
                    Cell("Переводы Библии", "Синодальный, King James и модули MyBible", "bible", onClick = { ui.sheet("Переводы Библии") { BibleModulesSheet(c) } })
                }
            }
            Note("Кабель HDMI и беспроводной дисплей (Миракаст) появляются в «Экранах» сами, как только телефон их видит.")
        }
    }
}
