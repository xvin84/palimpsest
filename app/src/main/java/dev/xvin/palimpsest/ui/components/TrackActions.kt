package dev.xvin.palimpsest.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.data.db.PlaylistEntity
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.screens.TrackListKind
import dev.xvin.palimpsest.ui.theme.Ink
import kotlinx.coroutines.launch
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.i18n.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(initial: TrackEntity, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val track by graph.db.tracks().observe(initial.id).collectAsStateWithLifecycle(initial)
    val t = track ?: return
    var pickPlaylist by remember { mutableStateOf(false) }
    var renderDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val storedArtists by graph.db.artists().observeForTrack(initial.id).collectAsStateWithLifecycle(emptyList())
    val artists = storedArtists.ifEmpty {
        dev.xvin.palimpsest.core.library.ArtistNames.split(t.artist).mapIndexed { i, n -> dev.xvin.palimpsest.data.db.TrackArtistEntity(t.id, i, n, null) }
    }

    fun act(block: () -> Unit) { block(); onDismiss() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Ink.Paper2) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackArtwork(t, Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(t.artist, t.album).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1)
                    Text(techLine(t), style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Ink.Line)
            Action(Icons.Rounded.SkipNext, tr(R.string.action_play_next)) { act { graph.player.playNext(listOf(t)) } }
            Action(Icons.AutoMirrored.Rounded.QueueMusic, tr(R.string.action_enqueue)) { act { graph.player.enqueue(listOf(t)) } }
            Action(Icons.AutoMirrored.Rounded.PlaylistAdd, tr(R.string.action_add_to_playlist)) { pickPlaylist = true }
            Action(if (t.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (t.liked) tr(R.string.action_unlike) else tr(R.string.action_like)) {
                scope.launch {
                    graph.setLiked(t, !t.liked).onFailure { nav.toast(tr(R.string.like_not_saved_yandex, it.message)) }
                }
            }
            if (t.remoteId != null && (t.path == null || t.updateAvailable)) {
                Action(Icons.Rounded.Download, if (t.updateAvailable) tr(R.string.action_download_new_version) else tr(R.string.download)) {
                    act {
                        graph.appScope.launch { graph.downloads.enqueue(listOf(t.id)) }
                        nav.toast(tr(R.string.downloading_one, t.title))
                    }
                }
            }
            if (t.path != null) Action(Icons.Rounded.Edit, tr(R.string.action_tags)) { act { nav.openTrack(t.id, 0) } }
            if (t.path != null) Action(Icons.Rounded.Movie, tr(R.string.render_action)) { renderDialog = true }
            if (t.path != null) Action(Icons.Rounded.DriveFileMove, tr(R.string.action_move_file)) { moveDialog = true }
            if (t.path != null) Action(Icons.Rounded.Delete, tr(R.string.action_delete_file)) { confirmDelete = true }
            Action(Icons.Rounded.History, tr(R.string.action_versions)) { act { nav.openTrack(t.id, 2) } }
            t.album?.let { album -> Action(Icons.Rounded.Album, tr(R.string.action_album, album)) { act { nav.openList(TrackListKind.ALBUM, album, t.albumArtist ?: t.artist) } } }
            artists.forEach { a -> Action(Icons.Rounded.Person, a.name) { act { nav.openArtist(a.name, a.remoteId) } } }
            if (t.service == Service.YANDEX && t.remoteId != null && graph.yandex.isReady) {
                Action(Icons.Rounded.Radio, tr(R.string.action_track_radio)) {
                    act {
                        graph.mainScope.launch {
                            graph.radio.start("track:${t.remoteId}", tr(R.string.radio_title, t.title)).onFailure { nav.error(it, tr(R.string.action_track_radio)) }
                        }
                    }
                }
                Action(Icons.Rounded.AutoAwesome, tr(R.string.action_similar)) { act { nav.openSimilar(t.remoteId, t.title) } }
                t.remoteAlbumId?.let { id -> Action(Icons.Rounded.Album, tr(R.string.action_yandex_album)) { act { nav.openRemoteAlbum(id) } } }
            }
        }
    }

    if (confirmDelete) {
        val trashOn = graph.settings.state.value?.trashEnabled != false
        val days = graph.settings.state.value?.trashDays ?: 30
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Ink.Paper2,
            title = { Text(tr(R.string.delete_file_title)) },
            text = {
                Text(
                    listOfNotNull(
                        t.path?.substringAfterLast('/'),
                        if (!trashOn) tr(R.string.delete_file_forever) else if (days > 0) tr(R.string.delete_file_to_trash_days, days) else tr(R.string.delete_file_to_trash),
                        if (t.remoteId != null) tr(R.string.delete_file_stays_stream, t.service.label) else null,
                    ).joinToString("\n\n"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDismiss()
                    graph.appScope.launch {
                        val before = graph.trash.entries.value.size
                        graph.trash.delete(listOf(t))
                        val entry = graph.trash.entries.value.drop(before).firstOrNull { it.track.id == t.id }
                        if (entry != null) nav.toast(tr(R.string.deleted_to_trash, t.title), tr(R.string.undo)) {
                            graph.appScope.launch { graph.trash.restore(entry) }
                        } else nav.toast(tr(R.string.deleted_file, t.title))
                    }
                }) { Text(tr(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr(R.string.cancel)) } },
        )
    }

    if (moveDialog) {
        MoveFileDialog(t, onDismiss = { moveDialog = false; onDismiss() })
    }

    if (renderDialog) {
        dev.xvin.palimpsest.ui.screens.RenderDialog(t, onDismiss = { renderDialog = false; onDismiss() })
    }

    if (pickPlaylist) {
        PlaylistPicker(
            onDismiss = { pickPlaylist = false },
            onPick = { p ->
                graph.appScope.launch { graph.db.playlists().append(p.id, listOf(t.id)) }
                nav.toast(tr(R.string.added_to_playlist, p.name))
                pickPlaylist = false
                onDismiss()
            },
        )
    }
}

