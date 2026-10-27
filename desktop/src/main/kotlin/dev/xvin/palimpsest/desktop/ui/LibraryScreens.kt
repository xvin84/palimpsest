package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Wide enough: the title, the views and the filter share one line; narrower, the views go under the title.
    val oneLine = maxWidth >= 920.dp
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 20.dp, top = 22.dp)) {
        val tabs = @Composable {
            Tabs(listOf(LibTab.TRACKS to t("Треки", "Tracks"), LibTab.ALBUMS to t("Альбомы", "Albums"), LibTab.ARTISTS to t("Исполнители", "Artists"), LibTab.FOLDERS to t("Папки", "Folders")), tab, { tab = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Медиатека", "Library"), style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.width(12.dp))
            Text(
                count(all.count { it.isLocal }, "файл", "файла", "файлов", "file", "files") + " · " + tracksCount(all.size),
                style = Type.meta, color = pal.faint, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            if (oneLine) { tabs(); Spacer(Modifier.width(12.dp)) }
            PField(filter, { filter = it }, t("Фильтр", "Filter"), Modifier.width(200.dp))
            Spacer(Modifier.width(4.dp))
            PIconButton(Icons.Rounded.Refresh, t("Пересканировать", "Rescan"), { scope.launch { graph.library.scan() } }, iconSize = 18.dp)
        }
        if (!oneLine) Box(Modifier.padding(top = 12.dp)) { tabs() }
        Spacer(Modifier.height(14.dp))
        val words = filter.lowercase().replace('ё', 'е').split(' ').filter { it.isNotBlank() }
        fun Track.hit() = words.isEmpty() || "$title $artist ${album.orEmpty()}".lowercase().replace('ё', 'е').let { h -> words.all { it in h } }
        val visible = remember(all, filter) { all.filter { it.hit() } }
        when (tab) {
            LibTab.TRACKS -> TrackTable(visible, liked, showAdded = true)
            LibTab.ALBUMS -> {
                val albums = remember(visible) { visible.filter { !it.album.isNullOrBlank() && it.isLocal }.groupBy { it.albumKey }.values.sortedBy { (it.first().albumArtist ?: it.first().artist).lowercase() } }
                LazyVerticalGrid(GridCells.Adaptive(168.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(albums, key = { it.first().albumKey }) { tracks ->
                        val f = tracks.first()
                        Tile(
                            { TrackArt(f, Modifier.fillMaxSize(), Radius.md) }, f.album!!,
                            listOfNotNull(f.albumArtist ?: f.artist, f.year?.toString()).joinToString(" · "),
                            onPlay = { graph.player.play(tracks.sortedWith(compareBy({ it.trackNumber ?: 999 }, { it.title.lowercase() }))) },
                        ) { nav.go(Screen.LocalAlbum(f.album, f.albumArtist ?: f.artist)) }
                    }
                }
            }
            LibTab.ARTISTS -> {
                val artists = remember(visible) { visible.flatMap { t -> t.artists.map { it to t } }.groupBy({ it.first }, { it.second }).toList().sortedBy { it.first.lowercase() } }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(artists, key = { it.first }) { (name, tracks) ->
                        ListRow({ TrackArt(tracks.first(), Modifier.size(40.dp), 20.dp) }, name, tracksCount(tracks.size)) { nav.go(Screen.Artist(name)) }
                    }
                }
            }
            LibTab.FOLDERS -> {
                val folders = remember(visible) { visible.filter { it.isLocal }.groupBy { it.folder.orEmpty() }.toList().sortedBy { it.first.lowercase() } }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(folders, key = { it.first }) { (folder, tracks) ->
                        ListRow({
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radius.md)).background(pal.paper3), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Folder, null, tint = pal.muted, modifier = Modifier.size(20.dp))
                            }
                        }, folder.ifEmpty { "/" }, tracksCount(tracks.size)) { nav.go(Screen.Folder(folder)) }
                    }
                }
            }
        }
    }
    }
}

/** A row that leads somewhere (an artist, a folder): picture, name, a count, an arrow on hover. */
@Composable
fun ListRow(art: @Composable () -> Unit, title: String, meta: String, onClick: () -> Unit) {
    val pal = LocalPal.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(Radius.md)).background(if (hovered) pal.text.copy(alpha = 0.05f) else Color.Transparent)
            .hoverable(hover).clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art()
        Spacer(Modifier.width(14.dp))
        Text(title, style = Type.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(meta, style = Type.meta, color = pal.faint)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = if (hovered) pal.muted else Color.Transparent, modifier = Modifier.padding(start = 8.dp).size(18.dp))
    }
}

