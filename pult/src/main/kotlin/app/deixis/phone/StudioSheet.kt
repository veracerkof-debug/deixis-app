package app.deixis.phone

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.deixis.core.DeixisJson
import app.deixis.core.SceneBackground
import app.deixis.core.ai.Motion
import app.deixis.core.ai.ParamGroup
import app.deixis.core.ai.SceneEditor
import app.deixis.core.ai.SceneOps
import app.deixis.core.ai.SceneStudio
import app.deixis.core.effects.Layer
import app.deixis.core.effects.ParamDef
import app.deixis.core.effects.ParamKind
import app.deixis.core.effects.Scene
import app.deixis.core.effects.SceneCatalog
import app.deixis.core.effects.SceneLooks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Мои стили: сохранённые сцены оператора на этом устройстве (имя → сцена JSON). */
class StyleStore(private val prefs: Prefs) {
    fun all(): List<Pair<String, String>> = try {
        DeixisJson.parseToJsonElement(prefs.getString("styles", "[]") ?: "[]").jsonArray.mapNotNull {
            val o = it.jsonObject
            (o["name"] as? JsonPrimitive)?.contentOrNull?.let { n -> (o["scene"] as? JsonPrimitive)?.contentOrNull?.let { s -> n to s } }
        }
    } catch (e: Exception) { emptyList() }

    fun save(name: String, scene: String) = write(all().filter { it.first != name } + (name to scene))
    fun delete(name: String) = write(all().filter { it.first != name })

    private fun write(list: List<Pair<String, String>>) = prefs.edit {
        putString("styles", DeixisJson.encodeToString(JsonArray.serializer(), buildJsonArray {
            for ((n, s) in list.takeLast(MAX)) add(buildJsonObject { put("name", n); put("scene", s) })
        }))
    }

    companion object { const val MAX = 30 }
}

/** Состояние студии: живое оформление, откат шагов и передача результата теме. Все правки идут теми же операциями, что у помощника. */
class StudioState(start: SceneBackground, val platform: Platform, private val publish: (SceneBackground) -> Unit) {
    var bg by mutableStateOf(start)
        private set
    private val history = ArrayList<SceneBackground>()
    var canUndo by mutableStateOf(false)
        private set
    val scene: Scene? get() = SceneEditor.sceneOf(bg)

    /** Применить готовое оформление. [step] — отдельный шаг для «Отменить» (структурные правки; ползунки шагами не считаются). */
    fun set(next: SceneBackground?, step: Boolean = false) {
        if (next == null) return
        if (step) { history += bg; if (history.size > UNDO) history.removeAt(0); canUndo = true }
        bg = next; publish(next)
    }

    fun undo() { if (history.isEmpty()) return; bg = history.removeAt(history.lastIndex); canUndo = history.isNotEmpty(); publish(bg) }

    companion object { const val UNDO = 30 }
}

private sealed interface Page {
    object Root : Page
    object AddLayer : Page
    data class Layer(val id: String) : Page
    data class AddEffect(val layer: String) : Page
    data class Move(val layer: String, val key: String) : Page
}

/**
 * Студия фона — ручная правка живого фона: готовые стили, слои, образы, эффекты и движение. Превью внизу всегда на виду:
 * каждая правка видна сразу. Ничего не нужно знать про источники и формулы: параметры идут ползунками по разделам
 * («Положение», «Размер», «Движение», «Цвет»), движение — готовыми видами («Пульс», «Дыхание»…).
 */
