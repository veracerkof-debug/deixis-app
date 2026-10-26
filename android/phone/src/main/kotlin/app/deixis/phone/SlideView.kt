package app.deixis.phone

import app.deixis.core.LogoStyle
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
import androidx.compose.foundation.layout.size
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

/**
 * Пропорции экрана в зале (ширина к высоте): превью слайдов в тех же пропорциях, что и вывод.
 * Пока экран не подключён — 16:9. Крайние форматы ограничены, чтобы превью оставались видны в списках.
 */
val LocalScreenRatio = androidx.compose.runtime.staticCompositionLocalOf { 16f / 9f }

fun screenRatio(info: app.deixis.core.ReceiverInfo?): Float {
    val w = info?.screenWidth ?: 0
    val h = info?.screenHeight ?: 0
    return if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(S.MIN_RATIO, S.MAX_RATIO) else 16f / 9f
}

/** Превью слайда: крайние форматы экрана и размер миниатюр файлов. */
private object S {
    const val MIN_RATIO = 0.5f
    const val MAX_RATIO = 3.6f
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

/** Вид заставки «Логотип» для превью: задаётся один раз наверху по настройке. */
val LocalLogoStyle = androidx.compose.runtime.staticCompositionLocalOf { LogoStyle.WORDMARK }

/**
 * Превью слайда в пропорциях вывода. Показывает то же, что увидит зал: оформление, режим,
 * страницу PDF, кадр фото или видео.
 *
 * Место под превью — постоянное (от квадрата до 21:9), а слайд внутри — в настоящих пропорциях экрана:
 * вертикальный экран в зале не растягивает пульт в высоту, сверхширокий — не превращает превью в полоску.
 */
@Composable
fun SlideView(
    slide: Slide?, item: LibItem?, style: SlideStyle, modifier: Modifier = Modifier,
    mode: Mode = Mode.NORMAL, num: Int? = null, frame: Frame = Frame.NONE, round: Dp = T.rSlide,
    /** Вид заставки «Логотип» — какой выбран в настройках. */
    logo: LogoStyle = LocalLogoStyle.current,
) {
    val p = P.current
    val ratio = LocalScreenRatio.current
    val slot = ratio.coerceIn(LocalSlotMin.current.coerceAtMost(SLOT_MAX), SLOT_MAX)
    val label = (slide as? Slide.Text)?.label
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(slot), contentAlignment = Alignment.Center) {
            Box(
                (if (ratio < slot) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()).aspectRatio(ratio)
                    .frame(frame, p.live, if (frame == Frame.PREVIEW_TINT) p.tint else p.l3, T.outline, round)
                    .clip(RoundedCornerShape(round)).background(if (mode == Mode.BLACK) Color.Black else Color(style.background)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    mode == Mode.BLACK -> {}
                    mode == Mode.LOGO -> app.deixis.receiver.LogoFull(logo)
                    mode == Mode.CLEAR || slide == null -> BackgroundImage(style)
                    else -> { BackgroundImage(style); SlideBody(slide, item, style) }
                }
            }
        }
        // Номер и подпись («Припев») — под плиткой, как в Holyrics: не ложатся на текст слайда.
        num?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(T.gapXs)) {
                Txt("$it", T.fNum, p.l2, FontWeight.SemiBold)
                label?.let { l -> Txt(l, T.fNum, p.tintText, FontWeight.SemiBold, lines = 1) }
            }
        }
    }
}

/**
 * Самое узкое место под превью. Обычно квадрат; там, где «Сейчас» и «Предпросмотр» стоят друг под
 * другом в невысокой колонке планшета, раскладка поднимает его, чтобы оба окна поместились целиком.
 */
val LocalSlotMin = androidx.compose.runtime.compositionLocalOf { SLOT_MIN }

/** Место под превью: не уже квадрата и не шире 21:9, что бы ни стояло в зале. */
private const val SLOT_MIN = 1f
private const val SLOT_MAX = 21f / 9f

