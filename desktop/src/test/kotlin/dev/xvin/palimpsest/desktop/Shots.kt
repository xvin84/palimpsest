package dev.xvin.palimpsest.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.xvin.palimpsest.desktop.data.DesktopTheme
import dev.xvin.palimpsest.desktop.data.Graph
import dev.xvin.palimpsest.desktop.data.Paths
import dev.xvin.palimpsest.desktop.ui.CoverColors
import dev.xvin.palimpsest.desktop.ui.LocalGraph
import dev.xvin.palimpsest.desktop.ui.LocalNav
import dev.xvin.palimpsest.desktop.ui.Nav
import dev.xvin.palimpsest.desktop.ui.PalimpsestDesktopTheme
import dev.xvin.palimpsest.desktop.ui.Screen
import dev.xvin.palimpsest.desktop.ui.Shell
import dev.xvin.palimpsest.desktop.ui.Vermilion
import dev.xvin.palimpsest.desktop.ui.language
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Screenshots of the main screens without a window, drawn from the library and settings in [Paths.config]
 * (point XDG_CONFIG_HOME / XDG_CACHE_HOME at a copy to keep the real ones untouched). Nothing plays.
 *   ./gradlew :desktop:shots -Pout=/some/dir [-Pwidth=1280 -Pheight=820]
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/shots" }).apply { mkdirs() }
    val w = args.getOrNull(1)?.toInt() ?: 1280
    val h = args.getOrNull(2)?.toInt() ?: 820
    val graph = Graph().also { language = it.settings.value.language }
    val current = graph.player.state.value.current
    // Stand still part way into the track (SHOTS_POS, ms), so the lyrics have a sung line (the player reads the engine's position).
    @Suppress("UNCHECKED_CAST")
    (graph.player.engine.state as kotlinx.coroutines.flow.MutableStateFlow<dev.xvin.palimpsest.desktop.playback.AudioEngine.State>).value =
        dev.xvin.palimpsest.desktop.playback.AudioEngine.State(positionMs = System.getenv("SHOTS_POS")?.toLongOrNull() ?: 40_000)
    val colors = runBlocking { current?.let { CoverColors.of(graph, it) } } ?: (Vermilion to Color(0xFF2A2A30))
    val artist = current?.artists?.firstOrNull()
    val shots = buildList<Triple<String, DesktopTheme, (Nav) -> Unit>> {
        add(Triple("library", DesktopTheme.DARK) { it.root(Screen.Library) })
        add(Triple("now", DesktopTheme.DARK) { it.nowPlaying = true })
        add(Triple("now-cover", DesktopTheme.DARK) { it.nowPlaying = true; it.bigLyrics = false })
        add(Triple("lyrics-panel", DesktopTheme.DARK) { it.rightPanel = "lyrics" })
        add(Triple("queue-panel", DesktopTheme.DARK) { it.rightPanel = "queue" })
        add(Triple("search", DesktopTheme.DARK) { it.root(Screen.Search) })
        add(Triple("services", DesktopTheme.DARK) { it.root(Screen.Services) })
        add(Triple("liked", DesktopTheme.DARK) { it.root(Screen.Liked) })
        if (artist != null) add(Triple("artist", DesktopTheme.DARK) { it.go(Screen.Artist(artist)) })
        if (current?.album != null) add(Triple("album", DesktopTheme.DARK) { it.go(Screen.LocalAlbum(current.album!!, current.albumArtist ?: current.artist)) })
        add(Triple("settings", DesktopTheme.DARK) { it.root(Screen.Settings) })
        add(Triple("library-light", DesktopTheme.LIGHT) { it.root(Screen.Library) })
        add(Triple("now-light", DesktopTheme.LIGHT) { it.nowPlaying = true })
    }
    for ((name, theme, setup) in shots) {
        val nav = Nav().also(setup)
        val scene = ImageComposeScene(w, h, Density(1f)) {
            setSingletonImageLoaderFactory { ctx ->
                ImageLoader.Builder(ctx)
                    .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
                    .diskCache { DiskCache.Builder().directory(File(Paths.cache, "coil").toOkioPath()).maxSizeBytes(256L shl 20).build() }
                    .build()
            }
            PalimpsestDesktopTheme(graph.settings.value.style, theme, colors.first, colors.second) {
                CompositionLocalProvider(LocalGraph provides graph, LocalNav provides nav) { Shell() }
            }
        }
        // Real time for covers, lyrics and service pages to arrive; frame time for the animations to settle.
        var t = 0L
        repeat(40) { scene.render(t); t += 100_000_000; Thread.sleep(120) }
        File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("shot: $name")
    }
    kotlin.system.exitProcess(0)
}
