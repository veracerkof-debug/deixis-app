package app.deixis.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.deixis.core.Mode
import app.deixis.receiver.Didot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.deixis.core.TextStyle as SlideStyle

/** Пропорции превью слайда — доли его высоты, как на выводе. */
private object S {
    const val ratio = 16f / 9f
    const val pad = 0.05f
    const val lineHeight = 1.2f
    const val maxText = 0.11f
    const val fill = 0.8f
    const val charWidth = 0.55f
    const val ref = 0.045f
    const val thumbWidth = 480
    val pageBg = Color(0xFF2A2A2C)
    val paper = Color(0xFFFFFFFF)
    val paperLine = Color(0xFFDDDDDD)
}

/** Рамка превью: сплошная «В зале», пунктир — предпросмотр. */
enum class Frame { NONE, LIVE, PREVIEW, PREVIEW_TINT }

fun Modifier.frame(frame: Frame, live: Color, pre: Color, width: Dp, radius: Dp): Modifier = if (frame == Frame.NONE) this else drawWithContent {
    drawContent()
    val w = width.toPx()
    val gap = w * 2
    val color = if (frame == Frame.LIVE) live else pre
    drawRoundRect(
        color, topLeft = Offset(-gap, -gap), size = androidx.compose.ui.geometry.Size(size.width + 2 * gap, size.height + 2 * gap),
        cornerRadius = CornerRadius(radius.toPx() + gap),
        style = Stroke(w, pathEffect = if (frame == Frame.LIVE) null else PathEffect.dashPathEffect(floatArrayOf(w * 3, w * 2))),
    )
}

/**
 * Превью слайда в пропорциях вывода. Показывает то же, что увидит зал: оформление, режим,
 * страницу PDF, кадр фото или видео.
 */
@Composable
fun SlideView(
    slide: Slide?, item: LibItem?, style: SlideStyle, modifier: Modifier = Modifier,
    mode: Mode = Mode.NORMAL, num: Int? = null, frame: Frame = Frame.NONE, round: Dp = T.rSlide,
) {
    val p = P.current
    Box(
        modifier.fillMaxWidth().aspectRatio(S.ratio).frame(frame, p.live, if (frame == Frame.PREVIEW_TINT) p.tint else p.l3, T.outline, round)
            .clip(RoundedCornerShape(round)).background(if (mode == Mode.BLACK) Color.Black else Color(style.background)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            mode == Mode.BLACK -> {}
            mode == Mode.LOGO -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val d = LocalDensity.current
                val px = with(d) { app.deixis.core.LogoMark.size(maxWidth.toPx(), maxHeight.toPx()) }
                BasicText(
                    "ΔΕΙΞΙΣ", Modifier.padding(start = with(d) { (px * 0.18f).toDp() }),
                    style = TextStyle(fontFamily = Didot, fontSize = with(d) { px.toSp() }, letterSpacing = 0.18.em, color = Color.White), maxLines = 1, softWrap = false,
                )
            }
            mode == Mode.CLEAR || slide == null -> {}
            else -> SlideBody(slide, item, style)
        }
        num?.let {
            Txt("$it", T.fNum, Color.White.copy(alpha = 0.75f), FontWeight.SemiBold, Modifier.align(Alignment.TopStart).padding(start = T.gapS, top = 4.dp))
        }
    }
}

@Composable
private fun SlideBody(slide: Slide, item: LibItem?, style: SlideStyle) {
    when (slide) {
        is Slide.Text -> SlideText(listOf(slide.text), null, style)
        is Slide.Scripture -> {
            val c = slide.content
            if (c.sideBySide && c.blocks.size > 1) Row(Modifier.fillMaxSize()) {
                c.blocks.forEach { b -> Box(Modifier.weight(1f).fillMaxHeight()) { SlideText(listOf(b.text), b.ref, style) } }
            } else SlideText(c.blocks.map { it.text }, c.ref ?: c.blocks.firstOrNull()?.ref, style)
        }
        is Slide.Page -> Thumb(item, ThumbKind.PAGE, slide.index) { PagePlaceholder(slide.index + 1) }
        is Slide.Picture -> Thumb(item, ThumbKind.PHOTO, 0) { Icon("photo", Color.White, T.icon) }
        is Slide.Movie -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Thumb(item, ThumbKind.VIDEO, 0) {}
            Icon("play", Color.White, T.icon)
        }
        is Slide.Pending -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(T.gapS)) {
            Icon("pres", Color.White, T.icon)
            Txt("Переведётся при показе", T.fTag, Color.White.copy(alpha = 0.75f))
        }
        is Slide.Screen -> Icon("phone", Color.White, T.icon)
    }
}

