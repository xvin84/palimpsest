package dev.xvin.palimpsest.desktop.playback

import dev.xvin.palimpsest.desktop.data.Graph
import dev.xvin.palimpsest.desktop.data.Paths
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.io.File

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
}

/**
 * MPRIS on Linux: media keys, the panel's player widget, the lock screen and `playerctl` all
 * control Palimpsest. Elsewhere (or without a session bus) it quietly does nothing.
 */
class Mpris(private val graph: Graph, private val raise: () -> Unit, private val quit: () -> Unit) : MprisRoot, MprisPlayer, Properties {
    private var conn: DBusConnection? = null
    private val player get() = graph.player

    fun start() {
        if (Paths.isWindows || Paths.isMac) return
        val c = runCatching { DBusConnectionBuilder.forSessionBus().build() }.getOrNull() ?: return
        runCatching {
            c.requestBusName("org.mpris.MediaPlayer2.palimpsest")
            c.exportObject(PATH, this)
        }.onFailure { runCatching { c.close() }; return }
        conn = c
        // Tell the desktop when the track or play state changes.
        graph.scope.launch {
            player.state.map { Triple(it.current?.id, it.playing, it.index) }.distinctUntilChanged().collect {
                changed(PLAYER_IFACE, mapOf("Metadata" to Variant(metadata(), "a{sv}"), "PlaybackStatus" to Variant(status())))
            }
        }
    }

    fun stop() { runCatching { conn?.close() }; conn = null }

    private fun changed(iface: String, props: Map<String, Variant<*>>) {
        val c = conn ?: return
        runCatching { c.sendMessage(Properties.PropertiesChanged(PATH, iface, props, emptyList())) }
    }

    private fun status() = when {
        player.state.value.current == null -> "Stopped"
        player.state.value.playing -> "Playing"
        else -> "Paused"
    }

    private fun metadata(): Map<String, Variant<*>> {
        val t = player.state.value.current ?: return mapOf("mpris:trackid" to Variant(DBusPath(NO_TRACK)))
        val m = LinkedHashMap<String, Variant<*>>()
        m["mpris:trackid"] = Variant(DBusPath("/dev/xvin/palimpsest/track/" + t.id.hashCode().toUInt()))
        m["xesam:title"] = Variant(t.title)
        m["xesam:artist"] = Variant(t.artists.ifEmpty { listOf(t.artist) }, "as")
        t.album?.let { m["xesam:album"] = Variant(it) }
        if (t.durationMs > 0) m["mpris:length"] = Variant(t.durationMs * 1000)
        val art = kotlinx.coroutines.runBlocking { runCatching { graph.covers.model(t) }.getOrNull() }
        when (art) {
            is File -> m["mpris:artUrl"] = Variant("file://" + art.absolutePath)
            is String -> m["mpris:artUrl"] = Variant(art)
        }
        return m
    }

    // --- org.mpris.MediaPlayer2 ---
    override fun Raise() = raise()
    override fun Quit() = quit()

    // --- org.mpris.MediaPlayer2.Player ---
    override fun Next() = player.next()
    override fun Previous() = player.previous()
    override fun Pause() { if (player.state.value.playing) player.toggle() }
    override fun PlayPause() = player.toggle()
    override fun Stop() = Pause()
    override fun Play() { if (!player.state.value.playing) player.toggle() }
    override fun Seek(offset: Long) = player.seek((player.state.value.positionMs + offset / 1000).coerceAtLeast(0))
    override fun SetPosition(trackId: DBusPath, position: Long) = player.seek(position / 1000)
    override fun OpenUri(uri: String) = Unit

    // --- org.freedesktop.DBus.Properties ---
    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = (GetAll(interfaceName)[propertyName]?.value) as A

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        if (propertyName == "Volume") (value as? Double)?.let { graph.player.setVolume(it.toFloat()) }
    }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
        ROOT_IFACE -> mapOf(
            "CanQuit" to Variant(true), "CanRaise" to Variant(true), "HasTrackList" to Variant(false),
            "Identity" to Variant("Palimpsest"), "DesktopEntry" to Variant("palimpsest"),
            "SupportedUriSchemes" to Variant(emptyList<String>(), "as"), "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
        )
        PLAYER_IFACE -> mapOf(
            "PlaybackStatus" to Variant(status()),
            "LoopStatus" to Variant(when (player.state.value.repeat) { Repeat.OFF -> "None"; Repeat.ALL -> "Playlist"; Repeat.ONE -> "Track" }),
            "Rate" to Variant(1.0), "MinimumRate" to Variant(1.0), "MaximumRate" to Variant(1.0),
            "Shuffle" to Variant(player.state.value.shuffle),
            "Metadata" to Variant(metadata(), "a{sv}"),
            "Volume" to Variant(graph.settings.value.volume.toDouble()),
            "Position" to Variant(player.state.value.positionMs * 1000),
            "CanGoNext" to Variant(true), "CanGoPrevious" to Variant(true),
            "CanPlay" to Variant(player.state.value.current != null), "CanPause" to Variant(true),
            "CanSeek" to Variant(true), "CanControl" to Variant(true),
        )
        else -> emptyMap()
    }

    override fun getObjectPath(): String = PATH
    override fun isRemote(): Boolean = false

    private companion object {
        const val PATH = "/org/mpris/MediaPlayer2"
        const val ROOT_IFACE = "org.mpris.MediaPlayer2"
        const val PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"
        const val NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack"
    }
}
