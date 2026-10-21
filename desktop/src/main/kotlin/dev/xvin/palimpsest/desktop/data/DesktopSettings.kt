package dev.xvin.palimpsest.desktop.data

import dev.xvin.palimpsest.core.model.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

enum class DesktopStyle { PALIMPSEST, LANE }
enum class DesktopTheme { DARK, AMOLED, LIGHT }
enum class Rg { OFF, TRACK, ALBUM }

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
