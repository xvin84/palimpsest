package dev.xvin.palimpsest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import dev.xvin.palimpsest.ui.components.pluralRu
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import kotlinx.coroutines.launch

private enum class LibTab(val label: String) { TRACKS("Треки"), ALBUMS("Альбомы"), ARTISTS("Исполнители"), FOLDERS("Папки"), PLAYLISTS("Плейлисты") }
private enum class SortMode(val label: String) { TITLE("По названию"), ARTIST("По исполнителю"), ADDED("Недавно добавленные"), DURATION("По длительности") }

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
                Text("Медиатека", style = MaterialTheme.typography.displayMedium)
                Text(pluralRu(count, "трек", "трека", "треков"), style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
            }
            IconButton(onClick = { scope.launch { graph.scanner.scan() } }, enabled = !scan.running) {
                Icon(Icons.Rounded.Refresh, "Пересканировать", tint = Ink.Muted)
            }
            IconButton(onClick = nav::openSettings) { Icon(Icons.Rounded.Settings, "Настройки", tint = Ink.Muted) }
        }
        if (scan.running) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    if (scan.total > 0) "Читаю теги: ${scan.done} из ${scan.total}" else "Ищу музыку…",
                    style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
                )
                Spacer(Modifier.height(6.dp))
                if (scan.total > 0) LinearProgressIndicator(progress = { scan.done.toFloat() / scan.total }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        scan.error?.let { Text("Ошибка сканирования: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }

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
    val all by graph.db.tracks().observeAll().collectAsStateWithLifecycle(null)
    val playing by graph.player.state.collectAsStateWithLifecycle()
    var source by rememberSaveable { mutableStateOf<Service?>(null) }
    var onlyLiked by rememberSaveable { mutableStateOf(false) }
    var onlyOffline by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(SortMode.TITLE) }
    var sortMenu by remember { mutableStateOf(false) }

    val list = all ?: return
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
            "Пока пусто",
            "Положи музыку в папку Music на телефоне или подключи Яндекс.Музыку во вкладке «Сервисы».",
        )
        return
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item { MyWaveCard() }
        item { OfflineWaveRow() }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill("Все", source == null && !onlyLiked && !onlyOffline, { source = null; onlyLiked = false; onlyOffline = false })
                Pill("♥ Любимые", onlyLiked, { onlyLiked = !onlyLiked })
                if (list.any { it.path == null }) Pill("Скачанные", onlyOffline, { onlyOffline = !onlyOffline })
                services.forEach { s -> Pill(s.displayName, source == s, { source = if (source == s) null else s }) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { graph.player.play(tracks, shuffle = true) }) {
                    Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Перемешать · ${tracks.size}")
                }
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp), tint = Ink.Muted)
                        Spacer(Modifier.width(6.dp))
                        Text(sort.label, color = Ink.Muted)
                    }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortMode.entries.forEach { m -> DropdownMenuItem(text = { Text(m.label) }, onClick = { sort = m; sortMenu = false }) }
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
    if (albums.isEmpty()) { EmptyState("Нет альбомов", "Альбомы появятся, когда у треков будет тег «Альбом»."); return }
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
                        pluralRu(a.trackCount, "трек", "трека", "треков") + if (a.albumCount > 1) " · " + pluralRu(a.albumCount, "альбом", "альбома", "альбомов") else "",
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
                        (if ('/' in f.folder) f.folder.substringBeforeLast('/') + " · " else "") + pluralRu(f.trackCount, "трек", "трека", "треков"),
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
                Text("Новый плейлист", style = MaterialTheme.typography.bodyLarge)
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
                Text("Любимые треки", style = MaterialTheme.typography.bodyLarge)
            }
        }
        item { SmartRow("Часто слушаю", "↑") { nav.openList(TrackListKind.MOST_PLAYED, "smart") } }
        item { SmartRow("Недавно добавленные", "+") { nav.openList(TrackListKind.RECENT, "smart") } }
        item { SmartRow("Давно не слушал", "↺") { nav.openList(TrackListKind.FORGOTTEN, "smart") } }
        item { SmartRow("Новые релизы", "✦") { nav.openReleases() } }
        items(playlists, key = { it.id }) { p -> PlaylistRow(p) { nav.openList(TrackListKind.PLAYLIST, p.id.toString()) } }
    }
    if (creating) {
        NameDialog("Новый плейлист", "", onDismiss = { creating = false }) { name ->
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
            Text("Умный плейлист", style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
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
            p.service?.let { Text(it.displayName, style = MaterialTheme.typography.bodySmall, color = Ink.Muted) }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Название") }) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = Ink.Paper2,
    )
}

/** «Моя волна»: Yandex's endless personal radio, one tap from the library. */
@Composable
private fun MyWaveCard() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.flow.collectAsStateWithLifecycle(null)
    val station by graph.radio.station.collectAsStateWithLifecycle()
    var starting by remember { mutableStateOf(false) }
    var tuning by remember { mutableStateOf(false) }
    if (settings?.yandexToken == null) return
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
                    graph.radio.start(dev.xvin.palimpsest.playback.RadioController.MY_WAVE, "Моя волна", settings?.wave?.seeds().orEmpty())
                        .onSuccess { nav.openPlayer() }
                        .onFailure { e ->
                            // No network? Don't leave the user in silence — play the offline mix instead.
                            if (graph.errors.report(e, "Моя волна", show = false).title == "Нет интернета" && graph.startOfflineWave()) {
                                nav.toast("Нет сети — включил офлайн-волну из скачанного")
                                nav.openPlayer()
                            } else nav.error(e, "Моя волна")
                        }
                    starting = false
                }
            }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Моя волна", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (active) "Играет сейчас" else "Бесконечный поток под твой вкус",
                style = MaterialTheme.typography.bodySmall, color = Ink.Text.copy(alpha = 0.8f),
            )
        }
        IconButton(onClick = { tuning = true }) { Icon(Icons.Rounded.Tune, "Настроить волну", tint = Ink.Text) }
        Box(Modifier.size(48.dp).clip(CircleShape).background(Ink.Text), contentAlignment = Alignment.Center) {
            if (starting) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = Ink.Paper, strokeWidth = 2.dp)
            else Text(if (active) "≋" else "▶", color = Ink.Paper, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** «Офлайн-волна»: a mix of downloaded tracks shaped by your history; works with no network at all. */
@Composable
private fun OfflineWaveRow() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).clip(RoundedCornerShape(14.dp)).background(Ink.Paper2)
            .clickable {
                scope.launch {
                    if (graph.startOfflineWave()) nav.openPlayer() else nav.toast("Пока нечего играть: в медиатеке нет скачанных треков")
                }
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Офлайн-волна", style = MaterialTheme.typography.titleMedium)
            Text("Микс из скачанного под твой вкус — без интернета", style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
        }
        Text("▶", color = LocalAccent.current, style = MaterialTheme.typography.titleLarge)
    }
}
