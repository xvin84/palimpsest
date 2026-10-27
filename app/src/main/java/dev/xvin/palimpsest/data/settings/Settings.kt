package dev.xvin.palimpsest.data.settings

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.xvin.palimpsest.core.dsp.EqProfile
import dev.xvin.palimpsest.core.model.Quality
import dev.xvin.palimpsest.core.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import java.io.File
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

enum class ReplayGainMode(private val labelRes: Int) { OFF(R.string.off_short), TRACK(R.string.rg_track), ALBUM(R.string.rg_album); val label: String get() = tr(labelRes) }

/** «Моя волна» tuning, sent as extra seeds. "" = no preference. */
data class WavePrefs(val diversity: String = "", val mood: String = "", val language: String = "") {
    fun seeds(): List<String> = listOfNotNull(
        diversity.ifEmpty { null }?.let { "settingDiversity:$it" },
        mood.ifEmpty { null }?.let { "settingMoodEnergy:$it" },
        language.ifEmpty { null }?.let { "settingLanguage:$it" },
    )
}

/** App colours: follow the system, warm dark, true-black for OLED, or light "paper". */
enum class ThemeMode(@androidx.annotation.StringRes val labelRes: Int) {
    SYSTEM(R.string.theme_system), DARK(R.string.theme_dark), AMOLED(R.string.theme_amoled), LIGHT(R.string.theme_light),
}

/**
 * The look of the whole app. Palimpsest: warm ink and paper, a serif, the cover's colour as an
 * accent. Lane: the cover takes over — full-bleed art, the background tinted by it, a bold sans
 * and white pill buttons. More styles can join later.
 */
enum class UiStyle(@androidx.annotation.StringRes val labelRes: Int, @androidx.annotation.StringRes val descRes: Int) {
    PALIMPSEST(R.string.style_palimpsest, R.string.style_palimpsest_desc),
    LANE(R.string.style_lane, R.string.style_lane_desc),
    GLASS(R.string.style_glass, R.string.style_glass_desc),
    VINYL(R.string.style_vinyl, R.string.style_vinyl_desc),
    MONO(R.string.style_mono, R.string.style_mono_desc),
}

/** Synced lyrics: the original, the original with a translation under each line, or only the translation. */
enum class LyricsTranslation { OFF, BELOW, ONLY }

enum class VisualizerMode(private val labelRes: Int) { BARS(R.string.viz_bars), MIRROR(R.string.viz_mirror), RING(R.string.viz_ring), OFF(R.string.off_short); val label: String get() = tr(labelRes) }

/** The library's last sort and filters. [sort] and [source] are enum names (null source = all). */
data class LibraryView(val sort: String? = null, val source: String? = null, val onlyLiked: Boolean = false, val onlyOffline: Boolean = false)

@Serializable
data class SavedQueue(val trackIds: List<Long> = emptyList(), val index: Int = 0, val positionMs: Long = 0)

data class AppSettings(
    val musicRoots: List<String>,
    val downloadDir: String,
    val quality: Quality,
    val replayGain: ReplayGainMode,
    val replayGainPreampDb: Float,
    val eqEnabled: Boolean,
    val eqProfile: EqProfile,
    val customEqProfiles: List<EqProfile>,
    val visualizer: VisualizerMode,
    val dynamicColor: Boolean,
    val theme: ThemeMode,
    val yandexToken: String?,
    val yandexRefreshToken: String?,
    val yandexLogin: String?,
    val proxy: String?,
    val keepVersions: Boolean,
    val lastScanAt: Long,
    val autoSync: Boolean,
    /** Look for new app versions on GitHub once a day. */
    val checkUpdates: Boolean,
    val lyricsTranslation: LyricsTranslation,
    /** Last «Make a video» choices (enum names), so the next render starts from them. */
    val renderMode: String?,
    val renderAspect: String?,
    val renderQuality: String?,
    val autoDownloadLikes: Boolean,
    /** Output device key → EQ profile name. */
    val deviceEq: Map<String, String>,
    val listenBrainzToken: String?,
    val listenBrainzUser: String?,
    val wave: WavePrefs,
    /** Fade length at track changes, seconds (0 = off). */
    val fadeSec: Int,
    /** Size limit of the streaming cache, MB; takes effect on the next app start. */
    val streamCacheMb: Int,
    /** Library «Tracks» view: sort mode and filters (enum/service names), kept between launches. */
    val libraryView: LibraryView,
    /** Found or chosen lyrics are written into the file's tag right away. */
    val embedLyrics: Boolean,
    /** The sung line fills word by word (karaoke) when the lyrics have word timings; off: the whole line at once. */
    val lyricsKaraoke: Boolean,
    /** Deleted files go to the trash first (else they're gone at once). */
    val trashEnabled: Boolean,
    /** Days a file stays in the trash (0 = until emptied by hand). */
    val trashDays: Int,
    /** Trash size limit, MB: the oldest files go first when it's exceeded (0 = no limit). */
    val trashMaxMb: Int,
    /** Storage budget for the library, GB (0 = off): beyond it, rarely played tracks become AAC. */
    val budgetGb: Int,
    /** Keep to the budget by itself, once a week while charging. */
    val budgetAuto: Boolean,
    /** Last search queries, newest first. */
    val recentSearches: List<String>,
    val uiStyle: UiStyle,
) {
    /** Profile for [deviceKey]: its own if one is assigned, otherwise the global one. */
    fun eqFor(deviceKey: String): EqProfile {
        val name = deviceEq[deviceKey] ?: return eqProfile
        return (customEqProfiles + EqProfile.PRESETS).firstOrNull { it.name == EqProfile.canonicalName(name) } ?: eqProfile
    }
}

