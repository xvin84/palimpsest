package dev.xvin.palimpsest.data.library

import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** A file in the trash: where it was, where it lies now, and the library row to bring back with it. */
@Serializable
data class TrashEntry(
    val trashPath: String,
    val originalPath: String,
    val deletedAt: Long,
    val sizeBytes: Long,
    val track: TrackEntity,
    /** Files that travelled with it (a sidecar `.lrc`): trash path → original path. */
    val sidecars: Map<String, String> = emptyMap(),
)

/**
 * Deleting and moving music files. Deleted files go to `<music root>/.palimpsest/trash/` (hidden,
 * with .nomedia) and can be restored with their likes, history and playlists, until they're older
 * than the retention time or the trash outgrows its size limit. With the trash off, deletes are final.
 *
 * A track linked to a service stays in the library as a stream when its file goes; a local-only
 * track leaves the library (its row is kept in the trash entry, so a restore brings it back as it was).
 */
class Trash(
    private val db: AppDatabase,
    private val settings: Settings,
    private val scanner: LibraryScanner,
    private val indexFile: File,
) {
    private val mutex = Mutex()
    private val json = Http.json
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<TrashEntry>> = _entries

    private fun load(): List<TrashEntry> =
        runCatching { json.decodeFromString<List<TrashEntry>>(indexFile.readText()) }.getOrDefault(emptyList())

    private fun persist(list: List<TrashEntry>) {
        _entries.value = list
        runCatching { indexFile.writeText(json.encodeToString(list)) }
    }

    private suspend fun rootFor(path: String): File {
        val s = settings.state.filterNotNull().first()
        return s.musicRoots.map(::File).firstOrNull { path.startsWith(it.absolutePath + "/") } ?: File(settings.defaultMusicRoot)
    }

    private suspend fun trashDir(path: String): File =
        File(rootFor(path), ".palimpsest/trash").apply { mkdirs(); File(this, ".nomedia").createNewFile() }

    /** Deletes the files of [tracks] (to the trash when it's on). Returns how many files went. */
    suspend fun delete(tracks: List<TrackEntity>): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val s = settings.state.filterNotNull().first()
            val now = System.currentTimeMillis()
            val added = mutableListOf<TrashEntry>()
            var count = 0
            for (t in tracks) {
                val path = t.path ?: continue
                val file = File(path)
                val lrc = sidecar(file)
                if (s.trashEnabled && file.isFile) {
                    val dir = trashDir(path)
                    val target = unique(File(dir, "${now}_${file.name}"))
                    if (!moveFile(file, target)) continue
                    val side = lrc?.let { l -> File(dir, "${now}_${l.name}").takeIf { moveFile(l, it) }?.let { mapOf(it.absolutePath to l.absolutePath) } }.orEmpty()
                    added += TrashEntry(target.absolutePath, path, now, target.length(), t, side)
                } else {
                    if (file.exists() && !file.delete()) continue
                    lrc?.delete()
                }
                if (t.remoteId != null) db.tracks().detachFiles(listOf(t.id)) else db.tracks().delete(listOf(t.id))
                count++
            }
            if (added.isNotEmpty()) persist(_entries.value + added)
            enforceLimitsLocked()
            count
        }
    }

    /** Puts a file back where it was (next to it, if that name is taken now) with its library row. */
    suspend fun restore(entry: TrashEntry): Boolean = withContext(Dispatchers.IO) {
        val restored = mutex.withLock {
            val from = File(entry.trashPath)
            if (!from.isFile) { persist(_entries.value - entry); return@withLock null }
            val original = File(entry.originalPath)
            val target = if (original.exists()) unique(original) else original
            if (!moveFile(from, target)) return@withLock null
            entry.sidecars.forEach { (trashed, orig) -> File(trashed).takeIf { it.isFile }?.let { moveFile(it, unique(File(orig))) } }
            // Same id as before, so plays, playlists and lyrics point at it again — unless the track
            // got a new file meanwhile (downloaded again); then this one comes back as its own track.
            val current = db.tracks().get(entry.track.id)
            if (current?.path == null || !File(current.path).isFile) {
                db.tracks().upsert(listOf((current ?: entry.track).copy(path = target.absolutePath, folder = entry.track.folder)))
            }
            persist(_entries.value - entry)
            target
        } ?: return@withContext false
        scanner.rescanFile(restored.absolutePath)
        true
    }

    /** Deletes one entry for good. */
    suspend fun purge(entry: TrashEntry) = withContext(Dispatchers.IO) {
        mutex.withLock { purgeLocked(listOf(entry)) }
    }

    suspend fun empty() = withContext(Dispatchers.IO) {
        mutex.withLock { purgeLocked(_entries.value) }
    }

    /** Drops what's past the retention time, then the oldest until the trash fits its size limit. */
    suspend fun enforceLimits() = withContext(Dispatchers.IO) { mutex.withLock { enforceLimitsLocked() } }

    private suspend fun enforceLimitsLocked() {
        val s = settings.state.filterNotNull().first()
        // Files that vanished from the trash folder by other means.
        _entries.value.filter { !File(it.trashPath).isFile }.takeIf { it.isNotEmpty() }?.let { persist(_entries.value - it.toSet()) }
        val now = System.currentTimeMillis()
        if (s.trashDays > 0) {
            val old = _entries.value.filter { now - it.deletedAt > s.trashDays * 86_400_000L }
            if (old.isNotEmpty()) purgeLocked(old)
        }
        if (s.trashMaxMb > 0) {
            val limit = s.trashMaxMb.toLong() shl 20
            val byAge = _entries.value.sortedBy { it.deletedAt }
            var total = byAge.sumOf { it.sizeBytes }
            val drop = mutableListOf<TrashEntry>()
            for (e in byAge) { if (total <= limit) break; drop += e; total -= e.sizeBytes }
            if (drop.isNotEmpty()) purgeLocked(drop)
        }
    }

    private fun purgeLocked(list: List<TrashEntry>) {
        list.forEach { e -> File(e.trashPath).delete(); e.sidecars.keys.forEach { File(it).delete() } }
        persist(_entries.value - list.toSet())
    }

    /**
     * Moves the files of [tracks] into [dir], keeping each track's id, likes and history. Returns
     * how many moved.
     */
    suspend fun move(tracks: List<TrackEntity>, dir: File): Int = withContext(Dispatchers.IO) {
        if (!dir.isDirectory && !dir.mkdirs()) return@withContext 0
        var count = 0
        for (t in tracks) {
            val file = t.path?.let(::File)?.takeIf { it.isFile } ?: continue
            if (file.parentFile?.absolutePath == dir.absolutePath) continue
            val target = unique(File(dir, file.name))
            val lrc = sidecar(file)
            if (!moveFile(file, target)) continue
            lrc?.let { moveFile(it, unique(File(dir, target.nameWithoutExtension + ".lrc"))) }
            db.tracks().update(t.copy(path = target.absolutePath))
            scanner.rescanFile(target.absolutePath)
            count++
        }
        count
    }

    /** Folders music can be moved to: the music folders and every folder that holds library files. */
    suspend fun folders(): List<File> = withContext(Dispatchers.IO) {
        val s = settings.state.filterNotNull().first()
        (s.musicRoots.map(::File) + db.tracks().allPaths().mapNotNull { File(it).parentFile })
            .distinctBy { it.absolutePath }.filter { it.isDirectory }.sortedBy { it.absolutePath.lowercase() }
    }

    private fun sidecar(f: File) = File(f.parentFile, f.nameWithoutExtension + ".lrc").takeIf { it.isFile }

    private fun unique(f: File): File {
        if (!f.exists()) return f
        var i = 2
        while (true) {
            val c = File(f.parentFile, "${f.nameWithoutExtension} ($i)" + if (f.extension.isEmpty()) "" else ".${f.extension}")
            if (!c.exists()) return c
            i++
        }
    }

    /** A rename when it can (same storage), otherwise a copy and delete. */
    private fun moveFile(from: File, to: File): Boolean {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return true
        return try {
            from.copyTo(to, overwrite = false)
            if (to.length() == from.length() && from.delete()) true else { to.delete(); false }
        } catch (e: Exception) {
            to.delete()
            false
        }
    }
}
