package app.deixis.phone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs

enum class AppTab(val title: String, val icon: String) {
    SHOW("Показ", "show"), BIBLE("Библия", "bible"), LIBRARY("Библиотека", "lib"), SETLISTS("Сетлисты", "list"), DEVICES("Устройства", "devs"),
}

/** Шторка: заголовок и содержимое. */
class SheetSpec(val title: String, val content: @Composable ColumnScope.() -> Unit)

/** Слайд во весь экран: «Сейчас», «Предпросмотр» или элемент библиотеки со своим указателем. */
sealed interface FullSpec {
    data object Live : FullSpec
    data object Preview : FullSpec
    data class Local(val cue: Cue) : FullSpec
    /** Предпросмотр видео: играет на телефоне, «В зал» — с того же места. */
    data class Video(val cue: Cue) : FullSpec
}

internal data class ToastMsg(val id: Long, val text: String)

/** Состояние экрана пульта: вкладка, шторка, полноэкранный слайд, тосты. Вывод от него не зависит. */
class Ui {
    var tab by mutableStateOf(AppTab.SHOW)
    private val stack = mutableStateListOf<SheetSpec>()
    val sheet: SheetSpec? get() = stack.lastOrNull()
    var full by mutableStateOf<FullSpec?>(null)
    internal val toasts = mutableStateListOf<ToastMsg>()
    private var toastId = 0L

    /** Развёрнутый элемент сетлиста. */
    var open by mutableStateOf<String?>(null)
    var listSeg by mutableStateOf(0)
    var libSeg by mutableStateOf<Kind?>(null)
    var libQuery by mutableStateOf("")
    /** Окно Holyrics (церковный раздел). */
    var holyrics by mutableStateOf(false)
    /** Тема, которую тащат из панели тем на песню или слайд. */
    val themeDrag = ThemeDrag()

    fun sheet(title: String, content: @Composable ColumnScope.() -> Unit) { stack.clear(); stack += SheetSpec(title, content) }
    /** Шторка поверх шторки (например PIN из «Подключить»): закрытие возвращает к прежней. */
    fun push(title: String, content: @Composable ColumnScope.() -> Unit) { stack += SheetSpec(title, content) }
    fun close() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun closeAll() = stack.clear()

    fun toast(text: String) { toasts += ToastMsg(++toastId, text) }
}

val LocalUi = staticCompositionLocalOf { Ui() }

@Composable
fun DeixisPhone(controller: Controller, holyrics: HolyricsSource, ui: Ui) {
    val settings by controller.settings.collectAsState()
    val live by controller.live.collectAsState()
    val outputs by controller.outputs.collectAsState()
    // Тема пульта своя и сама не меняется; тёмная на время показа — только если её включили в настройках.
    val showing = live != null && outputs.isNotEmpty()
    val theme = if (settings.darkDuringShow && showing && (settings.theme == "light" || settings.theme == "paper")) "dark" else settings.theme
    LaunchedEffect(controller) { controller.messages.collect { ui.toast(it) } }
    val info by controller.receiverInfo.collectAsState()
    val testRatio by controller.testRatio.collectAsState()
    CompositionLocalProvider(P provides paletteOf(theme), LocalScreenRatio provides (testRatio ?: screenRatio(info)), LocalUi provides ui, LocalActs provides rememberActs(controller, ui), LocalBackgrounds provides { sha -> controller.localFile(sha) }, LocalLogoStyle provides settings.logo) {
        Root(controller, holyrics, ui)
    }
}

@Composable
private fun Root(c: Controller, h: HolyricsSource, ui: Ui) {
    val p = P.current
    val settings by c.settings.collectAsState()
    LaunchedEffect(settings.church) { if (!settings.church && ui.tab == AppTab.BIBLE) ui.tab = AppTab.SHOW }
    val needPin by c.needPin.collectAsState()
    LaunchedEffect(needPin) { needPin?.let { t -> ui.push("PIN с экрана") { PinSheet(c, t) } } }
    UpdatesEffects(c, ui)

    // «Библия» — церковный раздел: без него вкладки нет.
    val tabs = remember(settings.church) { AppTab.entries.filter { settings.church || it != AppTab.BIBLE } }
    val pager = rememberPagerState(initialPage = tabs.indexOf(ui.tab).coerceAtLeast(0)) { tabs.size }
    Box(Modifier.fillMaxSize().background(p.bg)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val rail = maxWidth >= 1024.dp
            if (rail) Row(Modifier.fillMaxSize()) {
                Tabs(ui, tabs, pager, vertical = true)
                Box(Modifier.width(T.hair).fillMaxHeight().background(p.sep))
                Box(Modifier.weight(1f)) { Pages(c, h, ui, tabs, pager) }
            } else Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { Pages(c, h, ui, tabs, pager) }
                Tabs(ui, tabs, pager, vertical = false)
            }
        }
        FullHost(c, ui)
        SheetHost(ui)
        Toasts(ui)
        ThemeGhost(ui.themeDrag)
    }
    if (ui.holyrics) HolyricsDialog(h) { ui.holyrics = false }
    BackHandler(enabled = ui.sheet != null || ui.full != null) {
        if (ui.sheet != null) ui.close() else ui.full = null
    }
}

