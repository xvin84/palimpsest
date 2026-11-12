package dev.xvin.palimpsest.ui.screens

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.BuildConfig
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.core.library.PlaylistExport
import dev.xvin.palimpsest.core.library.PlaylistItem
import dev.xvin.palimpsest.core.library.TrackLinks
import dev.xvin.palimpsest.data.db.PlaylistEntity
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.i18n.label
import dev.xvin.palimpsest.i18n.plural
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.PlaylistCover
import dev.xvin.palimpsest.ui.components.TrackRow
import dev.xvin.palimpsest.ui.components.formatListening
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import dev.xvin.palimpsest.ui.theme.Serif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

/** One row of the playlist as shown: [uid] stays put while rows are dragged around (a song can be in twice). */
private data class Entry(val uid: Int, val track: TrackEntity)

/**
 * A playlist: its cover, everything to do with it in plain sight (play, shuffle, listen through,
 * share, and ⋯ for the rest), songs that drag into a new order by the handle and swipe away.
 * Playlists from a service (Yandex) are read-only here: they come back with the next sync.
 */
@Composable
fun PlaylistScreen(id: Long) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dao = graph.db.playlists()
    val playlist by remember(id) { dao.observe(id) }.collectAsStateWithLifecycle(null)
    val tracks by remember(id) { dao.observeTracks(id) }.collectAsStateWithLifecycle(emptyList())
    val playing by graph.player.state.collectAsStateWithLifecycle()
    val preview by graph.player.preview.collectAsStateWithLifecycle()
    val p = playlist ?: return
    val own = p.service == null

    // Rows dragged into a new order show it at once; the database follows when the finger lifts.
    val rows = remember { mutableStateListOf<Entry>() }
    var dragging by remember { mutableStateOf(false) }
    // Each row keeps its key while it stays in the list, and a new row gets a key never used before:
    // reusing a removed row's key handed it that row's «swiped away» state, and one swipe emptied the list.
    val uids = remember { intArrayOf(0) }
    LaunchedEffect(tracks) {
        if (dragging) return@LaunchedEffect
        val kept = rows.groupBy { it.track.id }.mapValues { it.value.toMutableList() }
        val fresh = tracks.map { t -> kept[t.id]?.removeFirstOrNull()?.copy(track = t) ?: Entry(uids[0]++, t) }
        rows.clear(); rows.addAll(fresh)
    }
    fun save() { val ids = rows.map { it.track.id }; scope.launch { dao.replace(id, ids) } }

    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = rows.indexOfFirst { it.uid == from.key }
        val b = rows.indexOfFirst { it.uid == to.key }
        if (a >= 0 && b >= 0) rows.add(b, rows.removeAt(a))
    }

    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val url = withContext(Dispatchers.IO) { saveCover(context, id, uri) }
            if (url != null) dao.setCover(id, url) else nav.toast(tr(R.string.playlist_cover_failed))
        }
    }
    val exportM3u = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(PlaylistExport.m3u(p.name, items(tracks)).toByteArray()) } }.isSuccess
            }
            nav.toast(tr(if (ok) R.string.playlist_exported else R.string.playlist_export_failed))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                IconButton(onClick = nav::back, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlaylistCover(p, tracks, Modifier.size(132.dp).clickable(enabled = own) { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, corner = 14.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = own) { renaming = true },
                        )
                        p.service?.let { Text(it.label, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted) }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            plural(R.plurals.tracks, tracks.size) + " · " + formatListening(tracks.sumOf { it.durationMs }),
                            style = MaterialTheme.typography.labelMedium, color = Ink.Faint,
                        )
                    }
                }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { graph.player.play(tracks) }, enabled = tracks.isNotEmpty()) {
                        Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.listen))
                    }
                    IconButton(onClick = { graph.player.play(tracks, shuffle = true) }, enabled = tracks.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, tr(R.string.shuffled)) }
                    IconButton(onClick = { graph.player.preview(tracks, p.name); nav.toast(tr(R.string.preview_started)) }, enabled = tracks.size > 1) {
                        Icon(Icons.Rounded.FastForward, tr(R.string.playlist_listen_through))
                    }
                    IconButton(onClick = { sharing = true }, enabled = tracks.isNotEmpty()) { Icon(Icons.Rounded.Share, tr(R.string.share_playlist)) }
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, tr(R.string.more)) }
                        DropdownMenu(menu, { menu = false }, containerColor = Ink.Paper2) {
                            fun item(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) =
                                @Composable { DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = { menu = false; onClick() }) }
                            if (own) {
                                item(Icons.Rounded.Add, tr(R.string.playlist_add_songs)) { adding = true }()
                                item(Icons.Rounded.Edit, tr(R.string.rename)) { renaming = true }()
                                item(Icons.Rounded.Image, tr(R.string.playlist_change_cover)) { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }()
                                if (p.coverUrl != null) item(Icons.Rounded.GridView, tr(R.string.playlist_reset_cover)) { scope.launch { dao.setCover(id, null) } }()
                            }
                            item(Icons.Rounded.FastForward, tr(R.string.playlist_listen_through)) { graph.player.preview(tracks, p.name) }()
                            if (tracks.isNotEmpty()) {
                                item(Icons.AutoMirrored.Rounded.QueueMusic, tr(R.string.queue_all_next)) { graph.player.playNext(tracks); nav.toast(plural(R.plurals.queued_next_n, tracks.size)) }()
                                item(Icons.AutoMirrored.Rounded.QueueMusic, tr(R.string.queue_all_end)) { graph.player.enqueue(tracks); nav.toast(plural(R.plurals.queued_n, tracks.size)) }()
                            }
                            item(Icons.Rounded.ContentCopy, tr(R.string.playlist_duplicate)) {
                                scope.launch {
                                    val name = tr(R.string.playlist_copy_name, p.name)
                                    val copy = dao.insert(PlaylistEntity(name = name))
                                    dao.append(copy, tracks.map { it.id })
                                    nav.toast(tr(R.string.playlist_duplicated, name))
                                    nav.openList(TrackListKind.PLAYLIST, copy.toString())
                                }
                            }()
                            item(Icons.Rounded.SaveAlt, tr(R.string.playlist_export)) { exportM3u.launch(PlaylistExport.fileName(p.name, "m3u8")) }()
                            val remoteOnly = tracks.filter { it.remoteId != null && it.path == null }
                            if (remoteOnly.isNotEmpty()) item(Icons.Rounded.Download, tr(R.string.download_all)) {
                                scope.launch { graph.downloads.enqueue(remoteOnly.map { it.id }) }
                                nav.toast(tr(R.string.downloading_n, remoteOnly.size))
                            }()
                            if (own) item(Icons.Rounded.Delete, tr(R.string.playlist_delete)) { confirmDelete = true }()
                        }
                    }
                }
                preview?.takeIf { it.title == p.name }?.let { pv ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(LocalAccent.current.copy(alpha = 0.14f)).padding(start = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr(R.string.preview_now, pv.index, pv.total), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { graph.player.stopPreview(keep = true) }) { Text(tr(R.string.preview_keep)) }
                        TextButton(onClick = { graph.player.stopPreview(keep = false) }) { Text(tr(R.string.preview_stop)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (tracks.isEmpty()) {
                    Text(tr(if (own) R.string.playlist_empty_hint else R.string.list_empty), Modifier.padding(16.dp), color = Ink.Muted)
                    if (own) OutlinedButton(onClick = { adding = true }, Modifier.padding(horizontal = 16.dp)) {
                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.playlist_add_songs))
                    }
                }
            }
        }
        items(rows, key = { it.uid }) { e ->
            ReorderableItem(reorder, key = e.uid, enabled = own) { isDragging ->
                val row = @Composable {
                    Row(
                        Modifier.background(if (isDragging) Ink.Paper3 else MaterialTheme.colorScheme.background),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TrackRow(
                            e.track,
                            onClick = { graph.player.play(rows.map { it.track }, rows.indexOf(e)) },
                            onMore = { nav.showActions(e.track) },
                            modifier = Modifier.weight(1f),
                            playing = playing.nowPlaying?.trackId == e.track.id,
                        )
                        if (own) Icon(
                            Icons.Rounded.DragHandle, tr(R.string.playlist_drag),
                            Modifier.draggableHandle(
                                onDragStarted = { dragging = true },
                                onDragStopped = { dragging = false; save() },
                            ).padding(start = 4.dp, end = 14.dp),
                            tint = Ink.Faint,
                        )
                    }
                }
                if (own) {
                    val swipe = rememberSwipeToDismissBoxState()
                    LaunchedEffect(swipe.currentValue) {
                        if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) {
                            val before = rows.map { it.track.id }
                            val at = rows.indexOf(e)
                            if (at >= 0) rows.removeAt(at)
                            save()
                            nav.toast(tr(R.string.playlist_removed_song, e.track.title), tr(R.string.undo)) { scope.launch { dao.replace(id, before) } }
                        }
                    }
                    SwipeToDismissBox(
                        swipe,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error.copy(alpha = 0.25f)).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                                Icon(Icons.Rounded.Delete, null)
                            }
                        },
                    ) { row() }
                } else row()
            }
        }
    }

    if (renaming) NameDialog(tr(R.string.rename), p.name, onDismiss = { renaming = false }) { name ->
        scope.launch { dao.rename(id, name) }
        renaming = false
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = Ink.Paper2,
        title = { Text(tr(R.string.playlist_delete)) },
        text = { Text(tr(R.string.playlist_delete_text, p.name)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                val ids = tracks.map { it.id }
                scope.launch {
                    dao.delete(id)
                    dao.clear(id)
                    nav.back()
                    nav.toast(tr(R.string.playlist_deleted, p.name), tr(R.string.undo)) {
                        scope.launch { dao.insert(p); dao.replace(p.id, ids) }
                    }
                }
            }) { Text(tr(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr(R.string.cancel)) } },
    )
    if (sharing) SharePlaylistDialog(p, tracks) { sharing = false }
    if (adding) AddSongsDialog(tracks.map { it.id }.toSet(), onDismiss = { adding = false }) { picked ->
        adding = false
        scope.launch { dao.append(id, picked) }
        nav.toast(plural(R.plurals.playlist_added_n, picked.size))
    }
}