class Settings(private val context: Context, scope: CoroutineScope) {
    private object K {
        val roots = stringSetPreferencesKey("music_roots")
        val downloadDir = stringPreferencesKey("download_dir")
        val quality = stringPreferencesKey("quality")
        val rg = stringPreferencesKey("replay_gain")
        val rgPreamp = floatPreferencesKey("replay_gain_preamp")
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqProfile = stringPreferencesKey("eq_profile")
        val eqCustom = stringPreferencesKey("eq_custom")
        val visualizer = stringPreferencesKey("visualizer")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val theme = stringPreferencesKey("theme")
        val yToken = stringPreferencesKey("yandex_token")
        val yRefresh = stringPreferencesKey("yandex_refresh")
        val yLogin = stringPreferencesKey("yandex_login")
        val proxy = stringPreferencesKey("proxy")
        val keepVersions = booleanPreferencesKey("keep_versions")
        val queue = stringPreferencesKey("saved_queue")
        val lastScan = longPreferencesKey("last_scan")
        val autoSync = booleanPreferencesKey("auto_sync")
        val checkUpdates = booleanPreferencesKey("check_updates")
        val lyricsTranslation = stringPreferencesKey("lyrics_translation")
        val renderMode = stringPreferencesKey("render_mode")
        val renderAspect = stringPreferencesKey("render_aspect")
        val renderQuality = stringPreferencesKey("render_quality")
        val autoDownloadLikes = booleanPreferencesKey("auto_download_likes")
        val deviceEq = stringPreferencesKey("device_eq")
        val lbToken = stringPreferencesKey("listenbrainz_token")
        val lbUser = stringPreferencesKey("listenbrainz_user")
        val waveDiversity = stringPreferencesKey("wave_diversity")
        val waveMood = stringPreferencesKey("wave_mood")
        val waveLanguage = stringPreferencesKey("wave_language")
        val fadeSec = androidx.datastore.preferences.core.intPreferencesKey("fade_sec")
        val streamCacheMb = androidx.datastore.preferences.core.intPreferencesKey("stream_cache_mb")
        val embedLyrics = booleanPreferencesKey("embed_lyrics")
        val lyricsKaraoke = booleanPreferencesKey("lyrics_karaoke")
        val trashEnabled = booleanPreferencesKey("trash_enabled")
        val recentSearches = stringPreferencesKey("recent_searches")
        val uiStyle = stringPreferencesKey("ui_style")
        val trashDays = androidx.datastore.preferences.core.intPreferencesKey("trash_days")
        val trashMaxMb = androidx.datastore.preferences.core.intPreferencesKey("trash_max_mb")
        val budgetGb = androidx.datastore.preferences.core.intPreferencesKey("budget_gb")
        val budgetAuto = booleanPreferencesKey("budget_auto")
        val libSort = stringPreferencesKey("library_sort")
        val libSource = stringPreferencesKey("library_source")
        val libLiked = booleanPreferencesKey("library_only_liked")
        val libOffline = booleanPreferencesKey("library_only_offline")
    }

