package app.deixis.phone

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.deixis.core.VideoAction
import app.deixis.core.VideoCommand
import app.deixis.receiver.VlcPlayer
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout

/** Цвета экрана предпросмотра видео: он на чёрном, как зал. */
private object V {
    val ink = Color.White
    val dim = Color.White.copy(alpha = 0.6f)
    val button = Color.White.copy(alpha = 0.14f)
    val go = Color(0xFFBC9361)
    val track = Color.White.copy(alpha = 0.25f)
}

/**
 * Предпросмотр видео отдельным экраном: файл играет на телефоне, без передачи.
 * «В зал» начинает показ с того же места; «С начала» — с нуля.
 */
@Composable
fun VideoPreview(c: Controller, ui: Ui, cue: Cue) {
    val context = LocalContext.current
    // Элемент из библиотеки «вживую»: смена вписывания сразу видна в предпросмотре.
    val lib by c.data.collectAsState()
    val item = lib.items.firstOrNull { it.id == cue.source.itemId }
    var error by remember { mutableStateOf<String?>(null) }
    val player = remember { VlcPlayer(context) { error = it } }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(item?.durationMs ?: 0L) }
    var playing by remember { mutableStateOf(true) }
    var drag by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(cue) {
        val pfd: ParcelFileDescriptor? = runCatching { context.contentResolver.openFileDescriptor(Uri.parse(item?.uri), "r") }.getOrNull()
        if (pfd != null) player.play(pfd.fileDescriptor, item?.title.orEmpty()) else error = "Файл недоступен: возможно, его переместили или удалили"
        onDispose {
            player.stop()
            player.release()
            runCatching { pfd?.close() }
        }
    }
    LaunchedEffect(player) {
        while (true) {
            val st = player.state()
            if (drag == null) pos = st.positionMs
            if (st.durationMs > 0) dur = st.durationMs
            playing = st.playing
            delay(250)
        }
    }
    val close = { ui.full = null }

    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = T.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBtn("down", "Закрыть", V.ink) { close() }
            Txt(item?.title ?: "Видео", T.fBody, V.ink, FontWeight.SemiBold, Modifier.weight(1f), lines = 1)
            Txt("Предпросмотр", T.fSub, V.dim)
        }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).swipes(onDown = close),
            contentAlignment = Alignment.Center,
        ) {
            // Предпросмотр — в пропорциях экрана в зале и с тем же вписыванием, что увидит зал.
            val ratio = LocalScreenRatio.current
            val sw = minOf(maxWidth, maxHeight * ratio)
            val sh = sw / ratio
            val fit = item?.videoFit ?: app.deixis.core.VideoFit.FIT
            val backdrop = item?.videoBackdrop ?: app.deixis.core.VideoBackdrop.BLACK
            val bg = if (backdrop == app.deixis.core.VideoBackdrop.THEME) Color(c.styleFor(cue).background) else Color.Black
            Box(Modifier.size(sw, sh).clip(RoundedCornerShape(4.dp)).background(bg)) {
                val d = androidx.compose.ui.platform.LocalDensity.current
                val wpx = with(d) { sw.toPx() }
                val hpx = with(d) { sh.toPx() }
                val size = player.size.value
                // «Исходный» в предпросмотре — в масштабе экрана зала: как доля его ширины.
                val screenW = c.receiverInfo.value?.screenWidth?.takeIf { it > 0 }?.toFloat() ?: wpx
                val vs = size?.let { if (fit == app.deixis.core.VideoFit.ORIGINAL) it.first * wpx / screenW to it.second * wpx / screenW else it }
                val r = app.deixis.core.videoRect(wpx, hpx, vs?.first ?: wpx, vs?.second ?: hpx, if (vs == null) app.deixis.core.VideoFit.FIT else fit)
                LaunchedEffect(fit) { player.setStretch(fit == app.deixis.core.VideoFit.STRETCH) }
                if (backdrop == app.deixis.core.VideoBackdrop.BLUR) Box(Modifier.fillMaxSize().background(Color(0xFF3A3A3C)))
                AndroidView(
                    factory = { ctx -> VLCVideoLayout(ctx).also { player.attach(it); player.setStretch(fit == app.deixis.core.VideoFit.STRETCH) } },
                    modifier = Modifier.layout { m, _ ->
                        val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(r.width.toInt().coerceAtLeast(1), r.height.toInt().coerceAtLeast(1)))
                        layout(wpx.toInt(), hpx.toInt()) { p.place(r.left.toInt(), r.top.toInt()) }
                    },
                    onRelease = { player.detach() },
                )
            }
            error?.let { Txt(it, T.fSub, V.ink, modifier = Modifier.padding(T.gutter)) }
        }
        // Как видео ляжет на экран зала и чем заполнить поля.
        item?.let { it ->
            Seg(listOf("Вписать", "Заполнить", "Растянуть", "Исходный"), app.deixis.core.VideoFit.entries.indexOf(it.videoFit), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) { i ->
                c.setVideoFit(it.id, app.deixis.core.VideoFit.entries[i], it.videoBackdrop)
            }
            if (it.videoFit == app.deixis.core.VideoFit.FIT || it.videoFit == app.deixis.core.VideoFit.ORIGINAL) {
                Seg(listOf("Поля чёрные", "Размытые", "Цвет темы"), app.deixis.core.VideoBackdrop.entries.indexOf(it.videoBackdrop), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) { i ->
                    c.setVideoFit(it.id, it.videoFit, app.deixis.core.VideoBackdrop.entries[i])
                }
            }
        }
        // Перемотка: палец двигает позицию, на телефоне она применяется при отпускании.
        Row(Modifier.fillMaxWidth().padding(horizontal = T.gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gap)) {
            val frac = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            Txt(clock(if (drag != null) (frac * dur).toLong() else pos), T.fLabel, V.dim)
            BoxWithConstraints(
                Modifier.weight(1f).height(28.dp)
                    .pointerInput(dur) { detectTapGestures { o -> if (dur > 0) player.apply(VideoCommand(VideoAction.SEEK, positionMs = (o.x / size.width * dur).toLong())) } }
                    .pointerInput(dur) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = { drag?.let { player.apply(VideoCommand(VideoAction.SEEK, positionMs = (it * dur).toLong())) }; drag = null },
                            onDragCancel = { drag = null },
                        ) { ch, _ -> drag = (ch.position.x / size.width).coerceIn(0f, 1f) }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(T.rPill)).background(V.track))
                Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(T.rPill)).background(V.go))
                Box(Modifier.offset(x = (maxWidth - 18.dp) * frac).size(18.dp).clip(RoundedCornerShape(T.rPill)).background(V.ink))
            }
            Txt(clock(dur), T.fLabel, V.dim)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VBtn("back10", "−10 с", Modifier.weight(1f)) { player.apply(VideoCommand(VideoAction.SEEK, positionMs = (pos - 10_000).coerceAtLeast(0))) }
            VBtn(if (playing) "pause" else "play", if (playing) "Пауза" else "Пуск", Modifier.weight(1f)) {
                player.apply(VideoCommand(if (playing) VideoAction.PAUSE else VideoAction.PLAY))
            }
            VBtn("fwd10", "+10 с", Modifier.weight(1f)) { player.apply(VideoCommand(VideoAction.SEEK, positionMs = pos + 10_000)) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VBtn("prev", "С начала в зал", Modifier.weight(1f)) {
                player.apply(VideoCommand(VideoAction.PAUSE)); c.goLiveVideo(cue, 0); ui.toast("Видео в зале"); close()
            }
            VBtn("up", "В зал отсюда", Modifier.weight(1f), V.go) {
                player.apply(VideoCommand(VideoAction.PAUSE)); c.goLiveVideo(cue, pos); ui.toast("Видео в зале с ${clock(pos)}"); close()
            }
        }
    }
}

@Composable
private fun VBtn(icon: String, text: String, modifier: Modifier, bg: Color = V.button, onClick: () -> Unit) {
    Row(
        modifier.height(T.btn).clip(RoundedCornerShape(T.rBtn)).background(bg).press(scaleTo = 0.97f, label = text, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, V.ink, T.iconBar)
        Box(Modifier.width(T.gapS))
        Txt(text, T.fPill, V.ink, FontWeight.SemiBold, lines = 1)
    }
}
