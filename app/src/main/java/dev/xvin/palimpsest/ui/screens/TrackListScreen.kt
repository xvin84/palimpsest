package dev.xvin.palimpsest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.TrackArtwork
import dev.xvin.palimpsest.ui.components.TrackRow
import dev.xvin.palimpsest.ui.components.formatListening
import dev.xvin.palimpsest.ui.theme.Ink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.i18n.plural
import dev.xvin.palimpsest.i18n.label

@Serializable
enum class TrackListKind { ALBUM, ARTIST, FOLDER, PLAYLIST, LIKED, UPDATES, MOST_PLAYED, RECENT, FORGOTTEN }

@Composable
fun TrackListScreen(kind: TrackListKind, key: String, extra: String?) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val dao = graph.db.tracks()
    val flow: Flow<List<TrackEntity>> = remember(kind, key, extra) {
        when (kind) {
            TrackListKind.ALBUM -> dao.observeAlbumTracks(key, extra.orEmpty())
            TrackListKind.ARTIST -> graph.db.artists().observeTracksOf(key)
            TrackListKind.FOLDER -> dao.observeFolderTracks(key)
            TrackListKind.PLAYLIST -> key.toLongOrNull()?.let { graph.db.playlists().observeTracks(it) } ?: flowOf(emptyList())
            TrackListKind.LIKED -> dao.observeLiked()
            TrackListKind.UPDATES -> dao.observeUpdates()
            TrackListKind.MOST_PLAYED -> dao.observeMostPlayed()
            TrackListKind.RECENT -> dao.observeRecentlyAdded()
            TrackListKind.FORGOTTEN -> dao.observeForgotten(System.currentTimeMillis() - 60L * 24 * 3600 * 1000)
        }
    }
    val tracks by flow.collectAsStateWithLifecycle(emptyList())
    val playing by graph.player.state.collectAsStateWithLifecycle()
    val playlists by graph.db.playlists().observeAll().collectAsStateWithLifecycle(emptyList())
    val playlist = if (kind == TrackListKind.PLAYLIST) playlists.firstOrNull { it.id == key.toLongOrNull() } else null
    var renaming by remember { mutableStateOf(false) }
    var editingAlbum by remember { mutableStateOf(false) }
    if (editingAlbum) AlbumEditDialog(tracks, onDismiss = { editingAlbum = false })

    val title = when (kind) {
        TrackListKind.ALBUM, TrackListKind.ARTIST -> key
        TrackListKind.FOLDER -> key.substringAfterLast('/')
        TrackListKind.PLAYLIST -> playlist?.name ?: ""
        TrackListKind.LIKED -> tr(R.string.smart_favorites)
        TrackListKind.UPDATES -> tr(R.string.smart_new_versions)
        TrackListKind.MOST_PLAYED -> tr(R.string.smart_most_played)
        TrackListKind.RECENT -> tr(R.string.smart_recently_added)
        TrackListKind.FORGOTTEN -> tr(R.string.smart_forgotten)
    }
    val subtitle = when (kind) {
        TrackListKind.ALBUM -> extra
        TrackListKind.FOLDER -> key.substringBeforeLast('/', "").ifEmpty { null }
        TrackListKind.UPDATES -> tr(R.string.new_versions_hint)
        TrackListKind.MOST_PLAYED -> tr(R.string.smart_most_played_hint)
        TrackListKind.RECENT -> tr(R.string.smart_recently_added_hint)
        TrackListKind.FORGOTTEN -> tr(R.string.smart_forgotten_hint)
        else -> playlist?.service?.label
    }
    val remoteOnly = tracks.filter { it.remoteId != null && (it.path == null || it.updateAvailable) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                IconButton(onClick = nav::back, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    tracks.firstOrNull()?.let { TrackArtwork(it, Modifier.size(112.dp), corner = 12.dp) }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 4) }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            plural(R.plurals.tracks, tracks.size) + " · " + formatListening(tracks.sumOf { it.durationMs }),
                            style = MaterialTheme.typography.labelMedium, color = Ink.Faint,
                        )
                    }
                }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { graph.player.play(tracks) }, enabled = tracks.isNotEmpty()) {
                        Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.listen))
                    }
                    OutlinedButton(onClick = { graph.player.play(tracks, shuffle = true) }, enabled = tracks.isNotEmpty()) {
                        Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.shuffled))
                    }
                    if (remoteOnly.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch { graph.downloads.enqueue(remoteOnly.map { it.id }) }
                            nav.toast(tr(R.string.downloading_n, remoteOnly.size))
                        }) { Icon(Icons.Rounded.Download, tr(R.string.download_all)) }
                    }
                    if (kind == TrackListKind.ALBUM && tracks.any { it.path != null }) {
                        IconButton(onClick = { editingAlbum = true }) { Icon(Icons.Rounded.Edit, tr(R.string.album_edit), tint = Ink.Muted) }
                    }
                    if (kind == TrackListKind.PLAYLIST && playlist?.service == null) {
                        Box { IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, tr(R.string.rename), tint = Ink.Muted) } }
                        IconButton(onClick = {
                            scope.launch { playlist?.let { graph.db.playlists().delete(it.id) } }
                            nav.back()
                        }) { Icon(Icons.Rounded.Delete, tr(R.string.delete), tint = Ink.Muted) }
                    }
                }
            }
        }
        if (tracks.isEmpty()) item {
            Text(
                when (kind) {
                    TrackListKind.FORGOTTEN -> tr(R.string.smart_forgotten_empty)
                    TrackListKind.MOST_PLAYED -> tr(R.string.smart_most_played_empty)
                    TrackListKind.UPDATES -> tr(R.string.all_tracks_current)
                    else -> tr(R.string.list_empty)
                },
                Modifier.padding(horizontal = 16.dp), color = Ink.Muted,
            )
        }
        itemsIndexed(tracks, key = { i, t -> "${t.id}_$i" }) { i, t ->
            TrackRow(
                t,
                onClick = { graph.player.play(tracks, i) },
                onMore = { nav.showActions(t) },
                playing = playing.nowPlaying?.trackId == t.id,
                modifier = Modifier.animateItem(),
                index = if (kind == TrackListKind.ALBUM) (t.trackNumber ?: (i + 1)) else null,
                subtitle = when (kind) {
                    TrackListKind.ALBUM -> t.artist
                    TrackListKind.UPDATES -> tr(R.string.new_version_row, t.artist, dev.xvin.palimpsest.ui.components.formatDuration(t.durationMs), dev.xvin.palimpsest.ui.components.formatDuration(t.remoteDurationMs ?: 0))
                    else -> null
                },
            )
        }
    }
    if (renaming) {
        NameDialog(tr(R.string.rename), playlist?.name.orEmpty(), onDismiss = { renaming = false }) { name ->
            scope.launch { playlist?.let { graph.db.playlists().rename(it.id, name) } }
            renaming = false
        }
    }
}
