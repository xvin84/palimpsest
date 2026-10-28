package dev.xvin.palimpsest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.data.db.AlbumRow
import dev.xvin.palimpsest.data.db.PlaylistEntity
import dev.xvin.palimpsest.data.library.ArtProvider
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.Artwork
import dev.xvin.palimpsest.ui.components.EmptyState
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.Pill
import dev.xvin.palimpsest.ui.components.TrackRow
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.i18n.plural
import dev.xvin.palimpsest.i18n.label

private enum class LibTab(private val labelRes: Int) { TRACKS(R.string.lib_tracks), ALBUMS(R.string.lib_albums), ARTISTS(R.string.lib_artists), FOLDERS(R.string.lib_folders), PLAYLISTS(R.string.playlists); val label: String get() = tr(labelRes) }
private enum class SortMode(private val labelRes: Int) { TITLE(R.string.sort_title), ARTIST(R.string.sort_artist), ADDED(R.string.smart_recently_added), DURATION(R.string.sort_duration); val label: String get() = tr(labelRes) }

@Composable
fun LibraryScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(LibTab.TRACKS) }
    val scan by graph.scanner.progress.collectAsStateWithLifecycle()
    val count by graph.db.tracks().observeCount().collectAsStateWithLifecycle(0)

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(tr(R.string.tab_library), style = MaterialTheme.typography.displayMedium)
                Text(plural(R.plurals.tracks, count), style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
            }
            IconButton(onClick = { rescan(graph, nav, force = false) }, enabled = !scan.running) {
                Icon(Icons.Rounded.Refresh, tr(R.string.rescan), tint = Ink.Muted)
            }
            IconButton(onClick = nav::openSettings) { Icon(Icons.Rounded.Settings, tr(R.string.settings), tint = Ink.Muted) }
        }
        if (scan.running) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    if (scan.total > 0) tr(R.string.scan_reading_tags, scan.done, scan.total) else tr(R.string.scan_looking),
                    style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
                )
                Spacer(Modifier.height(6.dp))
                if (scan.total > 0) LinearProgressIndicator(progress = { scan.done.toFloat() / scan.total }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        scan.error?.let { Text(tr(R.string.scan_error, it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }

        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibTab.entries.forEach { t -> Pill(t.label, tab == t, { tab = t }) }
        }

        androidx.compose.animation.Crossfade(tab, animationSpec = androidx.compose.animation.core.tween(200), label = "lib-tab") { t ->
            when (t) {
                LibTab.TRACKS -> TracksTab()
                LibTab.ALBUMS -> AlbumsTab()
                LibTab.ARTISTS -> ArtistsTab()
                LibTab.FOLDERS -> FoldersTab()
                LibTab.PLAYLISTS -> PlaylistsTab()
            }
        }
    }
}

@Composable
private fun TracksTab() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val all by graph.db.tracks().observeAll().collectAsStateWithLifecycle(null)
    val playing by graph.player.state.collectAsStateWithLifecycle()
    // The hot copy: already loaded when the tab opens, so the list and the wave card come up together.
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    // Sort and filters live in settings, so the library opens the way it was left.
    val view = settings?.libraryView ?: dev.xvin.palimpsest.data.settings.LibraryView()
    val source = view.source?.let { runCatching { Service.valueOf(it) }.getOrNull() }
    val onlyLiked = view.onlyLiked
    val onlyOffline = view.onlyOffline
    val sort = view.sort?.let { runCatching { SortMode.valueOf(it) }.getOrNull() } ?: SortMode.TITLE
    fun update(v: dev.xvin.palimpsest.data.settings.LibraryView) { scope.launch { graph.settings.setLibraryView(v) } }
    var sortMenu by remember { mutableStateOf(false) }

    val list = all ?: return
    if (settings == null) return
    val tracks = remember(list, source, onlyLiked, onlyOffline, sort) {
        list.asSequence()
            .filter { source == null || it.service == source }
            .filter { !onlyLiked || it.liked }
            .filter { !onlyOffline || it.path != null }
            .let { s ->
                when (sort) {
                    SortMode.TITLE -> s.sortedBy { it.title.lowercase() }
                    SortMode.ARTIST -> s.sortedWith(compareBy({ it.artist.lowercase() }, { it.album?.lowercase() }, { it.trackNumber }))
                    SortMode.ADDED -> s.sortedByDescending { it.addedAt }
                    SortMode.DURATION -> s.sortedByDescending { it.durationMs }
                }
            }.toList()
    }
    val services = remember(list) { list.map { it.service }.distinct().sortedBy { it.ordinal } }

    if (list.isEmpty()) {
        EmptyState(
            tr(R.string.lib_empty_title),
            tr(R.string.lib_empty_hint),
            art = R.drawable.art_empty_playlist,
        )
        return
    }

    // The wave card (only with Yandex) is part of the very first frame of the list: it gets the
    // already loaded settings instead of loading its own. A card that popped in later, empty at
    // first, left the list anchored below it, hidden until the screen was touched.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val s = settings!!
    val hasWave = s.yandexToken != null
    LaunchedEffect(hasWave) {
        if (hasWave && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
        if (hasWave) item(key = "wave") { MyWaveCard(s.wave) }
        item(key = "offline-wave") { OfflineWaveRow() }
        item(key = "internet-radio") { InternetRadioRow() }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(tr(R.string.filter_all), source == null && !onlyLiked && !onlyOffline, { update(view.copy(source = null, onlyLiked = false, onlyOffline = false)) })
                Pill(tr(R.string.filter_favorites), onlyLiked, { update(view.copy(onlyLiked = !onlyLiked)) })
                if (list.any { it.path == null }) Pill(tr(R.string.filter_downloaded), onlyOffline, { update(view.copy(onlyOffline = !onlyOffline)) })
                services.forEach { s -> Pill(s.label, source == s, { update(view.copy(source = if (source == s) null else s.name)) }) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { graph.player.play(tracks, shuffle = true) }) {
                    Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr(R.string.shuffle_n, tracks.size))
                }
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp), tint = Ink.Muted)
                        Spacer(Modifier.width(6.dp))
                        Text(sort.label, color = Ink.Muted)
                    }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortMode.entries.forEach { m -> DropdownMenuItem(text = { Text(m.label) }, onClick = { update(view.copy(sort = m.name)); sortMenu = false }) }
                    }
                }
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            TrackRow(
                t,
                onClick = { graph.player.play(tracks, i) },
                onMore = { nav.showActions(t) },
                playing = playing.nowPlaying?.trackId == t.id,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun AlbumsTab() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val albums by graph.db.tracks().observeAlbums().collectAsStateWithLifecycle(emptyList())
    if (albums.isEmpty()) { EmptyState(tr(R.string.lib_no_albums), tr(R.string.lib_no_albums_hint), art = R.drawable.art_empty_playlist); return }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(albums, key = { "${it.artist}\u0000${it.album}" }) { a -> AlbumCard(a) { nav.openList(TrackListKind.ALBUM, a.album, a.artist) } }
    }
}

