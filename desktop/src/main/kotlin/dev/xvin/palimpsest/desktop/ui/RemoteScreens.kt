package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xvin.palimpsest.core.model.ArtistInfo
import dev.xvin.palimpsest.core.model.BrowsableProvider
import dev.xvin.palimpsest.core.model.RemoteAlbum
import dev.xvin.palimpsest.core.model.RemoteArtist
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.desktop.data.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loading / loaded / failed, for anything fetched from a service. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}

suspend fun <T> load(block: suspend () -> T): Load<T> =
    runCatching { withContext(Dispatchers.IO) { block() } }.fold({ Load.Ok(it) }, { Load.Failed(it) })

/** Artist info from the right service: Yandex by id (or found by name), YouTube Music / Bandcamp by channel. */
suspend fun artistInfo(graph: Graph, name: String, id: String?, service: Service): ArtistInfo? =
    if (service != Service.YANDEX && id != null) (graph.provider(service) as? BrowsableProvider)?.artist(id, name)
    else if (!graph.yandex.isReady) null
    else (id ?: graph.yandex.searchArtist(name)?.id)?.let { graph.yandex.artistInfo(it) }

/** A list of service tracks: double click plays the list from there, the arrow downloads. */
@Composable
fun RemoteTrackList(tracks: List<RemoteTrack>, numbered: Boolean = false) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val st by graph.player.state.collectAsState()
    val liked by graph.library.liked.collectAsState()
    Column {
        tracks.forEachIndexed { i, r ->
            val t = remember(r) { dev.xvin.palimpsest.desktop.data.Track(id = "${r.service}:${r.id}", title = r.title, artist = r.artistLine.ifBlank { "—" }, album = r.album, durationMs = r.durationMs, service = r.service, remoteId = r.id, remoteAlbumId = r.albumId, coverUrl = r.coverUrl, trackNumber = r.trackNumber) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TrackLine(t, if (numbered) r.trackNumber ?: (i + 1) else i + 1, st.current?.id == t.id, t.id in liked,
                        onPlay = { graph.player.playRemote(tracks, i) }, onMenu = { nav.menuFor = graph.library.ensureRemote(r) })
                }
                IconButton(onClick = { graph.downloads.enqueue(listOf(graph.library.ensureRemote(r).id)); nav.toast(t("Скачиваю «${r.title}»", "Downloading «${r.title}»")) }) {
                    Icon(Icons.Rounded.Download, null, tint = LocalPal.current.faint, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun ArtistCircle(image: Any?, name: String, onClick: () -> Unit) {
    Column(Modifier.width(110.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(image, name, Modifier.size(100.dp), 50.dp)
        Spacer(Modifier.height(6.dp))
        Text(name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AlbumTile(image: Any?, title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.width(160.dp).clickable(onClick = onClick)) {
        Artwork(image, title, Modifier.size(160.dp), 10.dp)
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, color = LocalPal.current.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun albumLine(a: RemoteAlbum) = listOfNotNull(a.artists.joinToString(", ").ifBlank { null }, a.year?.toString(), if (a.type == "single") t("сингл", "single") else null).joinToString(" · ")

@Composable
fun ArtistScreen(s: Screen.Artist) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val all by graph.library.tracks.collectAsState()
    val liked by graph.library.liked.collectAsState()
    val local = remember(all, s.name) { all.filter { t -> t.isLocal && t.artists.any { it.equals(s.name, true) } } }
    val info by produceState<Load<ArtistInfo?>>(Load.Loading, s) { value = load { artistInfo(graph, s.name, s.remoteId, s.service) } }
    val a = (info as? Load.Ok)?.value
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
                Artwork(a?.artist?.coverUrl, s.name, Modifier.size(160.dp), 80.dp)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(a?.artist?.name ?: s.name, style = MaterialTheme.typography.displayLarge, maxLines = 2)
                    Text(listOfNotNull(a?.genres?.take(3)?.joinToString(", ")?.ifBlank { null }, if (local.isNotEmpty()) t("у тебя ${local.size} тр.", "${local.size} tracks in your library") else null).joinToString(" · "), color = pal.muted)
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (local.isNotEmpty()) Button(onClick = { graph.player.play(local) }) { Icon(Icons.Rounded.PlayArrow, null); Text(t("Моё", "Mine")) }
                        a?.takeIf { it.popular.isNotEmpty() }?.let { info -> OutlinedButton(onClick = { graph.player.playRemote(info.popular, 0) }) { Text(t("Популярное", "Popular")) } }
                        if (a != null && a.artist.service == Service.YANDEX) OutlinedButton(onClick = {
                            graph.scope.launch { runCatching { graph.player.startRadio("artist:${a.artist.id}", t("Радио: ", "Radio: ") + a.artist.name) }.onFailure { nav.toast(it.message ?: "") } }
                        }) { Icon(Icons.Rounded.Radio, null); Spacer(Modifier.width(4.dp)); Text(t("Радио", "Radio")) }
                    }
                }
            }
        }
        a?.description?.let { item { Text(it, color = pal.muted, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp)) } }
        if (info is Load.Loading) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
        if (local.isNotEmpty()) {
            item { SectionHeader(t("В медиатеке", "In your library")) }
            items(local.take(30), key = { "l" + it.id }) { tr ->
                val st by graph.player.state.collectAsState()
                TrackLine(tr, null, st.current?.id == tr.id, tr.id in liked, onPlay = { graph.player.play(local, local.indexOf(tr)) }, onMenu = { nav.menuFor = tr })
            }
        }
        a?.let { info ->
            if (info.popular.isNotEmpty()) { item { SectionHeader(t("Популярное", "Popular")) }; item { RemoteTrackList(info.popular.take(15)) } }
            if (info.albums.isNotEmpty()) {
                item { SectionHeader(t("Альбомы и синглы", "Albums and singles")) }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(info.albums, key = { it.id }) { al -> AlbumTile(al.coverUrl, al.title, albumLine(al)) { nav.go(Screen.RemoteAlbum(al.id, al.service)) } } } }
            }
            if (info.similar.isNotEmpty()) {
                item { SectionHeader(t("Похожие", "Similar")) }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(info.similar, key = { it.id }) { sa -> ArtistCircle(sa.coverUrl, sa.name) { nav.go(Screen.Artist(sa.name, sa.id, sa.service)) } } } }
            }
        }
    }
}

