package dev.xvin.palimpsest.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import dev.xvin.palimpsest.AppGraph
import dev.xvin.palimpsest.MainActivity
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.ui.components.CrashDialog
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.TrackActionsSheet
import dev.xvin.palimpsest.ui.screens.EqualizerScreen
import dev.xvin.palimpsest.ui.screens.LibraryScreen
import dev.xvin.palimpsest.ui.screens.MiniPlayer
import dev.xvin.palimpsest.ui.screens.NowPlayingScreen
import dev.xvin.palimpsest.ui.screens.OnboardingScreen
import dev.xvin.palimpsest.ui.screens.SearchScreen
import dev.xvin.palimpsest.ui.screens.SettingsScreen
import dev.xvin.palimpsest.ui.screens.SourcesScreen
import dev.xvin.palimpsest.ui.screens.StatsScreen
import dev.xvin.palimpsest.ui.screens.TrackListKind
import dev.xvin.palimpsest.ui.screens.TrackListScreen
import dev.xvin.palimpsest.ui.screens.TrackScreen
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.PalimpsestTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable object LibraryRoute
@Serializable object SearchRoute
@Serializable object SourcesRoute
@Serializable object StatsRoute
@Serializable object SettingsRoute
@Serializable object EqualizerRoute
@Serializable data class ListRoute(val kind: TrackListKind, val key: String, val extra: String? = null)
@Serializable data class TrackRoute(val trackId: Long, val tab: Int = 0)
@Serializable data class ArtistRoute(val name: String, val remoteId: String? = null)
@Serializable data class RemoteAlbumRoute(val albumId: String)
@Serializable data class SimilarRoute(val trackRemoteId: String, val title: String)
@Serializable object ErrorsRoute

/** Navigation actions any screen may trigger. */
interface AppNav {
    fun openList(kind: TrackListKind, key: String, extra: String? = null)
    fun openTrack(trackId: Long, tab: Int = 0)
    fun openEqualizer()
    fun openSettings()
    fun openPlayer()
    fun openArtist(name: String, remoteId: String? = null)
    fun openRemoteAlbum(albumId: String)
    fun openSimilar(trackRemoteId: String, title: String)
    fun openErrors()
    fun showActions(track: TrackEntity)
    fun toast(message: String)
    /** Logs [t] and shows it explained, with hints and copyable details. */
    fun error(t: Throwable, context: String)
    fun showError(info: dev.xvin.palimpsest.errors.ErrorInfo)
    fun back()
}

val LocalNav = staticCompositionLocalOf<AppNav> { error("no nav") }

@Composable
fun AppRoot(graph: AppGraph, activity: MainActivity) {
    val settings by graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val player by graph.player.state.collectAsStateWithLifecycle()
    var accent by remember { mutableStateOf(Ink.Vermilion) }

    // Pull an accent colour out of the current cover.
    val ctx = LocalContext.current
    val artKey = player.nowPlaying?.artworkUri ?: player.nowPlaying?.artworkData
    LaunchedEffect(artKey, settings?.dynamicColor) {
        if (settings?.dynamicColor == false || artKey == null) { accent = Ink.Vermilion; return@LaunchedEffect }
        accent = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(ctx).data(artKey).size(128).allowHardware(false).build()
                val bmp = SingletonImageLoader.get(ctx).execute(req).image?.toBitmap() ?: return@runCatching null
                val p = Palette.from(bmp).generate()
                val sw = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch
                sw?.rgb?.let { Color(it) }
            }.getOrNull()
        } ?: Ink.Vermilion
    }

    PalimpsestTheme(accent) {
        CompositionLocalProvider(LocalGraph provides graph) {
            val hasAccess by activity.hasStorageAccess
            if (!hasAccess) {
                OnboardingScreen(
                    onFullAccess = activity::requestAllFilesAccess,
                    onReadOnly = activity::requestReadOnlyAccess,
                )
            } else {
                MainScaffold(graph, activity)
            }
        }
    }
}

