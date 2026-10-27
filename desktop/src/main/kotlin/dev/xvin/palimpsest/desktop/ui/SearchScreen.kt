package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.core.model.SearchHit
import dev.xvin.palimpsest.core.model.SearchResults
import dev.xvin.palimpsest.core.model.SearchType
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.desktop.data.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val searchSources = listOf<Service?>(null, Service.YANDEX, Service.YOUTUBE, Service.BANDCAMP)
private fun label(s: Service?) = when (s) { null -> t("Медиатека", "Library"); Service.YANDEX -> t("Яндекс Музыка", "Yandex Music"); Service.YOUTUBE -> "YouTube Music"; else -> s.displayName }
private fun norm(s: String?) = s.orEmpty().lowercase().replace('ё', 'е').trim()

/** One search box for the library and every service, by kind, with the best match on top. */
@Composable
fun SearchScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val settings by graph.settings.state.collectAsState()
    val all by graph.library.tracks.collectAsState()
    val liked by graph.library.liked.collectAsState()
    var query by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<Service?>(null) }
    var kind by remember { mutableStateOf(SearchType.ALL) }
    var remote by remember { mutableStateOf<Load<SearchResults>?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(nav.searchFocus) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query, source, kind) {
        remote = null
        val s = source ?: return@LaunchedEffect
        if (query.isBlank()) return@LaunchedEffect
        delay(400)
        remote = Load.Loading
        remote = load { graph.provider(s)!!.searchAll(query.trim(), kind) }
        if (query.isNotBlank()) graph.settings.update { it.copy(recentSearches = (listOf(query.trim()) + it.recentSearches.filterNot { r -> r.equals(query.trim(), true) }).take(12)) }
    }
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 20.dp, top = 22.dp)) {
        Text(t("Поиск", "Search"), style = MaterialTheme.typography.displayMedium)
        PField(query, { query = it }, t("Исполнитель, альбом, трек", "Artist, album, track"), Modifier.fillMaxWidth(), height = 42.dp, textModifier = Modifier.padding(top = 14.dp, bottom = 12.dp).focusRequester(focus))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tabs(searchSources.map { it to label(it) }, source, { source = it })
            Spacer(Modifier.width(16.dp))
            TextTabs(
                listOf(SearchType.ALL to t("Всё", "All"), SearchType.TRACKS to t("Треки", "Tracks"), SearchType.ARTISTS to t("Исполнители", "Artists"), SearchType.ALBUMS to t("Альбомы", "Albums"), SearchType.PLAYLISTS to t("Плейлисты", "Playlists")),
                kind, { kind = it },
            )
        }
        Spacer(Modifier.height(8.dp))
        if (source == Service.YANDEX && settings.yandexToken == null) { EmptyState(t("Яндекс не подключён", "Yandex isn’t connected"), t("Войди в «Сервисах».", "Sign in under Services.")); return@Column }
        if (query.isBlank()) {
            if (settings.recentSearches.isNotEmpty()) {
                SectionHeader(t("Недавние запросы", "Recent searches"))
                settings.recentSearches.forEach { r ->
                    ListRow({
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.History, null, tint = pal.faint, modifier = Modifier.size(18.dp)) }
                    }, r, "") { query = r }
                }
            }
            return@Column
        }
        if (source == null) LibraryResults(all, liked, query, kind) else when (val r = remote) {
            null, is Load.Loading -> CircularProgressIndicator(Modifier.padding(24.dp).size(24.dp), strokeWidth = 2.dp, color = pal.muted)
            is Load.Failed -> Text(t("Ошибка поиска: ", "Search failed: ") + r.error.message, color = MaterialTheme.colorScheme.error)
            is Load.Ok -> RemoteResults(r.value, kind) { kind = it }
        }
    }
}

