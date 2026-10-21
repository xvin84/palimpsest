package dev.xvin.palimpsest.data.library

import dev.xvin.palimpsest.core.library.YandexLinks
import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.yandex.YandexMusicProvider
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.remote.DownloadMode
import dev.xvin.palimpsest.data.remote.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class FlacSearch(
    val running: Boolean = false,
    val checked: Int = 0,
    val total: Int = 0,
    /** FLAC found and queued for download as a «same song?» candidate. */
    val found: Int = 0,
    val notFound: Int = 0,
    val error: String? = null,
)

/**
 * «Find FLAC for my music»: for every local file stored lossy (MP3, AAC, Opus…) looks up the same
 * recording on Yandex Music — by the file's link if it has one, else by artist, title and length —
 * and downloads the FLAC as a candidate version. Nothing is replaced until the user listens and
 * says it's the same song (see [VersionRepository.acceptCandidate]).
 */
class FlacFinder(
    private val db: AppDatabase,
    private val yandex: YandexMusicProvider,
    private val downloads: Downloads,
    private val versions: VersionRepository,
) {
    private val _state = MutableStateFlow(FlacSearch())
    val state: StateFlow<FlacSearch> = _state
    private val mutex = Mutex()

    /** Local files whose audio is lossy and that have no candidate waiting yet. */
    suspend fun lossyTracks(): List<TrackEntity> = withContext(Dispatchers.IO) {
        val waiting = db.versions().pending().map { it.trackId }.toSet()
        db.tracks().all().filter { t ->
            t.path != null && t.id !in waiting && isLossy(t)
        }
    }

    suspend fun run() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!yandex.isReady) { _state.value = FlacSearch(error = "yandex"); return@withContext }
            val todo = lossyTracks()
            _state.value = FlacSearch(running = true, total = todo.size)
            val found = LinkedHashMap<Long, String>()
            var notFound = 0
            todo.forEachIndexed { i, t ->
                val id = runCatching { match(t) }.getOrNull()
                if (id != null) found[t.id] = id else notFound++
                _state.value = _state.value.copy(checked = i + 1, found = found.size, notFound = notFound)
                // Queue in small batches, so the first FLACs arrive while the search goes on.
                if (found.size >= 10) { downloads.enqueue(found.keys.toList(), DownloadMode.CANDIDATE, found.toMap()); found.clear() }
                if (t.service != Service.YANDEX || t.remoteId == null) delay(250) // be gentle with search
            }
            downloads.enqueue(found.keys.toList(), DownloadMode.CANDIDATE, found.toMap())
            _state.value = _state.value.copy(running = false)
        }
    }

    /** The Yandex track id of the same recording, or null. Rejected matches aren't offered again. */
    private suspend fun match(t: TrackEntity): String? {
        val rejected = versions.rejectedLinks(t.id).mapNotNull { YandexLinks.parse(it)?.second }.toSet()
        if (t.service == Service.YANDEX && t.remoteId != null) return t.remoteId.takeIf { it !in rejected }
        val query = "${t.artist} ${TrackMatcher.normalizeTitle(t.title)}".trim()
        val results = yandex.search(query).take(10).filter { it.available && it.id !in rejected }
        val scored = results.map { r -> r to TrackMatcher.score(t.title, t.artist, t.durationMs, r.title, r.artistLine, r.durationMs) }
        // ≥ 0.8: «almost certainly the same recording». The user still confirms by ear.
        return scored.filter { it.second >= 0.8 }.maxByOrNull { it.second }?.first?.id
    }

    companion object {
        private val LOSSLESS = setOf("flac", "alac", "wav", "ape", "wv", "aiff", "aif", "dsf", "dff")

        fun isLossy(t: TrackEntity): Boolean {
            val codec = t.codec?.lowercase()
            if (codec != null) return LOSSLESS.none { codec.startsWith(it) }
            return File(t.path ?: return false).extension.lowercase() !in LOSSLESS
        }
    }
}
