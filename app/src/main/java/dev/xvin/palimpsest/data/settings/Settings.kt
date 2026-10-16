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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

enum class ReplayGainMode(val label: String) { OFF("Выкл."), TRACK("По треку"), ALBUM("По альбому") }

enum class VisualizerMode(val label: String) { BARS("Полосы"), MIRROR("Зеркало"), RING("Кольцо"), OFF("Выкл.") }

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
    val yandexToken: String?,
    val yandexRefreshToken: String?,
    val yandexLogin: String?,
    val proxy: String?,
    val keepVersions: Boolean,
    val lastScanAt: Long,
    val autoSync: Boolean,
    val autoDownloadLikes: Boolean,
    /** Output device key → EQ profile name. */
    val deviceEq: Map<String, String>,
    val listenBrainzToken: String?,
    val listenBrainzUser: String?,
) {
    /** Profile for [deviceKey]: its own if one is assigned, otherwise the global one. */
    fun eqFor(deviceKey: String): EqProfile {
        val name = deviceEq[deviceKey] ?: return eqProfile
        return (customEqProfiles + EqProfile.PRESETS).firstOrNull { it.name == name } ?: eqProfile
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
        val yToken = stringPreferencesKey("yandex_token")
        val yRefresh = stringPreferencesKey("yandex_refresh")
        val yLogin = stringPreferencesKey("yandex_login")
        val proxy = stringPreferencesKey("proxy")
        val keepVersions = booleanPreferencesKey("keep_versions")
        val queue = stringPreferencesKey("saved_queue")
        val lastScan = longPreferencesKey("last_scan")
        val autoSync = booleanPreferencesKey("auto_sync")
        val autoDownloadLikes = booleanPreferencesKey("auto_download_likes")
        val deviceEq = stringPreferencesKey("device_eq")
        val lbToken = stringPreferencesKey("listenbrainz_token")
        val lbUser = stringPreferencesKey("listenbrainz_user")
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
            eqProfile = p[K.eqProfile]?.let { runCatching { json.decodeFromString<EqProfile>(it) }.getOrNull() } ?: EqProfile.FLAT,
            customEqProfiles = p[K.eqCustom]?.let { runCatching { json.decodeFromString<List<EqProfile>>(it) }.getOrNull() } ?: emptyList(),
            visualizer = p[K.visualizer]?.let { runCatching { VisualizerMode.valueOf(it) }.getOrNull() } ?: VisualizerMode.BARS,
            dynamicColor = p[K.dynamicColor] ?: true,
            yandexToken = p[K.yToken],
            yandexRefreshToken = p[K.yRefresh],
            yandexLogin = p[K.yLogin],
            proxy = p[K.proxy],
            keepVersions = p[K.keepVersions] ?: true,
            lastScanAt = p[K.lastScan] ?: 0L,
            autoSync = p[K.autoSync] ?: true,
            autoDownloadLikes = p[K.autoDownloadLikes] ?: false,
            deviceEq = p[K.deviceEq]?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap(),
            listenBrainzToken = p[K.lbToken],
            listenBrainzUser = p[K.lbUser],
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
    suspend fun setProxy(proxy: String?) = edit { if (proxy.isNullOrBlank()) it.remove(K.proxy) else it[K.proxy] = proxy.trim() }
    suspend fun setKeepVersions(on: Boolean) = edit { it[K.keepVersions] = on }
    suspend fun setLastScan(at: Long) = edit { it[K.lastScan] = at }
    suspend fun setAutoSync(on: Boolean) = edit { it[K.autoSync] = on }
    suspend fun setAutoDownloadLikes(on: Boolean) = edit { it[K.autoDownloadLikes] = on }
    suspend fun setDeviceEq(map: Map<String, String>) = edit { it[K.deviceEq] = json.encodeToString(map) }
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
