package dev.xvin.palimpsest.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/** Right mouse button → [action] (context menus). */
fun Modifier.onRightClick(action: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) action()
        }
    }
}

/** Mouse wheel or touchpad over it: [onScroll] gets the vertical delta (a wheel notch is 1, a touchpad sends fractions). */
fun Modifier.onScroll(onScroll: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            if (e.type != PointerEventType.Scroll) continue
            val dy = e.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
            e.changes.forEach { it.consume() }
            if (dy != 0f) onScroll(dy)
        }
    }
}

/**
 * Mouse on a list row: [onPress] at once (with the keys held, for Ctrl/Shift selection), [onDouble]
 * on a double click, [onSecondary] on the right button.
 */
fun Modifier.rowClicks(onPress: (PointerKeyboardModifiers) -> Unit, onDouble: () -> Unit, onSecondary: () -> Unit): Modifier = composed {
    val press by rememberUpdatedState(onPress)
    val double by rememberUpdatedState(onDouble)
    val secondary by rememberUpdatedState(onSecondary)
    val last = remember { longArrayOf(0L) }
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                if (e.type != PointerEventType.Press) continue
                when {
                    e.buttons.isSecondaryPressed -> secondary()
                    e.buttons.isPrimaryPressed -> {
                        val now = e.changes.first().uptimeMillis
                        if (now - last[0] < viewConfiguration.doubleTapTimeoutMillis) { last[0] = 0; double() }
                        else { last[0] = now; press(e.keyboardModifiers) }
                    }
                }
            }
        }
    }
}

/**
 * Who has the keyboard: a text field or the shortcut catcher. While one has it, shortcuts without
 * Ctrl/Alt/⌘ stay with it (typing «k» in Search mustn't pause), and so do the usual editing keys.
 */
object KeyboardOwner {
    private var owner by androidx.compose.runtime.mutableStateOf<Any?>(null)
    val busy: Boolean get() = owner != null

    fun take(key: Any) { owner = key }
    fun release(key: Any) { if (owner === key) owner = null }

    /** What a field keeps while focused even with Ctrl: select all, copy, paste, undo, word jumps… */
    private val editing = setOf("A", "C", "V", "X", "Z", "Y", "Left", "Right", "Up", "Down", "Backspace", "Delete", "Home", "End")

    /** Whether a [stroke] like «Ctrl+Shift+K» should go to the shortcuts rather than the field. */
    fun letThrough(stroke: String): Boolean {
        if (!busy) return true
        val parts = stroke.split('+')
        return parts.size > 1 && parts.dropLast(1).any { it == "Ctrl" || it == "Alt" || it == "Meta" || it == "Cmd" } && parts.last() !in editing
    }
}

/** Marks a text field (or anything that reads keys) as owning the keyboard while it has focus. */
fun Modifier.ownsKeys(): Modifier = composed {
    val key = remember { Any() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { KeyboardOwner.release(key) } }
    onFocusChanged { if (it.isFocused) KeyboardOwner.take(key) else KeyboardOwner.release(key) }
}
