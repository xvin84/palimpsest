package dev.xvin.palimpsest.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xvin.palimpsest.data.db.CountRow
import dev.xvin.palimpsest.data.db.DayRow
import dev.xvin.palimpsest.data.db.HourRow
import dev.xvin.palimpsest.data.db.PlayEventEntity
import dev.xvin.palimpsest.data.library.ArtProvider
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.Artwork
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.Pill
import dev.xvin.palimpsest.ui.components.pluralRu
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import dev.xvin.palimpsest.ui.theme.Serif
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class Wrapped(
    val minutes: Long,
    val plays: Int,
    val artists: List<CountRow>,
    val tracks: List<CountRow>,
    val hours: List<HourRow>,
    val topDay: DayRow?,
    val first: PlayEventEntity?,
)

/** «Итоги»: your listening as a short story — swipe through big numbers. */
@Composable
fun WrappedScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    var allTime by remember { mutableStateOf(false) }
    val since = if (allTime) 0L else LocalDate.now().withDayOfYear(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val data by produceState<Wrapped?>(null, since) {
        val p = graph.db.plays()
        value = Wrapped(
            minutes = p.topTracksOnce(since, 100_000).sumOf { it.listenedMs } / 60_000,
            plays = p.countSince(since),
            artists = p.topArtistsOnce(since, 5),
            tracks = p.topTracksOnce(since, 5),
            hours = p.hours(since),
            topDay = p.topDay(since),
            first = p.firstSince(since),
        )
    }
    val accent = LocalAccent.current
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), Ink.Paper)))) {
        val d = data
        if (d == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else if (d.plays == 0) {
            Text("Пока нечего подводить — послушай что-нибудь 🙂", Modifier.align(Alignment.Center).padding(32.dp), color = Ink.Muted)
        } else {
            val pages = listOf<@Composable () -> Unit>(
                { Big("${d.minutes}", "минут музыки", if (allTime) "за всё время" else "в ${LocalDate.now().year} году") },
                { d.artists.firstOrNull()?.let { a -> Hero("Твой исполнитель", a.label, "${a.plays} прослушиваний", a.trackId) } },
                { TopList("Треки, которые ты заслушал", d.tracks) },
                { Hours(d.hours) },
                {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        d.topDay?.let { day ->
                            Big("${day.listenedMs / 60_000}", "минут в самый музыкальный день",
                                LocalDate.parse(day.day).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))))
                        }
                        d.first?.let {
                            Spacer(Modifier.height(32.dp))
                            Text("Первым ${if (allTime) "был" else "в этом году был"} трек", color = Ink.Muted)
                            Text("${it.artist} — ${it.title}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
                        }
                    }
                },
            )
            val pager = rememberPagerState { pages.size }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // Story-style progress segments.
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(pages.size) { i ->
                        Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(if (i <= pager.currentPage) Ink.Text else Ink.Text.copy(alpha = 0.25f)))
                    }
                }
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Этот год", !allTime, { allTime = false })
                    Spacer(Modifier.width(8.dp))
                    Pill("Всё время", allTime, { allTime = true })
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = nav::back) { Icon(Icons.Rounded.Close, "Закрыть") }
                }
                HorizontalPager(pager, Modifier.weight(1f)) { page ->
                    // Each page rises in as it comes into view.
                    val appear = remember { Animatable(0f) }
                    LaunchedEffect(pager.currentPage == page) { if (pager.currentPage == page) appear.animateTo(1f, tween(600)) }
                    Box(
                        Modifier.fillMaxSize().graphicsLayer { alpha = appear.value; translationY = (1 - appear.value) * 80f },
                        contentAlignment = Alignment.Center,
                    ) { pages[page]() }
                }
                Text("листай →", Modifier.align(Alignment.CenterHorizontally).padding(8.dp), color = Ink.Faint, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun Big(number: String, label: String, sub: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(number, style = MaterialTheme.typography.displayLarge.copy(fontSize = 96.sp, fontFamily = Serif), color = LocalAccent.current)
        Text(label, style = MaterialTheme.typography.headlineSmall)
        Text(sub, color = Ink.Muted)
    }
}

@Composable
private fun Hero(caption: String, name: String, sub: String, trackId: Long?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Text(caption.uppercase(), style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
        Spacer(Modifier.height(16.dp))
        Artwork(trackId?.let { ArtProvider.uriFor(it) }, name, Modifier.size(220.dp), corner = 110.dp)
        Spacer(Modifier.height(20.dp))
        Text(name, style = MaterialTheme.typography.displayMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(sub, color = Ink.Muted)
    }
}

@Composable
private fun TopList(caption: String, rows: List<CountRow>) {
    Column(Modifier.padding(24.dp)) {
        Text(caption, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        rows.forEachIndexed { i, r ->
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.displaySmall, color = LocalAccent.current, modifier = Modifier.width(48.dp))
                Artwork(r.trackId?.let { ArtProvider.uriFor(it) }, r.label, Modifier.size(52.dp), corner = 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(pluralRu(r.plays, "раз", "раза", "раз"), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                }
            }
        }
    }
}

@Composable
private fun Hours(hours: List<HourRow>) {
    val accent = LocalAccent.current
    val byHour = hours.associate { it.hour to it.plays }
    val peak = byHour.maxByOrNull { it.value }?.key ?: 0
    val label = when (peak) { in 5..11 -> "утром"; in 12..17 -> "днём"; in 18..22 -> "вечером"; else -> "ночью" }
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Чаще всего ты слушаешь $label", style = MaterialTheme.typography.headlineMedium)
        Text("пик — около $peak:00", color = Ink.Muted)
        Spacer(Modifier.height(24.dp))
        val max = (byHour.values.maxOrNull() ?: 1).toFloat()
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val slot = size.width / 24
            for (h in 0 until 24) {
                val v = (byHour[h] ?: 0) / max
                val bh = (v * size.height).coerceAtLeast(3f)
                drawRoundRect(if (h == peak) accent else accent.copy(alpha = 0.35f), Offset(h * slot + slot * 0.15f, size.height - bh), Size(slot * 0.7f, bh), CornerRadius(6f, 6f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("0", "6", "12", "18", "24").forEachIndexed { i, t ->
                Text(t, style = MaterialTheme.typography.labelSmall, color = Ink.Faint, modifier = Modifier.weight(1f))
            }
        }
    }
}
