package app.deixis.receiver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.viewinterop.AndroidView
import app.deixis.core.ImageContent
import app.deixis.core.Mode
import app.deixis.core.PdfContent
import app.deixis.core.ScriptureContent
import app.deixis.core.ScreenContent
import app.deixis.core.layoutScripture
import app.deixis.core.TextContent
import app.deixis.core.TextStyle as SlideStyle
import app.deixis.core.VideoContent
import app.deixis.core.layoutSlideText
import app.deixis.core.qrMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

val Didot = FontFamily(Font(R.font.gfs_didot))
val NotoSans = FontFamily(Font(R.font.noto_sans_semibold))

/**
 * Экран приёмника. В зал попадает только содержимое: ошибки уходят на пульт,
 * а здесь остаётся чёрный экран.
 */
@Composable
fun ReceiverScreen(
    runtime: ReceiverRuntime,
    player: VlcPlayer,
    showIdleInfo: Boolean = true,
    /** Экран может обрезать края (телевизор): текст отступает дальше от краёв. */
    overscan: Boolean = false,
) {
    val state by runtime.core.state.collectAsState()
    val pin by runtime.pin.collectAsState()
    val address by runtime.address.collectAsState()
    val content = state.content
    val mode = state.mode
    val video = content as? VideoContent

    LaunchedEffect(video?.file?.sha256) {
        runtime.core.reportError(null)
        if (video == null) {
            player.stop()
            runtime.core.reportVideo(null)
            return@LaunchedEffect
        }
        val file = runtime.core.files.file(video.file.sha256)
        if (file == null) runtime.core.reportError("Нет файла «${video.file.name}»")
        else player.play(file, video.file.name, video.loop, video.startMs)
        while (true) {
            delay(500)
            runtime.core.reportVideo(player.state())
        }
    }
    LaunchedEffect(mode, video?.file?.sha256) {
        if (video != null) if (mode == Mode.NORMAL) player.unhide() else player.hide()
    }
    LaunchedEffect(Unit) {
        runtime.core.videoCommands.collect {
            player.apply(it)
            runtime.core.reportVideo(player.state())
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Видеофон — самый нижний слой: играет без перерыва, пока у слайдов тот же фон; смена — плавная.
        val backdropRef = (content as? TextContent)?.style?.backgroundVideo ?: (content as? ScriptureContent)?.style?.backgroundVideo
            ?: (content as? app.deixis.core.CountdownContent)?.style?.backgroundVideo
        androidx.compose.animation.Crossfade(backdropRef?.sha256, label = "backdrop") { sha ->
            if (sha != null) BackdropVideo(runtime, sha)
        }
        if (video != null) VideoSurface(player, video)
        // Трансляция идёт и под «Чёрным» и «Логотипом»: картинка возвращается сразу.
        (content as? ScreenContent)?.let { ScreenSurface(runtime, it) }
        when {
            mode == Mode.BLACK -> Box(Modifier.fillMaxSize().background(Color.Black))
            mode == Mode.LOGO -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { LogoFull() }
            content == null -> if (showIdleInfo) IdleScreen(runtime.core.info.name, pin, address, runtime.pairingUri()) else Unit
            content is TextContent -> TextSlide(runtime, content, clear = mode == Mode.CLEAR, overscan)
            content is ScriptureContent -> ScriptureSlide(runtime, content, clear = mode == Mode.CLEAR, overscan)
            content is app.deixis.core.CountdownContent -> CountdownSlide(runtime, content, clear = mode == Mode.CLEAR, overscan)
            mode == Mode.CLEAR -> Box(Modifier.fillMaxSize().background(Color.Black))
            content is ImageContent -> ImageSlide(runtime, content)
            content is PdfContent -> PdfSlide(runtime, content)
            content is VideoContent -> Unit
            content is ScreenContent -> Unit
        }
        // Слой поверх показа — над всем, включая видео; «Чёрный» и «Заставка» убирают и его.
        if (app.deixis.core.overlaysVisible(mode) && !state.overlays.isEmpty) OverlayLayer(runtime, state.overlays)
    }
}

/**
 * Видео по формуле ядра ([app.deixis.core.videoRect]): вписано (по умолчанию), заполняет, растянуто или в исходном
 * размере. Поля вокруг вписанного кадра — чёрные, цвета темы или размытая копия кадра.
 */
@Composable
private fun VideoSurface(player: VlcPlayer, c: VideoContent) {
    val size = player.size.value
    BoxWithConstraints(Modifier.fillMaxSize().background(if (c.backdrop == app.deixis.core.VideoBackdrop.THEME) Color(c.backdropColor) else Color.Black)) {
        if (c.backdrop == app.deixis.core.VideoBackdrop.BLUR) BlurBackdrop(player)
        val d = LocalDensity.current
        val w = with(d) { maxWidth.toPx() }
        val h = with(d) { maxHeight.toPx() }
        val r = app.deixis.core.videoRect(w, h, size?.first ?: w, size?.second ?: h, if (size == null) app.deixis.core.VideoFit.FIT else c.fit)
        LaunchedEffect(c.fit) { player.setStretch(c.fit == app.deixis.core.VideoFit.STRETCH) }
        AndroidView(
            factory = { ctx -> VLCVideoLayout(ctx).also { player.attach(it); player.setStretch(c.fit == app.deixis.core.VideoFit.STRETCH) } },
            // Окно кадра может быть больше экрана (заполнить, исходный 4K): лишнее срезает край экрана.
            modifier = Modifier.layout { m, _ ->
                val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(r.width.toInt().coerceAtLeast(1), r.height.toInt().coerceAtLeast(1)))
                layout(w.toInt(), h.toInt()) { p.place(r.left.toInt(), r.top.toInt()) }
            },
            onRelease = { player.detach() },
        )
    }
}

