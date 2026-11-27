package app.deixis.phone

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import app.deixis.core.Mode

/** Действия, которые можно повесить на клавишу компьютера. Набор по умолчанию — как кликер; всё переназначается в «Управлении». */
enum class KeyAction(val id: String) {
    NEXT("next"), PREV("prev"), LIVE("live"), BLACK("black"), LOGO("logo"), CLEAR("clear"),
    TUNE("tune"), ASSISTANT("assistant"), NEXT_ITEM("nextItem"), PREV_ITEM("prevItem"),
}

/**
 * Клавиши компьютера: сочетание — строка вида «Ctrl+J», «Right», «Shift+B». Переназначения хранятся в настройках
 * ([AppSettings.keys], действие → сочетания через запятую); без переназначения действует [DEFAULTS].
 */
object Hotkeys {
    val DEFAULTS: Map<KeyAction, List<String>> = mapOf(
        KeyAction.NEXT to listOf("Right", "Space", "PageDown"),
        KeyAction.PREV to listOf("Left", "PageUp"),
        KeyAction.LIVE to listOf("Enter"),
        KeyAction.BLACK to listOf("B"),
        KeyAction.LOGO to listOf("L"),
        KeyAction.CLEAR to listOf("C"),
        KeyAction.TUNE to listOf("T"),
        KeyAction.ASSISTANT to listOf("Ctrl+J"),
        KeyAction.NEXT_ITEM to listOf("Ctrl+Down"),
        KeyAction.PREV_ITEM to listOf("Ctrl+Up"),
    )

    fun combosOf(a: KeyAction, keys: Map<String, String>): List<String> =
        keys[a.id]?.split(",")?.filter { it.isNotBlank() } ?: DEFAULTS.getValue(a)

    /** Действие для сочетания [combo]; null — оно ни за чем не закреплено. */
    fun actionOf(combo: String, keys: Map<String, String>): KeyAction? = KeyAction.entries.firstOrNull { combo in combosOf(it, keys) }

    /**
     * Назначить [combo] действию [a]: у остальных действий оно снимается, чтобы одна клавиша не делала двух дел.
     * [combo] = null — вернуть умолчание.
     */
    fun assign(keys: Map<String, String>, a: KeyAction, combos: List<String>?): Map<String, String> {
        val out = keys.toMutableMap()
        if (combos == null) { out.remove(a.id); return out }
        for (other in KeyAction.entries) if (other != a) {
            val have = combosOf(other, out)
            val left = have.filter { it !in combos }
            if (left.size != have.size) out[other.id] = left.joinToString(",")
        }
        out[a.id] = combos.joinToString(",")
        return out
    }

    private val NAMES: Map<Key, String> = buildMap {
        put(Key.DirectionRight, "Right"); put(Key.DirectionLeft, "Left"); put(Key.DirectionUp, "Up"); put(Key.DirectionDown, "Down")
        put(Key.Spacebar, "Space"); put(Key.Enter, "Enter"); put(Key.PageDown, "PageDown"); put(Key.PageUp, "PageUp")
        put(Key.MoveHome, "Home"); put(Key.MoveEnd, "End"); put(Key.Delete, "Delete"); put(Key.Backspace, "Backspace")
        put(Key.Tab, "Tab"); put(Key.MediaNext, "MediaNext"); put(Key.MediaPrevious, "MediaPrevious")
        for ((i, k) in listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12).withIndex()) put(k, "F${i + 1}")
        for ((i, k) in listOf(Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M, Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z).withIndex()) put(k, ('A' + i).toString())
        for ((i, k) in listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine).withIndex()) put(k, i.toString())
    }

    /** Сочетание из события клавиатуры; null — это не клавиша действия (одна «Ctrl», незнакомая клавиша). */
    fun comboOf(e: KeyEvent): String? {
        val base = NAMES[e.key] ?: return null
        return buildString {
            if (e.isCtrlPressed) append("Ctrl+")
            if (e.isAltPressed) append("Alt+")
            if (e.isShiftPressed) append("Shift+")
            append(base)
        }
    }

    /** Выполнить действие. Везде одинаково: из клавиши, из кнопки, из меню слайда. */
    fun run(c: Controller, ui: Ui, a: KeyAction) {
        when (a) {
            KeyAction.NEXT -> c.step(1)
            KeyAction.PREV -> c.step(-1)
            KeyAction.LIVE -> c.previewToLive()
            KeyAction.BLACK -> c.mode(if (c.mode.value == Mode.BLACK) Mode.NORMAL else Mode.BLACK)
            KeyAction.LOGO -> c.mode(if (c.mode.value == Mode.LOGO) Mode.NORMAL else Mode.LOGO)
            KeyAction.CLEAR -> c.mode(if (c.mode.value == Mode.CLEAR) Mode.NORMAL else Mode.CLEAR)
            KeyAction.TUNE -> if (c.tune.value != null) ui.sheet(L.tune) { LiveTuneSheet(c, ui) } else if (c.live.value != null) openTune(c, ui)
            KeyAction.ASSISTANT -> ui.sheet(L.assistant) { AssistantChooser(c, ui) }
            KeyAction.NEXT_ITEM, KeyAction.PREV_ITEM -> {
                val set = c.activeSet.value ?: return
                val cur = (c.preview.value ?: c.live.value)?.source?.itemId
                val i = set.items.indexOf(cur)
                val to = set.items.getOrNull(if (a == KeyAction.NEXT_ITEM) i + 1 else (i - 1).coerceAtLeast(0)) ?: return
                c.item(to)?.let { ui.open = it.id; c.toPreview(Cue(c.sourceOf(it), 0)) }
            }
        }
    }
}
