package dev.xvin.palimpsest.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.data.library.CompressTarget
import dev.xvin.palimpsest.data.library.LibraryScanner
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import kotlinx.coroutines.launch

/**
 * «Здоровье медиатеки»: everything worth fixing in one place — no cover, no lyrics, untagged,
 * duplicates, lossy files, hi-res files, new versions on the service, FLACs to review, audio not
 * analysed — each with a list and a one-tap fix.
 */
@Composable
fun HealthScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val dao = graph.db.tracks()
    val noCover by dao.observeNoCover().collectAsStateWithLifecycle(emptyList())
    val noLyrics by dao.observeNoLyrics().collectAsStateWithLifecycle(emptyList())
    val untagged by dao.observeUntagged(LibraryScanner.UNKNOWN_ARTIST).collectAsStateWithLifecycle(emptyList())
    val updates by dao.observeUpdates().collectAsStateWithLifecycle(emptyList())
    val pending by graph.db.versions().observePending().collectAsStateWithLifecycle(emptyList())
    val covers by graph.covers.progress.collectAsStateWithLifecycle()
    val check by graph.health.check.collectAsStateWithLifecycle()
    val lyricsJob by graph.health.lyricsJob.collectAsStateWithLifecycle()
    val analysis by graph.analyzer.state.collectAsStateWithLifecycle()
    val lossy by produceState(-1, pending) { value = graph.flacFinder.lossyTracks().size }
    val hires by produceState<dev.xvin.palimpsest.data.library.CompressPlan?>(null) { value = graph.compressor.plan(CompressTarget.FLAC16) }
    val dupes by produceState(-1) { value = runCatching { graph.duplicates.find().size }.getOrDefault(0) }
    val notAnalysed by produceState(-1, analysis.running) { value = dao.notAnalysed().size }
    LaunchedEffect(Unit) { graph.health.checkLyricsFlags() }
    fun size(b: Long) = Formatter.formatShortFileSize(context, b)

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) }
                Text(tr(R.string.health_title), style = MaterialTheme.typography.headlineMedium)
            }
        }
        if (check.running) item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(tr(R.string.health_checking, check.done, check.total), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                LinearProgressIndicator(progress = { if (check.total > 0) check.done.toFloat() / check.total else 0f }, Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        item {
            HealthRow(Icons.Rounded.Image, tr(R.string.health_no_cover), noCover.size, tr(R.string.health_no_cover_hint),
                onOpen = { nav.openList(TrackListKind.NO_COVER, "health") },
                fix = if (covers.running) tr(R.string.covers_progress, covers.done, covers.total, covers.found) else tr(R.string.health_fix_covers),
                fixEnabled = !covers.running) { graph.appScope.launch { graph.covers.run(limit = 5000) } }
        }
        item {
            HealthRow(Icons.Rounded.Lyrics, tr(R.string.health_no_lyrics), noLyrics.size, tr(R.string.health_no_lyrics_hint),
                onOpen = { nav.openList(TrackListKind.NO_LYRICS, "health") },
                fix = if (lyricsJob.running) tr(R.string.health_lyrics_progress, lyricsJob.done, lyricsJob.total, lyricsJob.found) else tr(R.string.health_fix_lyrics),
                fixEnabled = !lyricsJob.running) { graph.appScope.launch { graph.health.findMissingLyrics(noLyrics.map { it.id }) } }
        }
        item {
            HealthRow(Icons.Rounded.Sell, tr(R.string.health_untagged), untagged.size, tr(R.string.health_untagged_hint),
                onOpen = { nav.openList(TrackListKind.UNTAGGED, "health") })
        }
        item {
            HealthRow(Icons.Rounded.ContentCopy, tr(R.string.settings_find_duplicates), dupes, tr(R.string.settings_duplicates_hint),
                onOpen = { nav.openDuplicates() })
        }
        item {
            HealthRow(Icons.Rounded.HighQuality, tr(R.string.health_lossy), lossy, tr(R.string.health_lossy_hint, pending.size),
                onOpen = { nav.openFlacReview() })
        }
        item {
            HealthRow(Icons.Rounded.Compress, tr(R.string.health_hires), hires?.tracks?.size ?: -1,
                hires?.let { tr(R.string.health_hires_hint, size(it.bytesBefore - it.bytesAfter)) } ?: "",
                onOpen = { nav.openCompress() })
        }
        item {
            HealthRow(Icons.Rounded.NewReleases, tr(R.string.smart_new_versions), updates.size, tr(R.string.new_versions_hint),
                onOpen = { nav.openList(TrackListKind.UPDATES, "updates") })
        }
        item {
            HealthRow(Icons.Rounded.GraphicEq, tr(R.string.health_not_analysed), notAnalysed, tr(R.string.health_not_analysed_hint),
                fix = if (analysis.running) tr(R.string.health_analysis_progress, analysis.done, analysis.total) else tr(R.string.health_fix_analyse),
                fixEnabled = !analysis.running) { graph.analyzer.start() }
        }
    }
}

@Composable
private fun HealthRow(
    icon: ImageVector, title: String, count: Int, hint: String,
    onOpen: (() -> Unit)? = null, fix: String? = null, fixEnabled: Boolean = true, onFix: (() -> Unit)? = null,
) {
    val accent = LocalAccent.current
    val ok = count == 0
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.Paper2)
            .clickable(enabled = onOpen != null && count > 0) { onOpen?.invoke() }.padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background((if (ok) Ink.Muted else accent).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(if (ok) Icons.Rounded.CheckCircle else icon, null, tint = if (ok) Ink.Muted else accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
            }
            Text(if (count < 0) "…" else "$count", style = MaterialTheme.typography.titleLarge, color = if (ok) Ink.Muted else Ink.Text)
        }
        if (fix != null && onFix != null && count != 0) TextButton(onClick = onFix, enabled = fixEnabled) { Text(fix, color = if (fixEnabled) accent else Ink.Muted) }
    }
}
