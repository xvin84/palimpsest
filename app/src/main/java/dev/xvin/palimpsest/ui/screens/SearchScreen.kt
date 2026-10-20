package dev.xvin.palimpsest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.AppGraph
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.core.model.RemoteAlbum
import dev.xvin.palimpsest.core.model.RemoteArtist
import dev.xvin.palimpsest.core.model.RemotePlaylist
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.model.SearchHit
import dev.xvin.palimpsest.core.model.SearchResults
import dev.xvin.palimpsest.core.model.SearchType
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.data.db.AlbumRow
import dev.xvin.palimpsest.data.db.ArtistRow
import dev.xvin.palimpsest.data.db.PlaylistEntity
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.library.ArtProvider
import dev.xvin.palimpsest.i18n.plural
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.ui.AppNav
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.Artwork
import dev.xvin.palimpsest.ui.components.EmptyState
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.Pill
import dev.xvin.palimpsest.ui.components.RemoteTrackRow
import dev.xvin.palimpsest.ui.components.SectionHeader
import dev.xvin.palimpsest.ui.components.TrackRow
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What to look for. */
private enum class Kind(private val labelRes: Int, val type: SearchType) {
    ALL(R.string.search_kind_all, SearchType.ALL),
    TRACKS(R.string.lib_tracks, SearchType.TRACKS),
    ARTISTS(R.string.lib_artists, SearchType.ARTISTS),
    ALBUMS(R.string.lib_albums, SearchType.ALBUMS),
    PLAYLISTS(R.string.playlists, SearchType.PLAYLISTS);

    val label: String get() = tr(labelRes)
}

/** Order of the track list; relevance keeps the service's (or the library's ranking) order. */
private enum class TrackSort(private val labelRes: Int) {
    RELEVANCE(R.string.sort_relevance), TITLE(R.string.sort_title), ARTIST(R.string.sort_artist),
    DURATION(R.string.sort_duration), YEAR(R.string.sort_newest);

    val label: String get() = tr(labelRes)
}

/** Album kinds a service tells apart (Yandex marks singles). */
private enum class AlbumFilter { ALL, ALBUMS, SINGLES }

/** The library's answer to a query, shaped like a service's. */
private data class LocalResults(
    val best: LocalHit? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val artists: List<ArtistRow> = emptyList(),
    val albums: List<AlbumRow> = emptyList(),
    val playlists: List<PlaylistEntity> = emptyList(),
) {
    val isEmpty get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()
}

private sealed interface LocalHit {
    data class Artist(val row: ArtistRow) : LocalHit
    data class Album(val row: AlbumRow) : LocalHit
    data class Track(val track: TrackEntity) : LocalHit
}

/** Case- and «ё»-insensitive form for matching; SQLite's LIKE only folds Latin letters. */
private fun norm(s: String?) = s.orEmpty().lowercase().replace('ё', 'е').trim()

/**
 * Searches the library in memory: every word of the query must appear in the title, artist or
 * album. Exact and prefix matches rank first. An artist or album named exactly like the query
 * becomes the best match.
 */
private suspend fun searchLibrary(graph: AppGraph, query: String): LocalResults {
    val q = norm(query)
    val words = q.split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return LocalResults()
    fun matches(vararg fields: String?): Boolean {
        val hay = fields.joinToString(" ") { norm(it) }
        return words.all { it in hay }
    }
    fun rank(title: String, other: String?): Int {
        val t = norm(title)
        val o = norm(other)
        return when {
            t == q -> 0
            t.startsWith(q) -> 1
            o == q || o.startsWith(q) -> 2
            t.split(' ').any { it.startsWith(words.first()) } -> 3
            else -> 4
        }
    }
    val tracks = graph.db.tracks().all().filter { matches(it.title, it.artist, it.album) }
        .sortedWith(compareBy({ rank(it.title, it.artist) }, { norm(it.title) })).take(300)
    val artists = graph.db.artists().observeArtists().first().filter { matches(it.artist) }
        .sortedWith(compareBy({ rank(it.artist, null) }, { norm(it.artist) }))
    val albums = graph.db.tracks().observeAlbums().first().filter { matches(it.album, it.artist) }
        .sortedWith(compareBy({ rank(it.album, it.artist) }, { norm(it.album) }))
    val playlists = graph.db.playlists().observeAll().first().filter { matches(it.name) }
    val best = artists.firstOrNull { norm(it.artist) == q }?.let { LocalHit.Artist(it) }
        ?: albums.firstOrNull { norm(it.album) == q }?.let { LocalHit.Album(it) }
        ?: tracks.firstOrNull()?.let { LocalHit.Track(it) }
        ?: artists.firstOrNull()?.let { LocalHit.Artist(it) }
    return LocalResults(best, tracks, artists, albums, playlists)
}

