package dev.xvin.palimpsest.data.library

import dev.xvin.palimpsest.core.library.Versions
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.db.TrackVersionEntity
import dev.xvin.palimpsest.data.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Track version history. Old audio is moved to `<music root>/.palimpsest/versions/<trackId>/`
 * (hidden + .nomedia, so other players don't see duplicates) and recorded in `track_versions`.
 */
class VersionRepository(
    private val db: AppDatabase,
    private val settings: Settings,
    private val scanner: LibraryScanner,
) {
    sealed interface ReplaceResult {
        data object Identical : ReplaceResult
        data class Replaced(val archived: TrackVersionEntity?) : ReplaceResult
    }

    private suspend fun versionsDir(trackId: Long): File {
        val root = File(settings.state.filterNotNull().first().musicRoots.first(), ".palimpsest")
        root.mkdirs()
        File(root, ".nomedia").let { if (!it.exists()) runCatching { it.createNewFile() } }
        return File(root, "versions/$trackId").apply { mkdirs() }
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())

    private fun humanDate(at: Long = System.currentTimeMillis()) =
        SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("ru")).format(Date(at))

    /**
     * Puts freshly downloaded audio [incoming] at the track's place. If the track already has a
     * file with different audio, the old one is archived instead of being overwritten.
     */
    suspend fun installNewAudio(track: TrackEntity, incoming: File, target: File, note: String): ReplaceResult = withContext(Dispatchers.IO) {
        val existing = track.path?.let(::File)?.takeIf { it.isFile }
        val keep = settings.state.filterNotNull().first().keepVersions
        if (existing == null) {
            moveFile(incoming, target)
            attach(track, target)
            recordCurrent(track.id, target, note)
            return@withContext ReplaceResult.Replaced(null)
        }
        val oldFp = Versions.audioFingerprint(existing)
        val newFp = Versions.audioFingerprint(incoming)
        if (oldFp == newFp) {
            incoming.delete()
            return@withContext ReplaceResult.Identical
        }
        val archived = if (keep) archive(track, existing, oldFp, "Была текущей до ${humanDate()}") else { existing.delete(); null }
        // New file takes the old file's place (same name, so playlists in other players keep working).
        val dest = if (existing.extension.equals(target.extension, true)) existing else target
        moveFile(incoming, dest)
        attach(track, dest)
        recordCurrent(track.id, dest, note)
        ReplaceResult.Replaced(archived)
    }

    /** Points the track row at [file] and refreshes its tags/tech info from disk. */
    private suspend fun attach(track: TrackEntity, file: File) {
        if (track.path != file.absolutePath) {
            db.tracks().byPath(file.absolutePath)?.takeIf { it.id != track.id }?.let { db.tracks().delete(listOf(it.id)) }
            db.tracks().update(track.copy(path = file.absolutePath, updateAvailable = false))
        } else if (track.updateAvailable) {
            db.tracks().update(track.copy(updateAvailable = false))
        }
        scanner.rescanFile(file.absolutePath)
    }

    /** Moves a duplicate's file into [track]'s history (used when merging duplicates). */
    suspend fun absorb(track: TrackEntity, file: File, note: String): TrackVersionEntity? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        archive(track, file, Versions.audioFingerprint(file), note)
    }

    /** Copies the current file into history without changing anything (manual "save version"). */
    suspend fun snapshot(track: TrackEntity, note: String): TrackVersionEntity? = withContext(Dispatchers.IO) {
        val src = track.path?.let(::File)?.takeIf { it.isFile } ?: return@withContext null
        val dst = File(versionsDir(track.id), "${stamp()}_${src.name}")
        src.copyTo(dst, overwrite = true)
        insertVersion(track, dst, Versions.audioFingerprint(src), note, current = false)
    }

    /** Makes [version] the playing file again; the current file goes into history. */
    suspend fun restore(track: TrackEntity, version: TrackVersionEntity) = withContext(Dispatchers.IO) {
        val current = track.path?.let(::File)?.takeIf { it.isFile } ?: return@withContext
        val old = File(version.path).takeIf { it.isFile } ?: return@withContext
        val archived = archive(track, current, Versions.audioFingerprint(current), "Заменена восстановленной версией")
        val dest = if (current.extension.equals(old.extension, true)) current else File(current.parentFile, current.nameWithoutExtension + "." + old.extension)
        old.copyTo(dest, overwrite = true)
        if (dest != current) current.delete()
        db.versions().delete(version.id)
        File(version.path).delete()
        if (dest.absolutePath != track.path) db.tracks().update(track.copy(path = dest.absolutePath))
        recordCurrent(track.id, dest, "Восстановлена из истории (${humanDate(version.createdAt)})")
        scanner.rescanFile(dest.absolutePath)
        archived
    }

    suspend fun delete(version: TrackVersionEntity) = withContext(Dispatchers.IO) {
        if (!version.isCurrent) File(version.path).delete()
        db.versions().delete(version.id)
    }

    private suspend fun archive(track: TrackEntity, file: File, fingerprint: String, note: String): TrackVersionEntity {
        val known = db.versions().forTrack(track.id)
        // The previous "current" row now describes an archived file.
        known.filter { it.isCurrent }.forEach { db.versions().delete(it.id) }
        // Same audio is already in history (e.g. a manual snapshot): don't keep a second copy.
        known.firstOrNull { !it.isCurrent && it.fingerprint == fingerprint && File(it.path).isFile }?.let { dup ->
            file.delete()
            return dup
        }
        val dst = File(versionsDir(track.id), "${stamp()}_${file.name}")
        moveFile(file, dst)
        return insertVersion(track, dst, fingerprint, note, current = false)
    }

    private suspend fun recordCurrent(trackId: Long, file: File, note: String) {
        val track = db.tracks().get(trackId) ?: return
        db.versions().forTrack(trackId).filter { it.isCurrent }.forEach { db.versions().delete(it.id) }
        insertVersion(track, file, Versions.audioFingerprint(file), note, current = true)
    }

    private suspend fun insertVersion(track: TrackEntity, file: File, fp: String?, note: String, current: Boolean): TrackVersionEntity {
        val tags = runCatching { TagIO.read(file) }.getOrNull()
        val v = TrackVersionEntity(
            trackId = track.id,
            path = file.absolutePath,
            createdAt = System.currentTimeMillis(),
            durationMs = tags?.durationMs ?: track.durationMs,
            sizeBytes = file.length(),
            fingerprint = fp,
            codec = tags?.codec,
            bitrateKbps = tags?.bitrateKbps,
            note = note,
            isCurrent = current,
        )
        return v.copy(id = db.versions().insert(v))
    }

    private fun moveFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (dst.exists()) dst.delete()
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
    }
}