/** An album or playlist tile: the cover (with a play button on hover when there's something to play), title, a line under it. */
@Composable
fun Tile(cover: @Composable () -> Unit, title: String, subtitle: String, modifier: Modifier = Modifier, round: Boolean = false, onPlay: (() -> Unit)? = null, onClick: () -> Unit) {
    val pal = LocalPal.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(modifier.hoverable(hover).clickable(onClick = onClick), horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            cover()
            if (onPlay != null) androidx.compose.animation.AnimatedVisibility(
                hovered, Modifier.align(Alignment.BottomEnd).padding(10.dp),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 3 }, exit = androidx.compose.animation.fadeOut(),
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onPlay), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, t("Слушать", "Play"), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = Type.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (round) TextAlign.Center else TextAlign.Start)
        if (subtitle.isNotEmpty()) Text(subtitle, style = Type.meta, color = pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A sortable table of tracks: click a column header to sort by it, again to reverse. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TrackTable(tracks: List<Track>, liked: Set<String>, numbered: Boolean = false, showAdded: Boolean = false, header: (@Composable () -> Unit)? = null, onRemove: ((Int) -> Unit)? = null) {
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
    fun H(label: String, s: Sort, modifier: Modifier, end: Boolean = false) {
        val on = sort == s
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
            Row(
                Modifier.clip(RoundedCornerShape(Radius.xs)).clickable { if (on) desc = !desc else { sort = s; desc = s == Sort.ADDED } }.padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label.uppercase(), style = Type.caption, color = if (on) pal.text else pal.faint)
                if (on) Icon(if (desc) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, null, tint = pal.text, modifier = Modifier.padding(start = 3.dp).size(11.dp))
            }
        }
    }
    // Selection: click picks a row, Ctrl adds or removes one, Shift takes the range from the last click.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var anchor by remember { mutableStateOf(-1) }
    val chosen = remember(sorted, selected) { sorted.filter { it.id in selected } }
    fun select(i: Int, m: androidx.compose.ui.input.pointer.PointerKeyboardModifiers) {
        val id = sorted[i].id
        val ctrl = m.isCtrlPressed || m.isMetaPressed
        when {
            m.isShiftPressed && anchor in sorted.indices -> {
                val range = if (anchor <= i) anchor..i else i..anchor
                selected = (if (ctrl) selected else emptySet()) + range.map { sorted[it].id }
            }
            ctrl -> { selected = if (id in selected) selected - id else selected + id; anchor = i }
            else -> { selected = setOf(id); anchor = i }
        }
    }
    val latest by androidx.compose.runtime.rememberUpdatedState(sorted)
    androidx.compose.runtime.DisposableEffect(nav) {
        val hooks = SelectionHooks(
            selectAll = { selected = latest.mapTo(HashSet()) { it.id } },
            clear = { if (selected.isEmpty()) false else { selected = emptySet(); true } },
        )
        nav.selection = hooks
        onDispose { if (nav.selection === hooks) nav.selection = null }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Narrow windows drop the album column, and the date before it.
    val showAlbum = maxWidth >= 720.dp
    val withAdded = showAdded && maxWidth >= 1000.dp
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = if (chosen.size > 1) 72.dp else 24.dp)) {
        header?.let { h -> item { h() } }
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                PButton(t("Слушать", "Play"), { graph.player.play(sorted) }, icon = Icons.Rounded.PlayArrow, kind = Kind.PRIMARY, enabled = sorted.isNotEmpty())
                Spacer(Modifier.width(8.dp))
                PButton(t("Вперемешку", "Shuffle"), { graph.player.play(sorted, shuffle = true) }, icon = Icons.Rounded.Shuffle, enabled = sorted.isNotEmpty())
                Spacer(Modifier.weight(1f))
                // A page with its own header already says how many.
                if (header == null) Text(tracksCount(sorted.size) + " · " + fmtTotal(sorted.sumOf { it.durationMs }), style = Type.meta, color = pal.faint)
            }
        }
        stickyHeader {
            Row(
                Modifier.fillMaxWidth().background(pal.paper).drawBehind { drawLine(pal.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                    .padding(horizontal = Cols.pad).padding(top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#", style = Type.caption, color = pal.faint, textAlign = TextAlign.Center, modifier = Modifier.width(Cols.lead))
                Spacer(Modifier.width(Cols.art + Cols.gap))
                Row(Modifier.weight(1.4f), verticalAlignment = Alignment.CenterVertically) {
                    H(t("Название", "Title"), Sort.TITLE, Modifier)
                    Text("/", style = Type.caption, color = pal.line, modifier = Modifier.padding(horizontal = 6.dp))
                    H(t("Исполнитель", "Artist"), Sort.ARTIST, Modifier)
                }
                if (showAlbum) H(t("Альбом", "Album"), Sort.ALBUM, Modifier.weight(1f).padding(horizontal = 10.dp))
                if (withAdded) H(t("Добавлен", "Added"), Sort.ADDED, Modifier.width(Cols.added))
                Spacer(Modifier.width(Cols.like + Cols.cloud))
                H(t("Время", "Time"), Sort.DURATION, Modifier.width(Cols.time), end = true)
                Spacer(Modifier.width(Cols.more + if (onRemove != null) 32.dp else 0.dp))
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
        itemsIndexed(sorted, key = { i, t -> "$i:${t.id}" }) { i, t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TrackLine(t, if (numbered) (t.trackNumber ?: (i + 1)) else i + 1, st.current?.id == t.id, t.id in liked,
                        onPlay = { graph.player.play(sorted, i) },
                        // The menu of the selection when the row is in it, otherwise of this row alone.
                        onMenu = { if (t.id in selected && chosen.size > 1) nav.openMenu(chosen) else { selected = setOf(t.id); anchor = i; nav.openMenu(t) } },
                        showAlbum = showAlbum, selected = t.id in selected, onSelect = { select(i, it) }, showAdded = withAdded)
                }
                if (onRemove != null) PIconButton(Icons.Rounded.Close, t("Убрать из плейлиста", "Remove from playlist"), { onRemove(tracks.indexOf(t)) }, iconSize = 16.dp, tint = pal.faint)
            }
        }
    }
    // The bar rises from the bottom when a second row is picked and sinks when the selection goes.
    var barTracks by remember { mutableStateOf(chosen) }
    if (chosen.size > 1) barTracks = chosen
    androidx.compose.animation.AnimatedVisibility(
        chosen.size > 1, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f)) { it * 2 } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it * 2 } + androidx.compose.animation.fadeOut(),
    ) { SelectionBar(barTracks, { selected = emptySet() }, Modifier) }
    }
}

