package dev.xvin.palimpsest.desktop.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
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
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
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
import androidx.compose.ui.window.ApplicationScope
import dev.xvin.palimpsest.desktop.data.Hotkeys
import dev.xvin.palimpsest.desktop.data.CrashGuard
import dev.xvin.palimpsest.desktop.data.SingleInstance
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import dev.xvin.palimpsest.desktop.tray.AwtTray
import dev.xvin.palimpsest.desktop.tray.TrayController
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
val LocalTray = staticCompositionLocalOf<TrayController?> { null }

/** Where the main pane is. */
sealed interface Screen {
    data object Library : Screen
    data object Search : Screen
    data object Services : Screen
    data object History : Screen
    data object Settings : Screen
    data object Liked : Screen
    data object Flac : Screen
    data class Artist(val name: String, val remoteId: String? = null, val service: Service = Service.YANDEX) : Screen
    data class RemoteAlbum(val id: String, val service: Service) : Screen
    data class LocalAlbum(val album: String, val artist: String) : Screen
    data class Folder(val folder: String) : Screen
    data class Playlist(val id: Long) : Screen
    data class RemotePlaylist(val service: Service, val id: String, val title: String) : Screen
    data class Similar(val remoteId: String, val title: String) : Screen
}

/** Navigation and app-wide actions any screen may use. */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Library)
    val current get() = stack.last()
    /** The tracks whose menu is open (one, or the selected ones). */
    var menu by mutableStateOf<List<Track>>(emptyList())
    var dialog by mutableStateOf<TrackDialog?>(null)
    /** The track table on screen, for Ctrl+A and Esc. */
    var selection: SelectionHooks? = null
    var nowPlaying by mutableStateOf(false)
    /** Lyrics on the full-window player (its own switch, separate from the side panel). */
    var bigLyrics by mutableStateOf(true)
    var rightPanel by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var searchFocus by mutableStateOf(0)
    var waveTuning by mutableStateOf(false)

    fun go(s: Screen) { nowPlaying = false; if (current != s) stack.add(s) }
    fun root(s: Screen) { nowPlaying = false; stack.clear(); stack.add(s) }
    fun back() { if (nowPlaying) nowPlaying = false else if (stack.size > 1) stack.removeLast() }
    fun toast(m: String) { message = m }
    fun openMenu(t: Track) { menu = listOf(t) }
    fun openMenu(ts: List<Track>) { menu = ts }
}

/** What a track table lets the window do with its selection. */
class SelectionHooks(val selectAll: () -> Unit, val clear: () -> Boolean)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main() {
    CrashGuard.install()
    // One Palimpsest at a time: launching it again shows the running one (maybe hidden in the tray).
    // The window answers each request, so a launch can tell a working instance from a stuck one.
    val showRequests = kotlinx.coroutines.channels.Channel<CompletableDeferred<Unit>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    if (!SingleInstance.claim {
            val done = CompletableDeferred<Unit>()
            showRequests.trySend(done)
            // While the app is still starting, give it time; after that, a window that can't come up in seconds is stuck.
            runBlocking { withTimeoutOrNull(if (uiReady) 4_000 else 20_000) { done.await() } } != null
        }) return
    CrashGuard.beginStart()
    try {
        application(exitProcessOnExit = true) {
            // An error inside the window: logged and shown, and the process ends. (Compose's own handler
            // would close the window, which with «close to tray» left a process with no working window.)
            CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides WindowExceptionHandlerFactory { _ ->
                WindowExceptionHandler { e -> CrashGuard.fatal("Error in the window", e) }
            }) { App(showRequests) }
        }
    } catch (e: Throwable) {
        CrashGuard.fatal("Error while starting", e)
    }
}

/** The window's first frames are drawn (show requests can expect a quick answer from here on). */
@Volatile private var uiReady = false