/** One search box for the library and every connected service, by kind, with a best match on top. */
@Composable
fun SearchScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf<Service?>(null) } // null = library
    var kind by rememberSaveable { mutableStateOf(Kind.ALL) }
    var sort by rememberSaveable { mutableStateOf(TrackSort.RELEVANCE) }
    var onlyLiked by rememberSaveable { mutableStateOf(false) }
    var onlyDownloaded by rememberSaveable { mutableStateOf(false) }
    var onlyAvailable by rememberSaveable { mutableStateOf(false) }
    var albumFilter by rememberSaveable { mutableStateOf(AlbumFilter.ALL) }
    var local by remember { mutableStateOf(LocalResults()) }
    var remote by remember { mutableStateOf(SearchResults()) }
    var page by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val playing by graph.player.state.collectAsStateWithLifecycle()

    fun saveQuery(q: String = query) { if (q.isNotBlank()) scope.launch { graph.settings.addRecentSearch(q.trim()) } }

    LaunchedEffect(query, source, kind) {
        error = null
        page = 0
        if (query.isBlank()) { local = LocalResults(); remote = SearchResults(); loading = false; return@LaunchedEffect }
        delay(if (source == null) 150 else 450) // debounce
        loading = true
        try {
            if (source == null) local = searchLibrary(graph, query.trim())
            else {
                remote = graph.remote.searchAll(source!!, query.trim(), kind.type)
                hasMore = kind != Kind.ALL && remote.size(kind) >= 10
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure = e
            error = graph.errors.report(e, when (source) {
                Service.YOUTUBE -> tr(R.string.ctx_search_youtube)
                Service.YANDEX -> tr(R.string.ctx_search_yandex)
                else -> tr(R.string.tab_search) + " · " + source!!.displayName
            }, show = false)?.title
            remote = SearchResults()
        }
        loading = false
    }

    fun loadMore() {
        val s = source ?: return
        scope.launch {
            loading = true
            runCatching { graph.remote.searchAll(s, query.trim(), kind.type, page + 1) }
                .onSuccess { more ->
                    page++
                    hasMore = more.size(kind) > 0
                    remote = remote.copy(
                        tracks = (remote.tracks + more.tracks).distinctBy { it.id },
                        artists = (remote.artists + more.artists).distinctBy { it.id },
                        albums = (remote.albums + more.albums).distinctBy { it.id },
                        playlists = (remote.playlists + more.playlists).distinctBy { it.id },
                    )
                }
                .onFailure { nav.error(it, tr(R.string.tab_search)) }
            loading = false
        }
    }

    // Track lists as shown: filtered and sorted.
    val localTracks = remember(local, sort, onlyLiked, onlyDownloaded) {
        local.tracks.filter { (!onlyLiked || it.liked) && (!onlyDownloaded || it.path != null) }
            .sortedWithMode(sort, { it.title }, { it.artist }, { it.durationMs }, { it.year })
    }
    val remoteTracks = remember(remote, sort, onlyAvailable) {
        remote.tracks.filter { !onlyAvailable || it.available }
            .sortedWithMode(sort, { it.title }, { it.artistLine }, { it.durationMs }, { it.year })
    }
    val remoteAlbums = remember(remote, albumFilter) {
        remote.albums.filter {
            when (albumFilter) {
                AlbumFilter.ALL -> true
                AlbumFilter.SINGLES -> it.type == "single"
                AlbumFilter.ALBUMS -> it.type != "single"
            }
        }
    }

    // Remote rows → library tracks, so a tap plays the whole list from that row.
    val playRemote: (List<RemoteTrack>, Int) -> Unit = { list, i ->
        saveQuery()
        scope.launch { graph.player.play(list.map { graph.remote.ensureTrack(it) }, i) }
    }
    val downloadRemote: (RemoteTrack) -> Unit = { r ->
        scope.launch {
            graph.downloads.enqueue(listOf(graph.remote.ensureTrack(r).id))
            nav.toast(tr(R.string.downloading_one, r.title))
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(tr(R.string.tab_search), style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        OutlinedTextField(
            query, { query = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, tr(R.string.clear)) } },
            placeholder = { Text(tr(R.string.search_hint)) },
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { saveQuery(); focus.clearFocus() }),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(tr(R.string.tab_library), source == null, { source = null })
            Pill(tr(R.string.service_yandex), source == Service.YANDEX, { source = Service.YANDEX })
            Pill("YouTube Music", source == Service.YOUTUBE, { source = Service.YOUTUBE })
            Pill("Bandcamp", source == Service.BANDCAMP, { source = Service.BANDCAMP })
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Kind.entries.forEach { k -> KindChip(k.label, kind == k) { kind = k } }
            }
            if (kind == Kind.TRACKS) SortButton(sort) { sort = it }
        }
        // Filters that make sense for what's shown.
        if (query.isNotBlank() && (kind == Kind.TRACKS || (kind == Kind.ALBUMS && source == Service.YANDEX))) {
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    kind == Kind.ALBUMS -> {
                        KindChip(tr(R.string.filter_all), albumFilter == AlbumFilter.ALL) { albumFilter = AlbumFilter.ALL }
                        KindChip(tr(R.string.lib_albums), albumFilter == AlbumFilter.ALBUMS) { albumFilter = AlbumFilter.ALBUMS }
                        KindChip(tr(R.string.search_singles), albumFilter == AlbumFilter.SINGLES) { albumFilter = AlbumFilter.SINGLES }
                    }
                    source == null -> {
                        KindChip("♥ " + tr(R.string.filter_favorites), onlyLiked) { onlyLiked = !onlyLiked }
                        KindChip(tr(R.string.filter_downloaded), onlyDownloaded) { onlyDownloaded = !onlyDownloaded }
                    }
                    else -> KindChip(tr(R.string.search_only_available), onlyAvailable) { onlyAvailable = !onlyAvailable }
                }
            }
        }
        if (source == Service.YANDEX && settings?.yandexToken == null) {
            EmptyState(tr(R.string.yandex_not_connected), tr(R.string.yandex_not_connected_hint))
            return@Column
        }
        error?.let { msg ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(msg, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                failure?.let { f ->
                    TextButton(onClick = { graph.errors.log.value.firstOrNull()?.let(nav::showError) ?: nav.error(f, tr(R.string.tab_search)) }) { Text(tr(R.string.details)) }
                }
            }
        }

        if (query.isBlank()) {
            RecentSearches(settings?.recentSearches.orEmpty(), onPick = { query = it }, onClear = { scope.launch { graph.settings.clearRecentSearches() } })
            return@Column
        }

        val nothing = !loading && error == null && if (source == null) local.isEmpty else remote.isEmpty
        LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
            if (loading && page == 0) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            if (nothing) item { EmptyState(tr(R.string.search_nothing), tr(R.string.search_nothing_hint)) }
            if (!loading || page > 0) {
                if (source == null) libraryResults(local, localTracks, kind, playing.nowPlaying?.trackId, graph, nav, { kind = it }, ::saveQuery)
                else remoteResults(
                    remote, remoteTracks, remoteAlbums, kind, source!!, graph, nav, playRemote, downloadRemote, { kind = it }, ::saveQuery,
                    onPlayArtist = { a ->
                        saveQuery()
                        scope.launch {
                            runCatching { graph.remote.artistInfo(a.name, a.id, a.service) }
                                .onSuccess { info -> info?.popular?.takeIf { it.isNotEmpty() }?.let { playRemote(it, 0) } ?: nav.openArtist(a.name, a.id, a.service) }
                                .onFailure { nav.error(it, a.name) }
                        }
                    },
                    onRadio = { a ->
                        scope.launch { graph.radio.start("artist:${a.id}", tr(R.string.radio_title, a.name)).onFailure { nav.error(it, tr(R.string.ctx_radio_start)) } }
                    },
                )
                if (source != null && kind != Kind.ALL && hasMore) item {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        if (loading) CircularProgressIndicator(Modifier.size(28.dp)) else TextButton(onClick = ::loadMore) { Text(tr(R.string.show_more)) }
                    }
                }
            }
        }
    }
}