/** Видеофон: свой беззвучный проигрыватель по кругу, на весь экран с обрезкой краёв (как фото фона). */
@Composable
private fun BackdropVideo(runtime: ReceiverRuntime, sha: String) {
    val context = LocalContext.current
    val file = remember(sha) { runtime.core.files.file(sha) } ?: return
    val player = remember(sha) { BackdropPlayer(context) }
    androidx.compose.runtime.DisposableEffect(sha) {
        player.play(file)
        onDispose { player.release() }
    }
    AndroidView(factory = { ctx -> VLCVideoLayout(ctx).also { player.attach(it) } }, modifier = Modifier.fillMaxSize(), onRelease = { player.detach() })
}

/** Размытые поля: кадр из файла раз в несколько секунд, уменьшенный до горстки пикселей и растянутый, приглушённый. */
@Composable
private fun BlurBackdrop(player: VlcPlayer) {
    var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val file = player.file
    LaunchedEffect(file) {
        if (file == null) return@LaunchedEffect
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            while (true) {
                val us = player.state().positionMs * 1000
                val big = withContext(Dispatchers.IO) { runCatching { r.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull() }
                if (big != null) {
                    val tw = BLUR_W
                    val th = (BLUR_W * big.height / big.width.coerceAtLeast(1)).coerceAtLeast(1)
                    frame = android.graphics.Bitmap.createScaledBitmap(big, tw, th, true)
                    big.recycle()
                }
                delay(BLUR_MS)
            }
        } finally {
            runCatching { r.release() }
        }
    }
    frame?.let { b ->
        androidx.compose.foundation.Image(
            b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = BLUR_DIM)))
    }
}

private const val BLUR_W = 16
private const val BLUR_MS = 3_000L
private const val BLUR_DIM = 0.45f

/** Поверхность для трансляции экрана с пропорциями источника, по центру на чёрном. */
@Composable
private fun ScreenSurface(runtime: ReceiverRuntime, c: ScreenContent) {
    val scope = rememberCoroutineScope()
    val ratio = if (c.width > 0 && c.height > 0) c.width.toFloat() / c.height else 16f / 9
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    var decoder: ScreenDecoder? = null
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            decoder = ScreenDecoder(runtime.core.stream, h.surface) { runtime.core.reportError(it) }.also { it.start(scope) }
                        }
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            decoder?.stop()
                            decoder = null
                        }
                    })
                }
            },
            modifier = Modifier.aspectRatio(ratio),
        )
    }
}

/** «Логотип»: знак на всю ширину экрана. Пробел трекинга после последней буквы уравновешен слева. */
@Composable
fun LogoFull(color: Color = Color.White) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val d = LocalDensity.current
        val px = with(d) { app.deixis.core.LogoMark.size(maxWidth.toPx(), maxHeight.toPx()) }
        val size = with(d) { px.toSp() }
        val pad = with(d) { (px * 0.18f).toDp() }
        BasicText("ΔΕΙΞΙΣ", Modifier.padding(start = pad), style = TextStyle(fontFamily = Didot, fontSize = size, letterSpacing = 0.18.em, color = color), maxLines = 1, softWrap = false)
    }
}

