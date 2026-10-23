package dev.xvin.palimpsest.data.library

import dev.xvin.palimpsest.core.library.YandexLinks
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.yandex.YandexMusicProvider
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.remote.DownloadMode
import dev.xvin.palimpsest.data.remote.Downloads
import dev.xvin.palimpsest.core.yandex.FlacLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    /** Not found on the service at all. */
    val notFound: Int = 0,
    /** Found, but the service has it only lossy (MP3/AAC). */
    val noFlac: Int = 0,
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
    private val lookup = FlacLookup(yandex)

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
            if (lookup.accountHasPlus() == false) { _state.value = FlacSearch(error = "plus"); return@withContext }
            val todo = lossyTracks()
            _state.value = FlacSearch(running = true, total = todo.size)
            val batch = LinkedHashMap<Long, String>()
            try {
                todo.forEachIndexed { i, t ->
                    currentCoroutineContext().ensureActive()
                    val m = runCatching { match(t) }.getOrElse { if (it is CancellationException) throw it; FlacLookup.Match.NotFound }
                    if (m is FlacLookup.Match.Flac) batch[t.id] = m.remoteId
                    _state.value = _state.value.copy(
                        checked = i + 1,
                        found = _state.value.found + if (m is FlacLookup.Match.Flac) 1 else 0,
                        notFound = _state.value.notFound + if (m == FlacLookup.Match.NotFound) 1 else 0,
                        noFlac = _state.value.noFlac + if (m == FlacLookup.Match.LossyOnly) 1 else 0,
                    )
                    // Queue in small batches, so the first FLACs arrive while the search goes on.
                    if (batch.size >= 10) { downloads.enqueue(batch.keys.toList(), DownloadMode.CANDIDATE, batch.toMap()); batch.clear() }
                    delay(250) // be gentle with search
                }
            } finally {
                withContext(NonCancellable) { downloads.enqueue(batch.keys.toList(), DownloadMode.CANDIDATE, batch.toMap()) }
                _state.value = _state.value.copy(running = false)
            }
        }
    }

    private suspend fun match(t: TrackEntity): FlacLookup.Match {
        val rejected = versions.rejectedLinks(t.id).mapNotNull { YandexLinks.parse(it)?.second }.toSet()
        val linked = t.remoteId?.takeIf { t.service == Service.YANDEX }
        return lookup.find(t.title, t.artist, t.durationMs, linked, rejected)
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