/** «Move to folder»: the library's folders to pick from, or any path typed by hand. */
@Composable
fun MoveFileDialog(track: TrackEntity, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val current = track.path?.substringBeforeLast('/').orEmpty()
    var target by remember { mutableStateOf(current) }
    val folders by androidx.compose.runtime.produceState(emptyList<java.io.File>()) { value = graph.trash.folders() }
    val roots = graph.settings.state.value?.musicRoots.orEmpty()
    fun short(path: String) = roots.firstOrNull { path == it || path.startsWith("$it/") }
        ?.let { r -> r.substringAfterLast('/') + path.removePrefix(r) } ?: path
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Paper2,
        title = { Text(tr(R.string.move_file_title)) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    target, { target = it }, Modifier.fillMaxWidth(),
                    label = { Text(tr(R.string.move_file_folder)) },
                    supportingText = { Text(tr(R.string.move_file_hint)) },
                    trailingIcon = {
                        if (target.isNotEmpty()) androidx.compose.material3.IconButton(onClick = { target = "" }) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.Close, tr(R.string.clear))
                        }
                    },
                )
                LazyColumn(Modifier.padding(top = 6.dp).heightIn(max = 280.dp)) {
                    items(folders, key = { it.absolutePath }) { f ->
                        val selected = f.absolutePath == target.trimEnd('/')
                        Text(
                            short(f.absolutePath),
                            color = if (selected) MaterialTheme.colorScheme.primary else Ink.Text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { target = f.absolutePath }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = target.isNotBlank() && target.trimEnd('/') != current,
                onClick = {
                    val dir = java.io.File(target.trim().trimEnd('/'))
                    onDismiss()
                    graph.appScope.launch {
                        val moved = graph.trash.move(listOf(track), dir)
                        nav.toast(if (moved > 0) tr(R.string.moved_file, short(dir.absolutePath)) else tr(R.string.move_failed))
                    }
                },
            ) { Text(tr(R.string.move)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

fun techLine(t: TrackEntity): String = listOfNotNull(
    t.codec?.uppercase(),
    t.bitsPerSample?.takeIf { it > 0 }?.let { tr(R.string.tech_bits, it) },
    t.sampleRate?.let { tr(R.string.tech_khz).format(it / 1000f) },
    t.bitrateKbps?.let { tr(R.string.tech_kbps, it) },
    if (t.path == null) tr(R.string.tech_stream, t.service.label) else null,
).joinToString(" · ")

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Ink.Muted)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlaylistPicker(onDismiss: () -> Unit, onPick: (PlaylistEntity) -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val playlists by graph.db.playlists().observeAll().collectAsStateWithLifecycle(emptyList())
    val own = playlists.filter { it.service == null }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Paper2,
        title = { Text(tr(R.string.to_playlist)) },
        text = {
            LazyColumn {
                item {
                    Text(
                        tr(R.string.new_playlist_plus),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                val name = tr(R.string.playlist_n, own.size + 1)
                                val id = graph.db.playlists().insert(PlaylistEntity(name = name))
                                onPick(PlaylistEntity(id = id, name = name))
                            }
                        }.padding(vertical = 12.dp),
                    )
                }
                items(own, key = { it.id }) { p ->
                    Text(p.name, modifier = Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}
