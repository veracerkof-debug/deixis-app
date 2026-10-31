package app.deixis.phone

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.deixis.core.HolyricsClient
import app.deixis.core.HolyricsFile
import app.deixis.core.HolyricsLink
import app.deixis.core.HolyricsMedia
import app.deixis.core.holyricsColor
import app.deixis.core.backgroundImageName
import app.deixis.core.qrMatrix

private enum class Tab(val title: String) { PLAYLIST("Плейлист"), SONGS("Песни"), TEXTS("Тексты"), IMAGES("Фото"), VIDEOS("Видео"), THEMES("Темы") }

/** Окно Holyrics: подключение, библиотека, темы и пульт. */
@Composable
fun HolyricsDialog(h: HolyricsSource, onDismiss: () -> Unit) {
    val p = P.current
    val status by h.status.collectAsState()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(12.dp).clip(RoundedCornerShape(14.dp)).background(p.bg).padding(16.dp).fillMaxWidth().heightIn(max = 720.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("Holyrics", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.ink))
                Spacer(Modifier.weight(1f))
                BasicText(
                    when (val s = status) {
                        is HolyricsStatus.On -> "подключён · ${s.version}"
                        HolyricsStatus.Connecting -> "подключение…"
                        is HolyricsStatus.Failed -> "нет связи"
                        HolyricsStatus.Off -> ""
                    },
                    style = TextStyle(fontSize = 13.sp, color = p.l2),
                )
            }
            Spacer(Modifier.height(10.dp))
            when (val s = status) {
                is HolyricsStatus.On -> Library(h, s.link, onDismiss)
                else -> ConnectForm(h, (s as? HolyricsStatus.Failed)?.reason)
            }
            Spacer(Modifier.height(10.dp))
            PlainButton("Закрыть", onDismiss)
        }
    }
}

@Composable
private fun ConnectForm(h: HolyricsSource, error: String?) {
    val p = P.current
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(HolyricsClient.DEFAULT_PORT.toString()) }
    var token by remember { mutableStateOf("") }
    val found by h.found.collectAsState()
    val searching by h.searching.collectAsState()
    BasicText(
        "Проще всего — QR-код: в Deixis для компьютера рядом с Holyrics откройте «Holyrics → Телефон по QR» и наведите камеру телефона. " +
            "Или вручную: в Holyrics «Файл → Настройки → API Server» включите доступ по локальной сети, создайте токен и дайте ему права.",
        style = TextStyle(fontSize = 14.sp, color = p.l2),
    )
    Spacer(Modifier.height(10.dp))
    // Holyrics в своей сети: опрос по нажатию, адрес подставляется касанием.
    PlainButton(if (searching) "Ищем Holyrics в сети…" else "Найти Holyrics в сети") { if (!searching) h.find() }
    found?.let { list ->
        Spacer(Modifier.height(8.dp))
        if (list.isEmpty()) BasicText(
            "Holyrics не найден. В Holyrics включите API Server и доступ по локальной сети; телефон и компьютер — в одной сети Wi-Fi.",
            style = TextStyle(fontSize = 13.sp, color = p.l2),
        )
        else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { f -> SmallChip(listOfNotNull(f.name, f.host).joinToString(" · "), selected = host == f.host) { host = f.host } }
        }
    }
    Spacer(Modifier.height(10.dp))
    Field("Адрес компьютера или ссылка deixis-holyrics://", host, KeyboardType.Uri) { host = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { Field("Порт", port, KeyboardType.Number) { port = it } }
        Box(Modifier.weight(2f)) { Field("Токен", token, KeyboardType.Password) { token = it } }
    }
    error?.let { BasicText(it, style = TextStyle(fontSize = 13.sp, color = p.red)) }
    Spacer(Modifier.height(10.dp))
    val link = HolyricsLink.parse(host) ?: host.trim().takeIf { it.isNotEmpty() && token.isNotBlank() }
        ?.let { HolyricsLink(it.substringBefore(':'), port.toIntOrNull() ?: HolyricsClient.DEFAULT_PORT, token.trim()) }
    PrimaryButton("Подключить", enabled = link != null) { link?.let { h.connect(it) } }
}

