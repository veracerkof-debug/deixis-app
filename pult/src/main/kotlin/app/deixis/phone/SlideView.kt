package app.deixis.phone

import app.deixis.core.LogoStyle
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.deixis.core.Mode
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

/** Своя картинка «Логотипа» вместо знака; null — знак. */
val LocalLogoImage = androidx.compose.runtime.staticCompositionLocalOf<app.deixis.core.FileRef?> { null }

/** Зал без картинки слайда: «Чёрный», «Логотип» на чёрном, «Ушёл с экранов». */
private fun Mode.blackStage() = this == Mode.BLACK || this == Mode.LOGO || this == Mode.AWAY

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
                    .clip(RoundedCornerShape(round)).background(if (mode.blackStage()) Color.Black else Color(style.background)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    mode == Mode.BLACK -> {}
                    // Deixis ушёл с экранов: в окне — чёрное и знак выхода, в зале — то, что под Deixis.
                    mode == Mode.AWAY -> Icon("away", Color.White.copy(alpha = AWAY_ALPHA), T.icon)
                    mode == Mode.LOGO -> LogoPreview(logo)
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

/** «Логотип» в превью: своя картинка вписана целиком, иначе знак. */
@Composable
private fun LogoPreview(logo: LogoStyle) {
    val img = LocalLogoImage.current
    val uri = img?.let { LocalBackgrounds.current(it.sha256) }
    if (img != null && uri != null) Thumb(LibItem("logo", Kind.PHOTO, img.name, uri = uri), ThumbKind.PHOTO, 0, ContentScale.Fit) {}
    else LogoFull(logo)
}

private const val AWAY_ALPHA = 0.5f

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
        is Slide.Text -> TextSlideCanvas(slide.text, null, style, drawBackground = false)
        is Slide.Scripture -> ScriptureSlideCanvas(slide.content.copy(style = style), drawBackground = false)
        is Slide.Page -> Thumb(item, ThumbKind.PAGE, slide.index) { PagePlaceholder(slide.index + 1) }
        is Slide.Picture -> Thumb(item, ThumbKind.PHOTO, 0) { Icon("photo", Color.White, T.icon) }
        is Slide.Movie -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Как ляжет в зале: «Заполнить» — с обрезкой, «Растянуть» — во весь кадр, иначе вписано.
            Thumb(item, ThumbKind.VIDEO, 0, when (item?.videoFit) { app.deixis.core.VideoFit.FILL -> ContentScale.Crop; app.deixis.core.VideoFit.STRETCH -> ContentScale.FillBounds; else -> ContentScale.Fit }) {}
            Icon("play", Color.White, T.icon)
        }
        is Slide.Pending -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(T.gapS)) {
            Icon("pres", Color.White, T.icon)
            Txt(L.convertsOnShow, T.fTag, Color.White.copy(alpha = 0.75f))
        }
        is Slide.Screen -> Icon("phone", Color.White, T.icon)
        // Превью отсчёта: сколько он будет идти, в оформлении зала.
        is Slide.Countdown -> TextSlideCanvas(
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
    val platform = LocalHost.current.platform
    val uri = item?.uri
    val key = "$uri#$kind#$index"
    // Состояние produceState не сбрасывается при смене ключа: новая страница грузится всегда, а до тех пор
    // видна прежняя — листание без мигания заглушкой.
    val bmp by produceState(Thumbs.cached(key), key) {
        value = Thumbs.cached(key)
            ?: if (uri != null && (kind != ThumbKind.PAGE || item?.mime == "application/pdf")) Thumbs.load(platform, key, uri, kind, index) else null
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
    /** Кадры в памяти: до 48 МБ, давно не нужные уходят первыми. */
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        var bytes = 0L
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean {
            if (bytes <= CACHE_BYTES) return false
            bytes -= eldest.value.width.toLong() * eldest.value.height * 4
            return true
        }
    }

    @Synchronized fun cached(key: String): ImageBitmap? = cache[key]

    @Synchronized private fun put(key: String, b: ImageBitmap) {
        cache.put(key, b)?.let { cache.bytes -= it.width.toLong() * it.height * 4 }
        cache.bytes += b.width.toLong() * b.height * 4
    }

    suspend fun load(platform: Platform, key: String, uri: String, kind: ThumbKind, index: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        cached(key) ?: runCatching { platform.thumbnail(uri, kind, index, S.thumbWidth) }.getOrNull()?.also { put(key, it) }
    }

    private const val CACHE_BYTES = 48L * 1024 * 1024
}

/** Слайд и элемент, к которому он относится: для превью «Сейчас» и «Предпросмотр». */
@Composable
fun CueView(c: Controller, cue: Cue?, style: SlideStyle, modifier: Modifier = Modifier, mode: Mode = Mode.NORMAL, frame: Frame = Frame.NONE, round: Dp = T.rSlide) {
    // Смена слайда, темы или режима — мягкое перетекание: прежний кадр тает под новым. Жесты — на внешней рамке.
    Box(modifier) {
        androidx.compose.animation.Crossfade(Triple(cue, style, mode), animationSpec = androidx.compose.animation.core.tween(T.mid, easing = T.eOut), label = "cue") { (cu, st, m) ->
            SlideView(cu?.slide, c.item(cu?.source?.itemId), st, Modifier, m, frame = frame, round = round)
        }
    }
}
