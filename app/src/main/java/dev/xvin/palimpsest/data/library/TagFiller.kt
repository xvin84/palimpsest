package dev.xvin.palimpsest.data.library

import android.util.Log
import dev.xvin.palimpsest.data.db.AppDatabase
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * «Заполнить теги» for files without an artist or album: finds the song ([MetaLookup]) and
 * writes what the file lacks — artist, title, album, year, genre, track number, the cover, the
 * Yandex link. What the file already says stays as it is.
 */
class TagFiller(
    private val db: AppDatabase,
    private val meta: MetaLookup,
    private val http: OkHttpClient,
    private val scanner: LibraryScanner,
    private val cacheDir: File,
) {
    private val _progress = MutableStateFlow(HealthJob())
    val progress: StateFlow<HealthJob> = _progress
    private val mutex = Mutex()

    suspend fun run(ids: List<Long>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tracks = db.tracks().getMany(ids).filter { it.path != null }
            _progress.value = HealthJob(true, 0, tracks.size)
            var filled = 0
            try {
                tracks.forEachIndexed { i, t ->
                    currentCoroutineContext().ensureActive()
                    val f = File(t.path!!)
                    val ok = try {
                        val info = if (f.isFile && TagIO.canEdit(f)) meta.find(t) else null
                        info != null && fill(f, info)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "${f.name}: $e")
                        false
                    }
                    if (ok) {
                        filled++
                        ArtProvider.invalidate(cacheDir, t.id)
                        scanner.rescanFile(f.absolutePath)
                    }
                    advance(i, tracks.size, filled)
                    delay(300) // the catalogs are free: don't hammer them
                }
            } finally {
                _progress.value = _progress.value.copy(running = false)
            }
        }
    }

    private fun advance(i: Int, total: Int, filled: Int) {
        _progress.value = HealthJob(true, i + 1, total, filled)
    }

    /** Writes the fields the file is missing; false when it already had them all. */
    private fun fill(f: File, info: SongInfo): Boolean {
        val cur = TagIO.read(f)
        fun missing(v: String?) = v.isNullOrBlank()
        val cover = if (cur.hasCover) null else info.coverUrl?.let(::download)?.takeIf { it.size > 1000 }
        val edit = TagEdit(
            title = info.title.takeIf { missing(cur.title) },
            artist = info.artist.takeIf { MetaLookup.isUnknownArtist(cur.artist) },
            album = info.album?.takeIf { missing(cur.album) },
            albumArtist = info.artist.takeIf { missing(cur.albumArtist) && missing(cur.album) && info.album != null },
            year = info.year?.toString()?.takeIf { cur.year == null },
            genre = info.genre?.takeIf { missing(cur.genre) },
            trackNumber = info.trackNumber?.toString()?.takeIf { cur.trackNumber == null },
            url = info.webUrl?.takeIf { missing(cur.url) },
            cover = cover,
            coverMime = if (cover != null && cover[0] == 0x89.toByte()) "image/png" else "image/jpeg",
        )
        val changes = listOf(edit.title, edit.artist, edit.album, edit.year, edit.genre, edit.trackNumber, edit.url).any { it != null } || cover != null
        if (!changes) return false
        TagIO.write(f, edit)
        Log.i(TAG, "${f.name} ← ${info.source}: ${info.artist} — ${info.title} [${info.album}]")
        return true
    }

    private fun download(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.bytes() else null }
    }.getOrNull()

    private companion object {
        const val TAG = "TagFiller"
    }
}
