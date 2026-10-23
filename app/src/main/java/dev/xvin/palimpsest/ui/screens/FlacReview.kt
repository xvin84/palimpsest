package dev.xvin.palimpsest.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.db.TrackVersionEntity
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.TrackArtwork
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import kotlinx.coroutines.launch

/** «Сравнить»: the library file (A) against the FLAC (B), switched at the same second. */
fun compareFlac(graph: dev.xvin.palimpsest.AppGraph, track: TrackEntity, v: TrackVersionEntity) {
    graph.player.startAb(track.id, v.id, "FLAC")
}

/**
 * «FLAC-версии»: the search over the whole library, and every FLAC found waiting for «same song?».
 * Compare plays the file and the FLAC at the same second; «It's the one» puts the FLAC in place.
 */
@Composable
fun FlacReviewScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val accent = LocalAccent.current
    val search by graph.flacFinder.state.collectAsStateWithLifecycle()
    val pending by graph.db.versions().observePending().collectAsStateWithLifecycle(emptyList())
    val tracks by produceState(emptyMap<Long, TrackEntity>(), pending) {
        value = graph.db.tracks().getMany(pending.map { it.trackId }).associateBy { it.id }
    }
    val lossyCount by produceState(-1, pending, search.running) { value = graph.flacFinder.lossyTracks().size }
    val downloads by graph.downloads.items.collectAsStateWithLifecycle()
    fun size(b: Long) = Formatter.formatShortFileSize(context, b)

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) }
                Text(tr(R.string.flac_title), style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.Paper2).padding(16.dp)) {
                Text(tr(R.string.flac_explain), style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                Spacer(Modifier.height(12.dp))
                when {
                    search.running -> {
                        Text(tr(R.string.flac_searching, search.checked, search.total, search.found, search.noFlac), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { if (search.total > 0) search.checked.toFloat() / search.total else 0f }, Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    search.error == "yandex" -> Text(tr(R.string.flac_needs_yandex), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    search.error == "plus" -> Column {
                        Text(tr(R.string.flac_needs_plus), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { graph.appScope.launch { graph.flacFinder.run() } }) { Text(tr(R.string.flac_try_again), color = accent) }
                    }
                    else -> {
                        if (search.total > 0) Text(tr(R.string.flac_last_result, search.found, search.noFlac, search.notFound), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                        Button(
                            onClick = { graph.appScope.launch { graph.flacFinder.run() } },
                            enabled = lossyCount != 0,
                            modifier = Modifier.padding(top = 6.dp),
                        ) { Text(if (lossyCount > 0) tr(R.string.flac_search_n, lossyCount) else tr(R.string.flac_nothing_lossy)) }
                    }
                }
                val inFlight = downloads.values.count { it.state == dev.xvin.palimpsest.data.remote.DownloadState.QUEUED || it.state == dev.xvin.palimpsest.data.remote.DownloadState.RUNNING }
                if (inFlight > 0) {
                    Text(tr(R.string.flac_downloading, inFlight), style = MaterialTheme.typography.bodySmall, color = Ink.Muted, modifier = Modifier.padding(top = 8.dp))
                    Text(tr(R.string.flac_found_downloading), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                }
            }
        }
        if (pending.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.flac_to_review, pending.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
            }
        }
        items(pending, key = { it.id }) { v ->
            val t = tracks[v.trackId] ?: return@items
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Ink.Paper2).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrackArtwork(t, Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                    }
                }
                Text(
                    tr(R.string.flac_compare_line,
                        listOfNotNull(t.codec?.uppercase(), t.bitrateKbps?.let { "$it kbps" }).joinToString(" "), size(t.sizeBytes),
                        listOfNotNull(v.codec?.uppercase(), v.bitrateKbps?.let { "$it kbps" }).joinToString(" "), size(v.sizeBytes)),
                    style = MaterialTheme.typography.labelMedium, color = Ink.Muted, modifier = Modifier.padding(top = 8.dp),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { compareFlac(graph, t, v); nav.openPlayer() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Icon(Icons.Rounded.Compare, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr(R.string.flac_compare))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { graph.appScope.launch { graph.versions.rejectCandidate(v) } }) { Text(tr(R.string.flac_not_it), color = Ink.Muted) }
                    Button(onClick = {
                        graph.appScope.launch { graph.versions.acceptCandidate(t, v); nav.toast(tr(R.string.flac_replaced, t.title)) }
                    }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text(tr(R.string.flac_its_it)) }
                }
            }
        }
        if (pending.isEmpty() && !search.running) item {
            Text(tr(R.string.flac_none_waiting), Modifier.padding(16.dp), color = Ink.Muted)
        }
    }
}

/**
 * In the player: while comparing a track with its FLAC, «Same song?» with the two answers; when a
 * track with a FLAC waiting plays normally, an offer to compare.
 */
@Composable
internal fun FlacCandidatePrompt(track: TrackEntity?, mediaId: String) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val accent = LocalAccent.current
    val t = track ?: return
    val pending by remember(t.id) { graph.db.versions().observeFor(t.id) }.collectAsStateWithLifecycle(emptyList())
    val v = pending.firstOrNull { it.pending } ?: return
    val ab by graph.player.ab.collectAsStateWithLifecycle()
    val comparing = ab?.versionId == v.id || mediaId == dev.xvin.palimpsest.playback.MediaItems.VERSION_PREFIX + v.id
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(if (comparing) tr(R.string.flac_same_song) else tr(R.string.flac_found_for_track), style = MaterialTheme.typography.bodySmall, color = Ink.Text)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (comparing) {
                TextButton(onClick = {
                    graph.player.backToTrack(t.id)
                    graph.appScope.launch { graph.versions.rejectCandidate(v) }
                }) { Text(tr(R.string.flac_not_it), color = Ink.Muted) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    graph.appScope.launch {
                        graph.versions.acceptCandidate(t, v)
                        // Keep listening, now to the new library file.
                        graph.mainScope.launch { graph.player.backToTrack(t.id) }
                        nav.toast(tr(R.string.flac_replaced, t.title))
                    }
                }) { Text(tr(R.string.flac_its_it_replace), color = accent) }
            } else {
                TextButton(onClick = { compareFlac(graph, t, v) }) { Text(tr(R.string.flac_compare), color = accent) }
                TextButton(onClick = { nav.openFlacReview() }) { Text(tr(R.string.flac_all), color = Ink.Muted) }
            }
        }
    }
}