/** The best match on top: a quiet card — the picture, what it is, its name in the serif, and play. */
@Composable
private fun BestCard(image: Any?, title: String, kindLabel: String, subtitle: String, round: Boolean, onOpen: () -> Unit, onPlay: () -> Unit) {
    val pal = LocalPal.current
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).clip(RoundedCornerShape(Radius.xl)).background(pal.paper2)
            .border(1.dp, pal.line.copy(alpha = 0.7f), RoundedCornerShape(Radius.xl))
            .clickable(onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(image, title, Modifier.size(112.dp), if (round) 56.dp else Radius.lg)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(kindLabel.uppercase(), color = pal.muted, style = Type.caption)
            Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = pal.muted, maxLines = 1)
        }
        PButton(t("Слушать", "Play"), onPlay, icon = Icons.Rounded.PlayArrow, kind = Kind.PRIMARY)
    }
}

@Composable
private fun LibraryResults(all: List<Track>, liked: Set<String>, query: String, kind: SearchType) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val q = norm(query)
    val words = q.split(' ').filter { it.isNotBlank() }
    val tracks = remember(all, q) { all.filter { t -> norm("${t.title} ${t.artist} ${t.album}").let { h -> words.all { it in h } } }.sortedBy { if (norm(it.title).startsWith(q)) 0 else 1 } }
    val artists = remember(all, q) { all.flatMap { it.artists }.distinctBy(::norm).filter { a -> words.all { it in norm(a) } }.sortedBy { if (norm(it) == q) 0 else 1 } }
    val albums = remember(all, q) { all.filter { it.isLocal && !it.album.isNullOrBlank() }.groupBy { it.albumKey }.values.filter { g -> words.all { it in norm(g.first().album + " " + g.first().artist) } } }
    LazyColumn {
        if (kind == SearchType.ALL) {
            artists.firstOrNull { norm(it) == q }?.let { a ->
                item { BestCard(null, a, t("Исполнитель", "Artist"), t("в медиатеке", "in your library"), true, { nav.go(Screen.Artist(a)) }, { graph.player.play(tracks.filter { t -> t.artists.any { norm(it) == q } }) }) }
            }
        }
        if ((kind == SearchType.ALL || kind == SearchType.ARTISTS) && artists.isNotEmpty()) {
            item { SectionHeader(t("Исполнители", "Artists")) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) { items(artists.take(30)) { a -> ArtistCircle(null, a) { nav.go(Screen.Artist(a)) } } } }
        }
        if ((kind == SearchType.ALL || kind == SearchType.ALBUMS) && albums.isNotEmpty()) {
            item { SectionHeader(t("Альбомы", "Albums")) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) { items(albums) { g -> val f = g.first(); Tile({ TrackArt(f, Modifier.fillMaxSize(), Radius.md) }, f.album!!, f.albumArtist ?: f.artist, Modifier.width(160.dp), onPlay = { graph.player.play(g) }) { nav.go(Screen.LocalAlbum(f.album, f.albumArtist ?: f.artist)) } } } }
        }
        if ((kind == SearchType.ALL || kind == SearchType.TRACKS) && tracks.isNotEmpty()) {
            item { SectionHeader(t("Треки", "Tracks")) }
            items(tracks.take(if (kind == SearchType.ALL) 12 else 300), key = { it.id }) { tr ->
                val st by graph.player.state.collectAsState()
                TrackLine(tr, null, st.current?.id == tr.id, tr.id in liked, onPlay = { graph.player.play(tracks, tracks.indexOf(tr)) }, onMenu = { nav.openMenu(tr) })
            }
        }
        if (tracks.isEmpty() && artists.isEmpty()) item { EmptyState(t("Ничего не нашлось", "Nothing found"), t("Попробуй поискать в сервисах", "Try a service")) }
    }
}

