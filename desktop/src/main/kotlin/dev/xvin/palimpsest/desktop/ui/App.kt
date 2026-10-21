package dev.xvin.palimpsest.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.desktop.data.Graph
import dev.xvin.palimpsest.desktop.data.Paths
import dev.xvin.palimpsest.desktop.data.Track
import dev.xvin.palimpsest.desktop.playback.Repeat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

val LocalGraph = staticCompositionLocalOf<Graph> { error("no graph") }
val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }

/** Where the main pane is. */
sealed interface Screen {
    data object Library : Screen
    data object Search : Screen
    data object Services : Screen
    data object History : Screen
    data object Settings : Screen
    data object Liked : Screen
    data class Artist(val name: String, val remoteId: String? = null, val service: Service = Service.YANDEX) : Screen
    data class RemoteAlbum(val id: String, val service: Service) : Screen
    data class LocalAlbum(val album: String, val artist: String) : Screen
    data class Folder(val folder: String) : Screen
    data class Playlist(val id: Long) : Screen
    data class RemotePlaylist(val service: Service, val id: String, val title: String) : Screen
}

/** Navigation and app-wide actions any screen may use. */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Library)
    val current get() = stack.last()
    var menuFor by mutableStateOf<Track?>(null)
    var nowPlaying by mutableStateOf(false)
    /** Lyrics on the full-window player (its own switch, separate from the side panel). */
    var bigLyrics by mutableStateOf(true)
    var rightPanel by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var searchFocus by mutableStateOf(0)

    fun go(s: Screen) { nowPlaying = false; if (current != s) stack.add(s) }
    fun root(s: Screen) { nowPlaying = false; stack.clear(); stack.add(s) }
    fun back() { if (nowPlaying) nowPlaying = false else if (stack.size > 1) stack.removeLast() }
    fun toast(m: String) { message = m }
}

fun main() = application {
    val graph = remember { Graph() }
    val nav = remember { Nav() }
    val settings by graph.settings.state.collectAsState()
    val state = rememberWindowState(size = DpSize(1280.dp, 820.dp))
    Window(
        onCloseRequest = { graph.player.engine.stop(); exitApplication() },
        title = "Palimpsest",
        icon = painterResource("icon.png"),
        state = state,
        onKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown) return@Window false
            when {
                e.key == Key.Spacebar -> { graph.player.toggle(); true }
                e.isCtrlPressed && e.key == Key.F -> { nav.root(Screen.Search); nav.searchFocus++; true }
                e.isCtrlPressed && e.key == Key.DirectionRight -> { graph.player.next(); true }
                e.isCtrlPressed && e.key == Key.DirectionLeft -> { graph.player.previous(); true }
                e.key == Key.DirectionRight -> { graph.player.seek(graph.player.state.value.positionMs + 5000); true }
                e.key == Key.DirectionLeft -> { graph.player.seek((graph.player.state.value.positionMs - 5000).coerceAtLeast(0)); true }
                e.key == Key.MediaPlayPause -> { graph.player.toggle(); true }
                e.key == Key.MediaNext -> { graph.player.next(); true }
                e.key == Key.MediaPrevious -> { graph.player.previous(); true }
                e.key == Key.Escape -> { nav.back(); true }
                else -> false
            }
        },
    ) {
        LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(560, 480) }
        setSingletonImageLoaderFactory { ctx ->
            ImageLoader.Builder(ctx)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
                .diskCache { DiskCache.Builder().directory(java.io.File(Paths.cache, "coil").toOkioPath()).maxSizeBytes(256L shl 20).build() }
                .build()
        }
        val player by graph.player.state.collectAsState()
        val current = player.current
        // Accent and tint from the current cover.
        val colors by produceState(Vermilion to Color(0xFF2A2A30), current?.id, settings.dynamicColor) {
            value = if (!settings.dynamicColor || current == null) Vermilion to Color(0xFF2A2A30)
            else withContext(Dispatchers.IO) { CoverColors.of(graph, current) } ?: (Vermilion to Color(0xFF2A2A30))
        }
        PalimpsestDesktopTheme(settings.style, settings.theme, colors.first, colors.second) {
            CompositionLocalProvider(LocalGraph provides graph, LocalNav provides nav) {
                LaunchedEffect(Unit) {
                    graph.library.scan()
                    // Development aid: PALIMPSEST_DEMO="query[|now]" plays the first match (and opens the big player).
                    System.getenv("PALIMPSEST_DEMO")?.let { demo ->
                        val q = demo.substringBefore('|').lowercase()
                        val all = graph.library.tracks.value
                        all.indexOfFirst { "${it.artist} ${it.title}".lowercase().contains(q) }.takeIf { it >= 0 }?.let { graph.player.play(all, it) }
                        if (demo.endsWith("|now")) nav.nowPlaying = true
                        if (demo.endsWith("|cover")) { nav.nowPlaying = true; nav.bigLyrics = false }
                        if (demo.endsWith("|lyrics")) nav.rightPanel = "lyrics"
                    }
                }
                Shell()
            }
        }
    }
}

