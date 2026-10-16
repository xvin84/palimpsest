package dev.xvin.palimpsest.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.data.StorageReport
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.Pill
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import kotlinx.coroutines.launch

/** One slice of the usage bar. */
private data class Slice(val label: String, val bytes: Long, val color: Color)

/** «Память»: what the app keeps on the phone, and buttons to free it. Music files are never touched here. */
@Composable
fun StorageScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val accent = LocalAccent.current
    val settings by graph.settings.flow.collectAsStateWithLifecycle(null)
    var report by remember { mutableStateOf<StorageReport?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmVersions by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(reload) { report = graph.storage.report() }

    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    fun run(done: String, block: suspend () -> Unit) {
        busy = true
        graph.appScope.launch {
            runCatching { block() }.onFailure { nav.error(it, tr(R.string.storage)) }.onSuccess { nav.toast(done) }
            busy = false
            reload++
        }
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) }
                Text(tr(R.string.storage), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            }
        }
        val r = report
        if (r == null || busy) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        }
        if (r != null) {
            val slices = listOf(
                Slice(tr(R.string.storage_library), r.libraryBytes, accent),
                Slice(tr(R.string.storage_versions), r.versionsBytes, Color(0xFF8E7CC3)),
                Slice(tr(R.string.storage_stream_cache), r.streamCacheBytes, Color(0xFF4F9BC9)),
                Slice(tr(R.string.storage_images), r.imagesBytes, Color(0xFF6FAF7A)),
                Slice(tr(R.string.storage_other), r.otherCacheBytes, Color(0xFFC9A04F)),
                Slice(tr(R.string.backup), r.backupsBytes, Ink.Muted),
            )
            item { UsageBar(slices, r, ::size) }
            item {
                Category(
                    slices[0], size(r.libraryBytes),
                    tr(R.string.storage_library_hint, r.libraryFiles),
                )
            }
            item {
                Category(slices[1], size(r.versionsBytes), tr(R.string.storage_versions_hint, r.versionsCount)) {
                    if (r.versionsCount > 0) {
                        TextButton(onClick = { confirmVersions = 90 }, enabled = !busy) { Text(tr(R.string.storage_versions_older_90)) }
                        TextButton(onClick = { confirmVersions = 0 }, enabled = !busy) { Text(tr(R.string.storage_versions_all)) }
                    }
                }
            }
            item {
                Category(slices[2], size(r.streamCacheBytes), tr(R.string.storage_stream_cache_hint)) {
                    TextButton(onClick = { run(tr(R.string.storage_cleared)) { graph.storage.clearStreamCache() } }, enabled = !busy && r.streamCacheBytes > 0) {
                        Text(tr(R.string.clear))
                    }
                }
                Text(tr(R.string.storage_stream_limit), Modifier.padding(start = 40.dp, end = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                FlowRow(
                    Modifier.padding(start = 40.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(256, 512, 1024, 2048, 4096).forEach { mb ->
                        // Round labels (256 MB, 1 GB), not the exact binary sizes the system formatter would print.
                        val label = if (mb < 1024) mb * 1_000_000L else mb / 1024 * 1_000_000_000L
                        Pill(size(label), settings?.streamCacheMb == mb, { graph.appScope.launch { graph.settings.setStreamCacheMb(mb) } })
                    }
                }
            }
            item {
                Category(slices[3], size(r.imagesBytes), tr(R.string.storage_images_hint)) {
                    TextButton(onClick = { run(tr(R.string.storage_cleared)) { graph.storage.clearImages() } }, enabled = !busy && r.imagesBytes > 0) { Text(tr(R.string.clear)) }
                }
            }
            item {
                Category(slices[4], size(r.otherCacheBytes), tr(R.string.storage_other_hint)) {
                    TextButton(onClick = { run(tr(R.string.storage_cleared)) { graph.storage.clearOther() } }, enabled = !busy && r.otherCacheBytes > 0) { Text(tr(R.string.clear)) }
                }
            }
            item {
                Category(slices[5], size(r.backupsBytes), tr(R.string.storage_backups_hint, r.backupsCount)) {
                    if (r.backupsCount > 1) {
                        TextButton(onClick = { run(tr(R.string.storage_cleared)) { graph.storage.pruneBackups() } }, enabled = !busy) { Text(tr(R.string.storage_backups_keep_last)) }
                    }
                }
            }
        }
    }

    confirmVersions?.let { days ->
        AlertDialog(
            onDismissRequest = { confirmVersions = null },
            containerColor = Ink.Paper2,
            title = { Text(tr(if (days > 0) R.string.storage_versions_older_90 else R.string.storage_versions_all)) },
            text = { Text(tr(R.string.storage_versions_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmVersions = null
                    run(tr(R.string.storage_cleared)) { graph.storage.deleteVersions(days.takeIf { it > 0 }) }
                }) { Text(tr(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmVersions = null }) { Text(tr(R.string.cancel)) } },
        )
    }
}

/** The phone's storage as one bar: each slice is ours, the rest is other apps, and free space. */
@Composable
private fun UsageBar(slices: List<Slice>, r: StorageReport, size: (Long) -> String) {
    val ours = slices.sumOf { it.bytes }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(size(ours), style = MaterialTheme.typography.displayMedium)
        Text(tr(R.string.storage_free_of, size(r.freeBytes), size(r.totalBytes)), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
        Spacer(Modifier.height(12.dp))
        // Our share of the whole disk is often a sliver, so the bar shows how our usage splits up.
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Ink.Paper3)) {
            slices.filter { it.bytes > 0 }.forEach { s ->
                val w = (s.bytes.toFloat() / ours.coerceAtLeast(1)).coerceAtLeast(0.012f)
                Box(Modifier.weight(w).fillMaxHeight().padding(end = 2.dp).background(s.color))
            }
        }
    }
}

@Composable
private fun Category(slice: Slice, size: String, hint: String, actions: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(slice.color))
            Spacer(Modifier.width(12.dp))
            Text(slice.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(size, style = MaterialTheme.typography.titleMedium, color = Ink.Muted)
        }
        Text(hint, Modifier.padding(start = 24.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
        if (actions != null) Row(Modifier.padding(start = 12.dp)) { actions() }
    }
}
