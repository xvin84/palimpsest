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
import dev.xvin.palimpsest.desktop.data.DesktopStyle
import dev.xvin.palimpsest.desktop.data.DesktopTheme
import dev.xvin.palimpsest.desktop.ui.TrackDialog
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
 *   [SHOTS_ONLY=library,now] ./gradlew :desktop:shots -Pout=/some/dir [-Pwidth=1280 -Pheight=820]
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/shots" }).apply { mkdirs() }
    val w = args.getOrNull(1)?.toInt() ?: 1280
    val h = args.getOrNull(2)?.toInt() ?: 820
    // SHOTS_LANG=ru|en overrides the language from the settings (for the README in both languages).
    val graph = Graph().also { language = System.getenv("SHOTS_LANG") ?: it.settings.value.language }
    val current = graph.player.state.value.current
    // Stand still part way into the track (SHOTS_POS, ms), so the lyrics have a sung line (the player reads the engine's position).
    @Suppress("UNCHECKED_CAST")
    (graph.player.engine.state as kotlinx.coroutines.flow.MutableStateFlow<dev.xvin.palimpsest.desktop.playback.AudioEngine.State>).value =
        dev.xvin.palimpsest.desktop.playback.AudioEngine.State(positionMs = System.getenv("SHOTS_POS")?.toLongOrNull() ?: 40_000)
    val colors = runBlocking { current?.let { CoverColors.of(graph, it) } } ?: (Vermilion to Color(0xFF2A2A30))
    val artist = current?.artists?.firstOrNull()
    class Shot(val name: String, val theme: DesktopTheme = DesktopTheme.DARK, val style: DesktopStyle? = null, val setup: (Nav) -> Unit)
    val shots = buildList {
        add(Shot("library") { it.root(Screen.Library) })
        add(Shot("now") { it.nowPlaying = true })
        add(Shot("now-cover") { it.nowPlaying = true; it.bigLyrics = false })
        add(Shot("lyrics-panel") { it.rightPanel = "lyrics" })
        add(Shot("queue-panel") { it.rightPanel = "queue" })
        add(Shot("search") { it.root(Screen.Search) })
        add(Shot("services") { it.root(Screen.Services) })
        add(Shot("liked") { it.root(Screen.Liked) })
        add(Shot("history") { it.root(Screen.History) })
        add(Shot("flac") { it.go(Screen.Flac) })
        if (artist != null) add(Shot("artist") { it.go(Screen.Artist(artist)) })
        if (current?.album != null) add(Shot("album") { it.go(Screen.LocalAlbum(current.album!!, current.albumArtist ?: current.artist)) })
        add(Shot("settings") { it.root(Screen.Settings) })
        if (current != null) {
            add(Shot("menu") { it.menu = listOf(current) })
            if (current.path != null) {
                add(Shot("tags") { it.dialog = TrackDialog.EditTags(listOf(current)) })
                add(Shot("delete") { it.dialog = TrackDialog.Delete(listOf(current)) })
                add(Shot("move") { it.dialog = TrackDialog.Move(listOf(current)) })
            }
            add(Shot("video") { it.dialog = TrackDialog.Video(current) })
        }
        add(Shot("wave") { it.waveTuning = true })
        add(Shot("library-light", DesktopTheme.LIGHT) { it.root(Screen.Library) })
        add(Shot("now-light", DesktopTheme.LIGHT) { it.nowPlaying = true })
        add(Shot("library-lane", style = DesktopStyle.LANE) { it.root(Screen.Library) })
        add(Shot("now-lane", style = DesktopStyle.LANE) { it.nowPlaying = true })
        add(Shot("now-cover-lane", style = DesktopStyle.LANE) { it.nowPlaying = true; it.bigLyrics = false })
        add(Shot("artist-lane", style = DesktopStyle.LANE) { if (artist != null) it.go(Screen.Artist(artist)) })
        add(Shot("settings-lane", style = DesktopStyle.LANE) { it.root(Screen.Settings) })
        add(Shot("library-lane-light", DesktopTheme.LIGHT, DesktopStyle.LANE) { it.root(Screen.Library) })
    }
    val only = System.getenv("SHOTS_ONLY")?.split(',')
    for (shot in shots) {
        val name = shot.name
        if (only != null && name !in only) continue
        val nav = Nav().also(shot.setup)
        val scene = ImageComposeScene(w, h, Density(1f)) {
            setSingletonImageLoaderFactory { ctx ->
                ImageLoader.Builder(ctx)
                    .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
                    .diskCache { DiskCache.Builder().directory(File(Paths.cache, "coil").toOkioPath()).maxSizeBytes(256L shl 20).build() }
                    .build()
            }
            PalimpsestDesktopTheme(shot.style ?: graph.settings.value.style, shot.theme, colors.first, colors.second) {
                CompositionLocalProvider(LocalGraph provides graph, LocalNav provides nav) { Shell() }
            }
        }
        // The pointer over a row, so the menu opens there and the row shows its hover.
        scene.render(0)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.geometry.Offset(w * 0.45f, h * 0.36f))
        // Real time for covers, lyrics and service pages to arrive; frame time for the animations to settle.
        var t = 0L
        repeat(40) { scene.render(t); t += 100_000_000; Thread.sleep(120) }
        File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("shot: $name")
    }
    kotlin.system.exitProcess(0)
}