/** Floats under a track table while several rows are selected: the usual things for all of them at once. */
@Composable
private fun SelectionBar(tracks: List<Track>, onClear: () -> Unit, modifier: Modifier) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    androidx.compose.material3.Surface(modifier, shape = RoundedCornerShape(Radius.lg), color = pal.paper3, shadowElevation = 12.dp, border = androidx.compose.foundation.BorderStroke(1.dp, pal.line)) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t("Выбрано: ${tracks.size}", "Selected: ${tracks.size}"), Modifier.padding(horizontal = 10.dp), style = Type.rowTitle)
            PButton(t("Слушать", "Play"), { graph.player.play(tracks) }, icon = Icons.Rounded.PlayArrow, kind = Kind.PRIMARY)
            PButton(t("Следующими", "Next"), { graph.player.playNext(tracks); nav.toast(t("Сыграют следующими", "Play next")) }, kind = Kind.GHOST)
            PButton(t("В очередь", "Queue"), { graph.player.enqueue(tracks); nav.toast(t("В очереди", "Queued")) }, kind = Kind.GHOST)
            PButton(t("Ещё…", "More…"), { nav.openMenu(tracks) }, kind = Kind.GHOST)
            PIconButton(Icons.Rounded.Close, t("Снять выделение", "Clear selection"), onClear, iconSize = 18.dp)
        }
    }
}

/**
 * The top of an album, folder or playlist page: a back button, the cover, what kind of thing it is,
 * its name in the serif and a line under it. [actions] go below (play, shuffle…).
 */
