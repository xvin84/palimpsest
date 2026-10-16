package dev.xvin.palimpsest.playback

import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.remote.RemoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Endless radio from Yandex ("Моя волна", radio by track or artist). Fills the queue batch by
 * batch as it runs low and reports what was finished or skipped, so the station adapts.
 * Playing anything else ends the radio.
 */
class RadioController(
    private val remote: RemoteRepository,
    private val db: AppDatabase,
    private val player: PlayerConnection,
    private val scope: CoroutineScope,
) {
    data class Station(val seed: String, val title: String)

    private val _station = MutableStateFlow<Station?>(null)
    val station: StateFlow<Station?> = _station

    private var sessionId: String? = null
    private var batchId: String? = null
    private val received = ArrayList<String>()
    private var loading = false
    private var lastIndex = -1
    private var lastRemoteId: String? = null
    private var lastStartedAt = 0L
    private var lastDurationMs = 0L

    init {
        player.onQueueReplaced = { stop() }
        scope.launch { player.state.collect { onState(it) } }
    }

    suspend fun start(seed: String, title: String, extraSeeds: List<String> = emptyList()): Result<Unit> = runCatching {
        // Settings seeds are best effort: if the server doesn't take them, fall back to the plain station.
        val batch = if (extraSeeds.isEmpty()) remote.radioStart(listOf(seed))
        else runCatching { remote.radioStart(listOf(seed) + extraSeeds) }.getOrNull()?.takeIf { it.tracks.isNotEmpty() }
            ?: remote.radioStart(listOf(seed))
        if (batch.tracks.isEmpty()) error("Станция пока пустая")
        val rows = batch.tracks.map { remote.ensureTrack(it) }
        player.play(rows) // replaces the queue → would stop a previous station
        received.clear(); received += batch.tracks.map { it.id }
        sessionId = batch.sessionId
        batchId = batch.batchId
        lastIndex = -1
        _station.value = Station(seed, title)
        remote.radioFeedback(batch.sessionId, batch.batchId, "radioStarted")
    }

    fun stop() {
        _station.value = null
        sessionId = null
    }

    private suspend fun onState(s: PlayerUiState) {
        val session = sessionId ?: return
        if (_station.value == null) return
        if (s.index != lastIndex && s.nowPlaying != null) {
            val now = System.currentTimeMillis()
            lastRemoteId?.let { prev ->
                val played = (now - lastStartedAt) / 1000.0
                val finished = lastDurationMs > 0 && played * 1000 >= lastDurationMs - 8_000
                remote.radioFeedback(session, batchId, if (finished) "trackFinished" else "skip", prev, played)
            }
            val track = s.nowPlaying.trackId?.let { db.tracks().get(it) }
            lastRemoteId = track?.remoteId
            lastStartedAt = now
            lastDurationMs = s.nowPlaying.durationMs
            lastIndex = s.index
            lastRemoteId?.let { remote.radioFeedback(session, batchId, "trackStarted", it) }
        }
        if (s.queueSize - s.index <= 2 && !loading) loadMore(session)
    }

    private fun loadMore(session: String) {
        loading = true
        scope.launch {
            runCatching {
                val batch = remote.radioMore(session, received)
                batchId = batch.batchId ?: batchId
                val fresh = batch.tracks.filter { it.id !in received }
                received += fresh.map { it.id }
                if (sessionId == session && fresh.isNotEmpty()) player.enqueue(fresh.map { remote.ensureTrack(it) })
            }
            loading = false
        }
    }

    companion object {
        const val MY_WAVE = "user:onyourwave"
    }
}