private fun SearchResults.size(kind: Kind) = when (kind) {
    Kind.ALL, Kind.TRACKS -> tracks.size
    Kind.ARTISTS -> artists.size
    Kind.ALBUMS -> albums.size
    Kind.PLAYLISTS -> playlists.size
}

private fun <T> List<T>.sortedWithMode(
    sort: TrackSort,
    title: (T) -> String,
    artist: (T) -> String,
    duration: (T) -> Long,
    year: (T) -> Int?,
): List<T> = when (sort) {
    TrackSort.RELEVANCE -> this
    TrackSort.TITLE -> sortedBy { norm(title(it)) }
    TrackSort.ARTIST -> sortedWith(compareBy({ norm(artist(it)) }, { norm(title(it)) }))
    TrackSort.DURATION -> sortedByDescending(duration)
    TrackSort.YEAR -> sortedByDescending { year(it) ?: 0 }
}

private fun LazyListScope.libraryResults(
    r: LocalResults,
    tracks: List<TrackEntity>,
    kind: Kind,
    playingId: Long?,
    graph: AppGraph,
    nav: AppNav,
    switchTo: (Kind) -> Unit,
    save: () -> Unit,
) {
    fun openArtist(a: ArtistRow) { save(); nav.openArtist(a.artist) }
    fun openAlbum(a: AlbumRow) { save(); nav.openList(TrackListKind.ALBUM, a.album, a.artist) }
    fun openPlaylist(p: PlaylistEntity) { save(); nav.openList(TrackListKind.PLAYLIST, p.id.toString()) }
    fun playTracks(list: List<TrackEntity>, i: Int) { save(); graph.player.play(list, i) }
    val trackRow: @Composable (List<TrackEntity>, Int, TrackEntity) -> Unit = { list, i, t ->
        TrackRow(t, onClick = { playTracks(list, i) }, onMore = { nav.showActions(t) }, playing = playingId == t.id)
    }
    when (kind) {
        Kind.ALL -> {
            r.best?.let { best ->
                item(key = "best") {
                    when (best) {
                        is LocalHit.Artist -> BestCard(
                            ArtProvider.uriFor(best.row.coverTrackId), best.row.artist, tr(R.string.search_hit_artist),
                            plural(R.plurals.tracks, best.row.trackCount), round = true, onOpen = { openArtist(best.row) },
                            onPlay = {
                                save()
                                graph.appScope.launch {
                                    val list = graph.db.artists().observeTracksOf(best.row.artist).first()
                                    graph.mainScope.launch { graph.player.play(list) }
                                }
                            },
                        )
                        is LocalHit.Album -> BestCard(
                            ArtProvider.uriFor(best.row.coverTrackId), best.row.album, tr(R.string.search_hit_album),
                            listOfNotNull(best.row.artist, best.row.year?.toString()).joinToString(" · "), onOpen = { openAlbum(best.row) },
                            onPlay = {
                                save()
                                graph.appScope.launch {
                                    val list = graph.db.tracks().observeAlbumTracks(best.row.album, best.row.artist).first()
                                    graph.mainScope.launch { graph.player.play(list) }
                                }
                            },
                        )
                        is LocalHit.Track -> BestCard(
                            ArtProvider.uriFor(best.track.id, best.track.modifiedAt), best.track.title, tr(R.string.search_hit_track),
                            best.track.artist, onOpen = { playTracks(tracks, tracks.indexOf(best.track).coerceAtLeast(0)) },
                            onPlay = { playTracks(tracks, tracks.indexOf(best.track).coerceAtLeast(0)) },
                        )
                    }
                }
            }
            val others = r.artists.filterNot { (r.best as? LocalHit.Artist)?.row == it }
            if (others.isNotEmpty()) {
                item { SectionHeader(tr(R.string.lib_artists)) { if (r.artists.size > 15) MoreButton(r.artists.size) { switchTo(Kind.ARTISTS) } } }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(others.take(15), key = { it.artist }) { a -> ArtistCircle(ArtProvider.uriFor(a.coverTrackId), a.artist) { openArtist(a) } }
                    }
                }
            }
            if (tracks.isNotEmpty()) {
                item { SectionHeader(tr(R.string.lib_tracks)) { if (tracks.size > 6) MoreButton(tracks.size) { switchTo(Kind.TRACKS) } } }
                val shown = tracks.take(6)
                itemsIndexed(shown, key = { _, t -> "t${t.id}" }) { i, t -> trackRow(tracks, i, t) }
            }
            if (r.albums.isNotEmpty()) {
                item { SectionHeader(tr(R.string.lib_albums)) { if (r.albums.size > 15) MoreButton(r.albums.size) { switchTo(Kind.ALBUMS) } } }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(r.albums.take(15), key = { "${it.artist}\u0000${it.album}" }) { a ->
                            AlbumTile(ArtProvider.uriFor(a.coverTrackId), a.album, listOfNotNull(a.artist, a.year?.toString()).joinToString(" · ")) { openAlbum(a) }
                        }
                    }
                }
            }
            if (r.playlists.isNotEmpty()) {
                item { SectionHeader(tr(R.string.playlists)) }
                items(r.playlists, key = { "p${it.id}" }) { p -> ListRow(p.coverUrl, p.name, p.service?.let { tr(R.string.playlist) }, round = false) { openPlaylist(p) } }
            }
        }
        Kind.TRACKS -> itemsIndexed(tracks, key = { _, t -> "t${t.id}" }) { i, t -> trackRow(tracks, i, t) }
        Kind.ARTISTS -> items(r.artists, key = { it.artist }) { a ->
            ListRow(ArtProvider.uriFor(a.coverTrackId), a.artist, plural(R.plurals.tracks, a.trackCount), round = true) { openArtist(a) }
        }
        Kind.ALBUMS -> items(r.albums, key = { "${it.artist}\u0000${it.album}" }) { a ->
            ListRow(ArtProvider.uriFor(a.coverTrackId), a.album, listOfNotNull(a.artist, a.year?.toString()).joinToString(" · "), round = false) { openAlbum(a) }
        }
        Kind.PLAYLISTS -> items(r.playlists, key = { "p${it.id}" }) { p -> ListRow(p.coverUrl, p.name, null, round = false) { openPlaylist(p) } }
    }
}

