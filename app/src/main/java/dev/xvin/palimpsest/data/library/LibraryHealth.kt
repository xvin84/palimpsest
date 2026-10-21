package dev.xvin.palimpsest.data.library

import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.lyrics.LyricsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class HealthJob(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val found: Int = 0)

/**
 * The «Library health» screen's work: checking which files carry lyrics (once, for files indexed
 * before that was recorded) and fetching lyrics for every track that has none.
 */
class LibraryHealth(private val db: AppDatabase, private val lyrics: LyricsRepository) {
    private val _check = MutableStateFlow(HealthJob())
    val check: StateFlow<HealthJob> = _check
    private val _lyricsJob = MutableStateFlow(HealthJob())
    val lyricsJob: StateFlow<HealthJob> = _lyricsJob
    private val mutex = Mutex()

    /** Reads the lyrics flag for files that don't have one yet (a quick tag read each). */
    suspend fun checkLyricsFlags() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val todo = db.tracks().lyricsUnknown()
            if (todo.isEmpty()) return@withContext
            _check.value = HealthJob(true, 0, todo.size)
            todo.forEachIndexed { i, t ->
                val f = File(t.path ?: return@forEachIndexed)
                val has = runCatching { !TagIO.read(f).lyrics.isNullOrBlank() }.getOrDefault(false) ||
                    File(f.parentFile, f.nameWithoutExtension + ".lrc").isFile
                db.tracks().setHasLyrics(t.id, has)
                if (i % 20 == 0) _check.value = HealthJob(true, i + 1, todo.size)
            }
            _check.value = HealthJob(false, todo.size, todo.size)
        }
    }

    /** Looks up lyrics online for every track that has none; found ones are saved and written into the file. */
    suspend fun findMissingLyrics(ids: List<Long>) = withContext(Dispatchers.IO) {
        val tracks = db.tracks().getMany(ids)
        _lyricsJob.value = HealthJob(true, 0, tracks.size)
        var found = 0
        tracks.forEachIndexed { i, t ->
            currentCoroutineContext().ensureActive()
            if (runCatching { lyrics.fetchOnline(t) }.getOrNull() != null) {
                found++
                db.tracks().setHasLyrics(t.id, true)
            }
            _lyricsJob.value = HealthJob(true, i + 1, tracks.size, found)
        }
        _lyricsJob.value = _lyricsJob.value.copy(running = false)
    }
}
