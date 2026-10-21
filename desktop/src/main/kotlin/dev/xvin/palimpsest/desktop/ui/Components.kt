package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.xvin.palimpsest.desktop.data.Track

/** Cover art with a lettered tile underneath, so untagged files still look intentional. */
@Composable
fun Artwork(model: Any?, seed: String, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    val pal = LocalPal.current
    Box(modifier.clip(RoundedCornerShape(corner)).background(placeholderColor(seed, pal.light)), contentAlignment = Alignment.Center) {
        Text(seed.trim().take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = Color.White.copy(alpha = 0.7f))
        if (model != null) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

private fun placeholderColor(seed: String, light: Boolean): Color {
    val h = (seed.hashCode() and 0x7fffffff) % 360
    return Color(java.awt.Color.HSBtoRGB(h / 360f, 0.35f, if (light) 0.7f else 0.32f))
}

/** A track's cover: the embedded picture of a file (extracted once) or the service's URL. */
@Composable
fun TrackArt(t: Track?, modifier: Modifier = Modifier, corner: Dp = 6.dp) {
    val graph = LocalGraph.current
    val model by produceState<Any?>(t?.coverUrl, t?.id, t?.modifiedAt) { value = graph.covers.model(t) }
    Artwork(model, t?.album ?: t?.title ?: "?", modifier, corner)
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val pal = LocalPal.current
    Box(
        modifier.clip(RoundedCornerShape(50)).background(if (selected) accent else pal.paper3).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.onPrimary else pal.text) }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalPal.current.muted, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(body, color = LocalPal.current.muted)
    }
}

fun fmt(ms: Long): String = if (ms <= 0) "–:––" else if (ms >= 3_600_000) "%d:%02d:%02d".format(ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60) else "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

/**
 * A row of a track table: number or cover, title and artist, album, length. Hover highlights,
 * double click plays (like every desktop player), right click opens the menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackLine(
    t: Track,
    index: Int?,
    playing: Boolean,
    liked: Boolean,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
    showAlbum: Boolean = true,
) {
    val pal = LocalPal.current
    val accent = LocalAccent.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (playing) accent.copy(alpha = 0.12f) else if (hovered) pal.paper3 else Color.Transparent)
            .hoverable(hover)
            .combinedClickable(onClick = {}, onDoubleClick = onPlay, onLongClick = onMenu)
            .onRightClick(onMenu)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
            if (hovered) Text("▶", color = accent, modifier = Modifier.clickable(onClick = onPlay))
            else if (index != null) Text("$index", color = pal.faint, fontSize = 12.sp)
        }
        TrackArt(t, Modifier.size(36.dp), 4.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1.3f)) {
            Text(t.title, color = if (playing) accent else pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            Text(t.artist, color = pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        }
        if (showAlbum) Text(t.album.orEmpty(), color = pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Text(if (liked) "♥" else "", color = accent, modifier = Modifier.width(22.dp))
        Text(if (t.path == null) "☁" else "", color = pal.faint, modifier = Modifier.width(20.dp), fontSize = 12.sp)
        Text(fmt(t.durationMs), color = pal.faint, fontSize = 12.sp, modifier = Modifier.width(52.dp))
    }
}
