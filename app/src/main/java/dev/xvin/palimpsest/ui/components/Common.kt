package dev.xvin.palimpsest.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xvin.palimpsest.AppGraph
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr

val LocalGraph = compositionLocalOf<AppGraph> { error("no graph") }

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "–:––"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatListening(ms: Long): String {
    val m = ms / 60_000
    return when {
        m >= 60 * 24 -> tr(R.string.duration_days_hours).format(m / (60 * 24), (m / 60) % 24)
        m >= 60 -> tr(R.string.duration_hours_minutes).format(m / 60, m % 60)
        else -> tr(R.string.duration_minutes, m)
    }
}

@Composable
fun TrackRow(
    track: TrackEntity,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    index: Int? = null,
    subtitle: String? = null,
) {
    val accent = LocalAccent.current
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMore)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(Modifier.width(28.dp)) {
                if (playing) PlayingBars(Modifier.size(14.dp))
                else Text("$index", style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = Ink.Faint)
            }
        } else {
            // What's playing shows bars over its cover, not only a coloured title.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                TrackArtwork(track, Modifier.size(48.dp), corner = 6.dp)
                if (playing) Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    PlayingBars(Modifier.size(16.dp), color = Color.White)
                }
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (playing) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.liked) {
                    Icon(Icons.Rounded.Favorite, null, Modifier.size(12.dp), tint = Ink.Muted)
                    Spacer(Modifier.width(4.dp))
                }
                if (track.path == null && track.service != Service.LOCAL) {
                    Icon(Icons.Rounded.CloudQueue, tr(R.string.not_downloaded), Modifier.size(13.dp), tint = Ink.Muted)
                    Spacer(Modifier.width(4.dp))
                }
                if (track.updateAvailable) {
                    Icon(Icons.Rounded.NewReleases, tr(R.string.new_version_available), Modifier.size(13.dp), tint = accent)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    subtitle ?: listOfNotNull(track.artist, track.album).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(formatDuration(track.durationMs), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum", letterSpacing = 0.sp), color = Ink.Faint)
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, tr(R.string.more), tint = Ink.Muted) }
    }
}

/** Thin scrubber with buffered range; drag or tap to seek. */
@Composable
fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalAccent.current,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) dragFraction else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { o -> if (durationMs > 0) onSeek((o.x / size.width * durationMs).toLong()) }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = true; dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { dragging = false; onSeek((dragFraction * durationMs).toLong()) },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val h = if (dragging) 6.dp else 3.dp
            Box(Modifier.fillMaxWidth().height(h).clip(CircleShape).background(Ink.Line))
            Box(Modifier.fillMaxWidth(buffered).height(h).clip(CircleShape).background(Ink.Faint.copy(alpha = 0.6f)))
            Box(Modifier.fillMaxWidth(fraction).height(h).clip(CircleShape).background(color))
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Box(Modifier.size(if (dragging) 16.dp else 12.dp).clip(CircleShape).background(color)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(if (dragging) (dragFraction * durationMs).toLong() else positionMs), style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
        }
    }
}