@Composable
private fun MainScaffold(graph: AppGraph, activity: MainActivity) {
    val nav = rememberNavController()
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<TrackEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    val player by graph.player.state.collectAsStateWithLifecycle()
    val openRequests by activity.openPlayerRequests

    var errorShown by remember { mutableStateOf<dev.xvin.palimpsest.errors.ErrorInfo?>(null) }

    LaunchedEffect(openRequests) { if (openRequests > 0) playerOpen = true }
    LaunchedEffect(toastMessage) { toastMessage?.let { snackbar.showSnackbar(it); toastMessage = null } }
    // Background errors (playback, downloads, sync): a short snackbar, details one tap away.
    LaunchedEffect(Unit) {
        graph.errors.shown.collect { info ->
            val r = snackbar.showSnackbar(
                message = info.title + (info.context?.let { " · $it" } ?: ""),
                actionLabel = "Подробнее",
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) errorShown = info
        }
    }
    LaunchedEffect(Unit) { graph.scanner.scan() }
    CrashDialog()

    val appNav = remember(nav) {
        object : AppNav {
            override fun openList(kind: TrackListKind, key: String, extra: String?) { playerOpen = false; nav.navigate(ListRoute(kind, key, extra)) }
            override fun openTrack(trackId: Long, tab: Int) { playerOpen = false; nav.navigate(TrackRoute(trackId, tab)) }
            override fun openEqualizer() { playerOpen = false; nav.navigate(EqualizerRoute) }
            override fun openSettings() { nav.navigate(SettingsRoute) }
            override fun openPlayer() { playerOpen = true }
            override fun openArtist(name: String, remoteId: String?) { playerOpen = false; nav.navigate(ArtistRoute(name, remoteId)) }
            override fun openRemoteAlbum(albumId: String) { playerOpen = false; nav.navigate(RemoteAlbumRoute(albumId)) }
            override fun openSimilar(trackRemoteId: String, title: String) { playerOpen = false; nav.navigate(SimilarRoute(trackRemoteId, title)) }
            override fun openErrors() { nav.navigate(ErrorsRoute) }
            override fun showActions(track: TrackEntity) { actionsFor = track }
            override fun toast(message: String) { toastMessage = message }
            override fun error(t: Throwable, context: String) { errorShown = graph.errors.report(t, context, show = false) }
            override fun showError(info: dev.xvin.palimpsest.errors.ErrorInfo) { errorShown = info }
            override fun back() { if (playerOpen) playerOpen = false else nav.popBackStack() }
        }
    }

    CompositionLocalProvider(LocalNav provides appNav) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                // Screens draw edge-to-edge and pad for the status bar themselves.
                contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    Column {
                        AnimatedVisibility(
                            visible = player.nowPlaying != null,
                            enter = slideInVertically(tween(300)) { it } + fadeIn(tween(300)),
                            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(220)),
                        ) { MiniPlayer(onOpen = { playerOpen = true }) }
                        BottomBar(nav)
                    }
                },
            ) { padding ->
                NavHost(
                    nav, startDestination = LibraryRoute, modifier = Modifier.padding(padding),
                    // Opaque screens stack like cards: a new one slides over from the right, closing slides
                    // it back off and reveals the previous one (which moves a little for depth).
                    enterTransition = { androidx.compose.animation.slideInHorizontally(tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it } },
                    exitTransition = { androidx.compose.animation.slideOutHorizontally(tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(300), targetAlpha = 0.6f) },
                    popEnterTransition = { androidx.compose.animation.slideInHorizontally(tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(300), initialAlpha = 0.6f) },
                    popExitTransition = { androidx.compose.animation.slideOutHorizontally(tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it } },
                ) {
                    composable<LibraryRoute> { Screen { LibraryScreen() } }
                    composable<SearchRoute> { Screen { SearchScreen() } }
                    composable<SourcesRoute> { Screen { SourcesScreen() } }
                    composable<StatsRoute> { Screen { StatsScreen() } }
                    composable<SettingsRoute> { Screen { SettingsScreen() } }
                    composable<EqualizerRoute> { Screen { EqualizerScreen() } }
                    composable<ListRoute> { e -> Screen { val r = e.toRoute<ListRoute>(); TrackListScreen(r.kind, r.key, r.extra) } }
                    composable<TrackRoute> { e -> Screen { val r = e.toRoute<TrackRoute>(); TrackScreen(r.trackId, r.tab) } }
                    composable<ArtistRoute> { e -> Screen { val r = e.toRoute<ArtistRoute>(); dev.xvin.palimpsest.ui.screens.ArtistScreen(r.name, r.remoteId) } }
                    composable<RemoteAlbumRoute> { e -> Screen { dev.xvin.palimpsest.ui.screens.RemoteAlbumScreen(e.toRoute<RemoteAlbumRoute>().albumId) } }
                    composable<SimilarRoute> { e -> Screen { val r = e.toRoute<SimilarRoute>(); dev.xvin.palimpsest.ui.screens.SimilarScreen(r.trackRemoteId, r.title) } }
                    composable<ErrorsRoute> { Screen { dev.xvin.palimpsest.ui.screens.ErrorsScreen() } }
                }
            }

            AnimatedVisibility(
                visible = playerOpen && player.nowPlaying != null,
                enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
            ) {
                BackHandler { playerOpen = false }
                NowPlayingScreen(onClose = { playerOpen = false })
            }

            actionsFor?.let { t -> TrackActionsSheet(t, onDismiss = { actionsFor = null }) }
            errorShown?.let { info -> dev.xvin.palimpsest.ui.components.ErrorSheet(info, onDismiss = { errorShown = null }) }
        }
    }
}

private data class Tab(val route: Any, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun BottomBar(nav: androidx.navigation.NavHostController) {
    val tabs = listOf(
        Tab(LibraryRoute, "Медиатека", Icons.Rounded.LibraryMusic),
        Tab(SearchRoute, "Поиск", Icons.Rounded.Search),
        Tab(SourcesRoute, "Сервисы", Icons.Rounded.Hub),
        Tab(StatsRoute, "История", Icons.Rounded.Insights),
    )
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    NavigationBar(containerColor = Ink.Paper2, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
        tabs.forEach { tab ->
            val selected = dest?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, null) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = Ink.Muted,
                    unselectedTextColor = Ink.Muted,
                ),
            )
        }
    }
}


/** Opaque backdrop for a destination, so screens never show through each other mid-transition. */
@Composable
private fun Screen(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink.Paper)) { content() }
}
