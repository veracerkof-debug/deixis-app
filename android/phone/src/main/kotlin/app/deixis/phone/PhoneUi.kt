package app.deixis.phone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.deixis.core.Connection
import app.deixis.core.Mode
import app.deixis.core.VideoAction
import app.deixis.core.qrMatrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.deixis.receiver.Didot

/** Токены из DESIGN.md. */
data class Palette(
    val bg: Color, val cell: Color, val ink: Color, val l2: Color, val l3: Color,
    val tint: Color, val tintText: Color, val red: Color, val fill: Color,
)

private val Light = Palette(
    Color(0xFFF3F2EE), Color(0xFFFFFFFF), Color(0xFF161514), Color(0xFF87847E), Color(0xFFC6C3BC),
    Color(0xFF9C7242), Color(0xFF765A38), Color(0xFFA75750), Color(0x1F78736A),
)
private val Dark = Palette(
    Color(0xFF000000), Color(0xFF1C1C1E), Color(0xFFFFFFFF), Color(0xFF8D8D93), Color(0xFF48484A),
    Color(0xFFBC9361), Color(0xFFBC9361), Color(0xFFB96E65), Color(0x3D767680),
)
private val P = staticCompositionLocalOf { Light }

@Composable
fun DeixisPhone(controller: Controller) {
    CompositionLocalProvider(P provides if (isSystemInDarkTheme()) Dark else Light) {
        Main(controller)
    }
}

@Composable
private fun Main(c: Controller) {
    val p = P.current
    val targets by c.targets.collectAsState()
    val active by c.active.collectAsState()
    val connection by c.connection.collectAsState()
    val state by c.receiverState.collectAsState()
    val item by c.item.collectAsState()
    val progress by c.progress.collectAsState()
    val needPin by c.needPin.collectAsState()
    val viewUrl by c.viewUrl.collectAsState()
    val viewLocalUrl by c.viewLocalUrl.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(c::showUri) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(c::showUri) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(c::showUri) }

    Column(Modifier.fillMaxSize().background(p.bg).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("ΔΕΙΞΙΣ", style = TextStyle(fontFamily = Didot, fontSize = 22.sp, letterSpacing = 0.18.em, color = p.ink))
            Spacer(Modifier.weight(1f))
            BasicText(statusText(active, connection), style = TextStyle(fontSize = 13.sp, color = p.l2), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionTitle("Экраны")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(targets, key = { it.kind.name + it.id }) { t ->
                    Chip(t.name, selected = t == active, live = t == active && connection is Connection.Connected) { c.select(t) }
                }
                item { Chip("+ Адрес", selected = false, live = false) { dialog = "address" } }
            }
            if (targets.isEmpty()) Hint("Ищем экраны Deixis в этой сети Wi-Fi. Можно ввести адрес с экрана приёмника или подключить кабель HDMI.")

            viewUrl?.let {
                Row(Modifier.padding(start = 16.dp, top = 12.dp)) {
                    Chip("Зрители · ${state?.viewers ?: 0}", selected = false, live = false) { dialog = "viewers" }
                }
            }

            SectionTitle("Сейчас")
            NowCard(item, state?.mode, state?.video?.let { "${it.positionMs / 1000} / ${it.durationMs / 1000} с" + if (it.playing) "" else " · пауза" })
            progress?.let { Hint(it) }

            SectionTitle("Показать")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Фото") { pickImage.launch(arrayOf("image/*")) }
                Tile("Видео") { pickVideo.launch(arrayOf("video/*", "audio/*")) }
                Tile("PDF") { pickFile.launch(arrayOf("application/pdf", "text/plain")) }
                Tile("Текст") { dialog = "text" }
            }
            Spacer(Modifier.height(24.dp))
        }

        ControlBar(c, item, state?.mode, state?.video?.playing)
    }

    needPin?.let { t -> InputDialog("PIN с экрана «${t.name}»", "0000", KeyboardType.NumberPassword, singleLine = true, onDismiss = { c.needPin.value = null }) { c.pair(t, it) } }
    when (dialog) {
        "address" -> InputDialog("Адрес приёмника", "192.168.1.20", KeyboardType.Uri, singleLine = true, onDismiss = { dialog = null }) { dialog = null; c.addManual(it) }
        "text" -> InputDialog("Текст", "Пустая строка начинает новый слайд", KeyboardType.Text, singleLine = false, onDismiss = { dialog = null }) { dialog = null; c.showText(it) }
        "viewers" -> viewUrl?.let { ViewersDialog(it, viewLocalUrl, state?.viewers ?: 0) { dialog = null } }
    }
}

/** QR-код для зрителей: iPhone или любой телефон открывает показ в браузере без установки приложения. */
@Composable
private fun ViewersDialog(url: String, localUrl: String?, viewers: Int, onDismiss: () -> Unit) {
    val p = P.current
    val matrix = remember(url) { qrMatrix(url) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(14.dp)).background(p.bg).padding(16.dp).width(320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText("Зрители", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.ink))
            Spacer(Modifier.height(12.dp))
            Canvas(Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)) {
                val pad = size.width / 12f
                val cell = (size.width - 2 * pad) / matrix.size
                for (r in matrix.indices) for (col in matrix[r].indices) if (matrix[r][col]) {
                    drawRect(Color.Black, Offset(pad + col * cell, pad + r * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
            Spacer(Modifier.height(12.dp))
            BasicText(
                "Отсканируйте камерой телефона. Показ откроется в браузере, приложение не нужно. Телефон зрителя должен быть в этой же сети Wi-Fi.",
                style = TextStyle(fontSize = 14.sp, color = p.l2, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(8.dp))
            BasicText("Адрес в сети:", style = TextStyle(fontSize = 12.sp, color = p.l2, textAlign = TextAlign.Center))
            BasicText(url, style = TextStyle(fontSize = 13.sp, color = p.ink, textAlign = TextAlign.Center))
            localUrl?.let {
                Spacer(Modifier.height(4.dp))
                BasicText("Или по имени (iPhone, компьютер):", style = TextStyle(fontSize = 12.sp, color = p.l2, textAlign = TextAlign.Center))
                BasicText(it, style = TextStyle(fontSize = 13.sp, color = p.ink, textAlign = TextAlign.Center))
            }
            Spacer(Modifier.height(8.dp))
            BasicText("Смотрят сейчас: $viewers", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.ink))
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(p.fill).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                BasicText("Готово", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.ink))
            }
        }
    }
}

