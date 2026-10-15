package dev.xvin.palimpsest.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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

val LocalGraph = compositionLocalOf<AppGraph> { error("no graph") }

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "–:––"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatListening(ms: Long): String {
    val m = ms / 60_000
    return when {
        m >= 60 * 24 -> "%d д %d ч".format(m / (60 * 24), (m / 60) % 24)
        m >= 60 -> "%d ч %d мин".format(m / 60, m % 60)
        else -> "$m мин"
    }
}

fun pluralRu(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
    return "$n $w"
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
            Text(
                "$index",
                style = MaterialTheme.typography.labelMedium,
                color = if (playing) accent else Ink.Faint,
                modifier = Modifier.width(28.dp),
            )
        } else {
            TrackArtwork(track, Modifier.size(48.dp), corner = 6.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                color = if (playing) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.liked) {
                    Icon(Icons.Rounded.Favorite, null, Modifier.size(12.dp), tint = accent.copy(alpha = 0.8f))
                    Spacer(Modifier.width(4.dp))
                }
                if (track.path == null && track.service != Service.LOCAL) {
                    Icon(Icons.Rounded.CloudQueue, "Не скачан", Modifier.size(13.dp), tint = Ink.Muted)
                    Spacer(Modifier.width(4.dp))
                }
                if (track.updateAvailable) {
                    Icon(Icons.Rounded.NewReleases, "Есть новая версия", Modifier.size(13.dp), tint = accent)
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
        Text(formatDuration(track.durationMs), style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "Ещё", tint = Ink.Muted) }
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

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.Muted, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent else Ink.Paper3)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else Ink.Text,
        )
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Ink.Text)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}