/**
 * Вкладки листаются смахиванием, как страницы: соседняя выезжает за пальцем. Касание вкладки или переход
 * из кода (ui.tab) доводит страницу плавно. Пока тему несут из панели, страницы не листаются.
 */
@Composable
private fun Pages(c: Controller, h: HolyricsSource, ui: Ui, tabs: List<AppTab>, pager: PagerState) {
    LaunchedEffect(ui.tab, tabs) {
        val i = tabs.indexOf(ui.tab)
        if (i >= 0 && i != pager.targetPage) pager.animateScrollToPage(i, animationSpec = tween(T.slow, easing = T.eOut))
    }
    LaunchedEffect(pager, tabs) {
        snapshotFlow { pager.settledPage }.collect { i -> tabs.getOrNull(i)?.let { if (ui.tab != it) ui.tab = it } }
    }
    HorizontalPager(pager, Modifier.fillMaxSize(), key = { tabs[it] }, userScrollEnabled = ui.themeDrag.dragged == null) { i ->
        Screen(c, h, ui, tabs[i])
    }
}

@Composable
private fun Screen(c: Controller, h: HolyricsSource, ui: Ui, tab: AppTab) {
    when (tab) {
        AppTab.SHOW -> ShowScreen(c, h, ui)
        AppTab.BIBLE -> BibleScreen(c, ui)
        AppTab.LIBRARY -> LibraryScreen(c, h, ui)
        AppTab.SETLISTS -> SetlistsScreen(c, ui)
        AppTab.DEVICES -> DevicesScreen(c, h, ui)
    }
}

@Composable
private fun Tabs(ui: Ui, tabs: List<AppTab>, pager: PagerState, vertical: Boolean) {
    val p = P.current
    val content: @Composable (AppTab, Modifier) -> Unit = { t, m ->
        // Отметка идёт за страницей, пока её листают пальцем, а не ждёт остановки.
        val on = tabs.getOrNull(pager.targetPage) == t
        val color by animateColorAsState(if (on) p.tint else p.l2, tween(T.mid, easing = T.eOut), label = "tab")
        val lift by animateFloatAsState(if (on) T.tabLift else 1f, tween(T.mid, easing = T.eSpring), label = "tabLift")
        Column(
            m.heightIn(min = if (vertical) 64.dp else T.tabMin).clickable(role = Role.Tab) { ui.tab = t; ui.closeAll() }.padding(top = 7.dp, bottom = T.gapS),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(T.gapXs),
        ) {
            Box(Modifier.scale(lift)) { Icon(t.icon, color) }
            Txt(t.title, T.fTab, color, lines = 1)
        }
    }
    if (vertical) Column(Modifier.width(92.dp).fillMaxHeight().background(p.cell).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(T.gapS)) {
        tabs.forEach { content(it, Modifier.fillMaxWidth()) }
    } else Column(Modifier.fillMaxWidth().background(p.cell)) {
        Box(Modifier.fillMaxWidth().height(T.hair).background(p.sep))
        Row(Modifier.fillMaxWidth()) { tabs.forEach { content(it, Modifier.weight(1f)) } }
    }
}

// ——— Шторки ———

