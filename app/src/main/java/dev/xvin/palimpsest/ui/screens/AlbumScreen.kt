package dev.xvin.palimpsest.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.core.library.AlbumMerge
import dev.xvin.palimpsest.core.model.AlbumWithTracks
import dev.xvin.palimpsest.core.model.SearchType
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.i18n.plural
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.Artwork
import dev.xvin.palimpsest.ui.components.ListActions
import dev.xvin.palimpsest.ui.components.ListKey
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.RemoteTrackRow
import dev.xvin.palimpsest.ui.components.TrackArtwork
import dev.xvin.palimpsest.ui.components.TrackRow
import dev.xvin.palimpsest.ui.components.formatListening
import dev.xvin.palimpsest.ui.theme.Ink
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * One album, like the artist page: the service's whole tracklist with the songs you have in their
 * places (and playing from here), plus anything only you have. Opened by name (your library), by
 * the service's id (search, artist page, links) or both (a track's menu).
 */
@Composable
fun AlbumScreen(album: String?, artist: String?, remoteId: String?, service: Service) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val dao = graph.db.tracks()
    val playing by graph.player.state.collectAsStateWithLifecycle()

    // Yours by name first: it's what tells which service album this is when nothing else does.
    val byName by remember(album, artist) {
        if (album != null && artist != null) dao.observeAlbumTracks(album, artist) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(null)
    val hint = remoteId?.let { it to service }
        ?: byName?.firstNotNullOfOrNull { t -> t.remoteAlbumId?.takeIf { t.remoteId != null }?.let { it to t.service } }
    val remote by produceState<Loaded<AlbumWithTracks?>>(Loaded.Loading, hint, byName == null) {
        if (byName == null) return@produceState
        val linked = hint?.let { (id, svc) -> runCatching { graph.remote.album(id, svc) } }
        value = linked?.getOrNull()?.let { Loaded.Ok(it) } ?: runCatching {
            // No link, or its service is out of reach (Yandex signed out): look the album up by
            // name, on Yandex when signed in, otherwise on YouTube Music (it needs no account).
            if (album == null || artist == null) return@runCatching null
            val svc = if (graph.yandex.isReady) Service.YANDEX else Service.YOUTUBE
            val found = graph.remote.searchAll(svc, "$artist $album", SearchType.ALBUMS).albums.firstOrNull { a ->
                TrackMatcher.normalize(a.title) == TrackMatcher.normalize(album) &&
                    a.artists.any { TrackMatcher.normalize(it) == TrackMatcher.normalize(artist) }
            } ?: return@runCatching null
            graph.remote.album(found.id, svc)
        }.fold({ Loaded.Ok(it) }, { Loaded.Failed(linked?.exceptionOrNull() ?: it) })
    }
    val loaded = (remote as? Loaded.Ok)?.value
    // Yours by the service album too: a liked song whose tags name the album a little differently.
    val mine by remember(album, artist, loaded?.album?.id) {
        val a = loaded?.album
        val byId = if (a != null) dao.observeByRemoteAlbum(a.service, a.id) else flowOf(emptyList())
        val named = when {
            album != null && artist != null -> dao.observeAlbumTracks(album, artist)
            a != null && a.artists.isNotEmpty() -> dao.observeAlbumTracks(a.title, a.artists.first())
            else -> flowOf(emptyList())
        }
        combine(named, byId) { x, y -> (x + y).distinctBy { it.id } }
    }.collectAsStateWithLifecycle(emptyList())

    val rows = remember(loaded, mine) { AlbumMerge.merge(loaded?.tracks.orEmpty(), mine, { it.remoteId }, { it.title }) }
    // What plays may be a service song you don't keep: matched by its service id.
    val playingTrack by produceState<TrackEntity?>(null, playing.nowPlaying?.trackId) { value = playing.nowPlaying?.trackId?.let { dao.get(it) } }
    var editing by remember { mutableStateOf(false) }
    if (editing) AlbumEditDialog(mine, onDismiss = { editing = false })

    /** Everything playable, in order; service songs get their library row on the way. */
    fun play(from: Int = 0, shuffle: Boolean = false) = scope.launch {
        val playable = rows.filter { it !is AlbumMerge.Row.Theirs || it.track.available }
        val start = rows.getOrNull(from)?.let { playable.indexOf(it) }?.coerceAtLeast(0) ?: 0
        val list = playable.map { r -> when (r) { is AlbumMerge.Row.Mine -> r.track; is AlbumMerge.Row.Theirs -> graph.remote.ensureTrack(r.track) } }
        graph.player.play(list, start, shuffle)
    }
    val toDownload = rows.filter { r -> r is AlbumMerge.Row.Theirs && r.track.available || r is AlbumMerge.Row.Mine && r.track.path == null && r.track.remoteId != null }

    val title = loaded?.album?.title ?: album.orEmpty()
    val artistName = loaded?.album?.artists?.joinToString(", ")?.ifBlank { null } ?: artist
    val year = loaded?.album?.year ?: mine.mapNotNull { it.year }.maxOrNull()
    val total = rows.size
    val have = rows.count { it is AlbumMerge.Row.Mine }
    val minutes = rows.sumOf { r -> when (r) { is AlbumMerge.Row.Mine -> r.track.durationMs; is AlbumMerge.Row.Theirs -> r.track.durationMs } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) }
                    Spacer(Modifier.weight(1f))
                    if (mine.any { it.path != null }) IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, tr(R.string.album_edit), tint = Ink.Muted) }
                }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val cover = loaded?.album?.coverUrl
                    if (cover != null) Artwork(cover, title, Modifier.size(112.dp), corner = 12.dp)
                    else mine.firstOrNull()?.let { TrackArtwork(it, Modifier.size(112.dp), corner = 12.dp) }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        artistName?.let { a ->
                            Text(
                                listOfNotNull(a, year?.toString()).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 2,
                                modifier = Modifier.clickable { nav.openArtist(a.substringBefore(", ")) },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOfNotNull(
                                plural(R.plurals.tracks, total),
                                formatListening(minutes),
                                if (loaded != null && have in 1 until total) tr(R.string.album_you_have, have, total) else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = Ink.Faint,
                        )
                    }
                }
                ListActions(onPlay = { play() }, enabled = rows.isNotEmpty()) {
                    ListKey(Icons.Rounded.Shuffle, tr(R.string.shuffle), enabled = rows.isNotEmpty()) { play(shuffle = true) }
                    ListKey(Icons.AutoMirrored.Rounded.QueueMusic, tr(R.string.action_enqueue), enabled = rows.isNotEmpty()) {
                        scope.launch {
                            val list = rows.mapNotNull { r -> when (r) { is AlbumMerge.Row.Mine -> r.track; is AlbumMerge.Row.Theirs -> if (r.track.available) graph.remote.ensureTrack(r.track) else null } }
                            graph.player.enqueue(list)
                            nav.toast(plural(R.plurals.queued_n, list.size))
                        }
                    }
                    if (toDownload.isNotEmpty()) ListKey(Icons.Rounded.Download, tr(R.string.download_all)) {
                        scope.launch {
                            val ids = toDownload.map { r -> when (r) { is AlbumMerge.Row.Mine -> r.track.id; is AlbumMerge.Row.Theirs -> graph.remote.ensureTrack(r.track).id } }
                            graph.downloads.enqueue(ids)
                        }
                        nav.toast(tr(R.string.downloading_n, toDownload.size))
                    }
                }
            }
        }
        // Without a service album there's nothing to wait for or to fail: yours are the album.
        when (val r = remote) {
            is Loaded.Loading -> if (rows.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            is Loaded.Failed -> if (rows.isEmpty()) item { Text(tr(R.string.load_failed, r.error?.message ?: ""), Modifier.padding(16.dp), color = Ink.Faint) }
            is Loaded.Ok -> if (rows.isEmpty()) item { Text(tr(R.string.list_empty), Modifier.padding(horizontal = 16.dp), color = Ink.Muted) }
        }
        itemsIndexed(rows, key = { i, r -> when (r) { is AlbumMerge.Row.Mine -> "m${r.track.id}"; is AlbumMerge.Row.Theirs -> "r${r.track.id}#$i" } }) { i, r ->
            val number = (r as? AlbumMerge.Row.Mine)?.let { m -> if (m.remote != null) i + 1 else m.track.trackNumber } ?: (i + 1)
            when (r) {
                is AlbumMerge.Row.Mine -> TrackRow(
                    r.track, onClick = { play(i) }, onMore = { nav.showActions(r.track) },
                    playing = playing.nowPlaying?.trackId == r.track.id, modifier = Modifier.animateItem(),
                    index = number, subtitle = r.track.artist,
                )
                is AlbumMerge.Row.Theirs -> RemoteTrackRow(
                    r.track, onPlay = { play(i) },
                    onDownload = { scope.launch { graph.downloads.enqueue(listOf(graph.remote.ensureTrack(r.track).id)); nav.toast(tr(R.string.downloading_one, r.track.title)) } },
                    onMore = { scope.launch { nav.showActions(graph.remote.ensureTrack(r.track)) } },
                    index = number,
                    playing = playingTrack?.let { it.service == r.track.service && it.remoteId == r.track.id } == true,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}
