package dev.xvin.palimpsest.data.library

import android.util.Log
import dev.xvin.palimpsest.core.covers.CoverArtArchive
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.db.TrackEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Fills in missing covers: a cover.jpg next to the file, the linked Yandex track, a Yandex
 * search, Deezer and iTunes, and MusicBrainz' Cover Art Archive last (from Russia it needs the
 * proxy, and without one it only times out). The picture goes into the file so every player
 * sees it; files that can't take one (WebM, a tag the library can't write) keep it as the
 * track's cover link instead.
 */
class CoverFinder(
    private val db: AppDatabase,
    private val meta: MetaLookup,
    private val caa: CoverArtArchive,
    private val http: OkHttpClient,
    private val scanner: LibraryScanner,
    private val cacheDir: File,
) {
    data class Progress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val found: Int = 0)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress
    private val mutex = Mutex()

    /** MusicBrainz didn't answer this run: not asked again until the next one. */
    @Volatile private var caaDown = false

    suspend fun run(limit: Int = 300) = mutex.withLock {
        withContext(Dispatchers.IO) {
            caaDown = false
            val missing = db.tracks().all().filter { t ->
                t.path != null && !t.hasEmbeddedCover && t.coverUrl == null && File(t.path).isFile
            }.take(limit)
            _progress.value = Progress(true, 0, missing.size, 0)
            var found = 0
            try {
                missing.forEachIndexed { i, t ->
                    currentCoroutineContext().ensureActive()
                    val ok = runCatching { fix(t) }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "${t.title}: $it") }.getOrDefault(false)
                    if (ok) found++
                    _progress.value = Progress(true, i + 1, missing.size, found)
                }
            } finally {
                _progress.value = Progress(false, _progress.value.done, missing.size, found)
            }
        }
    }

    private suspend fun fix(t: TrackEntity): Boolean {
        val f = File(t.path!!)
        // A picture next to the file (cover.jpg, folder.jpg): it's shown already, now it goes inside.
        TagIO.coverBytes(f)?.let { return embed(t, f, it, null) }
        val info = meta.find(t)
        info?.coverUrl?.let { url -> download(url)?.let { return embed(t, f, it, url) } }
        delay(300) // the catalogs are free: don't hammer them
        if (caaDown) return false
        val (artist, title) = meta.query(t) ?: return false
        val bytes = try {
            withTimeoutOrNull(25_000) {
                caa.find(dev.xvin.palimpsest.core.library.ArtistNames.split(artist ?: return@withTimeoutOrNull null).firstOrNull() ?: artist, title, t.durationMs, t.album)
                    ?: if (t.album != null) caa.find(artist, title, t.durationMs) else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "MusicBrainz unreachable, skipped for this run: $e")
            caaDown = true
            null
        }
        return bytes != null && embed(t, f, bytes, null)
    }

    /** Writes the cover into the file; where that doesn't work, keeps [url] as the track's cover. */
    private suspend fun embed(t: TrackEntity, f: File, bytes: ByteArray, url: String?): Boolean {
        if (bytes.size < 1000) return false
        if (TagIO.canEdit(f)) {
            runCatching {
                TagIO.write(f, TagEdit(cover = bytes, coverMime = if (bytes[0] == 0x89.toByte()) "image/png" else "image/jpeg"))
                ArtProvider.invalidate(cacheDir, t.id)
                scanner.rescanFile(f.absolutePath)
            }.onFailure { Log.w(TAG, "can't write the cover into ${f.name}: $it") }
            if (db.tracks().get(t.id)?.hasEmbeddedCover == true) return true
        }
        if (url == null) return false
        db.tracks().setCoverUrl(t.id, url)
        ArtProvider.invalidate(cacheDir, t.id)
        return true
    }

    private fun download(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.bytes() else null }
    }.getOrNull()

    private companion object {
        const val TAG = "CoverFinder"
    }
}