@Composable
private fun ApplicationScope.App(showRequests: kotlinx.coroutines.channels.ReceiveChannel<CompletableDeferred<Unit>>) {
    val graph = remember { Graph().also { language = it.settings.value.language } }
    val nav = remember { Nav() }
    val settings by graph.settings.state.collectAsState()
    val state = rememberWindowState(size = DpSize(1280.dp, 820.dp))
    var visible by remember { mutableStateOf(true) }
    val win = remember { WindowRef() }
    fun quit() { graph.player.engine.stop(); exitApplication() }
    fun show() {
        visible = true
        java.awt.EventQueue.invokeLater { win.window?.apply { if (state.isMinimized) state.isMinimized = false; toFront(); requestFocus() } }
    }
    val tray = remember {
        // A click on the tray icon hides the window if it was in front (clicking the tray takes focus a moment
        // before we hear of it, hence the grace), otherwise brings it up.
        TrayController(graph, showOrHide = { if (visible && win.wasFocusedJustNow()) visible = false else show() }, quit = { quit() })
    }
    LaunchedEffect(Unit) { tray.start() }
    LaunchedEffect(Unit) { for (done in showRequests) { show(); done.complete(Unit) } }
    AwtTray(tray, graph)
    // The keys: stroke → action, from Settings (the user's own) over the defaults.
    val keys = remember(settings.hotkeys) { Hotkeys.effective(settings.hotkeys).flatMap { (a, ks) -> ks.map { it to a } }.toMap() }
    val controls = remember {
        WindowControls(
            hide = { if (settings.trayIcon && tray.kind.value != TrayController.Kind.NONE) visible = false else state.isMinimized = true },
            toggleFullScreen = {
                state.placement = if (state.placement == androidx.compose.ui.window.WindowPlacement.Fullscreen) androidx.compose.ui.window.WindowPlacement.Floating
                else androidx.compose.ui.window.WindowPlacement.Fullscreen
            },
            quit = { tray.stop(); quit() },
        )
    }
    Window(
        // With a tray to come back from, closing only hides the window and the music plays on.
        onCloseRequest = { if (settings.closeToTray && settings.trayIcon && tray.kind.value != TrayController.Kind.NONE) visible = false else quit() },
        visible = visible,
        title = "Palimpsest",
        icon = painterResource("icon.png"),
        state = state,
        // Space (and Enter) would otherwise press whatever button was clicked last — the heart, the
        // lyrics switch — since a clicked button keeps the focus; so those are taken before the
        // button sees them, unless a field is being typed in or a menu or dialog is open.
        onPreviewKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown || KeyboardOwner.busy || nav.menu.isNotEmpty() || nav.dialog != null || nav.waveTuning) return@Window false
            val stroke = strokeOf(e)
            val action = stroke?.takeIf { it == "Space" || it == "Enter" }?.let(keys::get)
            action != null && runHotAction(action, graph, nav, controls)
        },
        onKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown) return@Window false
            val stroke = strokeOf(e)
            // A field being typed in keeps its letters and editing keys.
            val action = stroke?.takeIf(KeyboardOwner::letThrough)?.let(keys::get)
            when {
                action != null -> runHotAction(action, graph, nav, controls)
                // Media keys on the keyboard always work.
                e.key == Key.MediaPlayPause -> { graph.player.toggle(); true }
                e.key == Key.MediaNext -> { graph.player.next(); true }
                e.key == Key.MediaPrevious -> { graph.player.previous(); true }
                else -> false
            }
        },
    ) {
        LaunchedEffect(Unit) {
            // A few frames drawn and some seconds alive: this start worked (see CrashGuard).
            repeat(3) { withFrameNanos { } }
            uiReady = true
            kotlinx.coroutines.delay(3_000)
            CrashGuard.started()
            if (CrashGuard.fellBack) nav.toast(t("Прошлый запуск не удался — рисую без видеокарты. Вернуть: Настройки → О программе", "The last start failed, so drawing without the video card. To undo: Settings → About"))
        }
        LaunchedEffect(Unit) {
            window.minimumSize = java.awt.Dimension(560, 480)
            win.attach(window)
            // macOS: a click on the Dock icon brings the hidden window back.
            if (Paths.isMac) runCatching { java.awt.Desktop.getDesktop().addAppEventListener(java.awt.desktop.AppReopenedListener { show() }) }
        }
        // Media keys and the system's player controls (Linux).
        DisposableEffect(Unit) {
            val mpris = dev.xvin.palimpsest.desktop.playback.Mpris(graph, raise = { java.awt.EventQueue.invokeLater { show() } }, quit = { tray.stop(); quit() })
            graph.scope.launch { mpris.start() }
            onDispose { mpris.stop() }
        }
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
            CompositionLocalProvider(LocalGraph provides graph, LocalNav provides nav, LocalTray provides tray) {
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
internal fun Shell() {
    val nav = LocalNav.current
    val pal = LocalPal.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(nav.message) { nav.message?.let { snackbar.showSnackbar(it); nav.message = null } }
    val graph = LocalGraph.current
    val spots = remember { CoverSpots() }
    CompositionLocalProvider(LocalCoverSpots provides spots) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(pal.paper)) {
        // Narrow windows (half a screen, a tiling WM): icons-only sidebar, the side panel floats over the content.
        val compact = maxWidth < 1000.dp
        val panelInline = maxWidth >= 1250.dp
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(compact)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        // A new screen rises a little as it fades in; the old one just fades.
                        androidx.compose.animation.AnimatedContent(
                            nav.current,
                            transitionSpec = {
                                (fadeIn(tween(260, delayMillis = 40)) + slideInVertically(tween(340, easing = androidx.compose.animation.core.EaseOutCubic)) { it / 28 }) togetherWith
                                    fadeOut(tween(140))
                            },
                        ) { s -> Box(Modifier.fillMaxSize()) { ScreenContent(s) } }
                        if (!panelInline) SidePanel(nav.rightPanel, Modifier.align(Alignment.CenterEnd)) { panel ->
                            RightPanel(panel, Modifier.width(340.dp).fillMaxHeight().androidxShadow())
                        }
                    }
                    if (panelInline) SidePanel(nav.rightPanel, Modifier) { panel -> RightPanel(panel, Modifier.width(360.dp).fillMaxHeight()) }
                }
                // The big player covers the content, never the player bar.
                BigPlayer(nav.nowPlaying)
                // The queue floats over it (its lyrics are its own, so only the queue).
                if (nav.nowPlaying && nav.rightPanel == "queue") {
                    RightPanel("queue", Modifier.align(Alignment.CenterEnd).width(360.dp).fillMaxHeight().androidxShadow())
                }
            }
            PlayerBar(compact)
        }
        // Above the bar and the big player: the cover on its way between them.
        val st by graph.player.state.collectAsState()
        val cur = st.current
        val coverModel by produceState<Any?>(null, cur?.id, cur?.coverUrl) { value = graph.covers.model(cur) }
        CoverFlight(nav.nowPlaying, coverModel, cur?.album ?: cur?.title ?: "?", cur?.albumArtist ?: cur?.artist)
        if (nav.menu.isNotEmpty()) TrackMenu(nav.menu) { nav.menu = emptyList() }
        TrackDialogs()
        if (nav.waveTuning) WaveTuningDialog { nav.waveTuning = false }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
    }
    }
}