private fun items(tracks: List<TrackEntity>) = tracks.map {
    PlaylistItem(it.title, it.artist, it.durationMs, it.path, TrackLinks.web(it.service, it.remoteId, it.remoteAlbumId))
}

/** The chosen picture, downscaled and kept in the app's own files (the original may be moved or deleted). */
private fun saveCover(context: android.content.Context, id: Long, uri: Uri): String? = runCatching {
    val src = context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return null
    val side = minOf(src.width, src.height)
    val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    val scaled = if (side > 1000) Bitmap.createScaledBitmap(square, 1000, 1000, true) else square
    val dir = File(context.filesDir, "playlist_covers").apply { mkdirs() }
    // A new name each time: the image loader would otherwise keep showing the old picture.
    dir.listFiles { f -> f.name.startsWith("${id}_") }?.forEach { it.delete() }
    val f = File(dir, "${id}_${System.currentTimeMillis()}.jpg")
    f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    Uri.fromFile(f).toString()
}.getOrNull()

/** Share the whole playlist: a picture of it, the list as text with links, or an `.m3u8` file. */
@Composable
private fun SharePlaylistDialog(p: PlaylistEntity, tracks: List<TrackEntity>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val accent = LocalAccent.current
    fun send(intent: Intent) {
        context.startActivity(Intent.createChooser(intent, tr(R.string.share_playlist)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        onDismiss()
    }
    fun sendFile(f: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", f)
        send(
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, p.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(null, uri) },
        )
    }
    fun shareDir() = File(context.cacheDir, "share").apply { mkdirs() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Paper2,
        title = { Text(tr(R.string.share_playlist)) },
        text = {
            Column {
                // The picture: the cover, the name and the first songs, ready for a chat or a story.
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.8f)
                        .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), Color.Black))),
                ) {
                    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        PlaylistCover(p, tracks, Modifier.fillMaxWidth(0.5f).aspectRatio(1f), corner = 12.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(p.name, style = MaterialTheme.typography.titleLarge.copy(fontFamily = Serif), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Text(plural(R.plurals.tracks, tracks.size) + " · " + formatListening(tracks.sumOf { it.durationMs }), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.fillMaxWidth().weight(1f)) {
                            tracks.take(6).forEach { t ->
                                Text("${t.artist} — ${t.title}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (tracks.size > 6) Text(tr(R.string.playlist_card_more, tracks.size - 6), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                        }
                        Text("Palimpsest", style = MaterialTheme.typography.labelLarge.copy(fontFamily = Serif, fontSize = 14.sp), color = Color.White.copy(alpha = 0.55f))
                    }
                }
                Spacer(Modifier.height(12.dp))
                ShareOption(tr(R.string.share_as_card)) {
                    scope.launch {
                        val bitmap = layer.toImageBitmap().asAndroidBitmap()
                        val f = withContext(Dispatchers.IO) { File(shareDir(), "playlist.png").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
                        sendFile(f, "image/png")
                    }
                }
                ShareOption(tr(R.string.share_as_text)) {
                    send(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, PlaylistExport.text(p.name, items(tracks))))
                }
                ShareOption(tr(R.string.share_as_file)) {
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { File(shareDir(), PlaylistExport.fileName(p.name, "m3u8")).also { it.writeText(PlaylistExport.m3u(p.name, items(tracks))) } }
                        sendFile(f, "audio/x-mpegurl")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.close)) } },
    )
}

@Composable
private fun ShareOption(text: String, onClick: () -> Unit) {
    Text(
        text,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
}

/** Several songs from the library into the playlist at once, found by typing. */
@Composable
private fun AddSongsDialog(already: Set<Long>, onDismiss: () -> Unit, onAdd: (List<Long>) -> Unit) {
    val graph = LocalGraph.current
    val all by graph.db.tracks().observeAll().collectAsStateWithLifecycle(emptyList())
    var query by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<Long>() }
    val shown = remember(all, query) {
        if (query.isBlank()) all else all.filter { dev.xvin.palimpsest.core.library.SearchText.matches(query, it.title, it.artist, it.album) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Paper2,
        title = { Text(tr(R.string.playlist_add_songs)) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr(R.string.search_hint)) })
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown, key = { it.id }) { t ->
                        val on = t.id in picked
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) picked.remove(t.id) else picked.add(t.id) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(on, { if (on) picked.remove(t.id) else picked.add(t.id) })
                            Column(Modifier.weight(1f)) {
                                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    t.artist + if (t.id in already) " · " + tr(R.string.playlist_already_in) else "",
                                    style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(picked.toList()) }, enabled = picked.isNotEmpty()) { Text(tr(R.string.add_n, picked.size)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}