@Composable
private fun Shell() {
    val nav = LocalNav.current
    val pal = LocalPal.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(nav.message) { nav.message?.let { snackbar.showSnackbar(it); nav.message = null } }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(pal.paper)) {
        // Narrow windows (half a screen, a tiling WM): icons-only sidebar, the side panel floats over the content.
        val compact = maxWidth < 1000.dp
        val panelInline = maxWidth >= 1250.dp
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(compact)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Crossfade(nav.current) { s -> Box(Modifier.fillMaxSize()) { ScreenContent(s) } }
                        if (!panelInline) nav.rightPanel?.let { panel ->
                            RightPanel(panel, Modifier.align(Alignment.CenterEnd).width(340.dp).fillMaxHeight().androidxShadow())
                        }
                    }
                    if (panelInline) nav.rightPanel?.let { panel -> RightPanel(panel, Modifier.width(360.dp).fillMaxHeight()) }
                }
                // The big player covers the content, never the player bar.
                BigPlayer(nav.nowPlaying)
            }
            PlayerBar(compact)
        }
        nav.menuFor?.let { t -> TrackMenu(t) { nav.menuFor = null } }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
    }
}

/** The full player sliding over the content (a function of its own: no Column scope around it). */
@Composable
private fun BigPlayer(visible: Boolean) {
    AnimatedVisibility(visible, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        NowPlayingView()
    }
}

@Composable
private fun ScreenContent(s: Screen) {
    when (s) {
        Screen.Library -> LibraryScreen()
        Screen.Search -> SearchScreen()
        Screen.Services -> ServicesScreen()
        Screen.History -> HistoryScreen()
        Screen.Settings -> SettingsScreen()
        Screen.Liked -> LikedScreen()
        is Screen.Artist -> ArtistScreen(s)
        is Screen.RemoteAlbum -> RemoteAlbumScreen(s)
        is Screen.LocalAlbum -> LocalListScreen(s.album, s.artist) { it.album == s.album && (it.albumArtist ?: it.artist) == s.artist }
        is Screen.Folder -> LocalListScreen(s.folder.substringAfterLast('/'), s.folder) { it.folder == s.folder }
        is Screen.Playlist -> PlaylistScreen(s.id)
        is Screen.RemotePlaylist -> RemotePlaylistScreen(s)
    }
}

private fun Modifier.androidxShadow() = this.then(Modifier.shadow(24.dp))