private fun statusText(t: Target?, conn: Connection): String = when {
    t == null -> "Экран не выбран"
    conn is Connection.Connected -> t.name
    conn is Connection.Connecting -> "Подключение…"
    conn is Connection.Lost -> "Нет связи, переподключение…"
    conn is Connection.Denied -> "Нужен PIN"
    else -> t.name
}

@Composable
private fun SectionTitle(text: String) {
    BasicText(text.uppercase(), Modifier.padding(start = 32.dp, top = 20.dp, bottom = 8.dp), style = TextStyle(fontSize = 13.sp, color = P.current.l2, letterSpacing = 0.02.em))
}

@Composable
private fun Hint(text: String) {
    BasicText(text, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = TextStyle(fontSize = 14.sp, color = P.current.l2))
}

@Composable
private fun Chip(text: String, selected: Boolean, live: Boolean, onClick: () -> Unit) {
    val p = P.current
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(999.dp))
            .background(if (selected) p.tint else p.cell)
            .then(if (live) Modifier.border(2.dp, p.red, RoundedCornerShape(999.dp)) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else p.ink))
    }
}

@Composable
private fun NowCard(item: Item?, mode: Mode?, videoText: String?) {
    val p = P.current
    Box(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
            .background(Color.Black).border(2.dp, if (item != null) p.red else p.l3, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val text = when {
            mode == Mode.BLACK -> "Чёрный экран"
            mode == Mode.LOGO -> "ΔΕΙΞΙΣ"
            item is Item.Text -> item.slides[item.index]
            item is Item.Pdf -> "${item.title}\nстраница ${item.index + 1} из ${item.pages}"
            item is Item.Video -> item.title + (videoText?.let { "\n$it" } ?: "")
            item != null -> item.title
            else -> "Ничего не показано"
        }
        BasicText(
            text, Modifier.padding(16.dp),
            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center, fontFamily = if (mode == Mode.LOGO) Didot else null),
        )
    }
}

@Composable
private fun RowScope.Tile(text: String, onClick: () -> Unit) {
    val p = P.current
    Box(
        Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(12.dp)).background(p.cell).clickable(onClick = onClick).padding(12.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        BasicText(text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.ink))
    }
}

@Composable
private fun ControlBar(c: Controller, item: Item?, mode: Mode?, playing: Boolean?) {
    val p = P.current
    Column(Modifier.fillMaxWidth().background(p.cell).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (item is Item.Video) {
                BarButton("−10 с") { c.video(VideoAction.SEEK, -10_000) }
                BarButton(if (playing == true) "Пауза" else "Пуск") { c.video(if (playing == true) VideoAction.PAUSE else VideoAction.PLAY) }
                BarButton("+10 с") { c.video(VideoAction.SEEK, 10_000) }
                BarButton("Повтор") { c.video(VideoAction.LOOP) }
            } else {
                BarButton("Назад", enabled = item is Item.Text || item is Item.Pdf) { c.step(-1) }
                BarButton("Дальше", enabled = item is Item.Text || item is Item.Pdf) { c.step(1) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BarButton("Чёрный", pressed = mode == Mode.BLACK) { c.mode(Mode.BLACK) }
            BarButton("Логотип", pressed = mode == Mode.LOGO) { c.mode(Mode.LOGO) }
            BarButton("Очистить", pressed = mode == Mode.CLEAR) { c.mode(Mode.CLEAR) }
        }
    }
}

@Composable
private fun RowScope.BarButton(text: String, pressed: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = P.current
    Box(
        Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(14.dp))
            .background(if (pressed) p.ink else p.fill)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = when {
            !enabled -> p.l3
            pressed -> p.bg
            else -> p.ink
        }))
    }
}

@Composable
private fun InputDialog(title: String, placeholder: String, keyboard: KeyboardType, singleLine: Boolean, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val p = P.current
    var value by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(14.dp)).background(p.bg).padding(16.dp).width(320.dp)) {
            BasicText(title, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.ink))
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.cell).padding(14.dp)) {
                if (value.isEmpty()) BasicText(placeholder, style = TextStyle(fontSize = 17.sp, color = p.l3))
                BasicTextField(
                    value, { value = it },
                    Modifier.fillMaxWidth().then(if (singleLine) Modifier else Modifier.heightIn(min = 120.dp)),
                    textStyle = TextStyle(fontSize = 17.sp, color = p.ink),
                    singleLine = singleLine,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(14.dp)).background(p.fill).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                    BasicText("Отмена", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.ink))
                }
                Box(
                    Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(14.dp)).background(p.tint)
                        .clickable(enabled = value.isNotBlank()) { onDone(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("Готово", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
                }
            }
        }
    }
}