@Composable
fun ListHeader(title: String, subtitle: String?, kind: String? = null, actions: (@Composable () -> Unit)? = null, art: @Composable (() -> Unit)? = null) {
    val nav = LocalNav.current
    val pal = LocalPal.current
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        PIconButton(Icons.AutoMirrored.Rounded.ArrowBack, t("Назад", "Back"), nav::back, Modifier.padding(bottom = 8.dp).offset(x = (-6).dp), iconSize = 20.dp)
        Row(verticalAlignment = Alignment.Bottom) {
            art?.let { Box(Modifier.size(160.dp)) { it() }; Spacer(Modifier.width(24.dp)) }
            Column(Modifier.weight(1f)) {
                kind?.let { Text(it.uppercase(), style = Type.caption, color = pal.muted, modifier = Modifier.padding(bottom = 6.dp)) }
                Text(title, style = MaterialTheme.typography.displayMedium.copy(fontSize = 34.sp, lineHeight = 40.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                actions?.let { Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { it() } }
            }
        }
    }
}

@Composable
fun LocalListScreen(title: String, subtitle: String, kind: String, filter: (Track) -> Boolean) {
    val graph = LocalGraph.current
    val all by graph.library.tracks.collectAsState()
    val liked by graph.library.liked.collectAsState()
    val tracks = remember(all) { all.filter(filter).sortedWith(compareBy({ it.trackNumber ?: 999 }, { it.title.lowercase() })) }
    val year = tracks.firstNotNullOfOrNull { it.year }
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 20.dp, top = 16.dp)) {
        TrackTable(tracks, liked, numbered = true, header = {
            ListHeader(title, listOfNotNull(subtitle, year?.toString(), tracksCount(tracks.size), fmtTotal(tracks.sumOf { it.durationMs })).joinToString(" · "), kind) {
                TrackArt(tracks.firstOrNull(), Modifier.fillMaxSize(), Radius.lg)
            }
        })
    }
}

@Composable
fun LikedScreen() {
    val graph = LocalGraph.current
    val all by graph.library.tracks.collectAsState()
    val liked by graph.library.liked.collectAsState()
    val tracks = remember(all, liked) { all.filter { it.id in liked } }
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 20.dp, top = 22.dp)) {
        Text(t("Любимые", "Favourites"), style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(bottom = 14.dp))
        if (tracks.isEmpty()) EmptyState(t("Пока пусто", "Nothing yet"), t("Отмечай треки сердечком — они соберутся здесь. Лайки Яндекса подтягиваются в «Сервисах».", "Heart the tracks you love and they gather here. Yandex likes sync in Services."))
        else TrackTable(tracks, liked, showAdded = true)
    }
}

@Composable
fun PlaylistScreen(id: Long) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val lists by graph.playlists.lists.collectAsState()
    val all by graph.library.tracks.collectAsState()
    val liked by graph.library.liked.collectAsState()
    val p = lists.firstOrNull { it.id == id } ?: return
    val byId = remember(all) { all.associateBy { it.id } }
    val tracks = p.trackIds.mapNotNull(byId::get)
    var name by remember(p.id) { mutableStateOf(p.name) }
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 20.dp, top = 22.dp)) {
        Text(t("ПЛЕЙЛИСТ", "PLAYLIST"), style = Type.caption, color = pal.muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The name is edited in place, set in the page title's own type.
            androidx.compose.foundation.text.BasicTextField(
                name, { name = it; graph.playlists.rename(p.id, it) }, Modifier.weight(1f), singleLine = true,
                textStyle = MaterialTheme.typography.displayMedium.copy(color = pal.text), cursorBrush = androidx.compose.ui.graphics.SolidColor(pal.text),
            )
            PButton(t("Удалить плейлист", "Delete playlist"), { graph.playlists.delete(p.id); nav.root(Screen.Library) }, icon = Icons.Rounded.DeleteOutline, kind = Kind.GHOST)
        }
        Text(tracksCount(tracks.size) + " · " + fmtTotal(tracks.sumOf { it.durationMs }), style = Type.meta, color = pal.faint, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
        if (tracks.isEmpty()) EmptyState(t("Плейлист пуст", "Empty playlist"), t("Правый клик или ⋯ у трека → «В плейлист…»", "Right-click or ⋯ on a track → «Add to playlist…»"))
        else TrackTable(tracks, liked, numbered = true, onRemove = { i -> graph.playlists.removeAt(p.id, i) })
    }
}
