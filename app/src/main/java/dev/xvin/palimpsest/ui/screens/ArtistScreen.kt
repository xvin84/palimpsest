package dev.xvin.palimpsest.ui.screens

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.core.model.AlbumWithTracks
import dev.xvin.palimpsest.core.model.ArtistInfo
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.Artwork
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.RemoteTrackRow
import dev.xvin.palimpsest.ui.components.SectionHeader
import dev.xvin.palimpsest.ui.components.TrackRow
import dev.xvin.palimpsest.ui.theme.Ink
import kotlinx.coroutines.launch
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.i18n.plural

private sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Ok<T>(val value: T) : Loaded<T>
    data class Failed(val error: Throwable?) : Loaded<Nothing>
}

/** Shared actions for lists of service tracks: play the whole list from a position, download, menu. */
@Composable
private fun rememberRemoteActions(): RemoteActions {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    return remember {
        RemoteActions(
            play = { list, i -> scope.launch { graph.player.play(list.map { graph.remote.ensureTrack(it) }, i) } },
            download = { list ->
                scope.launch {
                    graph.downloads.enqueue(list.filter { it.available }.map { graph.remote.ensureTrack(it).id })
                    nav.toast(if (list.size == 1) tr(R.string.downloading_one, list.first().title) else tr(R.string.downloading_n, list.size))
                }
            },
            more = { r -> scope.launch { nav.showActions(graph.remote.ensureTrack(r)) } },
        )
    }
}

private class RemoteActions(
    val play: (List<RemoteTrack>, Int) -> Unit,
    val download: (List<RemoteTrack>) -> Unit,
    val more: (RemoteTrack) -> Unit,
)