@Composable
fun StudioSheet(platform: Platform, start: SceneBackground, onChange: (SceneBackground) -> Unit, open: String? = null) {
    val st = remember { StudioState(start, platform, onChange) }
    val styles = remember { StyleStore(platform.prefs("studio")) }
    // [open] — страница, с которой начать (для проверок и снимков): «layer:id», «add-layer», «add-effect:id», «move:id:ключ».
    var page by remember {
        mutableStateOf<Page>(open?.split(":")?.let { v ->
            when (v[0]) { "layer" -> Page.Layer(v[1]); "add-layer" -> Page.AddLayer; "add-effect" -> Page.AddEffect(v[1]); "move" -> Page.Move(v[1], v[2]); else -> Page.Root }
        } ?: Page.Root)
    }
    var tab by remember { mutableIntStateOf(if (open == "layers") 1 else 0) }
    SheetFooter { PreviewBar(st) }
    if (st.scene == null) { Note(L.assistantNoScene); return }

    // Верхняя полоса: назад (в глубине) и «Отменить».
    Row(Modifier.fillMaxWidth().padding(bottom = T.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
        if (page != Page.Root) Pill(L.back, "prev") { page = when (val p = page) { is Page.AddEffect -> Page.Layer(p.layer); is Page.Move -> Page.Layer(p.layer); else -> Page.Root } }
        Column(Modifier.weight(1f)) {}
        if (st.canUndo) Pill(L.assistantUndo, "restart") { st.undo(); if (page is Page.Layer && st.scene?.layer((page as Page.Layer).id) == null) page = Page.Root }
    }
    when (val p = page) {
        Page.Root -> {
            Seg(listOf(L.studioStyles, L.studioLayers), tab, Modifier.fillMaxWidth()) { tab = it }
            Column(Modifier.padding(top = T.gap)) {
                if (tab == 0) StylesTab(st, styles) else LayersTab(st, { page = Page.Layer(it) }, { page = Page.AddLayer })
            }
        }
        Page.AddLayer -> AddLayerPage(st) { page = Page.Layer(it) }
        is Page.Layer -> LayerPage(st, p.id, { page = Page.Root }, { page = Page.AddEffect(p.id) }) { key -> page = Page.Move(p.id, key) }
        is Page.AddEffect -> AddEffectPage(st, p.layer) { page = Page.Layer(p.layer) }
        is Page.Move -> MovePage(st, p.layer, p.key)
    }
}

// ——— Превью и общие ручки ———

@Composable
private fun PreviewBar(st: StudioState) {
    Row(horizontalArrangement = Arrangement.spacedBy(T.gap), verticalAlignment = Alignment.CenterVertically) {
        ScenePreview(st.bg, st.platform, Modifier.width(T.studioPreviewW))
        Column(Modifier.weight(1f)) {
            ParamSlider(L.studioEnergy, "${Math.round(st.bg.energy * 100)}", st.bg.energy, { st.set(st.bg.copy(energy = it)) }, { st.set(st.bg.copy(energy = SceneBackground("x").energy)) })
            val quiet = st.bg.quiet ?: st.scene?.quiet?.toFloat() ?: 0f
            ParamSlider(L.liveQuiet, "${Math.round(quiet * 100)}", quiet, { st.set(st.bg.copy(quiet = it)) }, { st.set(st.bg.copy(quiet = null)) })
        }
    }
}

// ——— Стили ———

@Composable
private fun StylesTab(st: StudioState, styles: StyleStore) {
    var mine by remember { mutableStateOf(styles.all()) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val builtin = SceneLooks.IDS.map { it to SceneBackground(it, st.bg.energy) }
    val cards = buildList {
        add(Triple(L.studioBlank, SceneBackground("mine", st.bg.energy, scene = DeixisJson.encodeToString(JsonObject.serializer(), SceneOps.toTree(SceneStudio.blank()))), false))
        builtin.forEach { (id, b) -> add(Triple(L.sceneName(id), b, false)) }
        mine.forEach { (n, json) -> add(Triple(n, SceneBackground("mine", st.bg.energy, scene = json), true)) }
    }
    Grid(cards, T.styleCols) { (title, b, own) ->
        val picked = if (b.scene == null) st.bg.scene == null && st.bg.look == b.look else st.bg.scene == b.scene
        StyleCard(title, b, st.platform, picked, Modifier.weight(1f), onRemove = if (own) ({ styles.delete(title); mine = styles.all() }) else null) {
            st.set(b.copy(quiet = null), step = true)
        }
    }
    // Недостающая клетка в последнем ряду держит ширину.
    Column(Modifier.padding(top = T.gap), verticalArrangement = Arrangement.spacedBy(T.gapS)) {
        if (!naming) Btn(L.studioSaveStyle, icon = "plus") { naming = true }
        else {
            Field(name, L.studioStyleName) { name = it }
            Btn(L.save, kind = BtnKind.TINT, enabled = name.isNotBlank()) {
                val json = st.bg.scene ?: SceneEditor.withScene(st.bg, SceneEditor.sceneOf(st.bg)!!).scene!!
                styles.save(name.trim().take(40), json); mine = styles.all(); naming = false; name = ""
            }
        }
        Note(L.studioStylesNote)
    }
}

@Composable
private fun StyleCard(title: String, b: SceneBackground, platform: Platform, picked: Boolean, modifier: Modifier, onRemove: (() -> Unit)?, onClick: () -> Unit) {
    val p = P.current
    Column(modifier.press(label = title, onClick = onClick), verticalArrangement = Arrangement.spacedBy(T.gapXs)) {
        Column(Modifier.then(if (picked) Modifier.androidBorder() else Modifier)) { SceneThumb(b, platform) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
            Txt(title, T.fLabel, if (picked) p.tintText else p.ink, androidx.compose.ui.text.font.FontWeight.SemiBold, Modifier.weight(1f), lines = 1)
            onRemove?.let { Pill(L.remove, label = L.remove) { it() } }
        }
    }
}

@Composable
private fun Modifier.androidBorder(): Modifier = this.then(Modifier.border(T.outline, P.current.tint, androidx.compose.foundation.shape.RoundedCornerShape(T.rSlide)))

// ——— Слои ———

@Composable
private fun LayersTab(st: StudioState, open: (String) -> Unit, add: () -> Unit) {
    val scene = st.scene ?: return
    Group {
        // Как в редакторах: верхний слой (ближе к тексту) — первым в списке.
        scene.layers.asReversed().forEachIndexed { i, layer ->
            if (i > 0) Sep(T.gutter)
            Cell(layer.name, L.layerKindNote(layer.type), kind = layerIcon(layer.type), onClick = { open(layer.id) }) {
                Switch(layer.on, layer.name) { on -> st.set(SceneEditor.toggle(st.bg, layer.id, on)) }
            }
        }
    }
    Btn(L.studioAddLayer, Modifier.padding(top = T.gap), icon = "plus") { add() }
    Note(L.studioLayersNote, Modifier.padding(top = T.gapS))
}

private fun layerIcon(type: String) = when (type) {
    "gradient" -> "photo"; "field" -> "brush"; "shape" -> "logo"; "emitter" -> "show"; "rays" -> "onscreen"
    "rings" -> "refresh"; "thread" -> "loop"; "contour" -> "layers"; "text" -> "text"; else -> "layers"
}

@Composable
private fun AddLayerPage(st: StudioState, done: (String) -> Unit) {
    val scene = st.scene ?: return
    GHead(L.studioAddLayer)
    Group {
        SceneCatalog.layers.keys.filter { it != "text" || scene.layers.none { l -> l.type == "text" } }.forEachIndexed { i, type ->
            if (i > 0) Sep(T.gutter)
            val ok = SceneStudio.canAdd(scene, type)
            Cell(L.layerType(type), if (ok) L.layerKindNote(type) else L.studioNeedShape, kind = layerIcon(type), chevron = if (ok) "plus" else null, onClick = if (ok) ({
                SceneEditor.addLayer(st.bg, type)?.let { (next, id) -> st.set(next, step = true); done(id) }
            }) else null)
        }
    }
}

@Composable
private fun LayerPage(st: StudioState, id: String, leave: () -> Unit, addEffect: () -> Unit, motion: (String) -> Unit) {
    val scene = st.scene ?: return
    val layer = scene.layer(id) ?: run { leave(); return }
    val kind = SceneCatalog.layers[layer.type]
    var name by remember(id) { mutableStateOf(layer.name) }
    Field(name, L.studioLayerName) { name = it; SceneEditor.rename(st.bg, id, it)?.let { n -> st.set(n) } }
    Note(L.layerKindNote(layer.type) + ". " + L.studioMotionHint, Modifier.padding(top = T.gapS))
    val index = scene.layers.indexOfFirst { it.id == id }
    Row(Modifier.padding(vertical = T.gap), horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
        if (index < scene.layers.size - 1) Pill(L.layerUp, "up") { st.set(SceneEditor.move(st.bg, id, 1), step = true) }
        if (index > 0) Pill(L.layerDown, "down") { st.set(SceneEditor.move(st.bg, id, -1), step = true) }
        Pill(L.layerCopy, "copy") { SceneEditor.duplicate(st.bg, id)?.let { st.set(it, step = true) } }
        if (scene.layers.size > 1) Pill(L.remove, "trash") { SceneEditor.remove(st.bg, id)?.let { st.set(it, step = true); leave() } }
    }
    for ((group, defs) in SceneStudio.grouped(layer)) {
        GHead(L.paramGroup(group))
        Group {
            Column(Modifier.padding(horizontal = T.gutter, vertical = T.gapS)) { defs.forEach { ParamEditor(st, scene, layer, it, motion) } }
        }
    }
    GHead(L.studioForText)
    Group {
        Column(Modifier.padding(horizontal = T.gutter, vertical = T.gapS)) {
            for (d in SceneCatalog.COMMON) ParamEditor(st, scene, layer, d, null)
        }
    }
    GHead(L.studioEffects)
    EffectsBlock(st, layer, addEffect)
    if (kind == null) Note(L.layerType(layer.type))
}

/** Один параметр слоя: ползунок, цвета или выбор формы — по виду параметра. [motion] — открыть выбор движения (null — параметру оно не нужно). */
@Composable
private fun ParamEditor(st: StudioState, scene: Scene, layer: Layer, d: ParamDef, motion: ((String) -> Unit)?) {
    val label = L.paramName(d.key, d.label)
    when (d.kind) {
        ParamKind.NUM -> {
            val cur = if (layer.p[d.key] != null) layer.num(d.key) else d.def
            val m = SceneStudio.motionOf(scene, layer, d.key)
            ParamSlider(
                label, format(cur, d), ((cur - d.min) / (d.max - d.min)).toFloat(),
                { f -> st.set(SceneEditor.setParam(st.bg, layer.id, d.key, snap(d.min + f * (d.max - d.min), d))) },
                { st.set(SceneEditor.setParam(st.bg, layer.id, d.key, d.def)) },
            ) {
                if (motion != null) Pill(m?.let { L.motionName(it) } ?: "", if (m == null) "loop" else null, tint = m != null, label = "$label: ${L.motionAdd}") { motion(d.key) }
            }
        }
        ParamKind.COLOR -> {
            Txt(label, T.fBody, modifier = Modifier.padding(top = T.gapS))
            val cols = scene.palette.map { hexColor(it) }
            ColorRow(cols, Math.round(layer.num(d.key)).toInt().coerceIn(0, cols.lastIndex.coerceAtLeast(0)), { L.studioColorN(it + 1) }) { i -> st.set(SceneEditor.setParam(st.bg, layer.id, d.key, i.toDouble())) }
        }
        ParamKind.HEX -> {
            Txt(label, T.fBody, modifier = Modifier.padding(top = T.gapS))
            val set = (DARKS + scene.palette).distinct()
            val cur = layer.str(d.key).lowercase()
            ColorRow(set.map { hexColor(it) }, set.indexOfFirst { it.lowercase() == cur }, { L.studioColorN(it + 1) }) { i -> st.set(SceneEditor.setText(st.bg, layer.id, d.key, set[i])) }
        }
        ParamKind.SELECT -> {
            Txt(label, T.fBody, modifier = Modifier.padding(top = T.gapS))
            val cur = layer.str(d.key).ifEmpty { d.defText }
            if (d.key == "kind" && layer.type == "shape") {
                Grid(d.options, SHAPES_PER_ROW) { (v, ru) -> ShapeChip(v, v == cur, L.optionName(v, ru)) { st.set(SceneEditor.setText(st.bg, layer.id, d.key, v)) } }
                androidx.compose.foundation.layout.Spacer(Modifier.height(T.gap))
            } else {
                Seg(d.options.map { L.optionName(it.first, it.second) }, d.options.indexOfFirst { it.first == cur }.coerceAtLeast(0), Modifier.fillMaxWidth(), scroll = true) { i -> st.set(SceneEditor.setText(st.bg, layer.id, d.key, d.options[i].first)) }
            }
        }
        ParamKind.TEXT -> {
            var text by remember(layer.id) { mutableStateOf(layer.str(d.key)) }
            Txt(label, T.fBody, modifier = Modifier.padding(top = T.gapS))
            Field(text, label, single = false, minHeight = T.btn * 2) { text = it; st.set(SceneEditor.setText(st.bg, layer.id, d.key, it.take(app.deixis.core.effects.SceneLimits.MAX_TEXT))) }
        }
        ParamKind.LAYER -> Unit
    }
}

private const val SHAPES_PER_ROW = 5
private val DARKS = listOf("#000000", "#0d0a1c", "#1b1238", "#0b1a2e", "#10201a", "#1c0f0a", "#2a0f1a", "#1d1d22")

private fun snap(v: Double, d: ParamDef): Double {
    val steps = Math.round((v - d.min) / d.step)
    return (d.min + steps * d.step).coerceIn(d.min, d.max)
}

private fun format(v: Double, d: ParamDef): String = when {
    d.step >= 1.0 -> "${Math.round(v)}"
    d.step >= 0.1 -> "${Math.round(v * 10) / 10.0}"
    d.step >= 0.01 -> "${Math.round(v * 100) / 100.0}"
    else -> "${Math.round(v * 1000) / 1000.0}"
}

// ——— Эффекты ———

@Composable
private fun EffectsBlock(st: StudioState, layer: Layer, add: () -> Unit) {
    var open by remember(layer.id) { mutableIntStateOf(-1) }
    if (layer.fx.isNotEmpty()) Group {
        layer.fx.forEachIndexed { i, fx ->
            if (i > 0) Sep(T.gutter)
            val kind = SceneCatalog.effects[fx.type]
            Cell(L.fxName(fx.type), L.fxNote(fx.type), open = open == i, chevron = if (open == i) "up" else "chev", onClick = { open = if (open == i) -1 else i }) {
                Switch(!fx.off, L.fxName(fx.type)) { on -> st.set(SceneEditor.fxOff(st.bg, layer.id, i, !on)) }
            }
            if (open == i && kind != null) Column(Modifier.padding(horizontal = T.gutter, vertical = T.gapS)) {
                kind.params.forEach { d ->
                    val cur = fx.p[d.key] ?: d.def
                    ParamSlider(L.paramName(d.key, d.label), format(cur, d), ((cur - d.min) / (d.max - d.min)).toFloat(),
                        { f -> st.set(SceneEditor.fxParam(st.bg, layer.id, i, d.key, snap(d.min + f * (d.max - d.min), d))) },
                        { st.set(SceneEditor.fxParam(st.bg, layer.id, i, d.key, d.def)) })
                }
                Pill(L.remove, "trash") { SceneEditor.fxRemove(st.bg, layer.id, i)?.let { n -> open = -1; st.set(n, step = true) } }
            }
        }
    }
    Btn(L.studioAddEffect, Modifier.padding(top = T.gap), icon = "plus") { add() }
}

@Composable
private fun AddEffectPage(st: StudioState, layerId: String, done: () -> Unit) {
    GHead(L.studioAddEffect)
    Group {
        SceneCatalog.effects.keys.forEachIndexed { i, type ->
            if (i > 0) Sep(T.gutter)
            Cell(L.fxName(type), L.fxNote(type), kind = "brush", chevron = "plus", onClick = {
                val next = SceneEditor.addEffect(st.bg, layerId, type)
                if (next != null) { st.set(next, step = true); done() }
            })
        }
    }
    Note(L.studioEffectLimit, Modifier.padding(top = T.gapS))
}

// ——— Движение ———

@Composable
private fun MovePage(st: StudioState, layerId: String, key: String) {
    val scene = st.scene ?: return
    val layer = scene.layer(layerId) ?: return
    val def = SceneCatalog.layers[layer.type]?.byKey?.get(key)
    val label = L.paramName(key, def?.label ?: key)
    val cur = SceneStudio.motionOf(scene, layer, key)
    val custom = layer.bind[key]?.takeIf { cur == null && (it.expr.isNotEmpty() || it.src.isNotEmpty()) }
    var strength by remember(layerId, key) { mutableStateOf(SceneStudio.strengthOf(layer, key).let { if (it > 0.0) it else DEFAULT_STRENGTH }) }
    GHead(L.motionTitle(label))
    Group {
        Cell(L.motionName(null), L.motionNote(null), chevron = if (cur == null && custom == null) "check" else null, onClick = { st.set(SceneEditor.motion(st.bg, layerId, key, null, 0.0), step = true) })
        for (m in Motion.values()) {
            Sep(T.gutter)
            Cell(L.motionName(m), L.motionNote(m), chevron = if (cur == m) "check" else null, onClick = { st.set(SceneEditor.motion(st.bg, layerId, key, m, strength), step = true) })
        }
    }
    // Для опытных: своя формула (переменные t, energy, beat, pi, base…). Проверяется тем же кодом, что файл сцены.
    var formula by remember(layerId, key) { mutableStateOf(layer.bind[key]?.expr.orEmpty()) }
    var formulaError by remember(layerId, key) { mutableStateOf(false) }
    GHead(L.motionFormula)
    Field(formula, L.motionFormulaHint) { formula = it; formulaError = false }
    Row(Modifier.padding(top = T.gapS), horizontalArrangement = Arrangement.spacedBy(T.gapS)) {
        Btn(L.motionFormulaApply, Modifier.weight(1f), enabled = formula.isNotBlank()) {
            val next = SceneEditor.bindExpr(st.bg, layerId, key, formula)
            if (next == null) formulaError = true else { formulaError = false; st.set(next, step = true) }
        }
    }
    if (formulaError) Note(L.motionFormulaBad, Modifier.padding(top = T.gapS)) else Note(L.motionFormulaNote, Modifier.padding(top = T.gapS))
    if (custom != null) Note(L.motionCustom(if (custom.expr.isNotEmpty()) custom.expr else custom.src), Modifier.padding(top = T.gapS))
    if (cur != null) {
        Column(Modifier.padding(top = T.gap)) {
            ParamSlider(L.motionStrength, "${Math.round(strength * 100)}", strength.toFloat(), { f ->
                strength = f.toDouble(); st.set(SceneEditor.motion(st.bg, layerId, key, cur, strength))
            }, { strength = DEFAULT_STRENGTH; st.set(SceneEditor.motion(st.bg, layerId, key, cur, strength)) })
            Note(L.motionStrengthNote)
        }
    }
}

private const val DEFAULT_STRENGTH = 0.2