/** Знак ΔΕΙΞΙΣ. [heightFraction] — размер буквы как доля высоты экрана. */
@Composable
fun Wordmark(heightFraction: Float, color: Color = Color.White) {
    BoxWithConstraints {
        val px = with(LocalDensity.current) { maxHeight.toPx() }
        val size = with(LocalDensity.current) { (px.takeIf { it.isFinite() } ?: 1080f).times(heightFraction).toSp() }
        BasicText("ΔΕΙΞΙΣ", style = TextStyle(fontFamily = Didot, fontSize = size, letterSpacing = 0.18.em, color = color))
    }
}

@Composable
private fun IdleScreen(name: String, pin: String, address: String?, pairingUri: String?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        // Широкий экран: QR слева от PIN, размеры от кадра 16:9 внутри экрана. Квадратный и вертикальный —
        // всё столбиком, размеры от ширины. Знак не шире экрана, длинные строки переносятся.
        val tall = w < h * app.deixis.core.SIDE_BY_SIDE_MIN_ASPECT
        val u = if (tall) minOf(w, h * 9f / 16f) else minOf(h, w * 9f / 16f)
        fun sp(fraction: Float) = with(density) { (u * fraction).toSp() }
        fun dp(fraction: Float): Dp = with(density) { (u * fraction).toDp() }
        val markFraction = minOf(if (tall) 0.16f else 0.12f, w * 0.9f / app.deixis.core.LogoMark.WIDTH_EM / u)
        val dim = Color.White.copy(alpha = 0.6f)
        val pinBlock: @Composable (Alignment.Horizontal) -> Unit = { align ->
            Column(horizontalAlignment = align) {
                BasicText("PIN для подключения", style = TextStyle(fontFamily = NotoSans, fontSize = sp(if (tall) 0.04f else 0.025f), color = dim))
                BasicText(pin.toCharArray().joinToString(" "), style = TextStyle(fontFamily = NotoSans, fontSize = sp(if (tall) 0.11f else 0.07f), color = Color.White, letterSpacing = 0.1.em))
                BasicText(address ?: "Нет подключения к сети", style = TextStyle(fontFamily = NotoSans, fontSize = sp(if (tall) 0.04f else 0.025f), color = dim), maxLines = 1)
            }
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = with(density) { (w * 0.05f).toDp() }),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            BasicText("ΔΕΙΞΙΣ", style = TextStyle(fontFamily = Didot, fontSize = sp(markFraction), letterSpacing = 0.18.em, color = Color.White), maxLines = 1)
            Spacer(Modifier.height(dp(0.04f)))
            BasicText(
                name, style = TextStyle(fontFamily = NotoSans, fontSize = sp(if (tall) 0.06f else 0.04f), color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center),
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(dp(0.06f)))
            if (tall) {
                if (pairingUri != null) { QrCode(pairingUri, Modifier.size(dp(0.5f))); Spacer(Modifier.height(dp(0.06f))) }
                pinBlock(Alignment.CenterHorizontally)
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                if (pairingUri != null) {
                    QrCode(pairingUri, Modifier.size(dp(0.2f)))
                    Spacer(Modifier.width(dp(0.04f)))
                }
                pinBlock(Alignment.Start)
            }
            Spacer(Modifier.height(dp(0.08f)))
            BasicText(
                if (pairingUri != null) "Наведите камеру телефона на QR-код — откроется пульт, приложение не нужно"
                else "Подключите приставку к сети Wi-Fi, чтобы появился QR-код для телефона",
                style = TextStyle(fontFamily = NotoSans, fontSize = sp(if (tall) 0.035f else 0.025f), color = Color.White.copy(alpha = 0.45f), textAlign = TextAlign.Center),
            )
        }
    }
}

