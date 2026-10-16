package dev.xvin.palimpsest.data.library

import android.util.Log
import dev.xvin.palimpsest.core.library.FileNames
import dev.xvin.palimpsest.core.library.M3u
import dev.xvin.palimpsest.core.library.YandexLinks
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.db.PlaylistEntity
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class ScanProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val changed: Int = 0, val error: String? = null)

/**
 * Walks the music folders and keeps the `tracks` table in sync with the files.
 * Incremental: a file is only re-read when its size or mtime changed.
 */
class LibraryScanner(private val db: AppDatabase, private val settings: Settings, private val artists: ArtistIndex) {
    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress
    private val mutex = Mutex()

    suspend fun scan(force: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            try {
                _progress.value = ScanProgress(running = true)
                val s = settings.state.filterNotNull().first()
                val roots = s.musicRoots.map(::File).filter { it.isDirectory }
                val files = roots.flatMap { root -> walk(root).map { root to it } }
                val stamps = db.tracks().scanStamps().associateBy { it.path }
                val todo = files.filter { (_, f) ->
                    val st = stamps[f.absolutePath]
                    force || st == null || st.sizeBytes != f.length() || st.modifiedAt != f.lastModified()
                }
                _progress.value = ScanProgress(running = true, total = todo.size)
                val done = AtomicInteger()
                val io = Dispatchers.IO.limitedParallelism(4)
                coroutineScope {
                    todo.chunked(64).forEach { chunk ->
                        val entities = chunk.map { (root, f) ->
                            async(io) {
                                runCatching { buildEntity(root, f) }
                                    .onFailure { Log.w("Scanner", "skip ${f.path}: $it") }
                                    .getOrNull()
                                    .also { _progress.value = _progress.value.copy(done = done.incrementAndGet()) }
                            }
                        }.awaitAll().filterNotNull()
                        save(entities)
                    }
                }
                // Files that disappeared: forget local-only tracks, keep service-linked ones as streamable.
                val seen = files.mapTo(HashSet()) { it.second.absolutePath }
                val rootPaths = roots.map { it.absolutePath + "/" }
                val gone = stamps.values.filter { st -> st.path !in seen && (rootPaths.any { st.path.startsWith(it) } || !File(st.path).exists()) }
                val (detach, delete) = gone.partition { it.remoteId != null }
                if (delete.isNotEmpty()) db.tracks().delete(delete.map { it.id })
                if (detach.isNotEmpty()) db.tracks().detachFiles(detach.map { it.id })
                importM3u(roots)
                artists.syncAll()
                settings.setLastScan(System.currentTimeMillis())
                Log.i("Scanner", "scan: ${files.size} files, ${todo.size} read, ${gone.size} gone in ${System.currentTimeMillis() - startedAt} ms")
                _progress.value = ScanProgress(running = false, done = todo.size, total = todo.size, changed = todo.size + gone.size)
            } catch (e: Exception) {
                Log.e("Scanner", "scan failed", e)
                _progress.value = ScanProgress(running = false, error = e.message ?: e.toString())
            }
        }
    }

    /** Mirrors .m3u/.m3u8 files from the music folders as playlists (read-only copies). */
    private suspend fun importM3u(roots: List<File>) {
        val lists = roots.flatMap { root ->
            root.walkTopDown().onEnter { it == root || !it.name.startsWith(".") }
                .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in setOf("m3u", "m3u8") }.toList()
        }
        if (lists.isEmpty()) return
        val stamps = db.tracks().scanStamps()
        val idByPath = stamps.associate { it.path to it.id }
        val index = M3u.indexByFileName(idByPath.keys)
        for (f in lists) {
            val ids = M3u.entries(runCatching { f.readText() }.getOrDefault("")).mapNotNull { e -> M3u.match(e, index)?.let(idByPath::get) }
            val key = "m3u:${f.absolutePath}"
            val pid = db.playlists().byRemote(Service.LOCAL, key)?.id
                ?: db.playlists().insert(PlaylistEntity(name = f.nameWithoutExtension, service = Service.LOCAL, remoteId = key))
            db.playlists().replace(pid, ids)
        }
    }

    /** Re-reads a single file, e.g. after the tag editor saved it. */
    suspend fun rescanFile(path: String) = withContext(Dispatchers.IO) {
        val f = File(path)
        if (!f.isFile) return@withContext
        val s = settings.state.filterNotNull().first()
        val root = s.musicRoots.map(::File).firstOrNull { path.startsWith(it.absolutePath + "/") } ?: f.parentFile!!
        save(listOf(buildEntity(root, f)))
        artists.syncAll()
    }

    private fun walk(root: File): List<File> =
        root.walkTopDown()
            .onEnter { it == root || !it.name.startsWith(".") }
            // Dot-files are in-flight downloads/replacements (".download-12.flac") — never index them.
            .filter { it.isFile && !it.name.startsWith(".") && FileNames.isAudio(it.name) }
            .toList()

    private fun buildEntity(root: File, f: File): TrackEntity {
        val rel = f.absolutePath.removePrefix(root.absolutePath).trimStart('/')
        val tags = TagIO.read(f)
        val guess = FileNames.guess(rel)
        val service = FileNames.serviceFor(rel, tags.url)
        val yandex = YandexLinks.parse(tags.url)
        val remoteId = when (service) {
            Service.YANDEX -> yandex?.second
            Service.YOUTUBE -> guess.youtubeId ?: tags.url?.let { Regex("""(?:v=|youtu\.be/)([A-Za-z0-9_-]{11})""").find(it)?.groupValues?.get(1) }
            else -> null
        }
        val folder = rel.substringBeforeLast('/', "").ifEmpty { root.name }
        return TrackEntity(
            path = f.absolutePath,
            title = tags.title ?: guess.title,
            artist = tags.artist ?: guess.artist ?: UNKNOWN_ARTIST,
            album = tags.album ?: guess.album,
            albumArtist = tags.albumArtist,
            year = tags.year ?: guess.year,
            genre = tags.genre,
            trackNumber = tags.trackNumber ?: guess.trackNumber,
            discNumber = tags.discNumber,
            durationMs = tags.durationMs,
            folder = folder,
            service = service,
            remoteId = remoteId,
            remoteAlbumId = yandex?.first,
            coverUrl = null,
            hasEmbeddedCover = tags.hasCover,
            replayGainTrackDb = tags.replayGainTrackDb,
            replayGainAlbumDb = tags.replayGainAlbumDb,
            codec = tags.codec,
            bitrateKbps = tags.bitrateKbps,
            sampleRate = tags.sampleRate,
            bitsPerSample = tags.bitsPerSample,
            sizeBytes = f.length(),
            modifiedAt = f.lastModified(),
        )
    }

    /** Inserts or updates, preserving ids, likes and history links of rows we already know. */
    private suspend fun save(entities: List<TrackEntity>) {
        if (entities.isEmpty()) return
        val dao = db.tracks()
        val used = HashSet<Long>()
        val merged = entities.map { e ->
            val existing = (dao.byPath(e.path!!)
                ?: e.remoteId?.let { rid -> dao.byRemote(e.service, rid)?.takeIf { it.path == null || !File(it.path).exists() } })
                ?.takeIf { used.add(it.id) }
            if (existing == null) e else e.copy(
                id = existing.id,
                // A file without a URL tag must not lose the link the app already knows about.
                service = if (e.remoteId == null && existing.remoteId != null) existing.service else e.service,
                remoteId = e.remoteId ?: existing.remoteId,
                remoteAlbumId = e.remoteAlbumId ?: existing.remoteAlbumId,
                addedAt = existing.addedAt,
                liked = existing.liked,
                coverUrl = existing.coverUrl,
                updateAvailable = existing.updateAvailable && existing.remoteDurationMs != null &&
                    kotlin.math.abs(existing.remoteDurationMs - e.durationMs) > dev.xvin.palimpsest.core.library.Versions.DURATION_TOLERANCE_MS,
                remoteDurationMs = existing.remoteDurationMs,
            )
        }
        dao.upsert(merged)
    }

    companion object {
        const val UNKNOWN_ARTIST = "Неизвестный исполнитель"
    }
}