@Composable
fun RemoteAlbumScreen(s: Screen.RemoteAlbum) {
    val graph = LocalGraph.current
    val data by produceState<Load<dev.xvin.palimpsest.core.model.AlbumWithTracks>>(Load.Loading, s) {
        value = load {
            if (s.service == Service.YANDEX) graph.yandex.albumWithTracks(s.id)
            else (graph.provider(s.service) as BrowsableProvider).album(s.id)
        }
    }
    RemoteList(data, { it.album.title }, { albumLine(it.album) }, { it.album.coverUrl }, { it.tracks }, numbered = true)
}

@Composable
fun RemotePlaylistScreen(s: Screen.RemotePlaylist) {
    val graph = LocalGraph.current
    val data by produceState<Load<List<RemoteTrack>>>(Load.Loading, s) { value = load { graph.provider(s.service)!!.playlistTracks(s.id) } }
    RemoteList(data, { s.title }, { t("${it.size} треков", "${it.size} tracks") }, { it.firstOrNull()?.coverUrl }, { it }, numbered = false)
}

@Composable
private fun <T> RemoteList(data: Load<T>, title: (T) -> String, subtitle: (T) -> String, cover: (T) -> String?, tracks: (T) -> List<RemoteTrack>, numbered: Boolean) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        when (data) {
            is Load.Loading -> item { Row { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }; CircularProgressIndicator(Modifier.padding(24.dp)) } }
            is Load.Failed -> item { Row { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }; Text(t("Не загрузилось: ", "Couldn’t load: ") + data.error.message, color = MaterialTheme.colorScheme.error) } }
            is Load.Ok -> {
                val v = data.value
                val list = tracks(v)
                item { ListHeader(title(v), subtitle(v)) { Artwork(cover(v), title(v), Modifier.fillMaxSize(), 12.dp) } }
                item {
                    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { graph.player.playRemote(list, 0) }, enabled = list.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Text(t("Слушать", "Play")) }
                        OutlinedButton(onClick = { graph.downloads.enqueue(list.map { graph.library.ensureRemote(it).id }); nav.toast(t("Скачиваю ${list.size} тр.", "Downloading ${list.size} tracks")) }, enabled = list.isNotEmpty()) {
                            Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(4.dp)); Text(t("Скачать всё", "Download all"))
                        }
                    }
                }
                item { RemoteTrackList(list, numbered) }
            }
        }
    }
}