/** Фото фона темы (только у слайдов с текстом), обрезка по экрану, как на выводе. */
@Composable
private fun BackgroundImage(style: SlideStyle) {
    val ref = style.backgroundImage ?: return
    val uri = LocalBackgrounds.current(ref.sha256) ?: return
    Thumb(LibItem("bg", Kind.PHOTO, ref.name, uri = uri), ThumbKind.PHOTO, 0, ContentScale.Crop) {}
}

@Composable
private fun SlideBody(slide: Slide, item: LibItem?, style: SlideStyle) {
    when (slide) {
        // Текст и Писание — той же раскладкой, что в зале: превью — уменьшенный экран, а не прикидка.
        is Slide.Text -> app.deixis.receiver.TextSlideCanvas(slide.text, null, style, drawBackground = false)
        is Slide.Scripture -> app.deixis.receiver.ScriptureSlideCanvas(slide.content.copy(style = style), drawBackground = false)
        is Slide.Page -> Thumb(item, ThumbKind.PAGE, slide.index) { PagePlaceholder(slide.index + 1) }
        is Slide.Picture -> Thumb(item, ThumbKind.PHOTO, 0) { Icon("photo", Color.White, T.icon) }
        is Slide.Movie -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Как ляжет в зале: «Заполнить» — с обрезкой, «Растянуть» — во весь кадр, иначе вписано.
            Thumb(item, ThumbKind.VIDEO, 0, when (item?.videoFit) { app.deixis.core.VideoFit.FILL -> ContentScale.Crop; app.deixis.core.VideoFit.STRETCH -> ContentScale.FillBounds; else -> ContentScale.Fit }) {}
            Icon("play", Color.White, T.icon)
        }
        is Slide.Pending -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(T.gapS)) {
            Icon("pres", Color.White, T.icon)
            Txt("Переведётся при показе", T.fTag, Color.White.copy(alpha = 0.75f))
        }
        is Slide.Screen -> Icon("phone", Color.White, T.icon)
        // Превью отсчёта: сколько он будет идти, в оформлении зала.
        is Slide.Countdown -> app.deixis.receiver.TextSlideCanvas(
            listOfNotNull(slide.spec.label, slide.spec.title).joinToString("\n"), null, style.copy(color = slide.spec.color ?: style.color), drawBackground = false,
        )
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
private fun Thumb(
    item: LibItem?, kind: ThumbKind, index: Int,
    /** Как в зале: фото, страница и видео вписаны целиком; фон темы и миниатюры в списках — с обрезкой. */
    scale: ContentScale = ContentScale.Fit,
    placeholder: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val uri = item?.uri
    val key = "$uri#$kind#$index"
    // Состояние produceState не сбрасывается при смене ключа: новая страница грузится всегда, а до тех пор
    // видна прежняя — листание без мигания заглушкой.
    val bmp by produceState(Thumbs.cached(key), key) {
        value = Thumbs.cached(key)
            ?: if (uri != null && (kind != ThumbKind.PAGE || item?.mime == "application/pdf")) Thumbs.load(context, key, Uri.parse(uri), kind, index) else null
    }
    val b = bmp
    if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = scale)
    else placeholder()
}

/** У фото, видео и PDF есть что показать вместо значка. */
fun hasThumb(item: LibItem): Boolean = item.uri != null &&
    (item.kind == Kind.PHOTO || item.kind == Kind.VIDEO || item.mime == "application/pdf")

/** Миниатюра файла квадратом [size]: фото, кадр видео, первая страница PDF; пока считается — [placeholder]. */
@Composable
fun ItemThumb(item: LibItem, size: Dp, placeholder: @Composable () -> Unit) {
    val kind = when {
        item.kind == Kind.PHOTO -> ThumbKind.PHOTO
        item.kind == Kind.VIDEO -> ThumbKind.VIDEO
        else -> ThumbKind.PAGE
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(T.rSlide)).background(if (kind == ThumbKind.PAGE) S.pageBg else Color.Black), contentAlignment = Alignment.Center) {
        Thumb(item, kind, 0, ContentScale.Crop, placeholder)
        if (kind == ThumbKind.VIDEO) Icon("play", Color.White, size * 0.4f)
    }
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