    val defaultMusicRoot: String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath

    private val json = Http.json

    val flow = context.dataStore.data.map { p ->
        AppSettings(
            musicRoots = p[K.roots]?.toList()?.sorted() ?: listOf(defaultMusicRoot),
            downloadDir = p[K.downloadDir] ?: File(defaultMusicRoot, "Palimpsest").absolutePath,
            quality = p[K.quality]?.let { runCatching { Quality.valueOf(it) }.getOrNull() } ?: Quality.LOSSLESS,
            replayGain = p[K.rg]?.let { runCatching { ReplayGainMode.valueOf(it) }.getOrNull() } ?: ReplayGainMode.TRACK,
            replayGainPreampDb = p[K.rgPreamp] ?: 0f,
            eqEnabled = p[K.eqEnabled] ?: false,
            eqProfile = p[K.eqProfile]?.let { runCatching { json.decodeFromString<EqProfile>(it) }.getOrNull()?.let { e -> e.copy(name = EqProfile.canonicalName(e.name)) } } ?: EqProfile.FLAT,
            customEqProfiles = p[K.eqCustom]?.let { runCatching { json.decodeFromString<List<EqProfile>>(it) }.getOrNull() } ?: emptyList(),
            visualizer = p[K.visualizer]?.let { runCatching { VisualizerMode.valueOf(it) }.getOrNull() } ?: VisualizerMode.BARS,
            dynamicColor = p[K.dynamicColor] ?: true,
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            yandexToken = p[K.yToken],
            yandexRefreshToken = p[K.yRefresh],
            yandexLogin = p[K.yLogin],
            proxy = p[K.proxy],
            keepVersions = p[K.keepVersions] ?: true,
            lastScanAt = p[K.lastScan] ?: 0L,
            autoSync = p[K.autoSync] ?: true,
            checkUpdates = p[K.checkUpdates] ?: true,
            renderMode = p[K.renderMode],
            renderAspect = p[K.renderAspect],
            renderQuality = p[K.renderQuality],
            lyricsTranslation = p[K.lyricsTranslation]?.let { runCatching { LyricsTranslation.valueOf(it) }.getOrNull() } ?: LyricsTranslation.OFF,
            autoDownloadLikes = p[K.autoDownloadLikes] ?: true,
            deviceEq = p[K.deviceEq]?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap(),
            listenBrainzToken = p[K.lbToken],
            listenBrainzUser = p[K.lbUser],
            wave = WavePrefs(p[K.waveDiversity].orEmpty(), p[K.waveMood].orEmpty(), p[K.waveLanguage].orEmpty()),
            fadeSec = p[K.fadeSec] ?: 0,
            streamCacheMb = p[K.streamCacheMb] ?: 1024,
            embedLyrics = p[K.embedLyrics] ?: true,
            lyricsKaraoke = p[K.lyricsKaraoke] ?: false,
            trashEnabled = p[K.trashEnabled] ?: true,
            trashDays = p[K.trashDays] ?: 30,
            trashMaxMb = p[K.trashMaxMb] ?: 2048,
            budgetGb = p[K.budgetGb] ?: 0,
            budgetAuto = p[K.budgetAuto] ?: false,
            uiStyle = p[K.uiStyle]?.let { runCatching { UiStyle.valueOf(it) }.getOrNull() } ?: UiStyle.PALIMPSEST,
            recentSearches = p[K.recentSearches]?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList(),
            libraryView = LibraryView(p[K.libSort], p[K.libSource], p[K.libLiked] ?: false, p[K.libOffline] ?: false),
        )
    }