/** Текст слайда: размер подбирается под превью так же, как на выводе — до полей и предела из оформления. */
@Composable
private fun SlideText(paras: List<String>, ref: String?, style: SlideStyle) {
    val color = Color(style.color)
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val outerW = maxWidth
      val outerH = maxHeight
      BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = outerW * S.pad, vertical = outerH * S.pad), contentAlignment = Alignment.Center) {
        val text = paras.joinToString("\n").let { if (style.caps) it.uppercase() else it }
        val lines = text.lines().size.coerceAtLeast(1)
        val longest = text.lines().maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
        val h = maxHeight
        val byLimit = h * (S.maxText * style.sizePercent / 20f)
        val byHeight = h * S.fill / (lines * S.lineHeight)
        val byWidth = maxWidth / (longest * S.charWidth)
        val size = minOf(byLimit, byHeight, byWidth)
        val density = LocalDensity.current
        val fs = with(density) { size.toSp() }
        val base = TextStyle(
            fontSize = fs, lineHeight = (S.lineHeight).em, fontWeight = FontWeight.SemiBold, textAlign = when (style.align) {
                app.deixis.core.HAlign.LEFT -> TextAlign.Left
                app.deixis.core.HAlign.RIGHT -> TextAlign.Right
                else -> TextAlign.Center
            },
        )
        val shadow = if (style.shadow) Shadow(Color(style.shadowColor), Offset(0f, with(density) { (size * 0.06f).toPx() }), with(density) { (size * 0.2f).toPx() }) else null
        Box(contentAlignment = Alignment.Center) {
            if (style.outline) BasicText(
                text, style = base.copy(color = Color(style.outlineColor), drawStyle = Stroke(with(density) { (size * style.outlineWidth * 2).toPx() })),
            )
            BasicText(text, style = base.copy(color = color, shadow = shadow))
        }
        ref?.let {
            val rs = with(density) { (h * S.ref * 2).toSp() }
            BasicText(it, Modifier.align(Alignment.BottomEnd), style = TextStyle(fontSize = rs, color = color.copy(alpha = 0.75f), fontWeight = FontWeight.Medium))
        }
      }
    }
}

@Composable
private fun PagePlaceholder(n: Int) {
    Box(Modifier.fillMaxSize().background(S.pageBg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxHeight(0.86f).aspectRatio(1f / 1.414f).clip(RoundedCornerShape(2.dp)).background(S.paper).padding(T.gapS),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Txt("$n", T.fNum, Color.Black, FontWeight.SemiBold)
            listOf(1f, 0.8f, 1f, 0.6f, 1f, 0.7f).forEach { Box(Modifier.fillMaxWidth(it).height(3.dp).background(S.paperLine)) }
        }
    }
}

// ——— Миниатюры файлов ———

enum class ThumbKind { PAGE, PHOTO, VIDEO }

/** Кадр файла с телефона: страница PDF, фото, кадр видео. Считается один раз и держится в памяти. */
@Composable
private fun Thumb(item: LibItem?, kind: ThumbKind, index: Int, placeholder: @Composable () -> Unit) {
    val context = LocalContext.current
    val uri = item?.uri
    val key = "$uri#$kind#$index"
    val bmp by produceState(Thumbs.cached(key), key) {
        if (value == null && uri != null && (kind != ThumbKind.PAGE || item?.mime == "application/pdf")) value = Thumbs.load(context, key, Uri.parse(uri), kind, index)
    }
    val b = bmp
    if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = if (kind == ThumbKind.PAGE) ContentScale.Fit else ContentScale.Crop)
    else placeholder()
}

object Thumbs {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(key: String): ImageBitmap? = cache.get(key)

    suspend fun load(context: Context, key: String, uri: Uri, kind: ThumbKind, index: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(key) ?: runCatching {
            when (kind) {
                ThumbKind.PAGE -> page(context, uri, index)
                ThumbKind.PHOTO -> photo(context, uri)
                ThumbKind.VIDEO -> video(context, uri)
            }
        }.getOrNull()?.asImageBitmap()?.also { cache.put(key, it) }
    }

    private fun page(context: Context, uri: Uri, index: Int): Bitmap? {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return fd.use {
            PdfRenderer(it).use { r ->
                if (index !in 0 until r.pageCount) null
                else r.openPage(index).use { pg ->
                    val w = S.thumbWidth
                    val h = (w.toFloat() * pg.height / pg.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(android.graphics.Color.WHITE)
                        pg.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    private fun photo(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { return context.contentResolver.loadThumbnail(uri, Size(S.thumbWidth, S.thumbWidth), null) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                    val scale = (info.size.width / S.thumbWidth).coerceAtLeast(1)
                    d.setTargetSize(info.size.width / scale, info.size.height / scale)
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= S.thumbWidth) sample *= 2
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    private fun video(context: Context, uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val frame = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val h = (S.thumbWidth.toFloat() * frame.height / frame.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(frame, S.thumbWidth, h, true).also { if (it !== frame) frame.recycle() }
        } finally {
            runCatching { r.release() }
        }
    }
}

/** Слайд и элемент, к которому он относится: для превью «Сейчас» и «Предпросмотр». */
@Composable
fun CueView(c: Controller, cue: Cue?, style: SlideStyle, modifier: Modifier = Modifier, mode: Mode = Mode.NORMAL, frame: Frame = Frame.NONE, round: Dp = T.rSlide) {
    SlideView(cue?.slide, c.item(cue?.source?.itemId), style, modifier, mode, frame = frame, round = round)
}
