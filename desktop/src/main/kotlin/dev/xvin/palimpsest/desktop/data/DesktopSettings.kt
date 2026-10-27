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

/** «Моя волна» tuning, sent as extra seeds (as on the phone). "" = no preference. */
@Serializable
data class WavePrefs(val diversity: String = "", val mood: String = "", val language: String = "") {
    fun seeds(): List<String> = listOfNotNull(
        diversity.ifEmpty { null }?.let { "settingDiversity:$it" },
        mood.ifEmpty { null }?.let { "settingMoodEnergy:$it" },
        language.ifEmpty { null }?.let { "settingLanguage:$it" },
    )
}

@Serializable
data class DesktopSettings(
    val musicRoots: List<String> = listOf(Paths.music.absolutePath),
    val downloadDir: String = java.io.File(Paths.music, "Palimpsest").absolutePath,
    /** Where «Video from the track» saves; null — the system's videos folder, in «Palimpsest». */
    val videoDir: String? = null,
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
    /** Where lyrics come from first ("LRCLIB", "NetEase", "Genius", "Yandex Music"), or "" for any, timed first. */
    val lyricsSource: String = "",
    /** The sung line lights up word by word (karaoke); off: the whole line at once. */
    val lyricsKaraoke: Boolean = false,
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
    val wave: WavePrefs = WavePrefs(),
    /** The last «video from a track» choices: mode, aspect, quality, visualiser (enum names). */
    val renderChoice: List<String> = emptyList(),
    /** Keys the user set, by [HotAction] name (an empty list: no key); the rest keep their defaults. */
    val hotkeys: Map<String, List<String>> = emptyMap(),
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