@Composable
fun ArtistScreen(name: String, remoteId: String?) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val actions = rememberRemoteActions()
    val local by remember(name) { graph.db.artists().observeTracksOf(name) }.collectAsStateWithLifecycle(emptyList())
    val playing by graph.player.state.collectAsStateWithLifecycle()
    val info by produceState<Loaded<ArtistInfo?>>(Loaded.Loading, name, remoteId) {
        value = runCatching { graph.remote.artistInfo(name, remoteId) }.fold({ Loaded.Ok(it) }, { Loaded.Failed(it) })
    }
    val artist = (info as? Loaded.Ok)?.value
    var showAllLocal by remember { mutableStateOf(false) }
    var descExpanded by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(1.25f)) {
                Artwork(artist?.artist?.coverUrl ?: local.firstOrNull()?.let { dev.xvin.palimpsest.data.library.ArtProvider.uriFor(it.id, it.modifiedAt) }, name, Modifier.fillMaxSize(), corner = 0.dp)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Ink.Paper.copy(alpha = 0f), 1f to Ink.Paper)))
                IconButton(onClick = nav::back, modifier = Modifier.statusBarsPadding().padding(4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) }
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(artist?.artist?.name ?: name, style = MaterialTheme.typography.displayMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        artist?.genres?.take(3)?.joinToString(", ")?.ifBlank { null },
                        artist?.likes?.let { "♥ ${compact(it)}" },
                        if (local.isNotEmpty()) tr(R.string.artist_you_have, plural(R.plurals.tracks, local.size)) else null,
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                }
            }
        }
        item {
            // Weights + single-line labels: on narrow phones or with large fonts the labels used to wrap letter by letter.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val compactPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                if (local.isNotEmpty()) {
                    Button(onClick = { graph.player.play(local) }, Modifier.weight(1f), contentPadding = compactPadding) {
                        Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(4.dp))
                        Text(tr(R.string.artist_play_downloaded), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = { graph.player.play(local, shuffle = true) }, contentPadding = compactPadding) { Icon(Icons.Rounded.Shuffle, tr(R.string.shuffle)) }
                }
                artist?.let { a ->
                    OutlinedButton(onClick = {
                        scope.launch {
                            graph.radio.start("artist:${a.artist.id}", tr(R.string.radio_title, a.artist.name)).onFailure { nav.error(it, tr(R.string.ctx_radio_start)) }
                        }
                    }, Modifier.weight(1f), contentPadding = compactPadding) {
                        Icon(Icons.Rounded.Radio, null); Spacer(Modifier.width(4.dp))
                        Text(tr(R.string.radio), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        artist?.description?.let { d ->
            item {
                Text(
                    d, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted,
                    maxLines = if (descExpanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp).animateContentSize().clickable { descExpanded = !descExpanded },
                )
            }
        }
        if (info is Loaded.Loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        if (info is Loaded.Failed) item { Text(tr(R.string.artist_card_failed), Modifier.padding(16.dp), color = Ink.Faint) }

        if (local.isNotEmpty()) {
            item { SectionHeader(tr(R.string.artist_in_library, local.size)) }
            val shown = if (showAllLocal) local else local.take(5)
            itemsIndexed(shown, key = { _, t -> "l${t.id}" }) { i, t ->
                TrackRow(t, onClick = { graph.player.play(local, i) }, onMore = { nav.showActions(t) }, playing = playing.nowPlaying?.trackId == t.id, modifier = Modifier.animateItem())
            }
            if (local.size > 5 && !showAllLocal) item { TextButton(onClick = { showAllLocal = true }, Modifier.padding(horizontal = 8.dp)) { Text(tr(R.string.show_all_n, local.size)) } }
        }
        artist?.let { a ->
            if (a.popular.isNotEmpty()) {
                item { SectionHeader(tr(R.string.artist_popular)) { TextButton(onClick = { actions.download(a.popular) }) { Icon(Icons.Rounded.Download, null); Text(tr(R.string.download)) } } }
                itemsIndexed(a.popular, key = { _, r -> "p${r.id}" }) { i, r ->
                    RemoteTrackRow(r, onPlay = { actions.play(a.popular, i) }, onDownload = { actions.download(listOf(r)) }, onMore = { actions.more(r) })
                }
            }
            if (a.albums.isNotEmpty()) {
                item { SectionHeader(tr(R.string.artist_albums)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(a.albums, key = { it.id }) { al ->
                            Column(Modifier.width(140.dp).clickable { nav.openRemoteAlbum(al.id) }) {
                                Artwork(al.coverUrl, al.title, Modifier.size(140.dp), corner = 10.dp)
                                Spacer(Modifier.height(6.dp))
                                Text(al.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(al.year?.toString(), al.type?.let(::albumType)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                            }
                        }
                    }
                }
            }
            if (a.similar.isNotEmpty()) {
                item { SectionHeader(tr(R.string.similar)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(a.similar, key = { it.id }) { s ->
                            Column(Modifier.width(88.dp).clickable { nav.openArtist(s.name, s.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Artwork(s.coverUrl, s.name, Modifier.size(80.dp), corner = 40.dp)
                                Spacer(Modifier.height(6.dp))
                                Text(s.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Ink.Text)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun albumType(t: String) = when (t) {
    "single" -> tr(R.string.album_type_single)
    "compilation" -> tr(R.string.album_type_compilation)
    "podcast" -> tr(R.string.album_type_podcast)
    else -> tr(R.string.album_type_album)
}

private fun compact(n: Int) = when {
    n >= 1_000_000 -> tr(R.string.compact_millions).format(n / 1_000_000f)
    n >= 1_000 -> tr(R.string.compact_thousands).format(n / 1_000f)
    else -> n.toString()
}

/** An album straight from Yandex: play, shuffle or download it whole. */
@Composable
fun RemoteAlbumScreen(albumId: String) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val actions = rememberRemoteActions()
    val data by produceState<Loaded<AlbumWithTracks>>(Loaded.Loading, albumId) {
        value = runCatching { graph.remote.album(albumId) }.fold({ Loaded.Ok(it) }, { Loaded.Failed(it) })
    }
    RemoteListLayout(
        title = (data as? Loaded.Ok)?.value?.album?.title ?: "",
        subtitle = (data as? Loaded.Ok)?.value?.album?.let { a -> listOfNotNull(a.artists.joinToString(", "), a.year?.toString()).joinToString(" · ") },
        cover = (data as? Loaded.Ok)?.value?.album?.coverUrl,
        state = data,
        tracks = (data as? Loaded.Ok)?.value?.tracks.orEmpty(),
        numbered = true,
        actions = actions,
        onArtist = { nav.openArtist(it, null) },
    )
}

/** "Похожие треки" for a service track. */
@Composable
fun SimilarScreen(trackRemoteId: String, title: String) {
    val graph = LocalGraph.current
    val actions = rememberRemoteActions()
    val data by produceState<Loaded<List<RemoteTrack>>>(Loaded.Loading, trackRemoteId) {
        value = runCatching { graph.remote.similar(trackRemoteId) }.fold({ Loaded.Ok(it) }, { Loaded.Failed(it) })
    }
    RemoteListLayout(
        title = tr(R.string.similar),
        subtitle = tr(R.string.similar_to, title),
        cover = null,
        state = data,
        tracks = (data as? Loaded.Ok)?.value.orEmpty(),
        numbered = false,
        actions = actions,
        onArtist = null,
    )
}

@Composable
private fun RemoteListLayout(
    title: String,
    subtitle: String?,
    cover: String?,
    state: Loaded<*>,
    tracks: List<RemoteTrack>,
    numbered: Boolean,
    actions: RemoteActions,
    onArtist: ((String) -> Unit)?,
) {
    val nav = LocalNav.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                IconButton(onClick = nav::back, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (cover != null) { Artwork(cover, title, Modifier.size(112.dp), corner = 12.dp); Spacer(Modifier.width(16.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 2,
                                modifier = if (onArtist != null) Modifier.clickable { onArtist(it.substringBefore(" · ").substringBefore(",")) } else Modifier)
                        }
                    }
                }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { actions.play(tracks, 0) }, enabled = tracks.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.listen)) }
                    OutlinedButton(onClick = { actions.play(tracks.shuffled(), 0) }, enabled = tracks.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, null) }
                    OutlinedButton(onClick = { actions.download(tracks) }, enabled = tracks.isNotEmpty()) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.download)) }
                }
            }
        }
        when (state) {
            is Loaded.Loading -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            is Loaded.Failed -> item { Text(tr(R.string.load_failed, state.error?.message ?: ""), Modifier.padding(16.dp), color = Ink.Faint) }
            else -> Unit
        }
        itemsIndexed(tracks, key = { i, r -> "${r.id}#$i" }) { i, r ->
            RemoteTrackRow(r, onPlay = { actions.play(tracks, i) }, onDownload = { actions.download(listOf(r)) }, onMore = { actions.more(r) }, index = if (numbered) i + 1 else null)
        }
    }
}
