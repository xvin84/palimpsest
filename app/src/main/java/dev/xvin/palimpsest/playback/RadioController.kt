package dev.xvin.palimpsest.playback

import androidx.media3.common.Player
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.radio.GuardedStation
import dev.xvin.palimpsest.core.radio.RadioFeed
import dev.xvin.palimpsest.core.radio.SavedStation
import dev.xvin.palimpsest.core.radio.StationSource
import dev.xvin.palimpsest.core.radio.StationSpec
import dev.xvin.palimpsest.core.radio.YandexStation
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.remote.RemoteRepository
import dev.xvin.palimpsest.data.settings.Settings
import dev.xvin.palimpsest.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Endless radio («Моя волна», radio by track or artist). Fills the queue batch by batch as it runs
 * low and reports what was finished or skipped, so the station adapts. The station is saved with
 * the queue, so after a restart it carries on instead of the last batch going round in circles.
 * Playing anything else ends the radio.
 */
class RadioController(
    private val remote: RemoteRepository,
    private val db: AppDatabase,
    private val player: PlayerConnection,
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val http: okhttp3.OkHttpClient,
    /** Songs from this phone's own files, for the mixed wave. */
    private val local: () -> StationSource,
) {
    data class Station(val seed: String, val title: String)

    private val _station = MutableStateFlow<Station?>(null)
    val station: StateFlow<Station?> = _station

    private val _shaking = MutableStateFlow(false)
    val shaking: StateFlow<Boolean> = _shaking

    @Volatile private var feed: RadioFeed? = null
    private var loading = false
    private var lastIndex = -1
    private var lastMediaId: String? = null
    private var playing: RemoteTrack? = null
    private var startedAt = 0L
    private var durationMs = 0L

    init {
        player.onQueueReplaced = { stop() }
        player.repeatAllAllowed = { feed == null }
        scope.launch {
            settings.loadStation()?.let { saved -> source(saved.spec)?.let { attach(RadioFeed(saved.spec, it, saved.heard)) } }
            player.state.collect { onState(it) }
        }
    }

    suspend fun start(seed: String, title: String, extraSeeds: List<String> = emptyList()): Result<Unit> =
        start(StationSpec(StationSpec.YANDEX, listOf(seed) + extraSeeds, title))

    suspend fun start(spec: StationSpec): Result<Unit> = runCatching {
        val seed = spec.seed
        // Starting the same station again remembers what it already played.
        val heard = feed?.save()?.takeIf { it.spec.source == spec.source && it.spec.seed == seed }?.heard
            ?: settings.loadStation()?.takeIf { it.spec.source == spec.source && it.spec.seed == seed }?.heard.orEmpty()
        val f = RadioFeed(spec, source(spec) ?: error(tr(R.string.radio_empty)), heard)
        val rows = f.start().map { row(it) }
        player.play(rows) // replaces the queue → stops a previous station
        attach(f)
    }

    /** Carries on a station that came with music handed over from the computer; false when it can't run here. */
    fun resume(saved: SavedStation): Boolean {
        val src = source(saved.spec) ?: return false
        attach(RadioFeed(saved.spec, src, saved.heard))
        return true
    }

    fun snapshot(): SavedStation? = feed?.save()

    /** «Встряхнуть»: a new session that avoids everything heard, playing from its first track now. */
    suspend fun shake(): Result<Unit> = runCatching {
        val f = feed ?: error(tr(R.string.radio_empty))
        _shaking.value = true
        try {
            val rows = f.shake().map { row(it) }
            player.replaceUpcoming(rows, playNow = true)
            persist()
        } finally {
            _shaking.value = false
        }
    }

    fun stop() {
        if (feed == null && _station.value == null) return
        feed = null
        _station.value = null
        playing = null
        scope.launch { settings.saveStation(null) }
    }

    private fun attach(f: RadioFeed) {
        feed = f
        lastIndex = -1
        lastMediaId = null
        playing = null
        _station.value = Station(f.spec.seed, f.spec.title)
        if (player.state.value.repeatMode == Player.REPEAT_MODE_ALL) player.setRepeat(Player.REPEAT_MODE_OFF)
        persist()
    }

    private fun persist() {
        val saved: SavedStation = feed?.save() ?: return
        scope.launch { settings.saveStation(saved) }
    }

    private fun source(spec: StationSpec): StationSource? = dev.xvin.palimpsest.core.radio.Waves.source(
        spec,
        yandex = if (remote.yandex.isReady) { seeds -> GuardedStation(YandexStation(remote.yandex, seeds)) { call -> remote.withYandex { call() } } } else null,
        youtube = remote.providers[dev.xvin.palimpsest.core.model.Service.YOUTUBE] as? dev.xvin.palimpsest.core.youtube.YouTubeProvider,
        bandcamp = remote.providers[dev.xvin.palimpsest.core.model.Service.BANDCAMP] as? dev.xvin.palimpsest.core.model.BrowsableProvider,
        local = local,
        http = http,
    )

    /** The library row for a station's song: this phone's own file for the mixed wave's local part, else the service track. */
    private suspend fun row(t: RemoteTrack): dev.xvin.palimpsest.data.db.TrackEntity =
        if (t.service == dev.xvin.palimpsest.core.model.Service.LOCAL) db.tracks().get(t.id.toLong()) ?: error("no track ${t.id}")
        else remote.ensureTrack(t)

    private suspend fun onState(s: PlayerUiState) {
        val f = feed ?: return
        if (s.repeatMode == Player.REPEAT_MODE_ALL) player.setRepeat(Player.REPEAT_MODE_OFF)
        val np = s.nowPlaying
        if (np != null && (s.index != lastIndex || np.mediaId != lastMediaId)) {
            val now = System.currentTimeMillis()
            playing?.let { prev -> scope.launch { f.ended(prev, now - startedAt, durationMs) } }
            val track = np.trackId?.let { db.tracks().get(it) }
            playing = track?.remoteId?.let { RemoteTrack(track.service, it, track.title, listOf(track.artist)) }
            startedAt = now
            durationMs = track?.durationMs?.takeIf { it > 0 } ?: np.durationMs
            lastIndex = s.index
            lastMediaId = np.mediaId
            playing?.let { cur -> scope.launch { f.started(cur) } }
            persist()
        }
        if (s.queueSize - s.index <= 2) topUp(f)
    }

    /** Brings the next tracks; a failed or empty answer is retried a few times, so the music doesn't just stop. */
    private fun topUp(f: RadioFeed) {
        if (loading) return
        loading = true
        scope.launch {
            try {
                for (wait in listOf(0L, 3_000L, 10_000L, 30_000L)) {
                    delay(wait)
                    if (feed !== f) return@launch
                    val fresh = runCatching { f.next() }.getOrDefault(emptyList())
                    if (fresh.isEmpty()) continue
                    val rows = fresh.mapNotNull { runCatching { row(it) }.getOrNull() }
                    if (feed === f) player.enqueue(rows)
                    persist()
                    return@launch
                }
            } finally {
                loading = false
            }
        }
    }

    companion object {
        const val MY_WAVE = StationSpec.MY_WAVE
    }
}