@Composable
private fun Library(h: HolyricsSource, link: HolyricsLink, onDismiss: () -> Unit) {
    val p = P.current
    var tab by remember { mutableStateOf(Tab.PLAYLIST) }
    var query by remember { mutableStateOf("") }
    var showQr by remember { mutableStateOf(false) }
    val remote by h.remote.collectAsState()
    val themeId by h.themeId.collectAsState()

    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(Tab.entries.toList()) { t -> SmallChip(t.title, t == tab) { tab = t } }
    }
    Spacer(Modifier.height(8.dp))
    if (tab != Tab.THEMES) Field("Поиск", query, KeyboardType.Text) { query = it }
    val q = query.trim().lowercase()
    Box(Modifier.heightIn(max = 380.dp)) {
        when (tab) {
            Tab.PLAYLIST, Tab.SONGS -> {
                val list by (if (tab == Tab.PLAYLIST) h.playlist else h.songs).collectAsState()
                Items(list.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }, { it.title }, { it.artist.ifBlank { it.key } }) { song ->
                    Actions({ h.showSong(song.id); onDismiss() }, { h.showInHolyrics(song = song) })
                }
            }
            Tab.TEXTS -> {
                val list by h.texts.collectAsState()
                Items(list.filter { q.isEmpty() || it.title.lowercase().contains(q) }, { it.title }, { it.folder }) { text ->
                    Actions({ h.showText(text.id); onDismiss() }, { h.showInHolyrics(text = text) })
                }
            }
            Tab.IMAGES, Tab.VIDEOS -> {
                val kind = if (tab == Tab.IMAGES) HolyricsMedia.IMAGE else HolyricsMedia.VIDEO
                val list by (if (kind == HolyricsMedia.IMAGE) h.images else h.videos).collectAsState()
                MediaItems(list.filter { !it.isDir && (q.isEmpty() || it.name.lowercase().contains(q)) }) { f -> h.showMedia(kind, f); onDismiss() }
            }
            Tab.THEMES -> {
                val list by h.themes.collectAsState()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { ThemeRow("Как в песне или тексте", null, themeId == null) { h.selectTheme(null) } }
                    items(list, key = { it.id }) { t ->
                        ThemeRow(t.name.ifBlank { t.id }, t, themeId == t.id) { h.selectTheme(t.id) }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    // Всё из Holyrics — своим в Deixis: песни, тексты, темы, плейлист. Повторно — обновит, без копий.
    val importing by h.importing.collectAsState()
    PrimaryButton(importing ?: "Перенести всё в Deixis", enabled = importing == null) { h.importAll() }
    BasicText(
        "Песни, тексты, темы и плейлист станут своими в библиотеке Deixis и останутся без Holyrics. Повторный перенос обновит их, а не добавит копии.",
        style = TextStyle(fontSize = 13.sp, color = p.l2),
        modifier = Modifier.padding(top = 6.dp),
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { PlainButton(if (remote) "Режим Deixis" else "Работать пультом Holyrics") { h.useAsRemote(!remote); onDismiss() } }
        Box(Modifier.weight(1f)) { PlainButton("Обновить") { h.refresh() } }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { PlainButton("QR для телефона") { showQr = !showQr } }
        Box(Modifier.weight(1f)) { PlainButton("Отключить") { h.disconnect() } }
    }
    if (showQr) {
        Spacer(Modifier.height(10.dp))
        LinkQr(link.toUri())
        BasicText(
            "Откройте камеру на втором телефоне с Deixis. Этот код даёт управление Holyrics — показывайте его только своим.",
            style = TextStyle(fontSize = 13.sp, color = p.l2, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Две кнопки у элемента: на экран Deixis или в сам Holyrics. */
private data class ItemActions(val toDeixis: () -> Unit, val toHolyrics: () -> Unit)

private fun Actions(toDeixis: () -> Unit, toHolyrics: () -> Unit) = ItemActions(toDeixis, toHolyrics)

@Composable
private fun <T> Items(list: List<T>, title: (T) -> String, subtitle: (T) -> String, actions: (T) -> ItemActions) {
    val p = P.current
    if (list.isEmpty()) return BasicText("Пусто", style = TextStyle(fontSize = 14.sp, color = p.l2))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(list) { x ->
            val a = actions(x)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.cell).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(title(x).ifBlank { "Без названия" }, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = p.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle(x).takeIf { it.isNotBlank() }?.let { BasicText(it, style = TextStyle(fontSize = 13.sp, color = p.l2), maxLines = 1) }
                }
                SmallChip("На экран", false, a.toDeixis)
                Spacer(Modifier.width(6.dp))
                SmallChip("В Holyrics", false, a.toHolyrics)
            }
        }
    }
}

@Composable
private fun MediaItems(list: List<HolyricsFile>, onPick: (HolyricsFile) -> Unit) {
    val p = P.current
    if (list.isEmpty()) return BasicText("Пусто", style = TextStyle(fontSize = 14.sp, color = p.l2))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(list, key = { it.name }) { f ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.cell).clickable { onPick(f) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val thumb = remember(f.thumbnail) {
                    f.thumbnail?.let { runCatching { Base64.decode(it.substringAfter("base64,"), Base64.DEFAULT).let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) } }.getOrNull() }
                }
                Box(Modifier.width(80.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                    thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop) }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(f.name, style = TextStyle(fontSize = 15.sp, color = p.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val info = listOfNotNull(
                        if (f.width != null && f.height != null) "${f.width}×${f.height}" else null,
                        f.durationMs?.takeIf { it > 0 }?.let { "${it / 60000}:${"%02d".format(it / 1000 % 60)}" },
                    ).joinToString(" · ")
                    if (info.isNotEmpty()) BasicText(info, style = TextStyle(fontSize = 12.sp, color = p.l2))
                }
            }
        }
    }
}