@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) { qrMatrix(text) }
    Canvas(modifier.background(Color.White)) {
        val pad = size.width / 12f
        val cell = (size.width - 2 * pad) / matrix.size
        for (r in matrix.indices) for (c in matrix[r].indices) if (matrix[r][c]) {
            drawRect(Color.Black, Offset(pad + c * cell, pad + r * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@Composable
private fun TextSlide(runtime: ReceiverRuntime, c: TextContent, clear: Boolean, overscan: Boolean) {
    val context = LocalContext.current
    val typeface = remember { context.resources.getFont(R.font.noto_sans_semibold) }
    val bg = rememberBackground(runtime, c.style)
    Canvas(Modifier.fillMaxSize()) {
        drawStyleBackground(c.style, bg)
        if (clear) return@Canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface }
        val layout = layoutSlideText(
            c.text, c.ref, size.width, size.height, c.style,
            measure = { s, sz -> paint.textSize = sz; paint.measureText(s) },
            ascent = { sz -> paint.textSize = sz; -paint.fontMetrics.ascent },
            descent = { sz -> paint.textSize = sz; paint.fontMetrics.descent },
            overscan = overscan,
        )
        paint.textSize = layout.fontSize
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            layout.block?.let { b -> drawBox(nc, c.style, b.left, b.top, b.right, b.bottom, layout.fontSize) }
            for (line in layout.lines) drawStyledText(nc, line.text, line.x, line.baseline, layout.fontSize, paint, c.style)
            layout.ref?.let {
                val refPaint = Paint(paint).apply {
                    textSize = layout.refFontSize
                    clearShadowLayer()
                    alpha = 190
                    textAlign = Paint.Align.RIGHT
                }
                nc.drawText(it, layout.refRight, layout.refBaseline, refPaint)
            }
        }
    }
}

@Composable
private fun ScriptureSlide(runtime: ReceiverRuntime, c: ScriptureContent, clear: Boolean, overscan: Boolean) {
    val context = LocalContext.current
    val typeface = remember { context.resources.getFont(R.font.noto_sans_semibold) }
    val bg = rememberBackground(runtime, c.style)
    Canvas(Modifier.fillMaxSize()) {
        drawStyleBackground(c.style, bg)
        if (clear) return@Canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface }
        val lines = layoutScripture(
            c, size.width, size.height,
            measure = { s, sz -> paint.textSize = sz; paint.measureText(s) },
            ascent = { sz -> paint.textSize = sz; -paint.fontMetrics.ascent },
            descent = { sz -> paint.textSize = sz; paint.fontMetrics.descent },
            overscan = overscan,
        )
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            if (c.style.box != null && lines.isNotEmpty()) {
                val size = lines.filter { !it.ref }.maxOfOrNull { it.size } ?: lines.first().size
                val right = lines.maxOf { paint.textSize = it.size; it.x + paint.measureText(it.text) }
                val pad = size * 0.35f
                drawBox(nc, c.style, lines.minOf { it.x } - pad, lines.minOf { it.baseline - it.size } - pad * 0.6f, right + pad, lines.maxOf { it.baseline + it.size * 0.3f } + pad * 0.6f, size)
            }
            for (l in lines) {
                if (l.ref) {
                    paint.textSize = l.size
                    paint.color = c.style.color.toInt()
                    paint.clearShadowLayer()
                    paint.alpha = 190
                    nc.drawText(l.text, l.x, l.baseline, paint)
                } else {
                    drawStyledText(nc, l.text, l.x, l.baseline, l.size, paint, c.style)
                }
            }
        }
    }
}

/**
 * Обратный отсчёт от часов этого экрана: с долями секунды — каждый кадр, иначе несколько раз в секунду.
 * Цифры — в ячейках одной ширины, после нуля — свой цвет; подпись сверху или снизу.
 */
@Composable
private fun CountdownSlide(runtime: ReceiverRuntime, c: app.deixis.core.CountdownContent, clear: Boolean, overscan: Boolean) {
    val context = LocalContext.current
    val sans = remember { context.resources.getFont(R.font.noto_sans_semibold) }
    val serif = remember { runCatching { context.resources.getFont(R.font.gfs_didot) }.getOrDefault(sans) }
    val bg = rememberBackground(runtime, c.style)
    val now by produceState(System.currentTimeMillis(), c) {
        while (true) {
            if (c.decimals > 0 && c.running) androidx.compose.runtime.withFrameMillis { } else delay(app.deixis.core.countdownFrameMs(c))
            value = System.currentTimeMillis()
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        drawStyleBackground(c.style, bg)
        val face = app.deixis.core.countdownFace(c, now)
        if (clear || face.hidden) return@Canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = if (c.font == "serif") serif else sans }
        val text = if (c.style.caps) face.text.uppercase() else face.text
        val l = app.deixis.core.layoutCountdown(
            c, text, size.width, size.height,
            measure = { s, sz -> paint.textSize = sz; paint.measureText(s) },
            ascent = { sz -> paint.textSize = sz; -paint.fontMetrics.ascent },
            descent = { sz -> paint.textSize = sz; paint.fontMetrics.descent },
            overscan = overscan,
        )
        val digits = if (face.overtime) c.style.copy(color = c.overtimeColor) else c.style
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            for ((ch, x) in l.cells) drawStyledText(nc, ch, x, l.baseline, l.size, paint, digits)
            c.label?.takeIf { it.isNotBlank() }?.let { label ->
                paint.typeface = sans
                drawStyledText(nc, if (c.style.caps) label.uppercase() else label, l.labelX, l.labelBaseline, l.labelSize, paint, c.style)
            }
        }
    }
}

