package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.clickable
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
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        item { Text(t("История", "History"), style = MaterialTheme.typography.displayMedium) }
        item {
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to t("Неделя", "Week"), 30 to t("Месяц", "Month"), 365 to t("Год", "Year"), 100_000 to t("Всё время", "All time")).forEach { (d, l) -> Pill(l, days == d, { days = d }) }
            }
        }
        item {
            Row {
                Column(Modifier.weight(1f)) { Text(fmt(recent.sumOf { it.listenedMs }), style = MaterialTheme.typography.displayMedium, color = accent); Text(t("музыки", "of music"), color = pal.muted) }
                Column(Modifier.weight(1f)) { Text("${recent.size}", style = MaterialTheme.typography.displayMedium); Text(t("прослушиваний", "plays"), color = pal.muted) }
            }
        }
        if (top.isNotEmpty()) {
            item { SectionHeader(t("Чаще всего", "Most played")) }
            itemsIndexed(top) { i, (id, p) ->
                Row(Modifier.fillMaxWidth().clickable {
                    val tracks = top.mapNotNull { graph.library.get(it.first) }
                    graph.library.get(id)?.let { tr -> graph.player.play(tracks, tracks.indexOf(tr).coerceAtLeast(0)) }
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = if (i < 3) accent else pal.faint, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.width(40.dp))
                    Text("${p.first().title} — ${p.first().artist}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${p.size}×", color = pal.muted)
                }
            }
        }
        if (artists.isNotEmpty()) {
            item { SectionHeader(t("Исполнители", "Artists")) }
            items(artists) { (a, ms) ->
                Row(Modifier.fillMaxWidth().clickable { nav.go(Screen.Artist(a)) }.padding(vertical = 6.dp)) { Text(a, Modifier.weight(1f)); Text(fmt(ms), color = pal.muted) }
            }
        }
        item { SectionHeader(t("Недавно", "Recently")) }
        items(plays.takeLast(100).reversed()) { p ->
            Row(Modifier.fillMaxWidth().clickable { graph.library.get(p.trackId)?.let { graph.player.play(listOf(it)) } }.padding(vertical = 5.dp)) {
                Column(Modifier.weight(1f)) { Text(p.title, maxLines = 1); Text(p.artist, color = pal.muted, fontSize = 12.sp, maxLines = 1) }
                Text(fmtDate.format(Date(p.at)), color = pal.faint, fontSize = 12.sp)
            }
        }
        if (plays.isEmpty()) item { EmptyState(t("Пока пусто", "Nothing yet"), t("Трек попадает в историю после 30 секунд прослушивания.", "A track counts after 30 seconds of listening.")) }
    }
}

