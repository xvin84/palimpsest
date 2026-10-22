package dev.xvin.palimpsest.desktop.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.isTraySupported
import dev.xvin.palimpsest.desktop.data.Graph
import dev.xvin.palimpsest.desktop.data.Paths
import dev.xvin.palimpsest.desktop.data.TrayStyle
import dev.xvin.palimpsest.desktop.ui.CoverColors
import dev.xvin.palimpsest.desktop.ui.Vermilion
import dev.xvin.palimpsest.desktop.ui.likeRemote
import dev.xvin.palimpsest.desktop.ui.readable
import dev.xvin.palimpsest.desktop.ui.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage

/**
 * The tray icon on every system: StatusNotifierItem over D-Bus on Linux (what KDE, GNOME with the
 * AppIndicator extension, Waybar, noctalia and most panels show), the AWT tray on Windows and macOS
 * and on Linux desktops that only have the old XEmbed tray. [kind] says which one is up; NONE means
 * there's no tray here, so closing the window quits as before.
 */
class TrayController(private val graph: Graph, val showOrHide: () -> Unit, val quit: () -> Unit) {
    enum class Kind { NONE, SNI, AWT }

    /** The icon as it is now, in the two sizes trays pick from. */
    class Icon(val look: TrayArt.Look, val small: BufferedImage, val large: BufferedImage)

    /** One line of the tray's menu; [action] null with [enabled] false is a caption, [separator] a rule. */
    class MenuLine(val id: Int, val label: String = "", val enabled: Boolean = true, val separator: Boolean = false, val action: (() -> Unit)? = null)

    private val player get() = graph.player
    private val _kind = MutableStateFlow(Kind.NONE)
    val kind: StateFlow<Kind> = _kind
    private var colors = Vermilion.toArgb() to Vermilion.toArgb()
    private val _icon = MutableStateFlow(render(look(TrayArt.REST, 0f)))
    val icon: StateFlow<Icon> = _icon
    private var sni: SniTray? = null

    fun start() {
        graph.scope.launch { graph.settings.state.map { it.trayIcon }.distinctUntilChanged().collect { on -> if (on) up() else down() } }
        graph.scope.launch { animate() }
        // The cover's colours for the dot, the bars and the record's label.
        graph.scope.launch {
            combine(player.state.map { it.current }.distinctUntilChanged { a, b -> a?.id == b?.id }, graph.settings.state.map { it.dynamicColor }.distinctUntilChanged()) { t, dynamic -> t to dynamic }
                .collect { (t, dynamic) ->
                    val c = if (t != null && dynamic) runCatching { CoverColors.of(graph, t) }.getOrNull() else null
                    colors = if (c == null) Vermilion.toArgb() to Vermilion.toArgb() else readable(c.first, light = false).toArgb() to c.first.toArgb()
                }
        }
        // The menu and tooltip follow the track, play state and like.
        graph.scope.launch {
            combine(player.state.map { it.current?.id to it.playing }.distinctUntilChanged(), graph.library.liked) { a, liked -> a to (a.first in liked) }
                .distinctUntilChanged().collect { sni?.menuChanged() }
        }
    }

    fun stop() = down()

    private fun up() {
        if (_kind.value != Kind.NONE) return
        if (!Paths.isWindows && !Paths.isMac) {
            val s = SniTray(this)
            if (s.start()) { sni = s; _kind.value = Kind.SNI; return }
        }
        if (runCatching { isTraySupported }.getOrDefault(false)) _kind.value = Kind.AWT
    }

    private fun down() {
        sni?.stop(); sni = null
        _kind.value = Kind.NONE
    }

    private fun look(levels: List<Float>, phase: Float): TrayArt.Look {
        val st = player.state.value
        val style = graph.settings.value.trayStyle
        return TrayArt.Look(
            style, playing = st.playing && st.current != null, accent = colors.first, cover = colors.second,
            levels = if (style == TrayStyle.BARS) levels else TrayArt.REST,
            phase = if (style == TrayStyle.LETTER || style == TrayStyle.VINYL) phase else 0f,
        )
    }

    private fun render(look: TrayArt.Look) = Icon(look, TrayArt.draw(look, 32), TrayArt.draw(look, 64))

