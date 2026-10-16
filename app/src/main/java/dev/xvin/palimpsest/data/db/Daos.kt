package dev.xvin.palimpsest.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import dev.xvin.palimpsest.core.model.Service
import kotlinx.coroutines.flow.Flow

data class ScanStamp(val id: Long, val path: String, val sizeBytes: Long, val modifiedAt: Long, val remoteId: String?)

data class AlbumRow(
    val album: String,
    val artist: String,
    val year: Int?,
    val trackCount: Int,
    val coverTrackId: Long,
)

data class ArtistRow(val artist: String, val trackCount: Int, val albumCount: Int, val coverTrackId: Long)

data class FolderRow(val folder: String, val trackCount: Int)

data class CountRow(val label: String, val plays: Int, val listenedMs: Long, val trackId: Long?)

data class DayRow(val day: String, val plays: Int, val listenedMs: Long)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observe(id: Long): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getMany(ids: List<Long>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE path = :path")
    suspend fun byPath(path: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE service = :service AND remoteId = :remoteId LIMIT 1")
    suspend fun byRemote(service: Service, remoteId: String): TrackEntity?

    @Query("SELECT id, path, sizeBytes, modifiedAt, remoteId FROM tracks WHERE path IS NOT NULL")
    suspend fun scanStamps(): List<ScanStamp>

    @Query("SELECT * FROM tracks WHERE service = :service AND remoteId IS NOT NULL")
    suspend fun linkedTo(service: Service): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE path IS NOT NULL AND remoteId IS NULL")
    suspend fun unlinkedLocal(): List<TrackEntity>

    /** Downloaded Yandex tracks stored lossy (MP3/AAC/…) — candidates for a FLAC upgrade. */
    @Query(
        """
        SELECT * FROM tracks WHERE service = 'YANDEX' AND remoteId IS NOT NULL AND path IS NOT NULL
        AND (codec IS NULL OR lower(codec) NOT IN ('flac', 'alac', 'wav', 'ape', 'wv', 'aiff'))
        """,
    )
    fun observeLossyYandex(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE updateAvailable = 1 ORDER BY artist, title")
    fun observeUpdates(): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(track: TrackEntity): Long

    @Upsert
    suspend fun upsert(tracks: List<TrackEntity>)

    @Update
    suspend fun update(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("UPDATE tracks SET path = NULL WHERE id IN (:ids)")
    suspend fun detachFiles(ids: List<Long>)

    @Query("UPDATE tracks SET liked = :liked WHERE id = :id")
    suspend fun setLiked(id: Long, liked: Boolean)

    @Query(
        """
        SELECT album, COALESCE(albumArtist, artist) AS artist, MAX(year) AS year, COUNT(*) AS trackCount, MIN(id) AS coverTrackId
        FROM tracks WHERE album IS NOT NULL AND album != ''
        GROUP BY album COLLATE NOCASE, COALESCE(albumArtist, artist) COLLATE NOCASE
        ORDER BY artist COLLATE NOCASE, year, album COLLATE NOCASE
        """,
    )
    fun observeAlbums(): Flow<List<AlbumRow>>


    @Query("SELECT folder, COUNT(*) AS trackCount FROM tracks WHERE folder IS NOT NULL GROUP BY folder ORDER BY folder COLLATE NOCASE")
    fun observeFolders(): Flow<List<FolderRow>>

    @Query("SELECT * FROM tracks WHERE album = :album COLLATE NOCASE AND COALESCE(albumArtist, artist) = :artist COLLATE NOCASE ORDER BY discNumber, trackNumber, title")
    fun observeAlbumTracks(album: String, artist: String): Flow<List<TrackEntity>>


    @Query("SELECT * FROM tracks WHERE folder = :folder ORDER BY path")
    fun observeFolderTracks(folder: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE liked = 1 ORDER BY addedAt DESC")
    fun observeLiked(): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM tracks WHERE title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%' OR album LIKE '%' || :q || '%'
        ORDER BY CASE WHEN title LIKE :q || '%' THEN 0 WHEN artist LIKE :q || '%' THEN 1 ELSE 2 END, title COLLATE NOCASE LIMIT 200
        """,
    )
    suspend fun search(q: String): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks")
    fun observeCount(): Flow<Int>

    // --- Smart playlists ---

    @Query(
        """
        SELECT t.* FROM tracks t JOIN (
            SELECT trackId, COUNT(*) AS c FROM play_events WHERE trackId IS NOT NULL GROUP BY trackId
        ) p ON p.trackId = t.id ORDER BY p.c DESC LIMIT 50
        """,
    )
    fun observeMostPlayed(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY addedAt DESC LIMIT 50")
    fun observeRecentlyAdded(): Flow<List<TrackEntity>>

    /** Tracks you used to love (liked or played 3+ times) that haven't been played for :sinceDays days. */
    @Query(
        """
        SELECT t.* FROM tracks t LEFT JOIN (
            SELECT trackId, COUNT(*) AS c, MAX(startedAt) AS lastPlayed FROM play_events WHERE trackId IS NOT NULL GROUP BY trackId
        ) p ON p.trackId = t.id
        WHERE (t.liked = 1 OR p.c >= 3) AND (p.lastPlayed IS NULL OR p.lastPlayed < :before)
        ORDER BY COALESCE(p.c, 0) DESC, RANDOM() LIMIT 50
        """,
    )
    fun observeForgotten(before: Long): Flow<List<TrackEntity>>
}

@Dao
interface PlayEventDao {
    @Insert
    suspend fun insert(e: PlayEventEntity): Long

    @Query("UPDATE play_events SET listenedMs = :listenedMs, completed = :completed WHERE id = :id")
    suspend fun updateProgress(id: Long, listenedMs: Long, completed: Boolean)

    @Query("SELECT * FROM play_events WHERE scrobbled = 0 AND startedAt > :since ORDER BY startedAt LIMIT 500")
    suspend fun unscrobbled(since: Long): List<PlayEventEntity>

    @Query("UPDATE play_events SET scrobbled = 1 WHERE id IN (:ids)")
    suspend fun markScrobbled(ids: List<Long>)

    @Query("SELECT * FROM play_events ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 300): Flow<List<PlayEventEntity>>

    @Query(
        """
        SELECT title || ' — ' || artist AS label, COUNT(*) AS plays, SUM(listenedMs) AS listenedMs, MAX(trackId) AS trackId
        FROM play_events WHERE startedAt >= :since GROUP BY title COLLATE NOCASE, artist COLLATE NOCASE
        ORDER BY plays DESC, listenedMs DESC LIMIT :limit
        """,
    )
    fun observeTopTracks(since: Long, limit: Int = 20): Flow<List<CountRow>>

    @Query(
        """
        SELECT ta.name AS label, COUNT(*) AS plays, SUM(p.listenedMs) AS listenedMs, MAX(p.trackId) AS trackId
        FROM play_events p JOIN track_artists ta ON ta.trackId = p.trackId
        WHERE p.startedAt >= :since GROUP BY ta.name COLLATE NOCASE
        ORDER BY plays DESC LIMIT :limit
        """,
    )
    fun observeTopArtists(since: Long, limit: Int = 20): Flow<List<CountRow>>

    @Query(
        """
        SELECT strftime('%Y-%m-%d', startedAt / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS plays, SUM(listenedMs) AS listenedMs
        FROM play_events WHERE startedAt >= :since GROUP BY day ORDER BY day
        """,
    )
    fun observeDays(since: Long): Flow<List<DayRow>>

    @Query("SELECT COUNT(*) FROM play_events WHERE trackId = :trackId")
    fun observePlayCount(trackId: Long): Flow<Int>

    @Query("SELECT trackId FROM play_events WHERE trackId IS NOT NULL GROUP BY trackId ORDER BY COUNT(*) DESC LIMIT :limit")
    suspend fun mostPlayedIds(limit: Int): List<Long>
}

@Dao
interface VersionDao {
    @Query("SELECT * FROM track_versions WHERE trackId = :trackId ORDER BY createdAt DESC")
    fun observeFor(trackId: Long): Flow<List<TrackVersionEntity>>

    @Query("SELECT * FROM track_versions WHERE trackId = :trackId ORDER BY createdAt DESC")
    suspend fun forTrack(trackId: Long): List<TrackVersionEntity>

    @Query("SELECT * FROM track_versions WHERE id = :id")
    suspend fun byId(id: Long): TrackVersionEntity?

    @Insert
    suspend fun insert(v: TrackVersionEntity): Long

    @Update
    suspend fun update(v: TrackVersionEntity)

    @Query("UPDATE track_versions SET isCurrent = (id = :versionId) WHERE trackId = :trackId")
    suspend fun markCurrent(trackId: Long, versionId: Long)

    @Query("DELETE FROM track_versions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM track_versions WHERE trackId = :trackId")
    fun observeCount(trackId: Long): Flow<Int>
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE service = :service AND remoteId = :remoteId")
    suspend fun byRemote(service: Service, remoteId: String): PlaylistEntity?

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT t.* FROM playlist_entries e JOIN tracks t ON t.id = e.trackId WHERE e.playlistId = :id ORDER BY e.position")
    fun observeTracks(id: Long): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM playlist_entries WHERE playlistId = :id")
    suspend fun size(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :id")
    suspend fun clear(id: Long)

    @Transaction
    suspend fun replace(id: Long, trackIds: List<Long>) {
        clear(id)
        insertEntries(trackIds.mapIndexed { i, t -> PlaylistEntryEntity(id, i, t) })
    }

    @Transaction
    suspend fun append(id: Long, trackIds: List<Long>) {
        val start = size(id)
        insertEntries(trackIds.mapIndexed { i, t -> PlaylistEntryEntity(id, start + i, t) })
    }
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE trackId = :trackId")
    suspend fun get(trackId: Long): LyricsEntity?

    @Upsert
    suspend fun put(l: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE trackId = :trackId")
    suspend fun delete(trackId: Long)
}

data class TrackArtistsRow(val trackId: Long, val artist: String)

@Dao
interface ArtistDao {
    @Query(
        """
        SELECT ta.name AS artist, COUNT(DISTINCT ta.trackId) AS trackCount,
               COUNT(DISTINCT t.album) AS albumCount, MIN(t.id) AS coverTrackId
        FROM track_artists ta JOIN tracks t ON t.id = ta.trackId
        GROUP BY ta.name COLLATE NOCASE ORDER BY ta.name COLLATE NOCASE
        """,
    )
    fun observeArtists(): Flow<List<ArtistRow>>

    @Query(
        """
        SELECT t.* FROM tracks t JOIN track_artists ta ON ta.trackId = t.id
        WHERE ta.name = :name COLLATE NOCASE
        ORDER BY t.year DESC, t.album, t.trackNumber, t.title
        """,
    )
    fun observeTracksOf(name: String): Flow<List<TrackEntity>>

    @Query("SELECT remoteId FROM track_artists WHERE name = :name COLLATE NOCASE AND remoteId IS NOT NULL LIMIT 1")
    suspend fun remoteIdOf(name: String): String?

    @Query("SELECT * FROM track_artists WHERE trackId = :trackId ORDER BY position")
    suspend fun forTrack(trackId: Long): List<TrackArtistEntity>

    @Query("SELECT * FROM track_artists WHERE trackId = :trackId ORDER BY position")
    fun observeForTrack(trackId: Long): Flow<List<TrackArtistEntity>>

    @Query("SELECT * FROM track_artists")
    suspend fun all(): List<TrackArtistEntity>

    @Query("SELECT id AS trackId, artist FROM tracks")
    suspend fun trackArtistLines(): List<TrackArtistsRow>

    @Query("DELETE FROM track_artists WHERE trackId = :trackId")
    suspend fun deleteFor(trackId: Long)

    @Query("DELETE FROM track_artists WHERE trackId NOT IN (SELECT id FROM tracks)")
    suspend fun deleteOrphans()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<TrackArtistEntity>)

    @Transaction
    suspend fun replaceFor(trackId: Long, names: List<String>, remoteIds: List<String?>) {
        deleteFor(trackId)
        insertAll(names.mapIndexed { i, n -> TrackArtistEntity(trackId, i, n, remoteIds.getOrNull(i)) })
    }
}
