package dev.xvin.palimpsest.desktop.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.desktop.data.Track
import kotlinx.coroutines.launch

private enum class LibTab { TRACKS, ALBUMS, ARTISTS, FOLDERS }
private enum class Sort { TITLE, ARTIST, ALBUM, DURATION, ADDED }

@Composable
fun LibraryScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    val all by graph.library.tracks.collectAsState()
    val liked by graph.library.liked.collectAsState()
    var tab by remember { mutableStateOf(LibTab.TRACKS) }
    var filter by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(t("Медиатека", "Library"), style = MaterialTheme.typography.displayMedium)
                Text(t("${all.count { it.isLocal }} файлов · ${all.size} треков", "${all.count { it.isLocal }} files · ${all.size} tracks"), color = pal.muted, fontSize = 13.sp)
            }
            OutlinedTextField(filter, { filter = it }, Modifier.width(220.dp), singleLine = true, placeholder = { Text(t("Фильтр", "Filter")) })
            IconButton(onClick = { scope.launch { graph.library.scan() } }) { Icon(Icons.Rounded.Refresh, null, tint = pal.muted) }
        }
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(t("Треки", "Tracks"), tab == LibTab.TRACKS, { tab = LibTab.TRACKS })
            Pill(t("Альбомы", "Albums"), tab == LibTab.ALBUMS, { tab = LibTab.ALBUMS })
            Pill(t("Исполнители", "Artists"), tab == LibTab.ARTISTS, { tab = LibTab.ARTISTS })
            Pill(t("Папки", "Folders"), tab == LibTab.FOLDERS, { tab = LibTab.FOLDERS })
        }
        val words = filter.lowercase().replace('ё', 'е').split(' ').filter { it.isNotBlank() }
        fun Track.hit() = words.isEmpty() || "$title $artist ${album.orEmpty()}".lowercase().replace('ё', 'е').let { h -> words.all { it in h } }
        val visible = remember(all, filter) { all.filter { it.hit() } }
        when (tab) {
            LibTab.TRACKS -> TrackTable(visible, liked)
            LibTab.ALBUMS -> {
                val albums = remember(visible) { visible.filter { !it.album.isNullOrBlank() && it.isLocal }.groupBy { it.albumKey }.values.sortedBy { (it.first().albumArtist ?: it.first().artist).lowercase() } }
                LazyVerticalGrid(GridCells.Adaptive(170.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(albums, key = { it.first().albumKey }) { tracks ->
                        val f = tracks.first()
                        Column(Modifier.clickable { nav.go(Screen.LocalAlbum(f.album!!, f.albumArtist ?: f.artist)) }) {
                            TrackArt(f, Modifier.fillMaxWidth().aspectRatio(1f), 10.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(f.album!!, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(f.albumArtist ?: f.artist, f.year?.toString()).joinToString(" · "), color = pal.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            LibTab.ARTISTS -> {
                val artists = remember(visible) { visible.flatMap { t -> t.artists.map { it to t } }.groupBy({ it.first }, { it.second }).toList().sortedBy { it.first.lowercase() } }
                LazyColumn {
                    items(artists, key = { it.first }) { (name, tracks) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { nav.go(Screen.Artist(name)) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TrackArt(tracks.first(), Modifier.size(44.dp), 22.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(name, Modifier.weight(1f))
                            Text(t("${tracks.size} тр.", "${tracks.size} tracks"), color = pal.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
            LibTab.FOLDERS -> {
                val folders = remember(visible) { visible.filter { it.isLocal }.groupBy { it.folder.orEmpty() }.toList().sortedBy { it.first.lowercase() } }
                LazyColumn {
                    items(folders, key = { it.first }) { (folder, tracks) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { nav.go(Screen.Folder(folder)) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Folder, null, tint = LocalAccent.current)
                            Spacer(Modifier.width(14.dp))
                            Text(folder.ifEmpty { "/" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${tracks.size}", color = pal.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** A sortable table of tracks: click a column header to sort by it, again to reverse. */
@Composable
fun TrackTable(tracks: List<Track>, liked: Set<String>, numbered: Boolean = false, header: (@Composable () -> Unit)? = null, onRemove: ((Int) -> Unit)? = null) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val st by graph.player.state.collectAsState()
    var sort by remember { mutableStateOf<Sort?>(if (numbered) null else Sort.TITLE) }
    var desc by remember { mutableStateOf(false) }
    val sorted = remember(tracks, sort, desc) {
        val s = when (sort) {
            null -> tracks
            Sort.TITLE -> tracks.sortedBy { it.title.lowercase() }
            Sort.ARTIST -> tracks.sortedWith(compareBy({ it.artist.lowercase() }, { it.album?.lowercase() }, { it.trackNumber }))
            Sort.ALBUM -> tracks.sortedWith(compareBy({ it.album?.lowercase() }, { it.trackNumber }))
            Sort.DURATION -> tracks.sortedBy { it.durationMs }
            Sort.ADDED -> tracks.sortedBy { it.addedAt }
        }
        if (desc) s.reversed() else s
    }
    @Composable
    fun H(label: String, s: Sort, modifier: Modifier) {
        Text(
            label + if (sort == s) (if (desc) " ↓" else " ↑") else "", color = if (sort == s) pal.text else pal.muted, fontSize = 12.sp,
            modifier = modifier.clickable { if (sort == s) desc = !desc else { sort = s; desc = false } },
        )
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    // Narrow windows drop the album column.
    val showAlbum = maxWidth >= 720.dp
    LazyColumn(Modifier.fillMaxSize()) {
        header?.let { h -> item { h() } }
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { graph.player.play(sorted) }, enabled = sorted.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Text(t("Слушать", "Play")) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { graph.player.play(sorted, shuffle = true) }, enabled = sorted.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(4.dp)); Text(t("Вперемешку", "Shuffle")) }
                Spacer(Modifier.weight(1f))
                Text(t("${sorted.size} треков · ", "${sorted.size} tracks · ") + fmt(sorted.sumOf { it.durationMs }), color = pal.muted, fontSize = 12.sp)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Spacer(Modifier.width(36.dp + 36.dp + 12.dp))
                Row(Modifier.weight(1.3f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    H(t("Название", "Title"), Sort.TITLE, Modifier)
                    H(t("Исполнитель", "Artist"), Sort.ARTIST, Modifier)
                    H(t("Добавлены", "Added"), Sort.ADDED, Modifier)
                }
                if (showAlbum) H(t("Альбом", "Album"), Sort.ALBUM, Modifier.weight(1f).padding(horizontal = 8.dp))
                Spacer(Modifier.width(42.dp))
                H(t("Время", "Time"), Sort.DURATION, Modifier.width(52.dp))
            }
        }
        itemsIndexed(sorted, key = { i, t -> "$i:${t.id}" }) { i, t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TrackLine(t, if (numbered) (t.trackNumber ?: (i + 1)) else i + 1, st.current?.id == t.id, t.id in liked,
                        onPlay = { graph.player.play(sorted, i) }, onMenu = { nav.menuFor = t }, showAlbum = showAlbum)
                }
                if (onRemove != null) TextButton(onClick = { onRemove(tracks.indexOf(t)) }) { Text("✕", color = pal.faint) }
            }
        }
    }
    }
}

@Composable
fun ListHeader(title: String, subtitle: String?, art: @Composable (() -> Unit)? = null) {
    val nav = LocalNav.current
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.Bottom) {
        IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
        art?.let { Box(Modifier.size(150.dp)) { it() }; Spacer(Modifier.width(20.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displayMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = pal.muted) }
        }
    }
}

@Composable
fun LocalListScreen(title: String, subtitle: String, filter: (Track) -> Boolean) {
    val graph = LocalGraph.current
    val all by graph.library.tracks.collectAsState()
    val liked by graph.library.liked.collectAsState()
    val tracks = remember(all) { all.filter(filter).sortedWith(compareBy({ it.trackNumber ?: 999 }, { it.title.lowercase() })) }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        ListHeader(title, subtitle) { TrackArt(tracks.firstOrNull(), Modifier.fillMaxSize(), 12.dp) }
        TrackTable(tracks, liked, numbered = true)
    }
}

@Composable
fun LikedScreen() {
    val graph = LocalGraph.current
    val all by graph.library.tracks.collectAsState()
    val liked by graph.library.liked.collectAsState()
    val tracks = remember(all, liked) { all.filter { it.id in liked } }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        Text(t("Любимые", "Favourites"), style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(bottom = 12.dp))
        if (tracks.isEmpty()) EmptyState(t("Пока пусто", "Nothing yet"), t("Ставь ♥ трекам — они соберутся здесь. Лайки Яндекса подтягиваются в «Сервисах».", "Tap ♥ on tracks and they gather here. Yandex likes sync in Services."))
        else TrackTable(tracks, liked)
    }
}

@Composable
fun PlaylistScreen(id: Long) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val lists by graph.playlists.lists.collectAsState()
    val all by graph.library.tracks.collectAsState()
    val liked by graph.library.liked.collectAsState()
    val p = lists.firstOrNull { it.id == id } ?: return
    val byId = remember(all) { all.associateBy { it.id } }
    val tracks = p.trackIds.mapNotNull(byId::get)
    var name by remember(p.id) { mutableStateOf(p.name) }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it; graph.playlists.rename(p.id, it) }, Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = { graph.playlists.delete(p.id); nav.root(Screen.Library) }) { Text(t("Удалить плейлист", "Delete playlist"), color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(12.dp))
        if (tracks.isEmpty()) EmptyState(t("Плейлист пуст", "Empty playlist"), t("Правый клик по треку → «В плейлист…»", "Right-click a track → «Add to playlist…»"))
        else TrackTable(tracks, liked, numbered = true, onRemove = { i -> graph.playlists.removeAt(p.id, i) })
    }
}