@Composable
private fun AlbumCard(a: AlbumRow, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Artwork(ArtProvider.uriFor(a.coverTrackId), a.album, Modifier.fillMaxWidth().aspectRatio(1f), corner = 10.dp)
        Spacer(Modifier.height(8.dp))
        Text(a.album, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(a.artist, a.year?.toString()).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArtistsTab() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val artists by graph.db.artists().observeArtists().collectAsStateWithLifecycle(emptyList())
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(artists, key = { it.artist }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable { nav.openArtist(a.artist) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(ArtProvider.uriFor(a.coverTrackId), a.artist, Modifier.size(52.dp), corner = 26.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.artist, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        plural(R.plurals.tracks, a.trackCount) + if (a.albumCount > 1) " · " + plural(R.plurals.albums, a.albumCount) else "",
                        style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun FoldersTab() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val folders by graph.db.tracks().observeFolders().collectAsStateWithLifecycle(emptyList())
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(folders, key = { it.folder }) { f ->
            Row(
                Modifier.fillMaxWidth().clickable { nav.openList(TrackListKind.FOLDER, f.folder) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Ink.Paper3), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Folder, null, tint = LocalAccent.current)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.folder.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if ('/' in f.folder) f.folder.substringBeforeLast('/') + " · " else "") + plural(R.plurals.tracks, f.trackCount),
                        style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistsTab() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val playlists by graph.db.playlists().observeAll().collectAsStateWithLifecycle(emptyList())
    var creating by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(LocalAccent.current.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = LocalAccent.current)
                }
                Spacer(Modifier.width(14.dp))
                Text(tr(R.string.new_playlist), style = MaterialTheme.typography.bodyLarge)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().clickable { nav.openList(TrackListKind.LIKED, "liked") }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(LocalAccent.current), contentAlignment = Alignment.Center) {
                    Text("♥", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(14.dp))
                Text(tr(R.string.smart_favorites), style = MaterialTheme.typography.bodyLarge)
            }
        }
        item { SmartRow(tr(R.string.smart_most_played), "↑") { nav.openList(TrackListKind.MOST_PLAYED, "smart") } }
        item { SmartRow(tr(R.string.smart_recently_added), "+") { nav.openList(TrackListKind.RECENT, "smart") } }
        item { SmartRow(tr(R.string.smart_forgotten), "↺") { nav.openList(TrackListKind.FORGOTTEN, "smart") } }
        item { SmartRow(tr(R.string.new_releases), "✦") { nav.openReleases() } }
        items(playlists, key = { it.id }) { p -> PlaylistRow(p) { nav.openList(TrackListKind.PLAYLIST, p.id.toString()) } }
    }
    if (creating) {
        NameDialog(tr(R.string.new_playlist), "", onDismiss = { creating = false }) { name ->
            scope.launch { graph.db.playlists().insert(PlaylistEntity(name = name)) }
            creating = false
        }
    }
}

