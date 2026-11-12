package dev.xvin.palimpsest.data.library

import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr

/**
 * Finds the same recording stored more than once (two folders, MP3 + FLAC, a file plus a cloud
 * copy) and merges it. Nothing is deleted: extra files become versions of the copy you keep.
 */
class Duplicates(private val db: AppDatabase, private val versions: VersionRepository, private val artists: ArtistIndex) {

    data class Group(val keep: TrackEntity, val others: List<TrackEntity>)

    suspend fun find(): List<Group> = withContext(Dispatchers.Default) {
        val tracks = db.tracks().all()
        tracks.groupBy { TrackMatcher.normalizeTitle(it.title) }
            .filterKeys { it.isNotBlank() }
            .values.filter { it.size > 1 }
            .flatMap { cluster(it) }
            .map { g -> val best = g.maxBy(::quality); Group(best, g - best) }
            .sortedBy { it.keep.artist.lowercase() }
    }

    /** Splits same-title tracks into groups of actually-the-same recordings. */
    private fun cluster(sameTitle: List<TrackEntity>): List<List<TrackEntity>> {
        val left = sameTitle.toMutableList()
        val out = ArrayList<List<TrackEntity>>()
        while (left.isNotEmpty()) {
            val seed = left.removeAt(0)
            val group = mutableListOf(seed)
            left.removeAll { t ->
                val same = (seed.durationMs <= 0 || t.durationMs <= 0 || abs(seed.durationMs - t.durationMs) <= 3_000) &&
                    TrackMatcher.score(seed.title, seed.artist, seed.durationMs, t.title, t.artist, t.durationMs) >= 0.85 &&
                    // Two cloud-only rows of different services are two sources, not clutter.
                    !(seed.path == null && t.path == null)
                if (same) group += t
                same
            }
            if (group.size > 1) out += group
        }
        return out
    }

    /** Higher is better: a real file, lossless, higher bitrate, then the older (original) entry. */
    fun quality(t: TrackEntity): Long {
        val lossless = t.codec?.lowercase() in setOf("flac", "alac", "wav", "ape", "wv")
        return (if (t.path != null) 1_000_000_000L else 0) + (if (lossless) 100_000_000L else 0) +
            (t.bitrateKbps ?: 0) * 1_000L + (t.bitsPerSample ?: 0) * 10L - (t.addedAt / 1_000_000_000L)
    }

    suspend fun merge(g: Group) = withContext(Dispatchers.IO) {
        val dao = db.tracks()
        var kept = g.keep
        for (o in g.others) {
            o.path?.let { p -> versions.absorb(kept, File(p), DUP_NOTE + (o.folder ?: File(p).parent)) }
            dao.repointPlaylists(o.id, kept.id)
            dao.repointPlays(o.id, kept.id)
            dao.repointVersions(o.id, kept.id)
            // Keep the service link and the like from whichever copy had them.
            kept = kept.copy(
                liked = kept.liked || o.liked,
                remoteId = kept.remoteId ?: o.remoteId,
                remoteAlbumId = kept.remoteAlbumId ?: o.remoteAlbumId,
                service = if (kept.remoteId == null && o.remoteId != null) o.service else kept.service,
                coverUrl = kept.coverUrl ?: o.coverUrl,
            )
            dao.delete(listOf(o.id))
        }
        dao.update(kept)
        artists.syncAll()
    }
}

/** A version's note is stored as a marker and worded when shown, so it follows the app's language. */
const val DUP_NOTE = "dup:"

/** A version's note as shown: «Duplicate from …» in today's language (older notes were stored already worded). */
fun versionNote(note: String): String {
    val folder = note.removePrefix(DUP_NOTE).takeIf { note.startsWith(DUP_NOTE) }
        ?: Regex("""^(?:Duplicate from|Дубликат из|Дубль из) [“«"](.*)[”»"]$""").find(note)?.groupValues?.get(1)
    return if (folder != null) tr(R.string.dup_note_from, folder) else note
}
