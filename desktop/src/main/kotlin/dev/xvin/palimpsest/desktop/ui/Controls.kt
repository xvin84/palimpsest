package dev.xvin.palimpsest.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The desktop's own small controls. Material's are sized for a finger (48dp buttons, 56dp fields,
 * a tall slider thumb); next to a table of 52dp rows they looked like another app's parts.
 */

enum class Kind { PRIMARY, SECONDARY, GHOST }

/** A 32dp button: the screen's main action (accent), an ordinary one, or one that only shows on hover. */
@Composable
fun PButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, kind: Kind = Kind.SECONDARY, enabled: Boolean = true, trailingIcon: ImageVector? = null) {
    val pal = LocalPal.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val primary = MaterialTheme.colorScheme.primary
    val base = when (kind) { Kind.PRIMARY -> primary; Kind.SECONDARY -> pal.paper3; Kind.GHOST -> pal.paper3.copy(alpha = 0f) }
    val bg by animateColorAsState(
        when {
            !hovered || !enabled -> base
            kind == Kind.PRIMARY -> lerp(primary, pal.text, 0.12f)
            kind == Kind.SECONDARY -> lerp(pal.paper3, pal.line, 0.7f)
            else -> pal.paper3
        },
        tween(120),
    )
    val fg = when (kind) { Kind.PRIMARY -> MaterialTheme.colorScheme.onPrimary; Kind.SECONDARY -> pal.text; Kind.GHOST -> if (hovered) pal.text else pal.muted }
    Row(
        modifier.height(32.dp).clip(RoundedCornerShape(Radius.md)).background(bg)
            .hoverable(hover).clickable(enabled = enabled, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .padding(start = if (icon != null) 10.dp else 14.dp, end = if (trailingIcon != null) 8.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(18.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        trailingIcon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(16.dp)) }
    }
}

/** A round icon button with a soft circle on hover; [active] lights the icon in the accent. */
@Composable
fun PIconButton(icon: ImageVector, description: String?, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 32.dp, iconSize: Dp = 20.dp, tint: Color? = null, active: Boolean = false) {
    val pal = LocalPal.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        modifier.size(size).clip(CircleShape).background(if (hovered) pal.text.copy(alpha = 0.08f) else Color.Transparent)
            .hoverable(hover).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint ?: if (active) LocalAccent.current else if (hovered) pal.text else pal.muted, modifier = Modifier.size(iconSize))
    }
}

/** A compact search or filter field: a soft well, no outline until it has focus. */
@Composable
fun PField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, height: Dp = 34.dp, textModifier: Modifier = Modifier, onSubmit: (() -> Unit)? = null) {
    val pal = LocalPal.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = MaterialTheme.typography.bodyMedium.copy(color = pal.text)
    BasicTextField(
        value, onChange, textModifier, singleLine = true, textStyle = style, interactionSource = interaction,
        cursorBrush = SolidColor(pal.text),
        keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }, onSearch = { onSubmit?.invoke() }),
        decorationBox = { inner ->
            Row(
                modifier.height(height).clip(RoundedCornerShape(Radius.md)).background(pal.paper3)
                    .border(1.dp, if (focused) pal.muted.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(Radius.md))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = pal.faint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = pal.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                if (value.isNotEmpty()) PIconButton(Icons.Rounded.Close, t("Очистить", "Clear"), { onChange("") }, size = 22.dp, iconSize = 14.dp)
            }
        },
    )
}

/** A segmented switch for a few views of the same thing (tracks / albums…, lyrics / queue). */
@Composable
fun <T> Tabs(items: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    Row(modifier.height(30.dp).clip(RoundedCornerShape(Radius.md)).background(pal.paper2).border(1.dp, pal.line.copy(alpha = 0.6f), RoundedCornerShape(Radius.md)).padding(2.dp)) {
        items.forEach { (key, label) ->
            val on = key == selected
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            val bg by animateColorAsState(if (on) pal.paper3 else pal.paper3.copy(alpha = 0f), tween(160))
            Box(
                Modifier.height(26.dp).clip(RoundedCornerShape(Radius.sm)).background(bg).hoverable(hover)
                    .clickable { onSelect(key) }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (on || hovered) pal.text else pal.muted, maxLines = 1) }
        }
    }
}

/** Plain words to pick one of (search kinds): the picked one bright, the others muted. */
@Composable
fun <T> TextTabs(items: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { (key, label) ->
            val on = key == selected
            Text(
                label, style = MaterialTheme.typography.labelLarge, color = if (on) pal.text else pal.muted,
                modifier = Modifier.clip(RoundedCornerShape(Radius.sm)).background(if (on) pal.text.copy(alpha = 0.07f) else Color.Transparent)
                    .clickable { onSelect(key) }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * A thin line to drag along — the seek bar, the volume. The knob shows while the pointer is over
 * it, so at rest it is just a line. [onChange] gets 0..1 while dragging, [onDone] at the end.
 */
@Composable
fun LineSlider(fraction: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, color: Color = LocalPal.current.text, onDone: ((Float) -> Unit)? = null) {
    val pal = LocalPal.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var drag by remember { mutableStateOf<Float?>(null) }
    val f = (drag ?: fraction).coerceIn(0f, 1f)
    val lively = hovered || drag != null
    Canvas(
        modifier.height(16.dp).hoverable(hover).pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) { detectTapGestures { o -> val v = (o.x / size.width).coerceIn(0f, 1f); onChange(v); onDone?.invoke(v) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f).also(onChange) },
                    onDragEnd = { drag?.let { onDone?.invoke(it) }; drag = null },
                    onDragCancel = { drag = null },
                ) { c, _ -> drag = (c.position.x / size.width).coerceIn(0f, 1f).also(onChange) }
            },
    ) {
        val y = size.height / 2
        val h = (if (lively) 4 else 3).dp.toPx()
        drawRoundRect(pal.line, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(if (lively) color else color.copy(alpha = 0.85f), Offset(0f, y - h / 2), Size(size.width * f, h), CornerRadius(h / 2))
        if (lively) drawCircle(pal.text, 6.dp.toPx(), Offset(size.width * f, y))
    }
}

/** Three bars bouncing next to what's playing (still while paused). */
@Composable
fun PlayingBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = LocalAccent.current) {
    val pulse = rememberInfiniteTransition()
    val levels = listOf(0 to 520, 1 to 380, 2 to 610).map { (k, ms) ->
        pulse.animateFloat(0.25f, 1f, infiniteRepeatable(tween(ms, delayMillis = k * 90), RepeatMode.Reverse))
    }
    Canvas(modifier.size(14.dp)) {
        val bar = size.width / 5
        levels.forEachIndexed { k, v ->
            val hh = size.height * (if (playing) v.value else listOf(0.5f, 0.8f, 0.35f)[k])
            drawRoundRect(color, Offset(k * bar * 2f, size.height - hh), Size(bar, hh), CornerRadius(bar / 2))
        }
    }
}