/**
 * The full player fading in over the content while the cover flies up from the bar into its place
 * (see [CoverFlight]); it stays put so the cover has somewhere to land.
 */
@Composable
private fun BigPlayer(visible: Boolean) {
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(320)),
        exit = fadeOut(tween(260)),
    ) {
        NowPlayingView()
    }
}

/** The lyrics / queue panel sliding in from the right and back out (the last one stays drawn while it leaves). */
@Composable
private fun SidePanel(panel: String?, modifier: Modifier, content: @Composable (String) -> Unit) {
    var last by remember { mutableStateOf(panel ?: "lyrics") }
    if (panel != null) last = panel
    AnimatedVisibility(
        panel != null, modifier,
        enter = androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.spring(dampingRatio = 0.92f, stiffness = 380f)) { it } + fadeIn(tween(200)),
        exit = androidx.compose.animation.slideOutHorizontally(tween(220)) { it } + fadeOut(tween(200)),
    ) { content(last) }
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
        Screen.Flac -> FlacScreen()
        is Screen.Artist -> ArtistScreen(s)
        is Screen.RemoteAlbum -> RemoteAlbumScreen(s)
        is Screen.LocalAlbum -> LocalListScreen(s.album, s.artist, t("Альбом", "Album")) { it.album == s.album && (it.albumArtist ?: it.artist) == s.artist }
        is Screen.Folder -> LocalListScreen(s.folder.substringAfterLast('/'), s.folder, t("Папка", "Folder")) { it.folder == s.folder }
        is Screen.Playlist -> PlaylistScreen(s.id)
        is Screen.RemotePlaylist -> RemotePlaylistScreen(s)
        is Screen.Similar -> SimilarScreen(s)
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
    Column(Modifier.width(if (compact) 64.dp else 232.dp).fillMaxHeight().background(pal.paper2).padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 12.dp)) {
        // The mark: a P written over a fainter one, the way a palimpsest is.
        Row(Modifier.padding(start = if (compact) 12.dp else 8.dp, top = 2.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Art("logo", Modifier.size(26.dp), LocalAccent.current)
            if (!compact) Text("Palimpsest", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 10.dp))
        }
        // Listening, then what you've kept, then your playlists; the settings wait at the bottom.
        NavItem(Icons.Rounded.LibraryMusic, t("Медиатека", "Library"), nav.current == Screen.Library, compact) { nav.root(Screen.Library) }
        WaveItem(compact)
        NavItem(Icons.Rounded.Search, t("Поиск", "Search"), nav.current == Screen.Search, compact) { nav.root(Screen.Search) }
        Spacer(Modifier.height(12.dp))
        NavItem(Icons.Rounded.Favorite, t("Любимые", "Favourites"), nav.current == Screen.Liked, compact) { nav.root(Screen.Liked) }
        NavItem(Icons.Rounded.History, t("История", "History"), nav.current == Screen.History, compact) { nav.root(Screen.History) }
        NavItem(Icons.Rounded.Radio, t("Сервисы", "Services"), nav.current == Screen.Services, compact) { nav.root(Screen.Services) }
        if (compact) {
            Spacer(Modifier.weight(1f))
            NavItem(Icons.Rounded.Settings, t("Настройки", "Settings"), nav.current == Screen.Settings, compact) { nav.root(Screen.Settings) }
            return@Column
        }
        SectionHeader(t("Плейлисты", "Playlists"), Modifier.padding(start = 10.dp, top = 8.dp)) {
            PIconButton(Icons.Rounded.Add, t("Новый плейлист", "New playlist"), {
                val p = graph.playlists.create(t("Плейлист ", "Playlist ") + (playlists.size + 1)); nav.go(Screen.Playlist(p.id))
            }, size = 26.dp, iconSize = 18.dp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            playlists.forEach { p -> NavItem(Icons.AutoMirrored.Rounded.QueueMusic, p.name, nav.current == Screen.Playlist(p.id)) { nav.root(Screen.Playlist(p.id)) } }
        }
        @Composable
        fun Job(label: String, progress: Float, onCancel: (() -> Unit)? = null) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = Type.meta, color = pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    onCancel?.let { PIconButton(Icons.Rounded.Close, t("Отменить", "Cancel"), it, size = 20.dp, iconSize = 14.dp) }
                }
                PProgress(progress, Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        if (scan.running) Job(
            if (scan.total > 0) t("Читаю теги: ${scan.done} из ${scan.total}", "Reading tags: ${scan.done} of ${scan.total}") else t("Ищу музыку…", "Looking for music…"),
            if (scan.total > 0) scan.done.toFloat() / scan.total else 0f,
        )
        val video by graph.videos.job.collectAsState()
        video?.let { v -> Job(t("Видео: ", "Video: ") + v.title, v.progress) { graph.videos.cancel() } }
        val dl by graph.downloads.state.collectAsState()
        dl.current?.let { Job(t("Скачиваю: ", "Downloading: ") + it, dl.progress) }
        NavItem(Icons.Rounded.Settings, t("Настройки", "Settings"), nav.current == Screen.Settings, compact) { nav.root(Screen.Settings) }
    }
}

