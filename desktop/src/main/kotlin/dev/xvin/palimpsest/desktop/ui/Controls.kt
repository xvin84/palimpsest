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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Check
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

enum class Kind { PRIMARY, SECONDARY, GHOST, DANGER }

/** A 32dp button: the screen's main action (accent), an ordinary one, one that only shows on hover, or one that destroys. */
@Composable
fun PButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, kind: Kind = Kind.SECONDARY, enabled: Boolean = true, trailingIcon: ImageVector? = null) {
    val pal = LocalPal.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val primary = if (kind == Kind.DANGER) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val base = when (kind) { Kind.PRIMARY, Kind.DANGER -> primary; Kind.SECONDARY -> pal.paper3; Kind.GHOST -> pal.paper3.copy(alpha = 0f) }
    val bg by animateColorAsState(
        when {
            !hovered || !enabled -> base
            kind == Kind.PRIMARY || kind == Kind.DANGER -> lerp(primary, pal.text, 0.12f)
            kind == Kind.SECONDARY -> lerp(pal.paper3, pal.line, 0.7f)
            else -> pal.paper3
        },
        tween(120),
    )
    val fg = when (kind) { Kind.PRIMARY -> MaterialTheme.colorScheme.onPrimary; Kind.DANGER -> MaterialTheme.colorScheme.onError; Kind.SECONDARY -> pal.text; Kind.GHOST -> if (hovered) pal.text else pal.muted }
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
fun PField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, height: Dp = 34.dp, textModifier: Modifier = Modifier, onSubmit: (() -> Unit)? = null) =
    PInput(value, onChange, placeholder, modifier, height = height, textModifier = textModifier, leading = Icons.Rounded.Search, clearable = true, onSubmit = onSubmit)

/**
 * A text field in the same well: an optional [label] above it, an optional icon in front;
 * [lines] above 1 makes it a text area (the tag editor's lyrics).
 */
@Composable
fun PInput(
    value: String, onChange: (String) -> Unit, placeholder: String = "", modifier: Modifier = Modifier,
    label: String? = null, height: Dp = 34.dp, lines: Int = 1, textModifier: Modifier = Modifier,
    leading: ImageVector? = null, clearable: Boolean = false, enabled: Boolean = true, onSubmit: (() -> Unit)? = null,
) {
    val pal = LocalPal.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = MaterialTheme.typography.bodyMedium.copy(color = if (enabled) pal.text else pal.muted)
    val multi = lines > 1
    androidx.compose.foundation.layout.Column(modifier) {
        label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = pal.muted, modifier = Modifier.padding(bottom = 5.dp)) }
        BasicTextField(
            value, onChange, textModifier.ownsKeys(), enabled = enabled, singleLine = !multi, minLines = if (multi) lines else 1, maxLines = if (multi) lines * 2 else 1,
            textStyle = style, interactionSource = interaction, cursorBrush = SolidColor(pal.text),
            keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }, onSearch = { onSubmit?.invoke() }),
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth()
                        .let { if (multi) it.heightIn(min = height) else it.height(height) }
                        .clip(RoundedCornerShape(Radius.md)).background(pal.paper3)
                        .border(1.dp, if (focused) pal.muted.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(Radius.md))
                        .padding(horizontal = 10.dp, vertical = if (multi) 8.dp else 0.dp),
                    verticalAlignment = if (multi) Alignment.Top else Alignment.CenterVertically,
                ) {
                    leading?.let { Icon(it, null, tint = pal.faint, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, style = style, color = pal.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                    if (clearable && value.isNotEmpty()) PIconButton(Icons.Rounded.Close, t("Очистить", "Clear"), { onChange("") }, size = 22.dp, iconSize = 14.dp)
                }
            },
        )
    }
}

/** An on/off switch at desktop size (Material's is 52×32). */
@Composable
fun PSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val track by animateColorAsState(if (checked) MaterialTheme.colorScheme.primary else pal.line, tween(160))
    val knob by androidx.compose.animation.core.animateDpAsState(if (checked) 16.dp else 2.dp, tween(160))
    Box(modifier.size(36.dp, 22.dp).clip(CircleShape).background(track).clickable { onChange(!checked) }.pointerHoverIcon(PointerIcon.Hand)) {
        Box(Modifier.padding(start = knob, top = 2.dp).size(18.dp).clip(CircleShape).background(if (checked) MaterialTheme.colorScheme.onPrimary else pal.text))
    }
}

/** A small square tick box. */
@Composable
fun PCheckbox(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val pal = LocalPal.current
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier.size(18.dp).clip(RoundedCornerShape(Radius.xs)).background(if (checked) primary else Color.Transparent)
            .border(1.5.dp, if (checked) primary else pal.muted, RoundedCornerShape(Radius.xs))
            .clickable(enabled = enabled) { onChange(!checked) }.graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        contentAlignment = Alignment.Center,
    ) { if (checked) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)) }
}

/** A thin progress line (scans, downloads, video). */
@Composable
fun PProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = LocalPal.current.text.copy(alpha = 0.7f)) {
    val pal = LocalPal.current
    val f by androidx.compose.animation.core.animateFloatAsState(fraction.coerceIn(0f, 1f), tween(250))
    Canvas(modifier.height(3.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(pal.line, cornerRadius = r)
        drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
    }
}

/** A small spinner in the text colour, for «loading». */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 20.dp) =
    androidx.compose.material3.CircularProgressIndicator(modifier.size(size), color = LocalPal.current.muted, strokeWidth = 2.dp, trackColor = Color.Transparent)

/**
 * A window over the app: a title (and a line under it), the [content], and [buttons] at the bottom
 * right — the main one last. Esc or a click outside closes it unless [dismissible] is off (while saving).
 */
@Composable
fun PDialog(
    title: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, width: Dp = 520.dp, subtitle: String? = null, dismissible: Boolean = true,
    buttons: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val pal = LocalPal.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            modifier.width(width), shape = RoundedCornerShape(Radius.xl), color = pal.paper2, shadowElevation = 24.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, pal.line),
        ) {
            androidx.compose.foundation.layout.Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 18.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = pal.muted, modifier = Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(16.dp))
                content()
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = buttons)
            }
        }
    }
}

/** A small capital heading inside a dialog or a card. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = Type.caption, color = LocalPal.current.muted, modifier = modifier)

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
