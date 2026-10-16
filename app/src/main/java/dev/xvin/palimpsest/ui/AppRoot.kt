package dev.xvin.palimpsest.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
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
import dev.xvin.palimpsest.data.settings.ThemeMode
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr

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
@Serializable object ReleasesRoute
@Serializable object DuplicatesRoute
@Serializable object WrappedRoute

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
    fun openReleases()
    fun openDuplicates()
    fun openWrapped()
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

    val theme = settings?.theme ?: ThemeMode.DARK
    PalimpsestTheme(accent, theme) {
        // Status/navigation bar icons must be dark on the light theme.
        val light = Ink.isLight
        LaunchedEffect(light) {
            val style = if (light) androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            else androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }
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
    val albumRequest by activity.openAlbumRequest
    LaunchedEffect(albumRequest) {
        albumRequest?.let { playerOpen = false; nav.navigate(RemoteAlbumRoute(it)); activity.openAlbumRequest.value = null }
    }
    LaunchedEffect(toastMessage) { toastMessage?.let { snackbar.showSnackbar(it); toastMessage = null } }
    // Background errors (playback, downloads, sync): a short snackbar, details one tap away.
    LaunchedEffect(Unit) {
        graph.errors.shown.collect { info ->
            val r = snackbar.showSnackbar(
                message = info.title + (info.context?.let { " · $it" } ?: ""),
                actionLabel = tr(R.string.details),
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
            override fun openReleases() { nav.navigate(ReleasesRoute) }
            override fun openDuplicates() { nav.navigate(DuplicatesRoute) }
            override fun openWrapped() { nav.navigate(WrappedRoute) }
            override fun showActions(track: TrackEntity) { actionsFor = track }
            override fun toast(message: String) { toastMessage = message }
            override fun error(t: Throwable, context: String) { graph.errors.report(t, context, show = false)?.let { errorShown = it } }
            override fun showError(info: dev.xvin.palimpsest.errors.ErrorInfo) { errorShown = info }
            override fun back() { if (playerOpen) playerOpen = false else nav.popBackStack() }
        }
    }

    CompositionLocalProvider(LocalNav provides appNav) {
        // The library blurs while the player slides over it; once the player covers it the blur is
        // dropped again, so nothing gets re-blurred every frame behind a full-screen player.
        val behindBlur = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(playerOpen) {
            if (playerOpen) {
                behindBlur.animateTo(1f, tween(Motion.ENTER + 40))
                behindBlur.snapTo(0f)
            } else {
                behindBlur.snapTo(1f)
                behindBlur.animateTo(0f, tween(Motion.EXIT + 60))
            }
        }
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                modifier = if (behindBlur.value > 0.01f) Modifier.blur(Motion.BLUR * behindBlur.value) else Modifier,
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
                    // Opaque screens stack like cards: a new one rises a little and comes into focus while the
                    // one underneath blurs and dims (see [Screen]); closing drops the card down and away.
                    // Bottom-bar tabs are siblings, not a stack — they just cross-fade.
                    enterTransition = {
                        if (isTabSwitch()) fadeIn(tween(Motion.TAB))
                        else slideInVertically(tween(Motion.ENTER, easing = Motion.Decelerate)) { it / 6 } + fadeIn(tween(Motion.ENTER))
                    },
                    exitTransition = { if (isTabSwitch()) fadeOut(tween(Motion.TAB)) else ExitTransition.KeepUntilTransitionsFinished },
                    popEnterTransition = { if (isTabSwitch()) fadeIn(tween(Motion.TAB)) else EnterTransition.None },
                    popExitTransition = {
                        if (isTabSwitch()) fadeOut(tween(Motion.TAB))
                        else slideOutVertically(tween(Motion.EXIT, easing = Motion.Accelerate)) { it } + fadeOut(tween(Motion.EXIT, delayMillis = Motion.EXIT / 2))
                    },
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
                    composable<ReleasesRoute> { Screen { dev.xvin.palimpsest.ui.screens.ReleasesScreen() } }
                    composable<DuplicatesRoute> { Screen { dev.xvin.palimpsest.ui.screens.DuplicatesScreen() } }
                    composable<WrappedRoute> { Screen { dev.xvin.palimpsest.ui.screens.WrappedScreen() } }
                }
            }

            AnimatedVisibility(
                visible = playerOpen && player.nowPlaying != null,
                enter = slideInVertically(tween(Motion.ENTER + 40, easing = Motion.Decelerate)) { it },
                exit = slideOutVertically(tween(Motion.EXIT, easing = Motion.Accelerate)) { it },
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
        Tab(LibraryRoute, tr(R.string.tab_library), Icons.Rounded.LibraryMusic),
        Tab(SearchRoute, tr(R.string.tab_search), Icons.Rounded.Search),
        Tab(SourcesRoute, tr(R.string.tab_services), Icons.Rounded.Hub),
        Tab(StatsRoute, tr(R.string.tab_history), Icons.Rounded.Insights),
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


/** Durations and curves shared by every screen transition. */
private object Motion {
    const val ENTER = 230
    const val EXIT = 200
    const val TAB = 140
    val BLUR = 18.dp
    val Decelerate = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Accelerate = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

private val tabRoutes = listOf(LibraryRoute::class, SearchRoute::class, SourcesRoute::class, StatsRoute::class)

private fun androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.isTabSwitch() =
    tabRoutes.any { initialState.destination.hasRoute(it) } && tabRoutes.any { targetState.destination.hasRoute(it) }

/**
 * Opaque backdrop for a destination, so screens never show through each other mid-transition.
 * Whenever the screen isn't the settled one (being covered, or just arriving) it is blurred and
 * dimmed, which gives the card stack its depth.
 */
@Composable
private fun androidx.compose.animation.AnimatedContentScope.Screen(content: @Composable () -> Unit) {
    val away by transition.animateFloat(transitionSpec = { tween(Motion.ENTER) }, label = "away") {
        if (it == androidx.compose.animation.EnterExitState.Visible) 0f else 1f
    }
    Box(Modifier.fillMaxSize().then(if (away > 0.01f) Modifier.blur(Motion.BLUR * away) else Modifier).background(Ink.Paper)) {
        content()
        if (away > 0.01f) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f * away)))
    }
}
