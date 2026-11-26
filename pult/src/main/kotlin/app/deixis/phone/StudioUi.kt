package app.deixis.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import app.deixis.core.SceneBackground
import app.deixis.core.effects.SceneFrames
import app.deixis.core.effects.SceneGlyphs
import app.deixis.core.effects.SceneRenderer
import app.deixis.core.effects.SceneText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ——— Кирпичики студии фона: ползунок, цвет, образец формы, превью ———

/**
 * Ползунок параметра. Перетащить или коснуться — значение; двойное касание — вернуть умолчание ([onReset]).
 * [frac] — положение 0…1; подпись слева, значение справа, справа же — [trailing] (например, «Оживить»).
 */
@Composable
fun ParamSlider(
    label: String, value: String, frac: Float, onFrac: (Float) -> Unit, onReset: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val p = P.current
    val set by rememberUpdatedState(onFrac)
    val reset by rememberUpdatedState(onReset)
    var width by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().padding(vertical = T.gapXs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
            Txt(label, T.fBody, modifier = Modifier.weight(1f), lines = 1)
            Txt(value, T.fSub, p.l2, lines = 1)
            trailing()
        }
        Box(
            Modifier.fillMaxWidth().height(T.seekHeight).onSizeChanged { width = it.width }
                .pointerInput(Unit) { detectTapGestures(onTap = { o -> if (width > 0) set((o.x / width).coerceIn(0f, 1f)) }, onDoubleTap = { reset?.invoke() }) }
                .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume(); if (width > 0) set((change.position.x / width).coerceIn(0f, 1f)) } },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(T.seekTrack).clip(RoundedCornerShape(T.rPill)).background(p.fill2))
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(T.seekTrack).clip(RoundedCornerShape(T.rPill)).background(p.tint))
            val knobPx = with(androidx.compose.ui.platform.LocalDensity.current) { T.seekKnob.toPx() }
            Box(
                Modifier.offset { IntOffset((frac.coerceIn(0f, 1f) * (width - knobPx)).roundToInt(), 0) }
                    .size(T.seekKnob).clip(RoundedCornerShape(T.rPill)).background(p.tint),
            )
        }
    }
}

/** Цвет из палитры сцены или набора: ряд образцов, выбранный обведён. */
@Composable
fun ColorRow(colors: List<Color>, selected: Int, label: (Int) -> String, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(T.gapS), modifier = Modifier.fillMaxWidth().padding(vertical = T.gapXs)) {
        colors.forEachIndexed { i, c -> Swatch(c, i == selected, label(i)) { onPick(i) } }
    }
}

fun hexColor(hex: String): Color = runCatching { Color(0xFF000000L or hex.removePrefix("#").toLong(16)) }.getOrDefault(Color.Gray)

/** Образец формы образа, нарисованный её же контуром. */
@Composable
fun ShapeChip(kind: String, selected: Boolean, label: String, onClick: () -> Unit) {
    val p = P.current
    val ink = p.ink
    Box(
        Modifier.size(T.shapeChip).clip(RoundedCornerShape(T.rCard)).background(p.fill)
            .then(if (selected) Modifier.border(T.outline, p.tint, RoundedCornerShape(T.rCard)) else Modifier)
            .press(label = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val polys = remember(kind) { SceneGlyphs.of(kind) }
        Canvas(Modifier.size(T.shapeChip - T.gap * 2)) {
            val r = size.minDimension / 2f * 0.9f
            val c = Offset(size.width / 2, size.height / 2)
            if (polys == null) { drawCircle(ink, r, c); return@Canvas }
            for (poly in polys) {
                if (poly.carve > 0.0) continue
                val path = Path()
                val pts = poly.pts
                var i = 0
                while (i + 1 < pts.size) {
                    val x = c.x + pts[i].toFloat() * r; val y = c.y + pts[i + 1].toFloat() * r
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    i += 2
                }
                if (poly.closed && poly.strokeW == 0.0) { path.close(); drawPath(path, ink) }
                else drawPath(path, ink, style = Stroke(width = (poly.strokeW.toFloat() * r).coerceAtLeast(2f)))
            }
        }
    }
}

/** Ряд образцов формы по несколько в строке. */
@Composable
fun <E> Grid(items: List<E>, perRow: Int, content: @Composable androidx.compose.foundation.layout.RowScope.(E) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(T.gapS), modifier = Modifier.fillMaxWidth()) {
        items.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(T.gapS), modifier = Modifier.fillMaxWidth()) { row.forEach { content(it) }; repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) } }
        }
    }
}

