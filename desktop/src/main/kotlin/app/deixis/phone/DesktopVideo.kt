package app.deixis.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import app.deixis.desktop.VideoProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/**
 * Предпросмотр видео на компьютере: кадр с места, куда поставлен ползунок, вписывание как в зале и
 * «С начала» / «С этого места». Само видео играет в зале — пульт его не проигрывает, только выбирает место.
 */
@Composable
fun VideoPreview(c: Controller, ui: Ui, cue: Cue) {
    val lib by c.data.collectAsState()
    val item = lib.items.firstOrNull { it.id == cue.source.itemId }
    val file = remember(item?.uri) { item?.uri?.let { u -> runCatching { File(URI(u)) }.getOrNull() }?.takeIf { it.isFile } }
    val dur = item?.durationMs?.takeIf { it > 0 } ?: 0L
    var pos by remember { mutableStateOf(0L) }
    val frame by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file, pos / FRAME_STEP_MS) {
        value = file?.let { f -> withContext(Dispatchers.IO) { VideoProbe.frame(f, pos, FRAME_WIDTH)?.toComposeImageBitmap() } } ?: value
    }
    val close = { ui.full = null }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = T.gap, vertical = T.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
            IconBtn("down", L.close, V.ink) { close() }
            Txt(item?.title ?: L.tVideo, T.fBody, V.ink, FontWeight.SemiBold, Modifier.weight(1f), lines = 1)
            Txt(L.preview, T.fSub, V.dim)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = T.gap), contentAlignment = Alignment.Center) {
            val ratio = LocalScreenRatio.current
            val sw = minOf(maxWidth, maxHeight * ratio)
            val sh = sw / ratio
            val fit = item?.videoFit ?: app.deixis.core.VideoFit.FIT
            val scale = when (fit) {
                app.deixis.core.VideoFit.FILL -> ContentScale.Crop
                app.deixis.core.VideoFit.STRETCH -> ContentScale.FillBounds
                else -> ContentScale.Fit
            }
            Box(Modifier.size(sw, sh).clip(RoundedCornerShape(T.rSlide)).background(Color.Black), contentAlignment = Alignment.Center) {
                frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = scale) }
                    ?: Txt(if (file == null) L.fileUnavailableMoved else L.loading, T.fSub, V.dim)
            }
        }
        item?.let { it ->
            Seg(listOf(L.fit, L.fill, L.stretch, L.original), app.deixis.core.VideoFit.entries.indexOf(it.videoFit), Modifier.fillMaxWidth().padding(horizontal = T.gap, vertical = T.gapXs)) { i ->
                c.setVideoFit(it.id, app.deixis.core.VideoFit.entries[i], it.videoBackdrop)
            }
            if (it.videoFit == app.deixis.core.VideoFit.FIT || it.videoFit == app.deixis.core.VideoFit.ORIGINAL) {
                Seg(listOf(L.barsBlack, L.blurred, L.themeColor), app.deixis.core.VideoBackdrop.entries.indexOf(it.videoBackdrop), Modifier.fillMaxWidth().padding(horizontal = T.gap, vertical = T.gapXs)) { i ->
                    c.setVideoFit(it.id, it.videoFit, app.deixis.core.VideoBackdrop.entries[i])
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = T.gutter, vertical = T.gapS), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gap)) {
            val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            Txt(clock(pos), T.fLabel, V.dim)
            BoxWithConstraints(
                Modifier.weight(1f).height(T.seekHeight)
                    .pointerInput(dur) { detectTapGestures { o -> if (dur > 0) pos = (o.x / size.width * dur).toLong() } }
                    .pointerInput(dur) { detectHorizontalDragGestures { ch, _ -> if (dur > 0) pos = (ch.position.x / size.width * dur).toLong().coerceIn(0, dur) } },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(T.seekTrack).clip(RoundedCornerShape(T.rPill)).background(V.track))
                Box(Modifier.fillMaxWidth(frac).height(T.seekTrack).clip(RoundedCornerShape(T.rPill)).background(V.go))
                Box(Modifier.offset(x = (maxWidth - T.seekKnob) * frac).size(T.seekKnob).clip(RoundedCornerShape(T.rPill)).background(V.ink))
            }
            Txt(clock(dur), T.fLabel, V.dim)
        }
        Row(Modifier.fillMaxWidth().padding(start = T.gap, end = T.gap, top = T.gapS, bottom = T.gap), horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
            Btn(L.fromStart, Modifier.weight(1f), icon = "prev") { c.goLiveVideo(cue, 0); ui.toast(L.videoInHall); close() }
            Btn(L.fromHere, Modifier.weight(1f), icon = "up", kind = BtnKind.TINT) { c.goLiveVideo(cue, pos); ui.toast(L.videoLiveFrom(clock(pos))); close() }
        }
    }
}

private const val FRAME_STEP_MS = 500L
private const val FRAME_WIDTH = 960
