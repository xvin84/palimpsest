package dev.xvin.palimpsest.core.radio

import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.yandex.YandexMusicProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * What a station is, enough to start it again after a restart: where its tracks come from
 * ([source]), what it's seeded with and what it's called.
 */
@Serializable
data class StationSpec(val source: String, val seeds: List<String>, val title: String) {
    val seed: String get() = seeds.firstOrNull().orEmpty()

    companion object {
        const val YANDEX = "yandex"
        const val MY_WAVE = "user:onyourwave"
    }
}

/** A station as it's kept between runs: the spec and what it already played, so it doesn't repeat. */
@Serializable
data class SavedStation(val spec: StationSpec, val heard: List<String> = emptyList())

/** Where an endless station gets its tracks from. One session at a time; [open] again restarts it. */
interface StationSource {
    /** Starts a fresh session. [avoid] = keys of tracks heard lately. */
    suspend fun open(avoid: List<String>): List<RemoteTrack>

    /** The next tracks of the current session (opens one when there's none yet). */
    suspend fun more(avoid: List<String>): List<RemoteTrack>

    /** Listening feedback so the station adapts. Best effort, never throws. */
    suspend fun started(track: RemoteTrack) {}
    suspend fun ended(track: RemoteTrack, playedSec: Double, finished: Boolean) {}
}

/**
 * Drives a station for a player, the same on the phone and the computer: never hands out a track
 * it already played (also across restarts, via [save]), opens a new session when the old one
 * dries up or fails — services drop idle sessions without telling — and [shake]s on request.
 */
class RadioFeed(val spec: StationSpec, private val source: StationSource, heard: List<String> = emptyList()) {
    private val heard = LinkedHashSet(heard)
    private val lock = Mutex()
    /** True until the first batch: a restored station has no live session yet. */
    var fresh = true
        private set

    /** The first tracks of a new session. Throws when the service has nothing to give. */
    suspend fun start(): List<RemoteTrack> = lock.withLock {
        fresh = false
        keep(source.open(avoid())).ifEmpty { throw IllegalStateException("The station returned no tracks") }
    }

    /**
     * More tracks. A batch made only of repeats, or a failed call, gets one more try with a new
     * session; an empty list means «try again later».
     */
    suspend fun next(): List<RemoteTrack> = lock.withLock {
        if (fresh) {
            fresh = false
            return@withLock keep(retrying { source.open(avoid()) })
        }
        val more = runCatching { keep(source.more(avoid())) }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
        more.ifEmpty { keep(retrying { source.open(avoid()) }) }
    }

    /** «Встряхнуть»: drop the session and start a new one that avoids everything heard so far. */
    suspend fun shake(): List<RemoteTrack> = start()

    suspend fun started(t: RemoteTrack) {
        runCatching { source.started(t) }
    }

    /** A track stopped playing after [playedMs]; near the end counts as finished, earlier as a skip. */
    suspend fun ended(t: RemoteTrack, playedMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && playedMs >= durationMs - 8_000
        runCatching { source.ended(t, playedMs / 1000.0, finished) }
    }

    fun save() = SavedStation(spec, heard.toList().takeLast(KEEP))

    private fun avoid() = heard.toList().takeLast(50)

    private fun keep(tracks: List<RemoteTrack>): List<RemoteTrack> {
        val out = tracks.distinctBy(::keyOf).filter { keyOf(it) !in heard }
        out.forEach { heard += keyOf(it) }
        while (heard.size > KEEP) heard.remove(heard.first())
        return out
    }

    private suspend fun retrying(block: suspend () -> List<RemoteTrack>): List<RemoteTrack> =
        runCatching { block() }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())

    companion object {
        private const val KEEP = 400

        /** The same song keeps its key between sessions (Yandex adds «:album» to some ids). */
        fun keyOf(t: RemoteTrack): String = "${t.service}:" + if (t.service == Service.YANDEX) t.id.substringBefore(':') else t.id
    }
}

/** Yandex's rotor: «Моя волна», stations by track, artist, genre, mood. */
class YandexStation(private val yandex: YandexMusicProvider, private val seeds: List<String>) : StationSource {
    @Volatile private var session: String? = null
    @Volatile private var batch: String? = null

    override suspend fun open(avoid: List<String>): List<RemoteTrack> {
        val ids = avoid.yandexIds()
        // Tuning seeds are best effort: if the server doesn't take them, fall back to the plain station.
        val b = runCatching { yandex.radioStart(seeds, ids) }.getOrNull()?.takeIf { it.tracks.isNotEmpty() || seeds.size == 1 }
            ?: yandex.radioStart(seeds.take(1), ids)
        session = b.sessionId
        batch = b.batchId
        runCatching { yandex.radioFeedback(b.sessionId, b.batchId, "radioStarted") }
        return b.tracks
    }

    override suspend fun more(avoid: List<String>): List<RemoteTrack> {
        val s = session ?: return open(avoid)
        val b = yandex.radioMore(s, avoid.yandexIds())
        batch = b.batchId ?: batch
        return b.tracks
    }

    override suspend fun started(track: RemoteTrack) {
        val s = session ?: return
        if (track.service != Service.YANDEX) return
        runCatching { yandex.radioFeedback(s, batch, "trackStarted", track.id) }
    }

    override suspend fun ended(track: RemoteTrack, playedSec: Double, finished: Boolean) {
        val s = session ?: return
        if (track.service != Service.YANDEX) return
        runCatching { yandex.radioFeedback(s, batch, if (finished) "trackFinished" else "skip", track.id, playedSec) }
    }

    private fun List<String>.yandexIds() = mapNotNull { k -> k.takeIf { it.startsWith("YANDEX:") }?.substringAfter(':') }
}

/** Runs another source's calls through [wrap] — e.g. the phone's Yandex token refresh. */
class GuardedStation(
    private val inner: StationSource,
    private val wrap: suspend (suspend () -> List<RemoteTrack>) -> List<RemoteTrack>,
) : StationSource {
    override suspend fun open(avoid: List<String>) = wrap { inner.open(avoid) }
    override suspend fun more(avoid: List<String>) = wrap { inner.more(avoid) }
    override suspend fun started(track: RemoteTrack) = inner.started(track)
    override suspend fun ended(track: RemoteTrack, playedSec: Double, finished: Boolean) = inner.ended(track, playedSec, finished)
}