@Composable
private fun SheetHost(ui: Ui) {
    val p = P.current
    val spec = ui.sheet
    // Последняя открытая шторка держится, пока уезжает.
    var shown by remember { mutableStateOf<SheetSpec?>(null) }
    if (spec != null) shown = spec
    AnimatedVisibility(spec != null, enter = fadeIn(tween(T.mid, easing = T.eOut)), exit = fadeOut(tween(T.mid, easing = T.eIn))) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = T.scrim))
                .clickable(remember { MutableInteractionSource() }, null) { ui.close() },
        )
    }
    // Шторку тянут вниз за ручку или заголовок: она идёт за пальцем; отпустили далеко или резко — закрывается.
    val pull = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(spec) { if (spec != null) pull.snapTo(0f) }
    val density = LocalDensity.current
    val far = with(density) { T.sheetDismiss.toPx() }
    val drag = Modifier.draggable(
        rememberDraggableState { d -> scope.launch { pull.snapTo((pull.value + d).coerceAtLeast(0f)) } },
        Orientation.Vertical,
        onDragStopped = { v ->
            if (pull.value > far || v > T.flingClose) ui.close()
            else pull.animateTo(0f, tween(T.mid, easing = T.eSpring))
        },
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 1024.dp
        val maxH = maxHeight * 0.88f
        AnimatedVisibility(
            spec != null,
            enter = if (wide) fadeIn(tween(T.slow, easing = T.eOut)) + scaleIn(tween(T.slow, easing = T.eOut), initialScale = 0.96f)
            else slideInVertically(tween(T.slow, easing = T.eOut)) { it },
            exit = if (wide) fadeOut(tween(T.mid, easing = T.eIn)) else slideOutVertically(tween(T.mid, easing = T.eIn)) { it },
        ) {
            val s = shown ?: return@AnimatedVisibility
            Box(Modifier.fillMaxSize(), contentAlignment = if (wide) Alignment.Center else Alignment.BottomCenter) {
            val shape = if (wide) RoundedCornerShape(T.rBtn) else RoundedCornerShape(topStart = T.rBtn, topEnd = T.rBtn)
            Column(
                Modifier.then(if (wide) Modifier.widthIn(max = 720.dp).padding(24.dp) else Modifier.fillMaxWidth())
                    .offset { IntOffset(0, if (wide) 0 else pull.value.roundToInt()) }
                    .heightIn(max = maxH).clip(shape).background(p.bg)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .safeDrawingPadding().imePadding(),
            ) {
                if (!wide) Box(
                    Modifier.fillMaxWidth().then(drag).padding(top = 8.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(T.grabW, T.grabH).clip(RoundedCornerShape(T.rPill)).background(p.l3)) }
                // Шторка поверх шторки (PIN из «Подключить») въезжает сбоку; возврат — так же мягко.
                AnimatedContent(
                    s, label = "sheet",
                    transitionSpec = {
                        (fadeIn(tween(T.mid, easing = T.eOut)) + slideInHorizontally(tween(T.mid, easing = T.eOut)) { it / 8 }) togetherWith
                            fadeOut(tween(T.fast, easing = T.eIn)) using SizeTransform(clip = false) { _, _ -> tween(T.mid, easing = T.eOut) }
                    },
                ) { cur ->
                    Column {
                        Row(Modifier.fillMaxWidth().then(if (wide) Modifier else drag).padding(start = T.gutter, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt(cur.title, T.fSheet, weight = FontWeight.Bold, modifier = Modifier.weight(1f), lines = 1)
                            IconBtn("close", "Закрыть") { ui.close() }
                        }
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = T.gutter, end = T.gutter, bottom = 20.dp)) { cur.content(this) }
                    }
                }
            }
            }
        }
    }
}

// ——— Тосты: коротко, сверху, только на пульте ———

@Composable
private fun Toasts(ui: Ui) {
    val p = P.current
    Column(
        Modifier.fillMaxWidth().safeDrawingPadding().padding(top = T.gap, start = T.gutter, end = T.gutter),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(T.gapS),
    ) {
        ui.toasts.takeLast(3).forEach { t ->
            androidx.compose.runtime.key(t.id) {
                // Тост спускается сверху с лёгким отскоком и уходит, тая.
                val shown = remember { MutableTransitionState(false).apply { targetState = true } }
                LaunchedEffect(t.id) {
                    delay(maxOf(1900L, t.text.length * 60L))
                    shown.targetState = false
                    delay(T.mid.toLong())
                    ui.toasts.remove(t)
                }
                AnimatedVisibility(
                    shown,
                    enter = fadeIn(tween(T.mid, easing = T.eOut)) + slideInVertically(tween(T.slow, easing = T.eSpring)) { -it } + scaleIn(tween(T.slow, easing = T.eSpring), T.popFrom),
                    exit = fadeOut(tween(T.mid, easing = T.eIn)) + scaleOut(tween(T.mid, easing = T.eIn), T.popFrom),
                ) {
                    Box(Modifier.clip(RoundedCornerShape(T.rPill)).background(p.ink).padding(horizontal = T.gutter, vertical = T.gap)) {
                        Txt(t.text, T.fPill, p.bg, FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ——— Жесты: касание и смахивания (DESIGN.md → «Карта жестов») ———

/** Касание и смахивание в четыре стороны. Порог — 40 dp, касание — сдвиг меньше 10 dp. */
@Composable
fun Modifier.swipes(
    onTap: (() -> Unit)? = null, onLeft: (() -> Unit)? = null, onRight: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null, onDown: (() -> Unit)? = null,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val left by rememberUpdatedState(onLeft)
    val right by rememberUpdatedState(onRight)
    val up by rememberUpdatedState(onUp)
    val down by rememberUpdatedState(onDown)
    val density = LocalDensity.current
    val far = with(density) { 40.dp.toPx() }
    val near = with(density) { 10.dp.toPx() }
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val start = awaitFirstDown(requireUnconsumed = false).position
            var end = start
            while (true) {
                val e = awaitPointerEvent()
                val ch = e.changes.firstOrNull() ?: break
                end = ch.position
                if (!ch.pressed) break
                // Свой жест: прокрутка списка и листание вкладок вокруг не перехватывают горизонтальное смахивание.
                val hx = abs(end.x - start.x)
                if (hx > near || ((left != null || right != null) && hx > 0f && hx >= abs(end.y - start.y))) ch.consume()
            }
            val dx = end.x - start.x
            val dy = end.y - start.y
            when {
                abs(dx) < near && abs(dy) < near -> tap?.invoke()
                abs(dx) > abs(dy) && abs(dx) > far -> if (dx < 0) left?.invoke() else right?.invoke()
                abs(dy) > far -> if (dy < 0) up?.invoke() else down?.invoke()
            }
        }
    }
}

@Composable
fun Spacer16() = Spacer(Modifier.height(T.gutter))
