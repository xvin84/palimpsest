package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.xvin.palimpsest.desktop.data.Track

/**
 * Cover art over a generated one ([GeneratedCover]: colours, a pattern and the title, all from the
 * name), so music without a picture still has a cover of its own. Round tiles (artists) skip the text.
 */
@Composable
fun Artwork(model: Any?, seed: String, modifier: Modifier = Modifier, corner: Dp = 8.dp, artist: String? = null) {
    Box(modifier.clip(RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
        GeneratedCover(seed, artist, Modifier.fillMaxSize(), withText = corner < 30.dp)
        if (model != null) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** A track's cover: the embedded picture of a file (extracted once) or the service's URL. */
@Composable
fun TrackArt(t: Track?, modifier: Modifier = Modifier, corner: Dp = 6.dp) {
    val graph = LocalGraph.current
    val model by produceState<Any?>(t?.coverUrl, t?.id, t?.modifiedAt) { value = graph.covers.model(t) }
    Artwork(model, t?.album ?: t?.title ?: "?", modifier, corner, artist = t?.albumArtist ?: t?.artist)
}

/** One choice of a few (settings, dialogs): picked — outlined and bright, the rest quiet. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier.height(30.dp).clip(shape).background(if (selected) pal.paper3 else Color.Transparent)
            .border(1.dp, if (selected) pal.muted.copy(alpha = 0.55f) else pal.line, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) pal.text else pal.muted, maxLines = 1) }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp).height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Type.caption, color = LocalPal.current.muted, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalPal.current.muted, textAlign = TextAlign.Center)
    }
}

fun fmt(ms: Long): String = if (ms <= 0) "–:––" else if (ms >= 3_600_000) "%d:%02d:%02d".format(ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60) else "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

/** A long total as people say it: «108 ч 31 мин». */
fun fmtTotal(ms: Long): String {
    val h = ms / 3_600_000
    val m = (ms / 60_000) % 60
    return if (h > 0) t("$h ч $m мин", "$h h $m min") else t("$m мин", "$m min")
}

/** «1 трек», «2 трека», «5 треков» — Russian counts take three forms. */
fun count(n: Int, one: String, few: String, many: String, enOne: String, enMany: String): String {
    if (!ru) return "$n " + if (n == 1) enOne else enMany
    val m10 = n % 10
    val m100 = n % 100
    return "$n " + when { m10 == 1 && m100 != 11 -> one; m10 in 2..4 && m100 !in 12..14 -> few; else -> many }
}

fun tracksCount(n: Int) = count(n, "трек", "трека", "треков", "track", "tracks")

private val dayRu = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.forLanguageTag("ru"))
private val dayEn = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)
fun fmtDay(epochMs: Long): String = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).format(if (ru) dayRu else dayEn)

/** Column widths shared by a track table's header and its rows, so the two line up. */
object Cols {
    val lead = 40.dp
    val art = 36.dp
    val gap = 12.dp
    val added = 108.dp
    val like = 32.dp
    val cloud = 20.dp
    val time = 48.dp
    val more = 36.dp
    val pad = 8.dp
}

/**
 * A row of a track table: number or cover, title and artist, album, length. Hover highlights,
 * click selects (Ctrl and Shift add), double click plays (like every desktop player), right click or ⋯ opens the menu.
 * The heart is a button: shown when liked, offered on hover.
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
    selected: Boolean = false,
    onSelect: ((PointerKeyboardModifiers) -> Unit)? = null,
    showAdded: Boolean = false,
) {
    val graph = LocalGraph.current
    val pal = LocalPal.current
    val accent = LocalAccent.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg by androidx.compose.animation.animateColorAsState(
        if (selected) accent.copy(alpha = 0.16f) else if (hovered) pal.text.copy(alpha = 0.05f) else Color.Transparent,
        androidx.compose.animation.core.tween(140),
    )
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(Radius.md)).background(bg)
            .hoverable(hover)
            .rowClicks(onPress = { onSelect?.invoke(it) }, onDouble = onPlay, onSecondary = onMenu)
            .padding(horizontal = Cols.pad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(Cols.lead), contentAlignment = Alignment.Center) {
            when {
                hovered -> PIconButton(Icons.Rounded.PlayArrow, t("Слушать", "Play"), onPlay, size = 28.dp, iconSize = 20.dp, tint = pal.text)
                playing -> {
                    // Only the playing row listens to play/pause, so the rest don't redraw with every position report.
                    val running by remember(graph) { graph.player.state.map { it.playing }.distinctUntilChanged() }.collectAsState(false)
                    PlayingBars(running)
                }
                index != null -> Text("$index", style = Type.meta, color = pal.faint)
            }
        }
        TrackArt(t, Modifier.size(Cols.art), Radius.xs)
        Spacer(Modifier.width(Cols.gap))
        Column(Modifier.weight(1.4f)) {
            Text(t.title, style = Type.rowTitle, color = if (playing) accent else pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, style = Type.rowSub, color = pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showAlbum) Text(t.album.orEmpty(), style = Type.rowSub, color = pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        if (showAdded) Text(fmtDay(t.addedAt), style = Type.meta, color = pal.faint, maxLines = 1, modifier = Modifier.width(Cols.added))
        Box(Modifier.width(Cols.like), contentAlignment = Alignment.Center) {
            if (liked || hovered) PIconButton(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, t("Любимое", "Favourite"),
                { graph.library.setLiked(t.id, !liked); likeRemote(graph, t, !liked) }, size = 28.dp, iconSize = 16.dp, tint = if (liked) pal.muted else pal.faint,
            )
        }
        Box(Modifier.width(Cols.cloud), contentAlignment = Alignment.Center) {
            if (t.path == null) Icon(Icons.Rounded.Cloud, t("Не скачан", "Not downloaded"), tint = pal.faint.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
        }
        Text(fmt(t.durationMs), style = Type.meta, color = pal.faint, textAlign = TextAlign.End, modifier = Modifier.width(Cols.time))
        Box(Modifier.width(Cols.more), contentAlignment = Alignment.CenterEnd) {
            if (hovered || selected) PIconButton(Icons.Rounded.MoreHoriz, t("Действия", "Actions"), onMenu, size = 28.dp, iconSize = 18.dp)
        }
    }
}
