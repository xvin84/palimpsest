package dev.xvin.palimpsest.desktop.ui

import androidx.compose.ui.Modifier
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
