package dev.xvin.palimpsest.data.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
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
import dev.xvin.palimpsest.MainActivity
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.sample
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
import dev.xvin.palimpsest.i18n.tr

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
 * What a download is for: a normal one; a FLAC kept as a «same song?» candidate next to the
 * library file; or a re-download in another quality replacing the file.
 */
enum class DownloadMode { NORMAL, CANDIDATE, REQUALITY }

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
    private val onFailure: (Throwable, String) -> Unit = { _, _ -> },
) {
    private val _items = MutableStateFlow<Map<Long, DownloadItem>>(emptyMap())
    val items: StateFlow<Map<Long, DownloadItem>> = _items

    @Volatile private var currentJob: Job? = null

    /** Downloads one track as a child job, so [skipCurrent] can drop just this one. */
    suspend fun runOne(trackId: Long, mode: DownloadMode, candidateRemoteId: String? = null, quality: dev.xvin.palimpsest.core.model.Quality? = null, keepOld: Boolean? = null) = coroutineScope {
        val job = launch { download(trackId, mode, candidateRemoteId, quality, keepOld) }
        currentJob = job
        job.join()
        currentJob = null
    }

    /** «Пропустить» in the notification: abandons the track being downloaded, the queue goes on. */
    fun skipCurrent() { currentJob?.cancel() }

    suspend fun enqueue(trackIds: List<Long>) = enqueue(trackIds, DownloadMode.NORMAL)

    /**
     * [candidates]: track → Yandex track id of a FLAC to fetch for it as a «same song?» candidate
     * (the id may come from a search, for files not linked to Yandex). [quality]/[keepOld]: for
     * [DownloadMode.REQUALITY].
     */
    suspend fun enqueue(
        trackIds: List<Long>,
        mode: DownloadMode,
        candidates: Map<Long, String> = emptyMap(),
        quality: dev.xvin.palimpsest.core.model.Quality? = null,
        keepOld: Boolean? = null,
    ) {
        if (trackIds.isEmpty()) return
        val tracks = db.tracks().getMany(trackIds).filter { it.remoteId != null || candidates[it.id] != null }
        _items.update { m -> m + tracks.associate { it.id to DownloadItem(it.id, it.title, it.artist, DownloadState.QUEUED) } }
        val wm = WorkManager.getInstance(context)
        tracks.map { it.id }.chunked(250).forEach { chunk ->
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(
                    workDataOf(
                        DownloadWorker.KEY_IDS to chunk.toLongArray(),
                        DownloadWorker.KEY_MODE to mode.name,
                        DownloadWorker.KEY_REMOTE_IDS to chunk.map { candidates[it].orEmpty() }.toTypedArray(),
                        DownloadWorker.KEY_QUALITY to quality?.name,
                        DownloadWorker.KEY_KEEP_OLD to (keepOld?.toString() ?: ""),
                    ),
                )
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
    suspend fun download(
        trackId: Long,
        mode: DownloadMode = DownloadMode.NORMAL,
        candidateRemoteId: String? = null,
        quality: dev.xvin.palimpsest.core.model.Quality? = null,
        keepOld: Boolean? = null,
    ) = withContext(Dispatchers.IO) {
        val track = db.tracks().get(trackId) ?: return@withContext
        // A candidate found by search is a Yandex track, whatever the file was linked to (if anything).
        val service = if (candidateRemoteId != null) dev.xvin.palimpsest.core.model.Service.YANDEX else track.service
        val remoteId = candidateRemoteId ?: track.remoteId ?: return@withContext
        if (_items.value[trackId] == null) _items.update { it + (trackId to DownloadItem(trackId, track.title, track.artist, DownloadState.QUEUED)) }
        if (track.path != null && File(track.path).isFile && !track.updateAvailable && mode == DownloadMode.NORMAL) {
            set(trackId) { it.copy(state = DownloadState.SKIPPED, message = tr(R.string.dl_already)) }
            return@withContext
        }
        set(trackId) { it.copy(state = DownloadState.RUNNING, progress = 0f) }
        val job = currentCoroutineContext()[Job]
        // Temp files of this attempt; whatever is still lying around at the end (skip, failure) gets deleted.
        val temps = ArrayList<File>()
        try {
            val s = settings.state.filterNotNull().first()
            val provider = remote.providers[service] ?: error(tr(R.string.dl_no_provider, service))
            val meta: RemoteTrack? = runCatching { provider.track(remoteId) }.getOrNull()
            val dir = File(s.downloadDir, serviceFolder(service)).apply { mkdirs() }
            val tmp = File(dir, ".download-$trackId.part").also { temps += it }
            // Stream URLs are short-lived and YouTube occasionally rejects one with 403:
            // resolve a fresh link and try again before giving up.
            var attempt = 0
            val wanted = when (mode) {
                DownloadMode.CANDIDATE -> dev.xvin.palimpsest.core.model.Quality.LOSSLESS
                DownloadMode.REQUALITY -> quality ?: s.quality
                DownloadMode.NORMAL -> s.quality
            }
            var info = provider.resolveStream(remoteId, wanted, forDownload = true)
            if (mode == DownloadMode.CANDIDATE && !info.codec.startsWith("flac") && info.codec != "alac") {
                set(trackId) { it.copy(state = DownloadState.SKIPPED, message = tr(R.string.dl_no_lossless, info.codec)) }
                return@withContext
            }
            while (true) {
                try {
                    fetch(info.url, info.headers, tmp, info.aesCtrKey) { p -> set(trackId) { it.copy(progress = p) } }
                    break
                } catch (e: IOException) {
                    if (++attempt >= 3 || e.message?.startsWith("HTTP 4") != true) throw e
                    Log.i(TAG, "retrying $trackId after ${e.message}")
                    delay(1500L * attempt)
                    info = provider.resolveStream(remoteId, wanted, forDownload = true)
                }
            }

            // Tags: everything we know, so the file stands on its own in any player.
            val cover = (meta?.coverUrl ?: track.coverUrl)?.let { url -> runCatching { get(url) }.getOrNull() }
            // Lyrics already found or picked in the app win; otherwise the service's own.
            val lyrics = db.lyrics().get(track.id)?.let { dev.xvin.palimpsest.core.model.Lyrics(it.plain, it.synced, it.source) }
                ?.takeIf { !(it.synced ?: it.plain).isNullOrBlank() }
                ?: runCatching { provider.lyrics(remoteId) }.getOrNull()
            // FLAC packed in MP4 («flac-mp4») is still FLAC: unpacked into a real .flac, bit for bit.
            val flacInMp4 = dev.xvin.palimpsest.core.audio.FlacMp4.isFlacInMp4(tmp)
            val ext = if (flacInMp4) "flac" else info.extension
            val codec = if (flacInMp4) "flac" else info.codec
            val tagged = File(dir, ".download-$trackId.$ext").also { temps += it }
            currentCoroutineContext().ensureActive()
            if (flacInMp4) {
                dev.xvin.palimpsest.core.audio.FlacMp4.extract(tmp, tagged)
                tmp.delete()
            } else if (ext == "m4a" && Remux.isFragmentedMp4(tmp)) {
                runCatching { Remux.toPlainMp4(tmp, tagged) { job?.isActive == false }; tmp.delete() }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.w(TAG, "remux failed, keeping fragmented file", it); tagged.delete(); tmp.renameTo(tagged)
                    }
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

            currentCoroutineContext().ensureActive()
            val baseName = FileNames.sanitize("${track.artist} - ${track.title}")
            var target = File(dir, "$baseName.$ext")
            // Never overwrite a file that isn't this track's own (another track's, or an unindexed one).
            if (target.exists() && db.tracks().byPath(target.absolutePath)?.id != track.id) {
                target = File(dir, "$baseName ($remoteId).$ext")
            }

            val note = tr(R.string.dl_note, service.displayName, codec, if (info.bitrateKbps > 0) " ${info.bitrateKbps} kbps" else "")
            if (mode == DownloadMode.CANDIDATE) {
                versions.addCandidate(db.tracks().get(trackId)!!, tagged, tr(R.string.flac_candidate_note, service.displayName))
                set(trackId) { it.copy(state = DownloadState.DONE, progress = 1f, message = tr(R.string.flac_candidate_ready)) }
                return@withContext
            }
            when (versions.installNewAudio(db.tracks().get(trackId)!!, tagged, target, note, keepOld)) {
                VersionRepository.ReplaceResult.Identical ->
                    set(trackId) { it.copy(state = DownloadState.SKIPPED, progress = 1f, message = tr(R.string.dl_unchanged)) }
                is VersionRepository.ReplaceResult.Replaced ->
                    set(trackId) { it.copy(state = DownloadState.DONE, progress = 1f) }
            }
        } catch (e: CancellationException) {
            set(trackId) { it.copy(state = DownloadState.SKIPPED, message = tr(R.string.dl_skipped)) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download $trackId failed", e)
            set(trackId) { it.copy(state = DownloadState.FAILED, message = e.message ?: e.toString()) }
            onFailure(e, tr(R.string.ctx_download, track.title))
        } finally {
            temps.forEach { if (it.exists()) it.delete() }
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
                        currentCoroutineContext().ensureActive()
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
            Service.BANDCAMP -> "Bandcamp"
            Service.LOCAL -> "Разное"
        }
    }
}

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as PalimpsestApp).graph
        val ids = inputData.getLongArray(KEY_IDS) ?: return Result.success()
        val mode = inputData.getString(KEY_MODE)?.let { runCatching { DownloadMode.valueOf(it) }.getOrNull() } ?: DownloadMode.NORMAL
        val remoteIds = inputData.getStringArray(KEY_REMOTE_IDS)
        val quality = inputData.getString(KEY_QUALITY)?.let { runCatching { dev.xvin.palimpsest.core.model.Quality.valueOf(it) }.getOrNull() }
        val keepOld = inputData.getString(KEY_KEEP_OLD)?.toBooleanStrictOrNull()
        var index = 0
        runCatching { setForeground(ForegroundInfo(NOTIFICATION_ID, notification(null, 0, ids.size), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) }
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        dev.xvin.palimpsest.data.library.LongWork.awake(applicationContext, "downloads") { coroutineScope {
            // Track title and progress, refreshed a couple of times a second (Android drops faster updates anyway).
            val ticker = launch {
                graph.downloads.items.sample(500).collect { items ->
                    // Every tap on «Скачать» is its own work request, so count the whole queue, not this batch.
                    val all = items.values
                    val finished = all.count { it.state != DownloadState.QUEUED && it.state != DownloadState.RUNNING }
                    nm.notify(NOTIFICATION_ID, notification(items[ids.getOrNull(index)], finished, all.size))
                }
            }
            ids.forEachIndexed { i, id ->
                index = i
                graph.downloads.runOne(id, mode, remoteIds?.getOrNull(i)?.ifEmpty { null }, quality, keepOld)
            }
            ticker.cancel()
        } }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notification(null, 0, 1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(item: DownloadItem?, done: Int, total: Int): android.app.Notification {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.downloads), NotificationManager.IMPORTANCE_LOW))
        fun action(action: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, DownloadActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val percent = ((item?.progress ?: 0f) * 100).toInt()
        val counter = if (total > 1) tr(R.string.n_of_m, done + 1, total) else null
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(item?.let { "${it.artist} — ${it.title}" } ?: tr(R.string.dl_notif_title))
            .setContentText(listOfNotNull(counter, if (item != null) "$percent%" else null).joinToString(" · ").ifEmpty { tr(R.string.dl_preparing) })
            .setProgress(100, percent, item == null || item.state == DownloadState.QUEUED)
            .setContentIntent(open)
            .addAction(0, tr(R.string.skip), action(DownloadActionReceiver.SKIP, 1))
            .addAction(0, tr(R.string.stop), action(DownloadActionReceiver.STOP, 2))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val KEY_IDS = "ids"
        const val KEY_MODE = "mode"
        const val KEY_REMOTE_IDS = "remote_ids"
        const val KEY_QUALITY = "quality"
        const val KEY_KEEP_OLD = "keep_old"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 42
    }
}

/** «Пропустить» / «Остановить» buttons of the download notification. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val downloads = (context.applicationContext as PalimpsestApp).graph.downloads
        when (intent.action) {
            SKIP -> downloads.skipCurrent()
            STOP -> downloads.cancelAll()
        }
    }

    companion object {
        const val SKIP = "dev.xvin.palimpsest.download.SKIP"
        const val STOP = "dev.xvin.palimpsest.download.STOP"
    }
}
