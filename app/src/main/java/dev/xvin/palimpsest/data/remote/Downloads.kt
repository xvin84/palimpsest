package dev.xvin.palimpsest.data.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.xvin.palimpsest.PalimpsestApp
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.core.crypto.AesCtr
import dev.xvin.palimpsest.core.library.FileNames
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.library.TagEdit
import dev.xvin.palimpsest.data.library.TagIO
import dev.xvin.palimpsest.data.library.VersionRepository
import dev.xvin.palimpsest.data.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

enum class DownloadState { QUEUED, RUNNING, DONE, SKIPPED, FAILED }

data class DownloadItem(
    val trackId: Long,
    val title: String,
    val artist: String,
    val state: DownloadState,
    val progress: Float = 0f,
    val message: String? = null,
)

/**
 * Downloads service tracks into the music folder, fully tagged (cover, lyrics, URL link), and
 * hands them to [VersionRepository] so a re-uploaded track never silently replaces the old one.
 * Work runs in WorkManager so big batches ("download all likes") survive the app going away.
 */
class Downloads(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: Settings,
    private val http: OkHttpClient,
    private val remote: RemoteRepository,
    private val versions: VersionRepository,
) {
    private val _items = MutableStateFlow<Map<Long, DownloadItem>>(emptyMap())
    val items: StateFlow<Map<Long, DownloadItem>> = _items

    suspend fun enqueue(trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        val tracks = db.tracks().getMany(trackIds).filter { it.remoteId != null }
        _items.update { m -> m + tracks.associate { it.id to DownloadItem(it.id, it.title, it.artist, DownloadState.QUEUED) } }
        val wm = WorkManager.getInstance(context)
        tracks.map { it.id }.chunked(250).forEach { chunk ->
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(DownloadWorker.KEY_IDS to chunk.toLongArray()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        _items.update { m -> m.filterValues { it.state == DownloadState.DONE } }
    }

    fun clearFinished() = _items.update { m -> m.filterValues { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING } }

    private fun set(id: Long, f: (DownloadItem) -> DownloadItem) =
        _items.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }

    /** Downloads one track. Called from the worker. */
    suspend fun download(trackId: Long) = withContext(Dispatchers.IO) {
        val track = db.tracks().get(trackId) ?: return@withContext
        val remoteId = track.remoteId ?: return@withContext
        if (_items.value[trackId] == null) _items.update { it + (trackId to DownloadItem(trackId, track.title, track.artist, DownloadState.QUEUED)) }
        if (track.path != null && File(track.path).isFile && !track.updateAvailable) {
            set(trackId) { it.copy(state = DownloadState.SKIPPED, message = "Уже скачан") }
            return@withContext
        }
        set(trackId) { it.copy(state = DownloadState.RUNNING, progress = 0f) }
        try {
            val s = settings.state.filterNotNull().first()
            val provider = remote.providers[track.service] ?: error("Нет провайдера ${track.service}")
            val meta: RemoteTrack? = runCatching { provider.track(remoteId) }.getOrNull()
            val dir = File(s.downloadDir, serviceFolder(track.service)).apply { mkdirs() }
            val tmp = File(dir, ".download-$trackId.part")
            // Stream URLs are short-lived and YouTube occasionally rejects one with 403:
            // resolve a fresh link and try again before giving up.
            var attempt = 0
            var info = provider.resolveStream(remoteId, s.quality, forDownload = true)
            while (true) {
                try {
                    fetch(info.url, info.headers, tmp, info.aesCtrKey) { p -> set(trackId) { it.copy(progress = p) } }
                    break
                } catch (e: IOException) {
                    if (++attempt >= 3 || e.message?.startsWith("HTTP 4") != true) throw e
                    Log.i(TAG, "retrying $trackId after ${e.message}")
                    delay(1500L * attempt)
                    info = provider.resolveStream(remoteId, s.quality, forDownload = true)
                }
            }

            // Tags: everything we know, so the file stands on its own in any player.
            val cover = (meta?.coverUrl ?: track.coverUrl)?.let { url -> runCatching { get(url) }.getOrNull() }
            val lyrics = runCatching { provider.lyrics(remoteId) }.getOrNull()
            val tagged = File(dir, ".download-$trackId.${info.extension}")
            if (info.extension == "m4a" && Remux.isFragmentedMp4(tmp)) {
                runCatching { Remux.toPlainMp4(tmp, tagged); tmp.delete() }
                    .onFailure { Log.w(TAG, "remux failed, keeping fragmented file", it); tagged.delete(); tmp.renameTo(tagged) }
            } else {
                tmp.renameTo(tagged)
            }
            runCatching {
                TagIO.write(
                    tagged,
                    TagEdit(
                        title = meta?.title ?: track.title,
                        artist = meta?.artistLine?.ifBlank { null } ?: track.artist,
                        album = meta?.album ?: track.album,
                        albumArtist = meta?.artists?.firstOrNull(),
                        year = (meta?.year ?: track.year)?.toString(),
                        genre = meta?.genre ?: track.genre,
                        trackNumber = (meta?.trackNumber ?: track.trackNumber)?.toString(),
                        discNumber = (meta?.discNumber ?: track.discNumber)?.toString(),
                        lyrics = lyrics?.synced ?: lyrics?.plain,
                        url = meta?.webUrl,
                        cover = cover,
                    ),
                )
            }.onFailure { Log.w(TAG, "tagging failed for ${tagged.name}: $it") }

            val baseName = FileNames.sanitize("${track.artist} - ${track.title}")
            var target = File(dir, "$baseName.${info.extension}")
            // Never overwrite a file that isn't this track's own (another track's, or an unindexed one).
            if (target.exists() && db.tracks().byPath(target.absolutePath)?.id != track.id) {
                target = File(dir, "$baseName ($remoteId).${info.extension}")
            }

            val note = "Скачано: ${track.service.displayName}, ${info.codec}${if (info.bitrateKbps > 0) " ${info.bitrateKbps} kbps" else ""}"
            when (versions.installNewAudio(db.tracks().get(trackId)!!, tagged, target, note)) {
                VersionRepository.ReplaceResult.Identical ->
                    set(trackId) { it.copy(state = DownloadState.SKIPPED, progress = 1f, message = "Файл не изменился") }
                is VersionRepository.ReplaceResult.Replaced ->
                    set(trackId) { it.copy(state = DownloadState.DONE, progress = 1f) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "download $trackId failed", e)
            set(trackId) { it.copy(state = DownloadState.FAILED, message = e.message ?: e.toString()) }
        }
    }

    private fun get(url: String): ByteArray =
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body.bytes()
        }

    /**
     * Downloads in ranged chunks: YouTube throttles single long requests, and a failed chunk
     * only costs a few MB.
     */
    private suspend fun fetch(url: String, headers: Map<String, String>, out: File, key: ByteArray?, onProgress: (Float) -> Unit) {
        val chunk = 4L shl 20
        var pos = 0L
        var total = -1L
        out.outputStream().buffered().use { sink ->
            while (total < 0 || pos < total) {
                currentCoroutineContext().ensureActive()
                val req = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                    header("Range", "bytes=$pos-${pos + chunk - 1}")
                }.build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val ranged = r.code == 206
                    if (total < 0) {
                        total = if (ranged) r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L else r.body.contentLength()
                    }
                    val raw = r.body.byteStream()
                    val input = if (key != null) AesCtr.decrypting(raw, key, pos) else raw
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        pos += n
                        if (total > 0) onProgress(pos.toFloat() / total)
                    }
                    // Server ignored Range and sent everything at once.
                    if (!ranged) total = pos
                }
                if (total <= 0) break
            }
        }
    }

    companion object {
        private const val TAG = "Downloads"
        private const val UNIQUE = "downloads"

        fun serviceFolder(s: Service) = when (s) {
            Service.YANDEX -> "Яндекс"
            Service.YOUTUBE -> "YouTube"
            Service.SPOTIFY -> "Spotify"
            Service.LOCAL -> "Разное"
        }
    }
}

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as PalimpsestApp).graph
        val ids = inputData.getLongArray(KEY_IDS) ?: return Result.success()
        runCatching { setForeground(foregroundInfo(0, ids.size)) }
        ids.forEachIndexed { i, id ->
            runCatching { setForeground(foregroundInfo(i, ids.size)) }
            graph.downloads.download(id)
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 1)

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Скачивание треков")
            .setContentText("$done из $total")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_IDS = "ids"
        private const val CHANNEL = "downloads"
    }
}
