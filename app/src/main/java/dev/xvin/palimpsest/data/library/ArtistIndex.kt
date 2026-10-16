package dev.xvin.palimpsest.data.library

import dev.xvin.palimpsest.core.library.ArtistNames
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps `track_artists` in step with the artist tag of every track. Service tracks get the exact
 * artist list (with ids) from the service; files get their tag split by [ArtistNames].
 */
class ArtistIndex(private val db: AppDatabase) {
    private val mutex = Mutex()

    /** Rebuilds rows for tracks whose tag no longer matches their stored artists. Cheap: one pass over ids. */
    suspend fun syncAll() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dao = db.artists()
            dao.deleteOrphans()
            val stored = dao.all().groupBy { it.trackId }
            for (row in dao.trackArtistLines()) {
                val have = stored[row.trackId].orEmpty().sortedBy { it.position }
                if (have.isNotEmpty() && have.joinToString(", ") { it.name } == row.artist) continue
                val names = ArtistNames.split(row.artist)
                if (have.map { it.name } == names) continue
                // Keep service ids the service gave us, matched by name.
                val ids = have.associate { it.name.lowercase() to it.remoteId }
                dao.replaceFor(row.trackId, names, names.map { ids[it.lowercase()] })
            }
        }
    }

    /** Exact artists from a service track. */
    suspend fun setFromRemote(trackId: Long, remote: RemoteTrack) = withContext(Dispatchers.IO) {
        val names = remote.artists.filter { it.isNotBlank() }
        if (names.isEmpty()) return@withContext
        db.artists().replaceFor(trackId, names, remote.artistIds)
    }
}