/** Vertical fader for EQ bands: drag or tap anywhere on the column; centre line marks 0. */
@Composable
fun VerticalFader(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalAccent.current,
) {
    fun toValue(y: Float, h: Int) = (range.endInclusive - (y / h).coerceIn(0f, 1f) * (range.endInclusive - range.start))
    val frac = (value - range.start) / (range.endInclusive - range.start)
    val zero = (0f - range.start) / (range.endInclusive - range.start)
    androidx.compose.foundation.Canvas(
        modifier
            .pointerInput(range) { detectTapGestures { o -> onChange(toValue(o.y, size.height)) } }
            .pointerInput(range) {
                detectVerticalDragGestures { change, _ -> onChange(toValue(change.position.y, size.height)) }
            },
    ) {
        val cx = size.width / 2
        val yOf = { f: Float -> size.height * (1 - f) }
        val track = 4.dp.toPx()
        drawLine(Ink.Line, androidx.compose.ui.geometry.Offset(cx, 0f), androidx.compose.ui.geometry.Offset(cx, size.height), track, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(cx, yOf(zero)), androidx.compose.ui.geometry.Offset(cx, yOf(frac)), track, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(Ink.Faint, androidx.compose.ui.geometry.Offset(cx - 8.dp.toPx(), yOf(zero)), androidx.compose.ui.geometry.Offset(cx + 8.dp.toPx(), yOf(zero)), 1.dp.toPx())
        drawCircle(color, 9.dp.toPx(), androidx.compose.ui.geometry.Offset(cx, yOf(frac)))
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.Muted, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Lane: selected pills are plain white (the theme's primary); Palimpsest: the accent ink.
    val uiStyle = dev.xvin.palimpsest.ui.theme.LocalUiStyle.current
    val accent = if (uiStyle != dev.xvin.palimpsest.data.settings.UiStyle.PALIMPSEST) MaterialTheme.colorScheme.primary else LocalAccent.current
    val mono = uiStyle == dev.xvin.palimpsest.data.settings.UiStyle.MONO
    // Palimpsest and Vinyl: a quiet choice — the picked one outlined and bright, the rest muted;
    // the accent is kept for what plays. The other styles keep their filled pills.
    val quiet = uiStyle == dev.xvin.palimpsest.data.settings.UiStyle.PALIMPSEST || uiStyle == dev.xvin.palimpsest.data.settings.UiStyle.VINYL
    val bg by androidx.compose.animation.animateColorAsState(
        if (quiet) (if (selected) Ink.Paper3 else Ink.Paper3.copy(alpha = 0f)) else if (selected) accent else Ink.Paper3,
        androidx.compose.animation.core.tween(220), label = "pill",
    )
    val shape = if (mono) RoundedCornerShape(0.dp) else RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .then(if (quiet) Modifier.border(1.dp, if (selected) Ink.Muted.copy(alpha = 0.6f) else Ink.Line, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = when { quiet -> if (selected) Ink.Text else Ink.Muted; selected -> MaterialTheme.colorScheme.onPrimary; else -> Ink.Text },
        )
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, art: Int? = null, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // A small drawing of what would be here (the same ones as on the computer).
        art?.let { Icon(androidx.compose.ui.res.painterResource(it), null, Modifier.size(120.dp, 96.dp).padding(bottom = 16.dp), tint = Ink.Faint) }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Ink.Text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** Seek bar drawn as the track's loudness envelope; played part in the accent colour. */
@Composable
fun WaveSeekBar(
    wave: FloatArray,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalAccent.current,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier) {
        androidx.compose.foundation.Canvas(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .pointerInput(durationMs) { detectTapGestures { o -> if (durationMs > 0) onSeek((o.x / size.width * durationMs).toLong()) } }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { dragFraction?.let { onSeek((it * durationMs).toLong()) }; dragFraction = null },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
        ) {
            val n = wave.size
            val slot = size.width / n
            val w = slot * 0.62f
            val mid = size.height / 2
            for (i in 0 until n) {
                val h = (0.12f + wave[i] * 0.88f) * size.height / 2
                val x = i * slot + (slot - w) / 2
                val played = (i + 0.5f) / n <= fraction
                drawRoundRect(
                    color = if (played) color else Ink.Faint.copy(alpha = 0.55f),
                    topLeft = androidx.compose.ui.geometry.Offset(x, mid - h),
                    size = androidx.compose.ui.geometry.Size(w, h * 2),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(dragFraction?.let { (it * durationMs).toLong() } ?: positionMs), style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
        }
    }
}

/**
 * Three bars by what's playing: they bounce while it plays and rest while paused (the phone's
 * analyser runs only on the player screen, so they keep their own rhythm here).
 */
@Composable
fun PlayingBars(modifier: Modifier = Modifier, color: Color = LocalAccent.current) {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "bars")
    val levels = listOf(0 to 520, 1 to 380, 2 to 610).map { (k, ms) ->
        pulse.animateFloat(0.25f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(ms, delayMillis = k * 90), androidx.compose.animation.core.RepeatMode.Reverse), label = "bar$k")
    }
    androidx.compose.foundation.Canvas(modifier) {
        val bar = size.width / 5
        levels.forEachIndexed { k, v ->
            val hh = size.height * (if (state.isPlaying) v.value else listOf(0.5f, 0.8f, 0.35f)[k])
            drawRoundRect(color, androidx.compose.ui.geometry.Offset(k * bar * 2f, size.height - hh), androidx.compose.ui.geometry.Size(bar, hh), androidx.compose.ui.geometry.CornerRadius(bar / 2))
        }
    }
}

/** The like button: liking makes the heart jump with a ring breathing out of it; unliking just empties it. */
@Composable
fun HeartButton(liked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, likedTint: Color = LocalAccent.current, idleTint: Color = Ink.Muted, iconSize: androidx.compose.ui.unit.Dp = 26.dp) {
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    val ring = remember { androidx.compose.animation.core.Animatable(1f) }
    var was by remember { mutableStateOf(liked) }
    androidx.compose.runtime.LaunchedEffect(liked) {
        if (liked && !was) kotlinx.coroutines.coroutineScope {
            launch { ring.snapTo(0f); ring.animateTo(1f, androidx.compose.animation.core.tween(520)) }
            scale.snapTo(0.55f)
            scale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.32f, stiffness = 520f))
        }
        was = liked
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (ring.value < 1f) androidx.compose.foundation.Canvas(Modifier.size(iconSize * 1.9f)) {
            drawCircle(likedTint.copy(alpha = 0.4f * (1f - ring.value)), radius = size.minDimension / 2 * (0.4f + 0.6f * ring.value), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        IconButton(onClick = onToggle) {
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                tr(if (liked) R.string.action_unlike else R.string.action_like),
                Modifier.size(iconSize).graphicsLayer { scaleX = scale.value; scaleY = scale.value },
                tint = if (liked) likedTint else idleTint,
            )
        }
    }
}