private fun LazyListScope.remoteResults(
    r: SearchResults,
    tracks: List<RemoteTrack>,
    albums: List<RemoteAlbum>,
    kind: Kind,
    service: Service,
    graph: AppGraph,
    nav: AppNav,
    play: (List<RemoteTrack>, Int) -> Unit,
    download: (RemoteTrack) -> Unit,
    switchTo: (Kind) -> Unit,
    save: () -> Unit,
    onPlayArtist: (RemoteArtist) -> Unit,
    onRadio: (RemoteArtist) -> Unit,
) {
    fun openArtist(a: RemoteArtist) { save(); nav.openArtist(a.name, a.id, a.service) }
    fun openAlbum(a: RemoteAlbum) { save(); nav.openRemoteAlbum(a.id, a.service) }
    fun openPlaylist(p: RemotePlaylist) { save(); nav.openRemotePlaylist(p.service, p.id, p.title) }
    val trackRow: @Composable (List<RemoteTrack>, Int, RemoteTrack) -> Unit = { list, i, t ->
        RemoteTrackRow(t, onPlay = { play(list, i) }, onDownload = { download(t) }, onMore = { graph.mainScope.launch { nav.showActions(graph.remote.ensureTrack(t)) } })
    }
    val albumLine: (RemoteAlbum) -> String = { a -> listOfNotNull(a.artists.joinToString(", ").ifBlank { null }, a.year?.toString(), a.type?.takeIf { it == "single" }?.let { tr(R.string.album_type_single) }).joinToString(" · ") }
    when (kind) {
        Kind.ALL -> {
            val best = r.best
            if (best != null) item(key = "best") {
                when (best) {
                    is SearchHit.Artist -> BestCard(
                        best.artist.coverUrl, best.artist.name, tr(R.string.search_hit_artist), service.labelText(), round = true,
                        onOpen = { openArtist(best.artist) }, onPlay = { onPlayArtist(best.artist) },
                        onRadio = if (service == Service.YANDEX) ({ onRadio(best.artist) }) else null,
                    )
                    is SearchHit.Album -> BestCard(
                        best.album.coverUrl, best.album.title, tr(R.string.search_hit_album), albumLine(best.album),
                        onOpen = { openAlbum(best.album) }, onPlay = { openAlbum(best.album) },
                    )
                    is SearchHit.Playlist -> BestCard(
                        best.playlist.coverUrl, best.playlist.title, tr(R.string.search_hit_playlist), best.playlist.owner.orEmpty(),
                        onOpen = { openPlaylist(best.playlist) }, onPlay = { openPlaylist(best.playlist) },
                    )
                    is SearchHit.Track -> BestCard(
                        best.track.coverUrl, best.track.title, tr(R.string.search_hit_track), best.track.artistLine,
                        onOpen = { play(listOf(best.track) + tracks.filter { it.id != best.track.id }, 0) },
                        onPlay = { play(listOf(best.track) + tracks.filter { it.id != best.track.id }, 0) },
                    )
                }
            }
            // Searching for an artist: their songs first, others after.
            val bestArtist = (best as? SearchHit.Artist)?.artist?.name?.let(::norm)
            val ordered = if (bestArtist == null) tracks else tracks.sortedByDescending { t -> t.artists.any { norm(it) == bestArtist } }
            if (ordered.isNotEmpty()) {
                item { SectionHeader(tr(R.string.lib_tracks)) { MoreButton(null) { switchTo(Kind.TRACKS) } } }
                val shown = ordered.take(6)
                itemsIndexed(shown, key = { i, t -> "t${t.id}#$i" }) { i, t -> trackRow(ordered, i, t) }
            }
            val otherArtists = r.artists.filterNot { (best as? SearchHit.Artist)?.artist?.id == it.id }
            if (otherArtists.isNotEmpty()) {
                item { SectionHeader(tr(R.string.lib_artists)) { MoreButton(null) { switchTo(Kind.ARTISTS) } } }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(otherArtists, key = { it.id }) { a -> ArtistCircle(a.coverUrl, a.name) { openArtist(a) } }
                    }
                }
            }
            if (albums.isNotEmpty()) {
                item { SectionHeader(tr(R.string.lib_albums)) { MoreButton(null) { switchTo(Kind.ALBUMS) } } }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(albums, key = { it.id }) { a -> AlbumTile(a.coverUrl, a.title, albumLine(a)) { openAlbum(a) } }
                    }
                }
            }
            if (r.playlists.isNotEmpty()) {
                item { SectionHeader(tr(R.string.playlists)) { MoreButton(null) { switchTo(Kind.PLAYLISTS) } } }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(r.playlists, key = { it.id }) { p -> AlbumTile(p.coverUrl, p.title, p.owner.orEmpty()) { openPlaylist(p) } }
                    }
                }
            }
        }
        Kind.TRACKS -> itemsIndexed(tracks, key = { i, t -> "t${t.id}#$i" }) { i, t -> trackRow(tracks, i, t) }
        Kind.ARTISTS -> items(r.artists, key = { it.id }) { a -> ListRow(a.coverUrl, a.name, null, round = true) { openArtist(a) } }
        Kind.ALBUMS -> items(albums, key = { it.id }) { a -> ListRow(a.coverUrl, a.title, albumLine(a), round = false) { openAlbum(a) } }
        Kind.PLAYLISTS -> items(r.playlists, key = { it.id }) { p ->
            ListRow(p.coverUrl, p.title, listOfNotNull(p.owner, p.trackCount.takeIf { it > 0 }?.let { plural(R.plurals.tracks, it) }).joinToString(" · "), round = false) { openPlaylist(p) }
        }
    }
}