@Composable
private fun RemoteResults(r: SearchResults, kind: SearchType, switchTo: (SearchType) -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    LazyColumn {
        if (kind == SearchType.ALL) r.best?.let { best ->
            item {
                when (best) {
                    is SearchHit.Artist -> BestCard(best.artist.coverUrl, best.artist.name, t("Исполнитель", "Artist"), best.artist.service.displayName, true,
                        { nav.go(Screen.Artist(best.artist.name, best.artist.id, best.artist.service)) },
                        { graph.scope.launch2 { artistInfo(graph, best.artist.name, best.artist.id, best.artist.service)?.popular?.let { if (it.isNotEmpty()) graph.player.playRemote(it, 0) } } })
                    is SearchHit.Album -> BestCard(best.album.coverUrl, best.album.title, t("Альбом", "Album"), albumLine(best.album), false, { nav.go(Screen.RemoteAlbum(best.album.id, best.album.service)) }, { nav.go(Screen.RemoteAlbum(best.album.id, best.album.service)) })
                    is SearchHit.Playlist -> BestCard(best.playlist.coverUrl, best.playlist.title, t("Плейлист", "Playlist"), best.playlist.owner.orEmpty(), false, { nav.go(Screen.RemotePlaylist(best.playlist.service, best.playlist.id, best.playlist.title)) }, { nav.go(Screen.RemotePlaylist(best.playlist.service, best.playlist.id, best.playlist.title)) })
                    is SearchHit.Track -> BestCard(best.track.coverUrl, best.track.title, t("Трек", "Track"), best.track.artistLine, false, { graph.player.playRemote(listOf(best.track), 0) }, { graph.player.playRemote(listOf(best.track) + r.tracks.filter { it.id != best.track.id }, 0) })
                }
            }
        }
        val bestArtist = (r.best as? SearchHit.Artist)?.artist?.name?.let(::norm)
        val tracks = if (bestArtist == null) r.tracks else r.tracks.sortedByDescending { tr -> tr.artists.any { norm(it) == bestArtist } }
        if (tracks.isNotEmpty() && (kind == SearchType.ALL || kind == SearchType.TRACKS)) {
            item { SectionHeader(t("Треки", "Tracks")) { if (kind == SearchType.ALL) PButton(t("Все", "All"), { switchTo(SearchType.TRACKS) }, kind = Kind.GHOST, trailingIcon = Icons.AutoMirrored.Rounded.KeyboardArrowRight) } }
            item { RemoteTrackList(if (kind == SearchType.ALL) tracks.take(8) else tracks) }
        }
        val artists = r.artists.filterNot { (r.best as? SearchHit.Artist)?.artist?.id == it.id }
        if (artists.isNotEmpty() && (kind == SearchType.ALL || kind == SearchType.ARTISTS)) {
            item { SectionHeader(t("Исполнители", "Artists")) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) { items(artists, key = { it.id }) { a -> ArtistCircle(a.coverUrl, a.name) { nav.go(Screen.Artist(a.name, a.id, a.service)) } } } }
        }
        if (r.albums.isNotEmpty() && (kind == SearchType.ALL || kind == SearchType.ALBUMS)) {
            item { SectionHeader(t("Альбомы", "Albums")) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) { items(r.albums, key = { it.id }) { a -> AlbumTile(a.coverUrl, a.title, albumLine(a)) { nav.go(Screen.RemoteAlbum(a.id, a.service)) } } } }
        }
        if (r.playlists.isNotEmpty() && (kind == SearchType.ALL || kind == SearchType.PLAYLISTS)) {
            item { SectionHeader(t("Плейлисты", "Playlists")) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) { items(r.playlists, key = { it.id }) { p -> AlbumTile(p.coverUrl, p.title, p.owner.orEmpty()) { nav.go(Screen.RemotePlaylist(p.service, p.id, p.title)) } } } }
        }
        if (r.isEmpty) item { EmptyState(t("Ничего не нашлось", "Nothing found"), "") }
    }
}

/** Fire-and-forget on the app scope (for buttons). */
fun kotlinx.coroutines.CoroutineScope.launch2(block: suspend () -> Unit) { val scope = this; scope.launch { runCatching { block() } } }