@Composable
private fun Sidebar(compact: Boolean) {
    val nav = LocalNav.current
    val graph = LocalGraph.current
    val pal = LocalPal.current
    val playlists by graph.playlists.lists.collectAsState()
    val scan by graph.library.progress.collectAsState()
    Column(Modifier.width(if (compact) 64.dp else 230.dp).fillMaxHeight().background(pal.paper2).padding(if (compact) 8.dp else 12.dp)) {
        if (compact) Text("P", style = MaterialTheme.typography.headlineLarge, color = LocalAccent.current, modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 14.dp))
        else Text("Palimpsest", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 14.dp))
        NavItem(Icons.Rounded.LibraryMusic, t("Медиатека", "Library"), nav.current == Screen.Library, compact) { nav.root(Screen.Library) }
        NavItem(Icons.Rounded.Search, t("Поиск", "Search"), nav.current == Screen.Search, compact) { nav.root(Screen.Search) }
        NavItem(Icons.Rounded.Radio, t("Сервисы", "Services"), nav.current == Screen.Services, compact) { nav.root(Screen.Services) }
        NavItem(Icons.Rounded.Favorite, t("Любимые", "Favourites"), nav.current == Screen.Liked, compact) { nav.root(Screen.Liked) }
        NavItem(Icons.Rounded.History, t("История", "History"), nav.current == Screen.History, compact) { nav.root(Screen.History) }
        NavItem(Icons.Rounded.Settings, t("Настройки", "Settings"), nav.current == Screen.Settings, compact) { nav.root(Screen.Settings) }
        if (compact) { Spacer(Modifier.weight(1f)); return@Column }
        SectionHeader(t("Плейлисты", "Playlists"), Modifier.padding(start = 8.dp)) {
            Text("+", color = LocalAccent.current, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable {
                val p = graph.playlists.create(t("Плейлист ", "Playlist ") + (playlists.size + 1)); nav.go(Screen.Playlist(p.id))
            }.padding(horizontal = 8.dp))
        }
        Column(Modifier.weight(1f)) {
            playlists.forEach { p -> NavItem(Icons.AutoMirrored.Rounded.PlaylistPlay, p.name, nav.current == Screen.Playlist(p.id)) { nav.root(Screen.Playlist(p.id)) } }
        }
        if (scan.running) {
            Text(if (scan.total > 0) t("Читаю теги: ${scan.done} из ${scan.total}", "Reading tags: ${scan.done} of ${scan.total}") else t("Ищу музыку…", "Looking for music…"), color = pal.muted, fontSize = 12.sp)
            LinearProgressIndicator(progress = { if (scan.total > 0) scan.done.toFloat() / scan.total else 0f }, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        val dl by graph.downloads.state.collectAsState()
        dl.current?.let {
            Text(t("Скачиваю: ", "Downloading: ") + it, color = pal.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { dl.progress }, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val pal = LocalPal.current
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = if (selected) accent else pal.muted, modifier = Modifier.size(if (compact) 24.dp else 20.dp))
        if (!compact) {
            Spacer(Modifier.width(12.dp))
            Text(label, color = if (selected) pal.text else pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The bar at the bottom: what's playing, transport, seek, volume, panels. */
@Composable
private fun PlayerBar(compact: Boolean) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val accent = LocalAccent.current
    val st by graph.player.state.collectAsState()
    val liked by graph.library.liked.collectAsState()
    val settings by graph.settings.state.collectAsState()
    val t = st.current
    val lane = LocalStyle.current == dev.xvin.palimpsest.desktop.data.DesktopStyle.LANE
    val tint = LocalTint.current
    Row(
        Modifier.fillMaxWidth().height(92.dp)
            .background(if (lane) androidx.compose.ui.graphics.lerp(pal.paper2, tint, 0.35f) else pal.paper2)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            TrackArt(t, Modifier.size(60.dp).clickable { if (t != null) nav.nowPlaying = !nav.nowPlaying }, 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t?.title ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(t?.artist.orEmpty(), color = pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                    modifier = Modifier.clickable(enabled = t != null) { t?.artists?.firstOrNull()?.let { nav.go(Screen.Artist(it)) } })
                st.radio?.let { Text("≋ $it", color = accent, fontSize = 11.sp, maxLines = 1) }
            }
            if (t != null) IconButton(onClick = { graph.library.setLiked(t.id, t.id !in liked); if (t.id !in liked) likeRemote(graph, t, true) else likeRemote(graph, t, false) }) {
                Icon(if (t.id in liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (t.id in liked) accent else pal.muted)
            }
        }
        Column(Modifier.weight(if (compact) 1.3f else 1.4f).padding(horizontal = if (compact) 8.dp else 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(onClick = { graph.player.setShuffle(!st.shuffle) }) { Icon(Icons.Rounded.Shuffle, null, tint = if (st.shuffle) accent else pal.muted) }
                IconButton(onClick = graph.player::previous) { Icon(Icons.Rounded.SkipPrevious, null, Modifier.size(30.dp)) }
                Box(
                    Modifier.size(48.dp).clip(if (lane) RoundedCornerShape(14.dp) else CircleShape).background(MaterialTheme.colorScheme.primary).clickable { graph.player.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (st.loading != null) Text("${(st.loading!! * 100).toInt()}%", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp)
                    else Icon(if (st.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = graph.player::next) { Icon(Icons.Rounded.SkipNext, null, Modifier.size(30.dp)) }
                IconButton(onClick = graph.player::cycleRepeat) {
                    Icon(if (st.repeat == Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null, tint = if (st.repeat != Repeat.OFF) accent else pal.muted)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(st.positionMs), color = pal.faint, fontSize = 11.sp, modifier = Modifier.width(48.dp))
                SeekLine(st.positionMs, t?.durationMs ?: 0, Modifier.weight(1f)) { graph.player.seek(it) }
                Text(fmt(t?.durationMs ?: 0), color = pal.faint, fontSize = 11.sp, modifier = Modifier.width(48.dp).padding(start = 8.dp))
            }
            st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Row(Modifier.weight(if (compact) 0.7f else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            // On the full-window player the button shows or hides its lyrics; elsewhere the side panel.
            val lyricsOn = if (nav.nowPlaying) nav.bigLyrics else nav.rightPanel == "lyrics"
            IconButton(onClick = {
                if (nav.nowPlaying) nav.bigLyrics = !nav.bigLyrics
                else nav.rightPanel = if (nav.rightPanel == "lyrics") null else "lyrics"
            }) { Icon(Icons.Rounded.Lyrics, null, tint = if (lyricsOn) accent else pal.muted) }
            IconButton(onClick = { nav.rightPanel = if (nav.rightPanel == "queue") null else "queue" }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = if (nav.rightPanel == "queue") accent else pal.muted) }
            IconButton(onClick = { if (t != null) nav.nowPlaying = !nav.nowPlaying }) { Icon(Icons.Rounded.OpenInFull, null, tint = if (nav.nowPlaying) accent else pal.muted) }
            if (!compact) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = pal.muted, modifier = Modifier.padding(start = 8.dp))
                Slider(settings.volume, { graph.player.setVolume(it) }, Modifier.width(120.dp))
            }
        }
    }
}

/** Likes on the service too, for Yandex tracks when signed in. */
fun likeRemote(graph: Graph, t: Track, liked: Boolean) {
    if (t.service != Service.YANDEX || t.remoteId == null || !graph.yandex.isReady) return
    graph.scope.launch { runCatching { graph.yandex.setLiked(t.remoteId, liked) } }
}

/** A thin seek line; click or drag to move. */
@Composable
fun SeekLine(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier, color: Color = LocalAccent.current, onSeek: (Long) -> Unit) {
    val pal = LocalPal.current
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    androidx.compose.foundation.Canvas(
        modifier.height(18.dp)
            .pointerInput(durationMs) { detectTapGestures { o -> if (durationMs > 0) onSeek((o.x / size.width * durationMs).toLong()) } }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let { onSeek((it * durationMs).toLong()) }; drag = null },
                    onDragCancel = { drag = null },
                ) { c, _ -> drag = (c.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        val y = size.height / 2
        val h = 4.dp.toPx()
        drawRoundRect(pal.line, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(color, Offset(0f, y - h / 2), Size(size.width * frac, h), CornerRadius(h / 2))
        drawCircle(color, 6.dp.toPx(), Offset(size.width * frac, y))
    }
}

/** Right-click (or long-press) menu of a track. */
@Composable
private fun TrackMenu(t: Track, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val liked by graph.library.liked.collectAsState()
    val playlists by graph.playlists.lists.collectAsState()
    var pickPlaylist by remember { mutableStateOf(false) }
    fun act(block: () -> Unit) { block(); onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Column { Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(t.artist, style = MaterialTheme.typography.bodyMedium, color = LocalPal.current.muted) } },
        text = {
            Column {
                if (pickPlaylist) {
                    playlists.forEach { p -> MenuLine(p.name) { act { graph.playlists.add(p.id, listOf(t.id)); nav.toast(t("Добавлено в «${p.name}»", "Added to «${p.name}»")) } } }
                    MenuLine(t("+ Новый плейлист", "+ New playlist")) { act { val p = graph.playlists.create(t("Плейлист ", "Playlist ") + (playlists.size + 1)); graph.playlists.add(p.id, listOf(t.id)) } }
                } else {
                    MenuLine(t("Играть следующим", "Play next")) { act { graph.player.playNext(listOf(t)) } }
                    MenuLine(t("В очередь", "Add to queue")) { act { graph.player.enqueue(listOf(t)) } }
                    MenuLine(if (t.id in liked) t("Убрать из любимых", "Unlike") else t("В любимые", "Like")) { act { graph.library.setLiked(t.id, t.id !in liked); likeRemote(graph, t, t.id !in liked) } }
                    MenuLine(t("В плейлист…", "Add to playlist…")) { pickPlaylist = true }
                    if (t.path == null && t.remoteId != null) MenuLine(t("Скачать", "Download")) { act { graph.downloads.enqueue(listOf(t.id)); nav.toast(t("Скачиваю «${t.title}»", "Downloading «${t.title}»")) } }
                    t.artists.forEach { a -> MenuLine(t("Исполнитель: ", "Artist: ") + a) { act { nav.go(Screen.Artist(a)) } } }
                    t.album?.let { a ->
                        MenuLine(t("Альбом: ", "Album: ") + a) {
                            act { if (t.path != null) nav.go(Screen.LocalAlbum(a, t.albumArtist ?: t.artist)) else t.remoteAlbumId?.let { nav.go(Screen.RemoteAlbum(it, t.service)) } }
                        }
                    }
                    if (t.service == Service.YANDEX && t.remoteId != null && graph.yandex.isReady) {
                        MenuLine(t("Радио по треку", "Track radio")) { act { graph.scope.launch { runCatching { graph.player.startRadio("track:${t.remoteId}", t("Радио: ", "Radio: ") + t.title) }.onFailure { nav.toast(it.message ?: "") } } } }
                    }
                    t.path?.let { p ->
                        MenuLine(t("Показать в папке", "Show in folder")) { act { runCatching { java.awt.Desktop.getDesktop().open(java.io.File(p).parentFile) } } }
                        MenuLine(t("Удалить файл (в корзину системы)", "Delete file (to the system trash)")) {
                            act {
                                val ok = runCatching { java.awt.Desktop.getDesktop().moveToTrash(java.io.File(p)) }.getOrDefault(false)
                                if (ok) { graph.library.remove(t.id); if (t.remoteId != null) graph.library.upsert(t.copy(path = null)) }
                                nav.toast(if (ok) t("«${t.title}» — в корзине", "«${t.title}» moved to the trash") else t("Не удалось удалить", "Couldn’t delete"))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Закрыть", "Close")) } },
    )
}

@Composable
private fun MenuLine(text: String, onClick: () -> Unit) {
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 9.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
}