    /** Hot copy for code that can't suspend (audio thread, data sources). */
    val state: StateFlow<AppSettings?> = flow.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun setMusicRoots(roots: List<String>) = edit { it[K.roots] = roots.toSet() }
    suspend fun setDownloadDir(dir: String) = edit { it[K.downloadDir] = dir }
    suspend fun setQuality(q: Quality) = edit { it[K.quality] = q.name }
    suspend fun setReplayGain(mode: ReplayGainMode) = edit { it[K.rg] = mode.name }
    suspend fun setReplayGainPreamp(db: Float) = edit { it[K.rgPreamp] = db }
    suspend fun setEqEnabled(on: Boolean) = edit { it[K.eqEnabled] = on }
    suspend fun setEqProfile(p: EqProfile) = edit { it[K.eqProfile] = json.encodeToString(p) }
    suspend fun setCustomEqProfiles(list: List<EqProfile>) = edit { it[K.eqCustom] = json.encodeToString(list) }
    suspend fun setVisualizer(mode: VisualizerMode) = edit { it[K.visualizer] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = edit { it[K.dynamicColor] = on }
    suspend fun setTheme(t: ThemeMode) = edit { it[K.theme] = t.name }
    suspend fun setProxy(proxy: String?) = edit { if (proxy.isNullOrBlank()) it.remove(K.proxy) else it[K.proxy] = proxy.trim() }
    suspend fun setKeepVersions(on: Boolean) = edit { it[K.keepVersions] = on }
    suspend fun setLastScan(at: Long) = edit { it[K.lastScan] = at }
    suspend fun setAutoSync(on: Boolean) = edit { it[K.autoSync] = on }
    suspend fun setCheckUpdates(on: Boolean) = edit { it[K.checkUpdates] = on }
    suspend fun setLyricsTranslation(m: LyricsTranslation) = edit { it[K.lyricsTranslation] = m.name }
    suspend fun setRenderChoice(mode: String, aspect: String, quality: String) = edit {
        it[K.renderMode] = mode; it[K.renderAspect] = aspect; it[K.renderQuality] = quality
    }
    suspend fun setAutoDownloadLikes(on: Boolean) = edit { it[K.autoDownloadLikes] = on }
    suspend fun setDeviceEq(map: Map<String, String>) = edit { it[K.deviceEq] = json.encodeToString(map) }
    suspend fun setFadeSec(sec: Int) = edit { it[K.fadeSec] = sec }
    suspend fun setStreamCacheMb(mb: Int) = edit { it[K.streamCacheMb] = mb }
    suspend fun setUiStyle(s: UiStyle) = edit { it[K.uiStyle] = s.name }
    suspend fun addRecentSearch(q: String) = edit { p ->
        val old = p[K.recentSearches]?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()
        p[K.recentSearches] = json.encodeToString((listOf(q) + old.filterNot { it.equals(q, ignoreCase = true) }).take(12))
    }
    suspend fun clearRecentSearches() = edit { it.remove(K.recentSearches) }
    suspend fun setTrash(enabled: Boolean, days: Int, maxMb: Int) = edit {
        it[K.trashEnabled] = enabled; it[K.trashDays] = days; it[K.trashMaxMb] = maxMb
    }
    suspend fun setBudget(gb: Int, auto: Boolean) = edit { it[K.budgetGb] = gb; it[K.budgetAuto] = auto }
    suspend fun setEmbedLyrics(on: Boolean) = edit { it[K.embedLyrics] = on }
    suspend fun setLyricsKaraoke(on: Boolean) = edit { it[K.lyricsKaraoke] = on }
    suspend fun setLibraryView(v: LibraryView) = edit {
        if (v.sort == null) it.remove(K.libSort) else it[K.libSort] = v.sort
        if (v.source == null) it.remove(K.libSource) else it[K.libSource] = v.source
        it[K.libLiked] = v.onlyLiked
        it[K.libOffline] = v.onlyOffline
    }
    suspend fun setWave(w: WavePrefs) = edit {
        it[K.waveDiversity] = w.diversity; it[K.waveMood] = w.mood; it[K.waveLanguage] = w.language
    }
    suspend fun setListenBrainz(token: String?, user: String?) = edit {
        if (token == null) { it.remove(K.lbToken); it.remove(K.lbUser) } else { it[K.lbToken] = token; if (user != null) it[K.lbUser] = user }
    }

    suspend fun setYandexAuth(token: String?, refresh: String?, login: String?) = edit {
        if (token == null) { it.remove(K.yToken); it.remove(K.yRefresh); it.remove(K.yLogin) } else {
            it[K.yToken] = token
            if (refresh != null) it[K.yRefresh] = refresh
            if (login != null) it[K.yLogin] = login
        }
    }

    suspend fun saveQueue(q: SavedQueue) = edit { it[K.queue] = json.encodeToString(q) }

    suspend fun loadQueue(): SavedQueue? {
        val raw = context.dataStore.data.first()[K.queue]
        return raw?.let { runCatching { json.decodeFromString<SavedQueue>(it) }.getOrNull() }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