/** «Моя волна» one click away: the row starts it, the knob beside it opens its settings. */
@Composable
private fun WaveItem(compact: Boolean) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val st by graph.player.state.collectAsState()
    val on = st.radio == t("Моя волна", "My Wave")
    val tune = @Composable { PIconButton(Icons.Rounded.Tune, t("Настроить волну", "Tune the wave"), { nav.waveTuning = true }, size = 26.dp, iconSize = 16.dp, tint = LocalPal.current.faint) }
    NavItem(null, t("Моя волна", "My Wave"), on, compact, tune, art = "wave") { if (on) graph.player.toggle() else startMyWave(graph, nav) }
}

@Composable
private fun NavItem(icon: ImageVector?, label: String, selected: Boolean, compact: Boolean = false, trailing: (@Composable () -> Unit)? = null, art: String? = null, onClick: () -> Unit) {
    val pal = LocalPal.current
    val accent = LocalAccent.current
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(if (compact) 44.dp else 34.dp).clip(RoundedCornerShape(Radius.md))
            .background(if (selected) pal.text.copy(alpha = 0.08f) else if (hovered) pal.text.copy(alpha = 0.04f) else Color.Transparent)
            .hoverable(hover).clickable(onClick = onClick).padding(start = if (compact) 12.dp else 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (selected) accent else pal.muted
        val size = Modifier.size(if (compact) 22.dp else 18.dp)
        if (art != null) Art(art, size, tint) else if (icon != null) Icon(icon, label, size, tint = tint)
        if (!compact) {
            Spacer(Modifier.width(12.dp))
            Text(label, color = if (selected || hovered) pal.text else pal.muted, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            trailing?.invoke()
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
        Modifier.fillMaxWidth().height(76.dp)
            .background(if (lane) androidx.compose.ui.graphics.lerp(pal.paper2, tint, 0.35f) else pal.paper2)
            .drawBehind { drawLine(pal.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Crossfade(t, Modifier.size(52.dp).coverSpot(big = false), animationSpec = tween(350)) { tr -> TrackArt(tr, Modifier.fillMaxSize().clickable { if (tr != null) nav.nowPlaying = !nav.nowPlaying }, Radius.sm) }
            Spacer(Modifier.width(12.dp))
            // The next track's name slides up into place.
            androidx.compose.animation.AnimatedContent(
                t, Modifier.weight(1f, fill = false),
                transitionSpec = { (fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 3 }) togetherWith (fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it / 3 }) },
                contentKey = { it?.id },
            ) { tr ->
                Column {
                    Text(tr?.title ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis, style = Type.rowTitle)
                    Text(tr?.artist.orEmpty(), color = pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = Type.rowSub,
                        modifier = Modifier.clickable(enabled = tr != null) { tr?.artists?.firstOrNull()?.let { nav.go(Screen.Artist(it)) } })
                    st.radio?.let {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Art("wave", Modifier.size(12.dp), pal.faint)
                            Spacer(Modifier.width(4.dp))
                            Text(it, style = Type.meta, color = pal.faint, maxLines = 1)
                        }
                    }
                }
            }
            if (t != null) {
                Spacer(Modifier.width(6.dp))
                val on = t.id in liked
                HeartButton(on, { graph.library.setLiked(t.id, !on); likeRemote(graph, t, !on) }, iconSize = 18.dp, likedTint = pal.text)
            }
        }
        Column(Modifier.weight(if (compact) 1.3f else 1.3f).padding(horizontal = if (compact) 8.dp else 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PIconButton(Icons.Rounded.Shuffle, t("Вперемешку", "Shuffle"), { graph.player.setShuffle(!st.shuffle) }, iconSize = 18.dp, active = st.shuffle)
                PIconButton(Icons.Rounded.SkipPrevious, t("Назад", "Previous"), graph.player::previous, size = 36.dp, iconSize = 26.dp, tint = pal.text)
                Box(
                    Modifier.size(40.dp).clip(if (lane) RoundedCornerShape(Radius.lg) else CircleShape).background(MaterialTheme.colorScheme.primary).clickable { graph.player.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (st.loading != null) Text("${(st.loading!! * 100).toInt()}%", color = MaterialTheme.colorScheme.onPrimary, style = Type.meta)
                    else androidx.compose.animation.AnimatedContent(
                        st.playing,
                        transitionSpec = { (androidx.compose.animation.scaleIn(tween(180), initialScale = 0.5f) + fadeIn(tween(180))) togetherWith (androidx.compose.animation.scaleOut(tween(140), targetScale = 0.5f) + fadeOut(tween(140))) },
                    ) { playing -> Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp)) }
                }
                PIconButton(Icons.Rounded.SkipNext, t("Дальше", "Next"), graph.player::next, size = 36.dp, iconSize = 26.dp, tint = pal.text)
                PIconButton(if (st.repeat == Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, t("Повтор", "Repeat"), graph.player::cycleRepeat, iconSize = 18.dp, active = st.repeat != Repeat.OFF)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(max = 620.dp)) {
                Text(fmt(st.positionMs), color = pal.faint, style = Type.meta, textAlign = TextAlign.End, modifier = Modifier.width(44.dp).padding(end = 8.dp))
                SeekLine(st.positionMs, t?.durationMs ?: 0, Modifier.weight(1f)) { graph.player.seek(it) }
                Text(fmt(t?.durationMs ?: 0), color = pal.faint, style = Type.meta, modifier = Modifier.width(44.dp).padding(start = 8.dp))
            }
            st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = Type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Row(Modifier.weight(if (compact) 0.7f else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End)) {
            if (t != null && !compact) PIconButton(Icons.Rounded.MoreHoriz, t("Действия", "Actions"), { nav.openMenu(graph.library.get(t.id) ?: t) })
            // On the full-window player the button shows or hides its lyrics; elsewhere the side panel.
            val lyricsOn = if (nav.nowPlaying) nav.bigLyrics else nav.rightPanel == "lyrics"
            Toggle(Icons.Rounded.Lyrics, t("Текст", "Lyrics"), lyricsOn) {
                if (nav.nowPlaying) nav.bigLyrics = !nav.bigLyrics
                else nav.rightPanel = if (nav.rightPanel == "lyrics") null else "lyrics"
            }
            Toggle(Icons.AutoMirrored.Rounded.QueueMusic, t("Очередь", "Queue"), nav.rightPanel == "queue") { nav.rightPanel = if (nav.rightPanel == "queue") null else "queue" }
            Toggle(Icons.Rounded.OpenInFull, t("Во всё окно", "Full window"), nav.nowPlaying) { if (t != null) nav.nowPlaying = !nav.nowPlaying }
            // Wheel or touchpad over the volume changes it, 5 % a notch; in a narrow window only the icon is left for that.
            Row(
                Modifier.padding(start = 6.dp).onScroll { dy -> graph.player.setVolume(graph.settings.value.volume - dy * 0.05f) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val v = settings.volume
                Icon(
                    when { v <= 0.001f -> Icons.AutoMirrored.Rounded.VolumeOff; v < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown; else -> Icons.AutoMirrored.Rounded.VolumeUp },
                    t("Громкость", "Volume"), tint = pal.muted, modifier = Modifier.size(18.dp),
                )
                if (!compact) LineSlider(v, { graph.player.setVolume(it) }, Modifier.padding(start = 8.dp).width(96.dp))
            }
        }
    }
}

/** A panel switch in the player bar: lit while its panel is open. */
@Composable
private fun Toggle(icon: ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    Box(Modifier.clip(CircleShape).background(if (on) pal.text.copy(alpha = 0.1f) else Color.Transparent)) {
        PIconButton(icon, description, onClick, iconSize = 18.dp, tint = if (on) pal.text else null)
    }
}

/** Likes on the service too, for Yandex tracks when signed in. */
fun likeRemote(graph: Graph, t: Track, liked: Boolean) {
    if (t.service != Service.YANDEX || t.remoteId == null || !graph.yandex.isReady) return
    graph.scope.launch { runCatching { graph.yandex.setLiked(t.remoteId, liked) } }
}

/** The seek line: click or drag to move; the knob shows under the pointer. */
@Composable
fun SeekLine(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, onSeek: (Long) -> Unit) {
    val frac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    LineSlider(frac, {}, modifier, color, onDone = { if (durationMs > 0) onSeek((it * durationMs).toLong()) })
}

/** The window, and when it last had focus — to tell «in front» from «somewhere behind». */
private class WindowRef {
    var window: java.awt.Window? = null
    @Volatile private var lostFocusAt = 0L

    fun attach(w: java.awt.Window) {
        window = w
        w.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
            override fun windowGainedFocus(e: java.awt.event.WindowEvent) = Unit
            override fun windowLostFocus(e: java.awt.event.WindowEvent) { lostFocusAt = System.currentTimeMillis() }
        })
    }

    fun wasFocusedJustNow(): Boolean = window?.isFocused == true || System.currentTimeMillis() - lostFocusAt < 600
}