// ——— Кадры сцены: живое превью и образцы стилей ———

private const val PREVIEW_W = 224
private const val PREVIEW_H = 126
private const val PREVIEW_SOFT = 112
private const val PREVIEW_MS = 66L
private const val THUMB_W = 160
private const val THUMB_H = 90

/**
 * Живое превью сцены: тот же рисовальщик, что на экране зала, в малом размере. Образец текста стоит там, где его поставит
 * слайд (поля по образу). Правка сцены не сбрасывает время: движение продолжается.
 */
@Composable
fun ScenePreview(bg: SceneBackground, platform: Platform, modifier: Modifier = Modifier) {
    val p = P.current
    val frame = remember { mutableStateOf<ImageBitmap?>(null) }
    val live by rememberUpdatedState(bg)
    val clock = remember { doubleArrayOf(0.0, bg.energy.toDouble()) }
    androidx.compose.runtime.LaunchedEffect(bg.key) {
        val scene = SceneText.sceneFor(bg) ?: run { frame.value = null; return@LaunchedEffect }
        withContext(Dispatchers.Default) {
            val r = SceneRenderer(scene, PREVIEW_W, PREVIEW_H, PREVIEW_SOFT)
            r.restore(clock[0], clock[1])
            val px = IntArray(PREVIEW_W * PREVIEW_H)
            var last = System.nanoTime()
            while (isActive) {
                val now = System.nanoTime()
                val dt = ((now - last) / 1e9).coerceAtMost(0.2); last = now
                val b = live
                r.quiet = b.quiet?.toDouble() ?: scene.quiet
                r.advance(dt, b.energy.toDouble())
                r.render(px, null)
                clock[0] = r.time; clock[1] = r.energy
                platform.decodeImage(SceneFrames.bmp(px, PREVIEW_W, PREVIEW_H))?.let { frame.value = it }
                delay(PREVIEW_MS)
            }
        }
    }
    Box(modifier.aspectRatio(T.wide).clip(RoundedCornerShape(T.rSlide)).background(p.fill)) {
        frame.value?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        val m = SceneText.sceneFor(bg)?.let { SceneText.margins(it) }
        val left = (m?.left ?: 6f).coerceAtLeast(1f); val right = (m?.right ?: 6f).coerceAtLeast(1f)
        Row(Modifier.matchParentSize(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(left))
            Txt(L.studioSample, T.fTag, Color.White, FontWeight.SemiBold, Modifier.weight(100f - left - right), lines = 3)
            Spacer(Modifier.weight(right))
        }
    }
}

private val thumbs = HashMap<String, ImageBitmap?>()

/** Образец стиля (один кадр на третьей секунде); считается один раз и держится в памяти. */
@Composable
fun SceneThumb(bg: SceneBackground, platform: Platform, modifier: Modifier = Modifier, aspect: Boolean = true) {
    val p = P.current
    val img by produceState<ImageBitmap?>(null, bg.key) {
        value = synchronized(thumbs) { thumbs[bg.key] } ?: withContext(Dispatchers.Default) {
            val scene = SceneText.sceneFor(bg) ?: return@withContext null
            platform.decodeImage(SceneFrames.bmp(SceneFrames.still(scene, THUMB_W, THUMB_H, bg.energy.toDouble(), 3.0, bg.quiet?.toDouble()), THUMB_W, THUMB_H))
        }?.also { synchronized(thumbs) { thumbs[bg.key] = it } }
    }
    Box((if (aspect) modifier.aspectRatio(T.wide).clip(RoundedCornerShape(T.rSlide)) else modifier).background(p.fill)) {
        img?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}


/** Платформа для кадров сцены в превью слайда; null — превью без живого фона (как раньше). */
val LocalPlatform = androidx.compose.runtime.staticCompositionLocalOf<Platform?> { null }
