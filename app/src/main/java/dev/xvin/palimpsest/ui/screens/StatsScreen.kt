package dev.xvin.palimpsest.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.data.db.DayRow
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.Pill
import dev.xvin.palimpsest.ui.components.SectionHeader
import dev.xvin.palimpsest.ui.components.formatListening
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.i18n.plural

private enum class Period(private val labelRes: Int, val days: Long?) {
    WEEK(R.string.period_week, 7), MONTH(R.string.period_month, 30), YEAR(R.string.tag_year, 365), ALL(R.string.period_all_time, null);

    val label: String get() = tr(labelRes)
}

@Composable
fun StatsScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    var period by rememberSaveable { mutableStateOf(Period.MONTH) }
    val since = remember(period) {
        period.days?.let { LocalDate.now().minusDays(it - 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() } ?: 0L
    }
    val plays = graph.db.plays()
    val topTracks by remember(since) { plays.observeTopTracks(since) }.collectAsStateWithLifecycle(emptyList())
    val topArtists by remember(since) { plays.observeTopArtists(since, 10) }.collectAsStateWithLifecycle(emptyList())
    val days by remember(since) { plays.observeDays(since) }.collectAsStateWithLifecycle(emptyList())
    val recent by remember { plays.observeRecent(100) }.collectAsStateWithLifecycle(emptyList())
    val accent = LocalAccent.current

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
                Text(tr(R.string.tab_history), style = MaterialTheme.typography.displayMedium, modifier = Modifier.weight(1f).padding(start = 16.dp, top = 12.dp))
                androidx.compose.material3.Button(onClick = nav::openWrapped, modifier = Modifier.padding(top = 12.dp)) { Text(tr(R.string.wrapped_button)) }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.entries.forEach { p -> Pill(p.label, period == p, { period = p }) }
            }
        }
        item {
            val totalMs = days.sumOf { it.listenedMs }
            val totalPlays = days.sumOf { it.plays }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(formatListening(totalMs), style = MaterialTheme.typography.headlineLarge, color = accent)
                    Text(tr(R.string.stats_of_music), color = Ink.Muted, style = MaterialTheme.typography.labelMedium)
                }
                Column(Modifier.weight(1f)) {
                    Text("$totalPlays", style = MaterialTheme.typography.headlineLarge)
                    Text(plural(R.plurals.plays_word, totalPlays), color = Ink.Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (days.isNotEmpty()) item { DaysChart(days, period, Modifier.fillMaxWidth().height(120.dp).padding(16.dp)) }

        if (topTracks.isNotEmpty()) {
            item { SectionHeader(tr(R.string.stats_top)) }
            itemsIndexed(topTracks.take(10)) { i, row ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = row.trackId != null) { row.trackId?.let { nav.openTrack(it, 0) } }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.headlineSmall, color = if (i < 3) accent else Ink.Faint, modifier = Modifier.width(36.dp))
                    Text(row.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${row.plays}×", style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                }
            }
        }
        if (topArtists.isNotEmpty()) {
            item { SectionHeader(tr(R.string.lib_artists)) }
            itemsIndexed(topArtists) { i, row ->
                Row(
                    Modifier.fillMaxWidth().clickable { nav.openArtist(row.label) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = Ink.Faint, modifier = Modifier.width(36.dp))
                    Text(row.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatListening(row.listenedMs), style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionHeader(tr(R.string.stats_recent)) }
            val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
            items(recent, key = { it.id }) { e ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = e.trackId != null) { e.trackId?.let { nav.openTrack(it, 0) } }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.artist, style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1)
                    }
                    Text(fmt.format(Date(e.startedAt)), style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
                }
            }
        } else {
            item {
                Text(
                    tr(R.string.stats_empty),
                    Modifier.padding(16.dp), color = Ink.Muted,
                )
            }
        }
    }
}

/** Minutes per day as rounded bars; empty days stay visible as dots so gaps read as gaps. */
@Composable
private fun DaysChart(days: List<DayRow>, period: Period, modifier: Modifier) {
    val accent = LocalAccent.current
    val byDay = remember(days) { days.associateBy { it.day } }
    val count = (period.days ?: days.size.toLong().coerceAtLeast(1)).toInt().coerceAtMost(365)
    val start = remember(period, days) {
        if (period.days != null) LocalDate.now().minusDays(count - 1L) else LocalDate.parse(days.first().day)
    }
    val series = remember(byDay, start, count) { (0 until count).map { byDay[start.plusDays(it.toLong()).toString()]?.listenedMs ?: 0L } }
    val max = series.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column {
        Canvas(modifier) {
            val n = series.size
            val slot = size.width / n
            val w = (slot * 0.7f).coerceAtLeast(1.5f)
            series.forEachIndexed { i, v ->
                val h = (v.toFloat() / max) * size.height
                val x = i * slot + (slot - w) / 2
                if (v == 0L) drawCircle(Ink.Line, radius = w.coerceAtMost(3f) / 2 + 0.5f, center = Offset(x + w / 2, size.height - 2f))
                else drawRoundRect(accent, Offset(x, size.height - h), Size(w, h), CornerRadius(w / 2, w / 2))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(start.toString().substring(5).replace('-', '.'), style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
            Spacer(Modifier.weight(1f))
            Text(tr(R.string.stats_max_per_day, formatListening(max)), style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
        }
    }
}