/** Картинка фона оформления, уменьшенная под экран; null — фона-картинки нет или он не читается. */
@Composable
private fun rememberBackground(runtime: ReceiverRuntime, style: SlideStyle): Bitmap? {
    val ref = style.backgroundImage ?: return null
    val metrics = LocalContext.current.resources.displayMetrics
    val bitmap by produceState<Bitmap?>(null, ref.sha256) {
        value = withContext(Dispatchers.IO) {
            runCatching { runtime.core.files.file(ref.sha256)?.let { decodeSampled(it, metrics.widthPixels, metrics.heightPixels) } }.getOrNull()
        }
    }
    return bitmap
}

/** Цвет фона, поверх — картинка во весь экран с обрезкой краёв. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStyleBackground(style: SlideStyle, bg: Bitmap?) {
    // Под слайдом видеофон: слайд прозрачный, фон — кадры видео.
    if (style.backgroundVideo != null) return
    drawRect(Color(style.background.toInt()))
    bg ?: return
    val scale = maxOf(size.width / bg.width, size.height / bg.height)
    val w = bg.width * scale
    val h = bg.height * scale
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawBitmap(bg, null, android.graphics.RectF((size.width - w) / 2, (size.height - h) / 2, (size.width + w) / 2, (size.height + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
    }
}

internal fun drawBox(nc: android.graphics.Canvas, style: SlideStyle, l: Float, t: Float, r: Float, b: Float, size: Float) {
    val color = style.box ?: return
    nc.drawRoundRect(l, t, r, b, size * 0.1f, size * 0.1f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toInt() })
}

/** Строка слайда с обводкой и тенью цветов оформления. */
internal fun drawStyledText(nc: android.graphics.Canvas, text: String, x: Float, baseline: Float, size: Float, paint: Paint, style: SlideStyle) {
    paint.textSize = size
    paint.clearShadowLayer()
    if (style.outline) {
        val stroke = Paint(paint).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = size * style.outlineWidth
            strokeJoin = Paint.Join.ROUND
            color = style.outlineColor.toInt()
        }
        nc.drawText(text, x, baseline, stroke)
    }
    paint.color = style.color.toInt()
    if (style.shadow) paint.setShadowLayer(size * 0.08f, 0f, size * 0.05f, style.shadowColor.toInt())
    nc.drawText(text, x, baseline, paint)
}

@Composable
private fun ImageSlide(runtime: ReceiverRuntime, c: ImageContent) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val bitmap by produceState<Bitmap?>(null, c.file.sha256, w, h) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val file = runtime.core.files.file(c.file.sha256) ?: error("Нет файла «${c.file.name}»")
                    decodeSampled(file, w, h) ?: error("Формат «${c.file.name}» не поддерживается")
                }.onFailure { runtime.core.reportError(it.message) }.getOrNull()
            }
        }
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

@Composable
private fun PdfSlide(runtime: ReceiverRuntime, c: PdfContent) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val bitmap by produceState<Bitmap?>(null, c.file.sha256, c.page, w, h) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val file = runtime.core.files.file(c.file.sha256) ?: error("Нет файла «${c.file.name}»")
                    renderPdfPage(file, c.page, w, h)
                }.onFailure { runtime.core.reportError(it.message ?: "Не удалось открыть PDF") }.getOrNull()
            }
        }
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

/** Картинка уменьшается при чтении: фото с телефона в 50 Мп не должно занять всю память приставки. */
fun decodeSampled(file: File, maxW: Int, maxH: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

fun renderPdfPage(file: File, page: Int, maxW: Int, maxH: Int): Bitmap =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            require(page in 0 until renderer.pageCount) { "В «${file.name}» нет страницы ${page + 1}" }
            renderer.openPage(page).use { p ->
                val scale = minOf(maxW.toFloat() / p.width, maxH.toFloat() / p.height)
                val bmp = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }
