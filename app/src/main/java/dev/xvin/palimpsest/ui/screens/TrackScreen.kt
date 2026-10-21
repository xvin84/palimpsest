package dev.xvin.palimpsest.ui.screens

import kotlin.math.roundToInt
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.db.TrackVersionEntity
import dev.xvin.palimpsest.data.library.ArtProvider
import dev.xvin.palimpsest.data.library.TagEdit
import dev.xvin.palimpsest.data.library.TagIO
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.Pill
import dev.xvin.palimpsest.ui.components.SectionHeader
import dev.xvin.palimpsest.ui.components.TrackArtwork
import dev.xvin.palimpsest.ui.components.formatDuration
import dev.xvin.palimpsest.ui.components.techLine
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.i18n.label

@Composable
fun TrackScreen(trackId: Long, initialTab: Int) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val track by remember(trackId) { graph.db.tracks().observe(trackId) }.collectAsStateWithLifecycle(null)
    var tab by remember { mutableIntStateOf(initialTab) }
    val t = track ?: return
    val plays by remember(trackId) { graph.db.plays().observePlayCount(trackId) }.collectAsStateWithLifecycle(0)

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        IconButton(onClick = nav::back, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackArtwork(t, Modifier.size(96.dp), corner = 12.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(t.artist, color = Ink.Muted, maxLines = 1)
                Text(tr(R.string.track_plays, techLine(t), plays), style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(tr(R.string.tab_tags), tr(R.string.lyrics), tr(R.string.tab_versions)).forEachIndexed { i, label -> Pill(label, tab == i, { tab = i }) }
        }
        when (tab) {
            0 -> TagsTab(t)
            1 -> LyricsTab(t)
            else -> VersionsTab(t)
        }
    }
}

@Composable
private fun TagsTab(t: TrackEntity) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val editable = t.path != null && TagIO.canEdit(File(t.path))
    var title by remember(t.id) { mutableStateOf(t.title) }
    var artist by remember(t.id) { mutableStateOf(t.artist) }
    var album by remember(t.id) { mutableStateOf(t.album.orEmpty()) }
    var albumArtist by remember(t.id) { mutableStateOf(t.albumArtist.orEmpty()) }
    var year by remember(t.id) { mutableStateOf(t.year?.toString().orEmpty()) }
    var genre by remember(t.id) { mutableStateOf(t.genre.orEmpty()) }
    var trackNo by remember(t.id) { mutableStateOf(t.trackNumber?.toString().orEmpty()) }
    var disc by remember(t.id) { mutableStateOf(t.discNumber?.toString().orEmpty()) }
    var cover by remember(t.id) { mutableStateOf<ByteArray?>(null) }
    var saving by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            cover = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
        }
    }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!editable) {
            Text(
                if (t.path == null) tr(R.string.tags_not_downloaded) else tr(R.string.tags_unsupported),
                color = Ink.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        Field(tr(R.string.tag_title), title, editable) { title = it }
        Field(tr(R.string.tag_artist), artist, editable) { artist = it }
        Field(tr(R.string.tag_album), album, editable) { album = it }
        Field(tr(R.string.tag_album_artist), albumArtist, editable) { albumArtist = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(tr(R.string.tag_year), year, editable, Modifier.weight(1f)) { year = it }
            Field(tr(R.string.tag_track_number), trackNo, editable, Modifier.weight(1f)) { trackNo = it }
            Field(tr(R.string.tag_disc), disc, editable, Modifier.weight(1f)) { disc = it }
        }
        Field(tr(R.string.tag_genre), genre, editable) { genre = it }
        if (editable) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(if (cover != null && cover!!.isNotEmpty()) tr(R.string.cover_picked) else tr(R.string.cover_change))
                }
                TextButton(onClick = { cover = ByteArray(0) }) { Text(tr(R.string.cover_remove), color = Ink.Muted) }
            }
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                TagIO.write(
                                    File(t.path!!),
                                    TagEdit(
                                        title = title, artist = artist, album = album, albumArtist = albumArtist,
                                        year = year, genre = genre, trackNumber = trackNo, discNumber = disc,
                                        cover = cover, coverMime = if (cover?.take(4) == listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())) "image/png" else "image/jpeg",
                                    ),
                                )
                                ArtProvider.invalidate(context.cacheDir, t.id)
                                graph.scanner.rescanFile(t.path)
                            }
                        }
                        saving = false
                        nav.toast(result.fold({ tr(R.string.saved_to_file) }, { tr(R.string.save_failed, it.message) }))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saving) tr(R.string.saving) else tr(R.string.save_to_file)) }
        }

        SectionHeader(tr(R.string.file))
        Text(t.path ?: tr(R.string.not_downloaded_stream), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Ink.Muted)
        if (t.remoteId != null) {
            Text("${t.service.label} · id ${t.remoteId}", style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
        }
        t.replayGainTrackDb?.let { Text(tr(R.string.track_replaygain).format(it), style = MaterialTheme.typography.bodySmall, color = Ink.Muted) }
        if (t.bpm != null && t.bpm > 0) {
            val key = dev.xvin.palimpsest.core.analysis.AudioFeatures.keyName(t.musicalKey)?.let { "$it (${t.musicalKey})" }
            Text(
                listOfNotNull(tr(R.string.track_bpm, t.bpm.roundToInt()), key, t.energy?.let { tr(R.string.track_energy, (it * 100).roundToInt()) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier, enabled = enabled, singleLine = true, label = { Text(label) })
}

@Composable
private fun LyricsTab(t: TrackEntity) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var text by remember(t.id) { mutableStateOf("") }
    var source by remember(t.id) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var embed by remember { mutableStateOf(t.path != null) }

    LaunchedEffect(t.id) {
        val l = graph.lyrics.local(t)
        text = l?.synced ?: l?.plain.orEmpty()
        source = l?.source
        loading = false
    }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            source?.let { tr(R.string.lyrics_source, it) } ?: if (loading) tr(R.string.loading) else tr(R.string.lyrics_none),
            style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
        )
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth(), minLines = 8, maxLines = 24,
            label = { Text(tr(R.string.lyrics_field_hint)) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                loading = true
                scope.launch {
                    val l = graph.lyrics.fetchOnline(t)
                    loading = false
                    if (l != null) { text = l.synced ?: l.plain.orEmpty(); source = l.source } else nav.toast(tr(R.string.nothing_found))
                }
            }, enabled = !loading) { Text(tr(R.string.find_online)) }
            Button(onClick = {
                scope.launch {
                    runCatching { graph.lyrics.save(t, text, embed) }
                        .onSuccess { source = tr(R.string.lyrics_src_manual); nav.toast(tr(R.string.lyrics_saved)) }
                        .onFailure { nav.toast(tr(R.string.error_with, it.message)) }
                }
            }) { Text(tr(R.string.save)) }
        }
        if (t.path != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(embed, { embed = it })
            Text(tr(R.string.lyrics_embed), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun VersionsTab(t: TrackEntity) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val versions by remember(t.id) { graph.db.versions().observeFor(t.id) }.collectAsStateWithLifecycle(emptyList())
    val fmt = remember { SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.getDefault()) }
    val context = LocalContext.current

    // Swap in another file (e.g. a better rip); the current one is archived, never lost.
    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val current = t.path?.let(::File) ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = dev.xvin.palimpsest.playback.MediaItems.displayName(context, uri) ?: "audio.${current.extension}"
                    val ext = name.substringAfterLast('.', current.extension).lowercase()
                    val tmp = File(current.parentFile, ".replace-${t.id}.$ext")
                    context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    val target = File(current.parentFile, "${current.nameWithoutExtension}.$ext")
                    graph.versions.installNewAudio(t, tmp, target, tr(R.string.version_note_replaced_manually, name))
                }
            }
            nav.toast(
                result.fold(
                    { if (it is dev.xvin.palimpsest.data.library.VersionRepository.ReplaceResult.Identical) tr(R.string.replace_identical) else tr(R.string.replace_done) },
                    { tr(R.string.failed_with, it.message) },
                ),
            )
        }
    }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            tr(R.string.versions_hint_1) +
                tr(R.string.versions_hint_2),
            style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
        )
        if (t.updateAvailable && t.remoteId != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LocalAccent.current.copy(alpha = 0.18f)).padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.version_new_on, t.service.label), style = MaterialTheme.typography.titleSmall)
                        Text(tr(R.string.version_not_downloaded, formatDuration(t.remoteDurationMs ?: 0)), style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                    }
                    IconButton(onClick = { graph.player.playNewVersion(t.id); nav.openPlayer() }) { Icon(Icons.Rounded.PlayArrow, tr(R.string.listen_new)) }
                }
                TextButton(onClick = {
                    scope.launch { graph.downloads.enqueue(listOf(t.id)) }
                    nav.toast(tr(R.string.downloading_new_keep_current))
                }) { Text(tr(R.string.download_make_current)) }
            }
        }
        if (t.path != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        graph.versions.snapshot(t, tr(R.string.version_note_saved_manually))
                        nav.toast(tr(R.string.version_current_saved))
                    }
                }) { Text(tr(R.string.version_save_current)) }
                OutlinedButton(onClick = { replacePicker.launch(arrayOf("audio/*")) }) { Text(tr(R.string.version_replace_file)) }
            }
        }

        if (versions.isEmpty()) Text(tr(R.string.versions_none), color = Ink.Faint)
        versions.forEach { v -> VersionCard(v, fmt, t) }
    }
}

@Composable
private fun VersionCard(v: TrackVersionEntity, fmt: SimpleDateFormat, t: TrackEntity) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val exists = remember(v.path) { File(v.path).isFile }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (v.isCurrent) LocalAccent.current.copy(alpha = 0.12f) else Ink.Paper2).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (v.isCurrent) tr(R.string.version_current) else fmt.format(Date(v.createdAt)), style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        formatDuration(v.durationMs), v.codec?.uppercase(), v.bitrateKbps?.let { tr(R.string.tech_kbps, it) },
                        tr(R.string.size_mb).format(v.sizeBytes / 1_048_576f),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = Ink.Muted,
                )
                v.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Faint) }
                if (!exists) Text(tr(R.string.file_not_found), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (exists) IconButton(onClick = {
                if (v.isCurrent && t.path != null) graph.player.play(listOf(t)) else graph.player.playVersion(v.id)
                nav.openPlayer()
            }) { Icon(Icons.Rounded.PlayArrow, tr(R.string.listen)) }
            if (!v.isCurrent && exists && t.path != null) {
                TextButton(onClick = {
                    graph.player.startAb(t.id, v.id, tr(R.string.version_from_prefix_lower) + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(v.createdAt)))
                    nav.openPlayer()
                }) { Text("A/B") }
                IconButton(onClick = {
                    scope.launch { graph.versions.restore(t, v); nav.toast(tr(R.string.version_restored)) }
                }) { Icon(Icons.Rounded.Restore, tr(R.string.make_current)) }
            }
            if (!v.isCurrent) IconButton(onClick = { scope.launch { graph.versions.delete(v) } }) { Icon(Icons.Rounded.Delete, tr(R.string.delete), tint = Ink.Faint) }
        }
    }
}