private fun Service.labelText() = when (this) {
    Service.YANDEX -> tr(R.string.service_yandex)
    Service.YOUTUBE -> "YouTube Music"
    else -> displayName
}

/** The best match, big: a photo or cover, what it is, and play (plus radio for a Yandex artist). */
@Composable
private fun BestCard(
    image: Any?,
    title: String,
    kindLabel: String,
    subtitle: String,
    round: Boolean = false,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onRadio: (() -> Unit)? = null,
) {
    val accent = LocalAccent.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(20.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), Ink.Paper2)))
            .clickable(onClick = onOpen).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(image, title, Modifier.size(96.dp), corner = if (round) 48.dp else 12.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(kindLabel.uppercase(), style = MaterialTheme.typography.labelSmall, color = accent)
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlay, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(tr(R.string.listen))
            }
            if (onRadio != null) OutlinedButton(onClick = onRadio, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(Icons.Rounded.Radio, null); Spacer(Modifier.width(4.dp)); Text(tr(R.string.radio))
            }
        }
    }
}

@Composable
private fun ArtistCircle(image: Any?, name: String, onClick: () -> Unit) {
    Column(Modifier.width(88.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(image, name, Modifier.size(80.dp), corner = 40.dp)
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Ink.Text)
    }
}

@Composable
private fun AlbumTile(image: Any?, title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clickable(onClick = onClick)) {
        Artwork(image, title, Modifier.size(140.dp), corner = 10.dp)
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ListRow(image: Any?, title: String, subtitle: String?, round: Boolean, size: Dp = 52.dp, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(image, title, Modifier.size(size), corner = if (round) size / 2 else 8.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MoreButton(count: Int?, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(if (count != null) tr(R.string.show_all_n, count) else tr(R.string.show_all)) }
}

/** A smaller, quieter pill for kinds and filters under the source row. */
@Composable
private fun KindChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) accent.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) accent else Ink.Muted)
    }
}

@Composable
private fun SortButton(sort: TrackSort, onPick: (TrackSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.Sort, sort.label, tint = if (sort == TrackSort.RELEVANCE) Ink.Muted else LocalAccent.current) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            TrackSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label, color = if (s == sort) LocalAccent.current else Ink.Text) },
                    onClick = { onPick(s); open = false },
                )
            }
        }
    }
}

@Composable
private fun RecentSearches(recent: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    if (recent.isEmpty()) {
        EmptyState(tr(R.string.search_empty_title), tr(R.string.search_empty_hint))
        return
    }
    SectionHeader(tr(R.string.search_recent)) { TextButton(onClick = onClear) { Text(tr(R.string.clear)) } }
    recent.forEach { q ->
        Row(Modifier.fillMaxWidth().clickable { onPick(q) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.History, null, tint = Ink.Faint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