@Composable
private fun SmartRow(title: String, glyph: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(LocalAccent.current.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Text(glyph, color = LocalAccent.current, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(tr(R.string.smart_playlist), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
        }
    }
}

@Composable
private fun PlaylistRow(p: PlaylistEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (p.coverUrl != null) Artwork(p.coverUrl, p.name, Modifier.size(44.dp))
        else Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Ink.Paper3), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = Ink.Muted)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            p.service?.let { Text(it.label, style = MaterialTheme.typography.bodySmall, color = Ink.Muted) }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(tr(R.string.tag_title)) }) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(tr(R.string.done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
        containerColor = Ink.Paper2,
    )
}

/** «Моя волна»: Yandex's endless personal radio, one tap from the library. */
@Composable
private fun MyWaveCard(wave: dev.xvin.palimpsest.data.settings.WavePrefs) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val station by graph.radio.station.collectAsStateWithLifecycle()
    var starting by remember { mutableStateOf(false) }
    var tuning by remember { mutableStateOf(false) }
    if (tuning) WaveTuningSheet(onDismiss = { tuning = false })
    val accent = LocalAccent.current
    val active = station?.seed == dev.xvin.palimpsest.playback.RadioController.MY_WAVE
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.12f))))
            .clickable(enabled = !starting) {
                if (active) { nav.openPlayer(); return@clickable }
                starting = true
                scope.launch {
                    graph.radio.start(dev.xvin.palimpsest.playback.RadioController.MY_WAVE, tr(R.string.my_wave), wave.seeds())
                        .onSuccess { nav.openPlayer() }
                        .onFailure { e ->
                            // No network? Don't leave the user in silence — play the offline mix instead.
                            if (graph.errors.report(e, tr(R.string.my_wave), show = false)?.title == tr(R.string.err_offline_title) && graph.startOfflineWave()) {
                                nav.toast(tr(R.string.wave_offline_fallback))
                                nav.openPlayer()
                            } else nav.error(e, tr(R.string.my_wave))
                        }
                    starting = false
                }
            }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tr(R.string.my_wave), style = MaterialTheme.typography.headlineSmall)
            Text(
                if (active) tr(R.string.playing_now) else tr(R.string.wave_subtitle),
                style = MaterialTheme.typography.bodySmall, color = Ink.Text.copy(alpha = 0.8f),
            )
        }
        IconButton(onClick = { tuning = true }) { Icon(Icons.Rounded.Tune, tr(R.string.wave_tune), tint = Ink.Text) }
        Box(Modifier.size(48.dp).clip(CircleShape).background(Ink.Text), contentAlignment = Alignment.Center) {
            if (starting) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = Ink.Paper, strokeWidth = 2.dp)
            // While it plays, the button shakes the wave up instead.
            else if (active) dev.xvin.palimpsest.ui.components.ShakeWaveButton(Ink.Paper)
            else Text("▶", color = Ink.Paper, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** The way into internet radio, under the waves. */
@Composable
private fun InternetRadioRow() {
    val nav = LocalNav.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Ink.Paper2)
            .clickable { nav.openInternetRadio() }.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Radio, null, Modifier.size(22.dp), tint = Ink.Muted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr(R.string.iradio_title), style = MaterialTheme.typography.titleMedium)
            Text(tr(R.string.iradio_subtitle), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
        }
    }
}

/** «Офлайн-волна»: a mix of downloaded tracks shaped by your history; works with no network at all. */
@Composable
private fun OfflineWaveRow() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val analysed by graph.db.tracks().observeAnalysedCount().collectAsStateWithLifecycle(0)
    fun start(mood: dev.xvin.palimpsest.playback.WaveMood) {
        scope.launch {
            if (graph.startOfflineWave(mood)) nav.openPlayer() else nav.toast(tr(R.string.offline_wave_empty))
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).clip(RoundedCornerShape(14.dp)).background(Ink.Paper2),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { start(dev.xvin.palimpsest.playback.WaveMood.ANY) }.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_my_wave), null, Modifier.size(22.dp), tint = Ink.Muted)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tr(R.string.offline_wave), style = MaterialTheme.typography.titleMedium)
                Text(tr(R.string.offline_wave_subtitle), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, tr(R.string.play), Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        // Moods need the audio analysed (Library health → Analyse audio).
        if (analysed > 0) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    dev.xvin.palimpsest.playback.WaveMood.CALM to R.string.mood_calm,
                    dev.xvin.palimpsest.playback.WaveMood.ENERGETIC to R.string.mood_energetic,
                    dev.xvin.palimpsest.playback.WaveMood.FOCUS to R.string.mood_focus,
                    dev.xvin.palimpsest.playback.WaveMood.SAD to R.string.mood_sad,
                ).forEach { (mood, label) ->
                    Text(
                        tr(label), style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink.Paper3).clickable { start(mood) }.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}
