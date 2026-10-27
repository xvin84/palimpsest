package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun HistoryScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val accent = LocalAccent.current
    val plays by graph.history.plays.collectAsState()
    var days by remember { mutableStateOf(30) }
    val since = System.currentTimeMillis() - days * 86_400_000L
    val recent = plays.filter { it.at >= since }
    val top = recent.groupBy { it.trackId }.map { (id, p) -> id to p }.sortedByDescending { it.second.size }.take(15)
    val artists = recent.flatMap { p -> dev.xvin.palimpsest.core.library.ArtistNames.split(p.artist).map { it to p.listenedMs } }
        .groupBy({ it.first }, { it.second }).map { it.key to it.value.sum() }.sortedByDescending { it.second }.take(10)
    val fmtDate = remember { SimpleDateFormat("d MMM, HH:mm") }
    LazyColumn(Modifier.fillMaxSize().padding(start = 28.dp, end = 20.dp), contentPadding = PaddingValues(top = 22.dp, bottom = 32.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("История", "History"), style = MaterialTheme.typography.displayMedium, modifier = Modifier.weight(1f))
                Tabs(listOf(7 to t("Неделя", "Week"), 30 to t("Месяц", "Month"), 365 to t("Год", "Year"), 100_000 to t("Всё время", "All time")), days, { days = it })
            }
        }
        item {
            // Two figures for the period, on quiet cards.
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                @Composable
                fun Stat(value: String, label: String, modifier: Modifier) {
                    Column(modifier.clip(RoundedCornerShape(Radius.xl)).background(pal.paper2).border(1.dp, pal.line.copy(alpha = 0.7f), RoundedCornerShape(Radius.xl)).padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text(value, style = MaterialTheme.typography.displayMedium)
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = pal.muted)
                    }
                }
                Stat(fmtTotal(recent.sumOf { it.listenedMs }), t("музыки", "of music"), Modifier.weight(1f))
                Stat("${recent.size}", count(recent.size, "прослушивание", "прослушивания", "прослушиваний", "play", "plays").substringAfter(' '), Modifier.weight(1f))
            }
        }
        if (top.isNotEmpty()) {
            item { SectionHeader(t("Чаще всего", "Most played")) }
            itemsIndexed(top) { i, (id, p) ->
                val track = graph.library.get(id)
                HistoryRow(track, p.first().title, p.first().artist, "${p.size}×", "${i + 1}") {
                    val tracks = top.mapNotNull { graph.library.get(it.first) }
                    track?.let { tr -> graph.player.play(tracks, tracks.indexOf(tr).coerceAtLeast(0)) }
                }
            }
        }
        if (artists.isNotEmpty()) {
            item { SectionHeader(t("Исполнители", "Artists")) }
            items(artists) { (a, ms) ->
                val any = remember(a) { graph.library.tracks.value.firstOrNull { tr -> tr.artists.any { it == a } } }
                ListRow({ TrackArt(any, Modifier.size(40.dp), 20.dp) }, a, fmtTotal(ms)) { nav.go(Screen.Artist(a)) }
            }
        }
        item { SectionHeader(t("Недавно", "Recently")) }
        items(plays.takeLast(100).reversed()) { p ->
            HistoryRow(graph.library.get(p.trackId), p.title, p.artist, fmtDate.format(Date(p.at)), null) { graph.library.get(p.trackId)?.let { graph.player.play(listOf(it)) } }
        }
        if (plays.isEmpty()) item { EmptyState(t("Пока пусто", "Nothing yet"), t("Трек попадает в историю после 30 секунд прослушивания.", "A track counts after 30 seconds of listening.")) }
    }
}

/** A played track: its place (for the top), cover, title and artist, and a figure on the right. */
@Composable
private fun HistoryRow(track: dev.xvin.palimpsest.desktop.data.Track?, title: String, artist: String, meta: String, rank: String?, onClick: () -> Unit) {
    val pal = LocalPal.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(Radius.md)).background(if (hovered) pal.text.copy(alpha = 0.05f) else Color.Transparent)
            .hoverable(hover).clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        rank?.let { Text(it, style = Type.meta, color = pal.faint, textAlign = TextAlign.Center, modifier = Modifier.width(Cols.lead)) }
        TrackArt(track, Modifier.size(36.dp), Radius.xs)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, style = Type.rowSub, color = pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(meta, style = Type.meta, color = pal.faint)
    }
}
