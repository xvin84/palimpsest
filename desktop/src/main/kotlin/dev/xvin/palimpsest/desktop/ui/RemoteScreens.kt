package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.Whatshot
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
                        onPlay = { graph.player.playRemote(tracks, i) }, onMenu = { nav.openMenu(graph.library.ensureRemote(r)) })
                }
                PIconButton(Icons.Rounded.Download, t("Скачать", "Download"), { graph.downloads.enqueue(listOf(graph.library.ensureRemote(r).id)); nav.toast(t("Скачиваю «${r.title}»", "Downloading «${r.title}»")) }, iconSize = 18.dp, tint = LocalPal.current.faint)
            }
        }
    }
}

@Composable
fun ArtistCircle(image: Any?, name: String, onClick: () -> Unit) {
    Tile({ Artwork(image, name, Modifier.fillMaxSize(), 80.dp) }, name, "", Modifier.width(140.dp), round = true, onClick = onClick)
}

@Composable
fun AlbumTile(image: Any?, title: String, subtitle: String, onClick: () -> Unit) {
    Tile({ Artwork(image, title, Modifier.fillMaxSize(), Radius.md) }, title, subtitle, Modifier.width(160.dp), onClick = onClick)
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
    LazyColumn(Modifier.fillMaxSize().padding(start = 28.dp, end = 20.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)) {
        item {
            PIconButton(Icons.AutoMirrored.Rounded.ArrowBack, t("Назад", "Back"), nav::back, Modifier.padding(bottom = 8.dp).offset(x = (-6).dp), iconSize = 20.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(a?.artist?.coverUrl, s.name, Modifier.size(168.dp), 84.dp)
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("ИСПОЛНИТЕЛЬ", "ARTIST"), style = Type.caption, color = pal.muted, modifier = Modifier.padding(bottom = 6.dp))
                    Text(a?.artist?.name ?: s.name, style = MaterialTheme.typography.displayLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(a?.genres?.take(3)?.joinToString(", ")?.ifBlank { null }, if (local.isNotEmpty()) t("в медиатеке ", "in your library: ") + tracksCount(local.size) else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = pal.muted, modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (local.isNotEmpty()) PButton(t("Моё", "Mine"), { graph.player.play(local) }, icon = Icons.Rounded.PlayArrow, kind = Kind.PRIMARY)
                        a?.takeIf { it.popular.isNotEmpty() }?.let { info -> PButton(t("Популярное", "Popular"), { graph.player.playRemote(info.popular, 0) }, icon = Icons.Rounded.Whatshot, kind = if (local.isEmpty()) Kind.PRIMARY else Kind.SECONDARY) }
                        if (a != null && a.artist.service == Service.YANDEX) PButton(t("Радио", "Radio"), {
                            graph.scope.launch { runCatching { graph.player.startRadio("artist:${a.artist.id}", t("Радио: ", "Radio: ") + a.artist.name) }.onFailure { nav.toast(it.message ?: "") } }
                        }, icon = Icons.Rounded.Radio)
                    }
                }
            }
        }
        a?.description?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, color = pal.muted, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 20.dp).widthIn(max = 760.dp)) } }
        if (info is Load.Loading) item { Spinner(Modifier.padding(24.dp)) }
        if (local.isNotEmpty()) {
            item { SectionHeader(t("В медиатеке", "In your library")) }
            items(local.take(30), key = { "l" + it.id }) { tr ->
                val st by graph.player.state.collectAsState()
                TrackLine(tr, null, st.current?.id == tr.id, tr.id in liked, onPlay = { graph.player.play(local, local.indexOf(tr)) }, onMenu = { nav.openMenu(tr) })
            }
        }
        a?.let { info ->
            if (info.popular.isNotEmpty()) { item { SectionHeader(t("Популярное", "Popular")) }; item { RemoteTrackList(info.popular.take(15)) } }
            if (info.albums.isNotEmpty()) {
                item { SectionHeader(t("Альбомы и синглы", "Albums and singles")) }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) { items(info.albums, key = { it.id }) { al -> AlbumTile(al.coverUrl, al.title, albumLine(al)) { nav.go(Screen.RemoteAlbum(al.id, al.service)) } } } }
            }
            if (info.similar.isNotEmpty()) {
                item { SectionHeader(t("Похожие", "Similar")) }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) { items(info.similar, key = { it.id }) { sa -> ArtistCircle(sa.coverUrl, sa.name) { nav.go(Screen.Artist(sa.name, sa.id, sa.service)) } } } }
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
    RemoteList(data, t("Альбом", "Album"), { it.album.title }, { albumLine(it.album) }, { it.album.coverUrl }, { it.tracks }, numbered = true)
}

@Composable
fun RemotePlaylistScreen(s: Screen.RemotePlaylist) {
    val graph = LocalGraph.current
    val data by produceState<Load<List<RemoteTrack>>>(Load.Loading, s) { value = load { graph.provider(s.service)!!.playlistTracks(s.id) } }
    RemoteList(data, t("Плейлист", "Playlist"), { s.title }, { tracksCount(it.size) }, { it.firstOrNull()?.coverUrl }, { it }, numbered = false)
}

@Composable
private fun <T> RemoteList(data: Load<T>, kind: String, title: (T) -> String, subtitle: (T) -> String, cover: (T) -> String?, tracks: (T) -> List<RemoteTrack>, numbered: Boolean) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    LazyColumn(Modifier.fillMaxSize().padding(start = 28.dp, end = 20.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)) {
        when (data) {
            is Load.Loading -> item {
                Column {
                    PIconButton(Icons.AutoMirrored.Rounded.ArrowBack, t("Назад", "Back"), nav::back, Modifier.offset(x = (-6).dp), iconSize = 20.dp)
                    Spinner(Modifier.padding(24.dp))
                }
            }
            is Load.Failed -> item {
                Column {
                    PIconButton(Icons.AutoMirrored.Rounded.ArrowBack, t("Назад", "Back"), nav::back, Modifier.offset(x = (-6).dp), iconSize = 20.dp)
                    Text(t("Не загрузилось: ", "Couldn’t load: ") + data.error.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
                }
            }
            is Load.Ok -> {
                val v = data.value
                val list = tracks(v)
                item {
                    ListHeader(title(v), subtitle(v), kind, actions = {
                        PButton(t("Слушать", "Play"), { graph.player.playRemote(list, 0) }, icon = Icons.Rounded.PlayArrow, kind = Kind.PRIMARY, enabled = list.isNotEmpty())
                        PButton(t("Скачать всё", "Download all"), {
                            graph.downloads.enqueue(list.map { graph.library.ensureRemote(it).id }); nav.toast(t("Скачиваю: ", "Downloading ") + tracksCount(list.size))
                        }, icon = Icons.Rounded.Download, enabled = list.isNotEmpty())
                    }) { Artwork(cover(v), title(v), Modifier.fillMaxSize(), Radius.lg) }
                }
                item { RemoteTrackList(list, numbered) }
            }
        }
    }
}

/** Songs Yandex Music finds similar to one (from a track's menu). */
@Composable
fun SimilarScreen(s: Screen.Similar) {
    val graph = LocalGraph.current
    val data by produceState<Load<List<RemoteTrack>>>(Load.Loading, s) { value = load { graph.yandex.similarTracks(s.remoteId) } }
    RemoteList(data, t("Похожие", "Similar"), { t("Похожие на «${s.title}»", "Similar to «${s.title}»") }, { tracksCount(it.size) }, { it.firstOrNull()?.coverUrl }, { it }, numbered = false)
}
