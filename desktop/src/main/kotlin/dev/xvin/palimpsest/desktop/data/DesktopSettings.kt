package dev.xvin.palimpsest.desktop.data

import dev.xvin.palimpsest.core.model.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

enum class DesktopStyle { PALIMPSEST, LANE }
enum class DesktopTheme { DARK, AMOLED, LIGHT }
enum class Rg { OFF, TRACK, ALBUM }
/** How the tray icon looks: the letter with a dot, live bars, a spinning record, or one plain colour. */
enum class TrayStyle { LETTER, BARS, VINYL, MONO }

@Serializable
data class DesktopSettings(
    val musicRoots: List<String> = listOf(Paths.music.absolutePath),
    val downloadDir: String = java.io.File(Paths.music, "Palimpsest").absolutePath,
    val quality: Quality = Quality.LOSSLESS,
    val yandexToken: String? = null,
    val yandexRefresh: String? = null,
    val yandexLogin: String? = null,
    /** host:port, http:// or socks://; used for YouTube, Spotify, Bandcamp and cover databases only. */
    val proxy: String? = null,
    /** "ru", "en", or "" to follow the system. */
    val language: String = "",
    val style: DesktopStyle = DesktopStyle.PALIMPSEST,
    val theme: DesktopTheme = DesktopTheme.DARK,
    val dynamicColor: Boolean = true,
    val eqEnabled: Boolean = false,
    val eqProfile: String = "Flat",
    val replayGain: Rg = Rg.TRACK,
    val preampDb: Float = 0f,
    val volume: Float = 0.9f,
    val embedLyrics: Boolean = true,
    val keepVersions: Boolean = true,
    val lastQueue: List<String> = emptyList(),
    val lastIndex: Int = 0,
    val lastPositionMs: Long = 0,
    val recentSearches: List<String> = emptyList(),
    val sidebarWidth: Int = 230,
    /** An icon in the tray (the system's notification area or menu bar) while the app runs. */
    val trayIcon: Boolean = true,
    val trayStyle: TrayStyle = TrayStyle.LETTER,
    /** The icon moves while music plays; off, it only changes with play/pause and the track. */
    val trayAnimate: Boolean = true,
    /** Closing the window keeps the music playing in the tray (when there's a tray to come back from). */
    val closeToTray: Boolean = true,
    /** Phone sync over the local network (off until turned on). */
    val lanSync: Boolean = false,
    /** Paired phones: token → device name. */
    val lanTokens: Map<String, String> = emptyMap(),
)

/** Settings as a state flow; every change is saved right away. */
class SettingsStore {
    private val file = JsonFile(java.io.File(Paths.config, "settings.json"), DesktopSettings.serializer()) { DesktopSettings() }
    private val _state = MutableStateFlow(file.load())
    val state: StateFlow<DesktopSettings> = _state
    val value get() = _state.value

    fun update(block: (DesktopSettings) -> DesktopSettings) {
        _state.update(block)
        file.save(_state.value)
    }
}
