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
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(initial: TrackEntity, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val track by graph.db.tracks().observe(initial.id).collectAsStateWithLifecycle(initial)
    val t = track ?: return
    var pickPlaylist by remember { mutableStateOf(false) }
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
            Action(Icons.Rounded.SkipNext, "Играть следующим") { act { graph.player.playNext(listOf(t)) } }
            Action(Icons.AutoMirrored.Rounded.QueueMusic, "Добавить в очередь") { act { graph.player.enqueue(listOf(t)) } }
            Action(Icons.AutoMirrored.Rounded.PlaylistAdd, "Добавить в плейлист…") { pickPlaylist = true }
            Action(if (t.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (t.liked) "Убрать из любимых" else "В любимые") {
                scope.launch {
                    graph.setLiked(t, !t.liked).onFailure { nav.toast("В Яндексе не сохранилось: ${it.message}") }
                }
            }
            if (t.remoteId != null && (t.path == null || t.updateAvailable)) {
                Action(Icons.Rounded.Download, if (t.updateAvailable) "Скачать новую версию (старая сохранится)" else "Скачать") {
                    act {
                        scope.launch { graph.downloads.enqueue(listOf(t.id)) }
                        nav.toast("Скачиваю «${t.title}»")
                    }
                }
            }
            if (t.path != null) Action(Icons.Rounded.Edit, "Теги, обложка и текст") { act { nav.openTrack(t.id, 0) } }
            Action(Icons.Rounded.History, "История версий") { act { nav.openTrack(t.id, 2) } }
            t.album?.let { album -> Action(Icons.Rounded.Album, "Альбом «$album»") { act { nav.openList(TrackListKind.ALBUM, album, t.albumArtist ?: t.artist) } } }
            artists.forEach { a -> Action(Icons.Rounded.Person, a.name) { act { nav.openArtist(a.name, a.remoteId) } } }
            if (t.service == Service.YANDEX && t.remoteId != null && graph.yandex.isReady) {
                Action(Icons.Rounded.Radio, "Радио по треку") {
                    act {
                        graph.mainScope.launch {
                            graph.radio.start("track:${t.remoteId}", "Радио: ${t.title}").onFailure { nav.error(it, "Радио по треку") }
                        }
                    }
                }
                Action(Icons.Rounded.AutoAwesome, "Похожие треки") { act { nav.openSimilar(t.remoteId, t.title) } }
                t.remoteAlbumId?.let { id -> Action(Icons.Rounded.Album, "Альбом в Яндексе") { act { nav.openRemoteAlbum(id) } } }
            }
        }
    }

    if (pickPlaylist) {
        PlaylistPicker(
            onDismiss = { pickPlaylist = false },
            onPick = { p ->
                scope.launch { graph.db.playlists().append(p.id, listOf(t.id)) }
                nav.toast("Добавлено в «${p.name}»")
                pickPlaylist = false
                onDismiss()
            },
        )
    }
}

fun techLine(t: TrackEntity): String = listOfNotNull(
    t.codec?.uppercase(),
    t.bitsPerSample?.takeIf { it > 0 }?.let { "$it бит" },
    t.sampleRate?.let { "%.1f кГц".format(it / 1000f) },
    t.bitrateKbps?.let { "$it кбит/с" },
    if (t.path == null) "стрим · ${t.service.displayName}" else null,
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
        title = { Text("В плейлист") },
        text = {
            LazyColumn {
                item {
                    Text(
                        "+ Новый плейлист",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                val name = "Плейлист ${own.size + 1}"
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