    /** Redraws the icon when what it shows changes: ten times a second while it plays, rarely otherwise. */
    private suspend fun animate() {
        var levels = TrayArt.REST
        var phase = 0f
        while (true) {
            val live = _kind.value != Kind.NONE && player.state.value.playing
            if (live) {
                phase = (phase + 0.025f) % 1f
                if (graph.settings.value.trayStyle == TrayStyle.BARS) {
                    val spectrum = player.engine.spectrum
                    val raw = synchronized(spectrum) { spectrum.compute().copyOf() }
                    // Lows, low mids, high mids, highs — stretched, since band levels mostly sit in the middle.
                    val groups = listOf(0 until 6, 6 until 16, 16 until 30, 30 until raw.size)
                    levels = groups.mapIndexed { i, r ->
                        val v = ((r.maxOf { raw[it] } - 0.3f) / 0.55f).coerceIn(0f, 1f)
                        levels[i] * 0.45f + v * 0.55f
                    }
                }
            } else levels = TrayArt.REST
            val look = look(levels.map { (it * 50).toInt() / 50f }, (phase * 100).toInt() / 100f)
            if (look != _icon.value.look) {
                _icon.value = render(look)
                sni?.iconChanged()
            }
            delay(if (live) 100 else 300)
        }
    }

    fun tooltip(): String = player.state.value.current?.let { "${it.artist} — ${it.title}" } ?: "Palimpsest"

    fun menu(): List<MenuLine> {
        val st = player.state.value
        val tr = st.current
        val liked = tr != null && tr.id in graph.library.liked.value
        val caption = tr?.let { "${it.artist} — ${it.title}" }?.let { if (it.length > 48) it.take(47) + "…" else it } ?: "Palimpsest"
        return listOf(
            MenuLine(1, caption, enabled = false),
            MenuLine(2, separator = true),
            MenuLine(3, if (st.playing) t("Пауза", "Pause") else t("Играть", "Play"), enabled = tr != null || graph.library.tracks.value.isNotEmpty()) { playPause() },
            MenuLine(4, t("Следующий трек", "Next track"), enabled = tr != null) { player.next() },
            MenuLine(5, t("Предыдущий трек", "Previous track"), enabled = tr != null) { player.previous() },
            MenuLine(6, if (liked) t("♥ Убрать из любимых", "♥ Remove from favourites") else t("♡ В любимые", "♡ Add to favourites"), enabled = tr != null) {
                if (tr != null) { graph.library.setLiked(tr.id, !liked); likeRemote(graph, tr, !liked) }
            },
            MenuLine(7, separator = true),
            MenuLine(8, t("Показать или спрятать окно", "Show or hide the window")) { showOrHide() },
            MenuLine(9, t("Выйти", "Quit")) { quit() },
        )
    }

    /** Pause or play; with nothing loaded yet, the library from the top (like «Play» on the library screen). */
    fun playPause() {
        if (player.state.value.current != null) player.toggle()
        else graph.library.tracks.value.takeIf { it.isNotEmpty() }?.let { player.play(it, 0) }
    }

    /** The mouse wheel over the icon (where the tray tells us): volume, 5 % a notch. */
    fun scroll(notches: Float) = graph.player.setVolume(graph.settings.value.volume + notches * 0.05f)
}

/** The AWT tray (Windows, macOS, XEmbed-only Linux), drawn by Compose while [TrayController.kind] is AWT. */
@Composable
fun ApplicationScope.AwtTray(c: TrayController, graph: Graph) {
    val kind by c.kind.collectAsState()
    if (kind != TrayController.Kind.AWT) return
    val icon by c.icon.collectAsState()
    // The menu and tooltip are rebuilt with the track, play state and likes.
    val st by graph.player.state.collectAsState()
    val liked by graph.library.liked.collectAsState()
    val painter = remember(icon) { BitmapPainter(icon.large.toComposeImageBitmap()) }
    val menu = remember(st.current?.id, st.playing, liked) { c.menu() }
    val tooltip = remember(st.current?.id) { c.tooltip() }
    Tray(icon = painter, tooltip = tooltip, onAction = c.showOrHide) {
        menu.forEach { line ->
            if (line.separator) Separator() else Item(line.label, enabled = line.enabled, onClick = { line.action?.invoke() })
        }
    }
}