@Composable
private fun ThemeRow(title: String, theme: app.deixis.core.HolyricsTheme?, selected: Boolean, onClick: () -> Unit) {
    val p = P.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.cell)
            .then(if (selected) Modifier.border(2.dp, p.red, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Образец: фон и цвет текста темы.
        Box(
            Modifier.width(64.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))
                .background(theme?.let { Color(if (it.background.type == "color") holyricsColor(it.background.id, 0xFF000000) else 0xFF303030) } ?: p.fill),
            contentAlignment = Alignment.Center,
        ) {
            theme?.let { BasicText("Аа", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(holyricsColor(it.font.color, 0xFFFFFFFF)))) }
        }
        Spacer(Modifier.width(10.dp))
        BasicText(title, style = TextStyle(fontSize = 15.sp, color = p.ink), modifier = Modifier.weight(1f))
        if (theme?.backgroundImageName != null) BasicText("фон-фото", style = TextStyle(fontSize = 12.sp, color = p.l2))
    }
}

/** Карточка «Сейчас» в режиме пульта Holyrics: превью текущего слайда и лента слайдов. */
@Composable
fun HolyricsLiveCard(h: HolyricsSource) {
    val p = P.current
    val live by h.live.collectAsState()
    val cur = live?.p
    Box(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
            .background(Color.Black).border(2.dp, if (cur != null) p.red else p.l3, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = cur?.let { live?.previews?.get(it.slideNumber) }
        when {
            bmp != null -> Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            cur != null -> BasicText(
                cur.slides?.firstOrNull { it.number == cur.slideNumber }?.text?.ifBlank { null } ?: cur.name,
                Modifier.padding(16.dp), style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center),
            )
            else -> BasicText("В Holyrics ничего не показано", style = TextStyle(fontSize = 15.sp, color = Color.White))
        }
    }
    cur?.let { c ->
        BasicText(
            "Holyrics · ${c.name} · слайд ${c.slideNumber} из ${c.totalSlides}",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = TextStyle(fontSize = 13.sp, color = p.l2), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(c.slides.orEmpty()) { i, s ->
                val thumb = live?.previews?.get(s.number)
                Box(
                    Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black)
                        .then(if (s.number == c.slideNumber) Modifier.border(2.dp, p.red, RoundedCornerShape(8.dp)) else Modifier)
                        .clickable { h.goTo(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumb != null) Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Fit)
                    else BasicText(s.description ?: s.text.lineSequence().firstOrNull().orEmpty(), Modifier.padding(4.dp), style = TextStyle(fontSize = 11.sp, color = Color.White, textAlign = TextAlign.Center), maxLines = 3)
                }
            }
        }
    }
}

@Composable
private fun LinkQr(text: String) {
    val matrix = remember(text) { qrMatrix(text) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)) {
            val pad = size.width / 12f
            val cell = (size.width - 2 * pad) / matrix.size
            for (r in matrix.indices) for (col in matrix[r].indices) if (matrix[r][col]) {
                drawRect(Color.Black, Offset(pad + col * cell, pad + r * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

@Composable
private fun Field(hint: String, value: String, keyboard: KeyboardType, onChange: (String) -> Unit) {
    val p = P.current
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(p.fill).padding(12.dp)) {
        if (value.isEmpty()) BasicText(hint, style = TextStyle(fontSize = 15.sp, color = p.l2))
        BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = p.ink), keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SmallChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = P.current
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (selected) p.ink else p.fill).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    ) { BasicText(text, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) p.bg else p.ink)) }
}

@Composable
private fun PlainButton(text: String, onClick: () -> Unit) {
    val p = P.current
    Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).background(p.fill).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        BasicText(text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.ink))
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val p = P.current
    Box(
        Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(if (enabled) p.red else p.fill).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { BasicText(text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.White else p.l3)) }
}
