package dev.xvin.palimpsest.data.library

import android.util.Log
import dev.xvin.palimpsest.core.covers.CoverArtArchive
import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.yandex.YandexMusicProvider
import dev.xvin.palimpsest.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Fills in missing covers: from the linked Yandex track, a Yandex search, or MusicBrainz' Cover
 * Art Archive — and writes the picture into the file so every player sees it.
 */
class CoverFinder(
    private val db: AppDatabase,
    private val yandex: YandexMusicProvider,
    private val caa: CoverArtArchive,
    private val http: OkHttpClient,
    private val scanner: LibraryScanner,
    private val cacheDir: File,
) {
    data class Progress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val found: Int = 0)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress
    private val mutex = Mutex()

    suspend fun run(limit: Int = 300) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val missing = db.tracks().all().filter { t ->
                t.path != null && !t.hasEmbeddedCover && File(t.path).isFile && TagIO.coverBytes(File(t.path)) == null
            }.take(limit)
            _progress.value = Progress(true, 0, missing.size, 0)
            var found = 0
            missing.forEachIndexed { i, t ->
                val bytes = runCatching { lookup(t) }.onFailure { Log.w("CoverFinder", "${t.title}: $it") }.getOrNull()
                if (bytes != null && bytes.size > 1000) {
                    val f = File(t.path!!)
                    val written = TagIO.canEdit(f) && runCatching {
                        TagIO.write(f, TagEdit(cover = bytes, coverMime = if (bytes[0] == 0x89.toByte()) "image/png" else "image/jpeg"))
                    }.isSuccess
                    if (written) {
                        ArtProvider.invalidate(cacheDir, t.id)
                        scanner.rescanFile(f.absolutePath)
                    }
                    found++
                }
                _progress.value = Progress(true, i + 1, missing.size, found)
            }
            _progress.value = Progress(false, missing.size, missing.size, found)
        }
    }

    private suspend fun lookup(t: dev.xvin.palimpsest.data.db.TrackEntity): ByteArray? {
        if (yandex.isReady) {
            val url = if (t.service == Service.YANDEX && t.remoteId != null) yandex.track(t.remoteId)?.coverUrl
            else yandex.search("${t.artist} ${t.title}").take(5)
                .firstOrNull { TrackMatcher.score(t.title, t.artist, t.durationMs, it.title, it.artistLine, it.durationMs) >= 0.85 }?.coverUrl
            url?.let { u -> download(u)?.let { return it } }
        }
        delay(1_000)
        return caa.find(dev.xvin.palimpsest.core.library.ArtistNames.split(t.artist).firstOrNull() ?: t.artist, t.title, t.durationMs, t.album)
            ?: if (t.album != null) caa.find(t.artist, t.title, t.durationMs) else null
    }

    private fun download(url: String): ByteArray? =
        http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.bytes() else null }
}
