package dev.xvin.palimpsest.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.xvin.palimpsest.core.model.Service

/**
 * One song in the library. A track can be local-only (path set, no remoteId), remote-only
 * (remoteId set, no path — streamed) or both (downloaded from a service; the file is
 * linked back through its URL tag).
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["path"], unique = true),
        Index(value = ["service", "remoteId"]),
        Index("artist"),
        Index("album"),
        Index("folder"),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val year: Int?,
    val genre: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    /** Folder relative to the music root, e.g. "Murglar/Яндекс". */
    val folder: String?,
    val service: Service,
    val remoteId: String?,
    val remoteAlbumId: String?,
    val coverUrl: String?,
    val hasEmbeddedCover: Boolean = false,
    val replayGainTrackDb: Float? = null,
    val replayGainAlbumDb: Float? = null,
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRate: Int? = null,
    val bitsPerSample: Int? = null,
    val sizeBytes: Long = 0,
    val modifiedAt: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val liked: Boolean = false,
    /** Set by the update check when the service now serves different audio under this id. */
    val remoteDurationMs: Long? = null,
    val updateAvailable: Boolean = false,
) {
    val isLocal: Boolean get() = path != null
    val albumKey: String get() = "${albumArtist ?: artist}\u0000${album.orEmpty()}"
}

@Entity(tableName = "play_events", indices = [Index("trackId"), Index("startedAt")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long?,
    // Denormalised so stats survive track deletion.
    val title: String,
    val artist: String,
    val album: String?,
    val startedAt: Long,
    val listenedMs: Long,
    val completed: Boolean,
)

/** An archived (or the current) audio file of a track. */
@Entity(tableName = "track_versions", indices = [Index("trackId")])
data class TrackVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val path: String,
    val createdAt: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val fingerprint: String?,
    val codec: String?,
    val bitrateKbps: Int?,
    val note: String?,
    val isCurrent: Boolean,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val service: Service? = null,
    val remoteId: String? = null,
    val coverUrl: String? = null,
)

@Entity(tableName = "playlist_entries", primaryKeys = ["playlistId", "position"], indices = [Index("trackId")])
data class PlaylistEntryEntity(
    val playlistId: Long,
    val position: Int,
    val trackId: Long,
)

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val trackId: Long,
    val synced: String?,
    val plain: String?,
    val source: String,
    val fetchedAt: Long = System.currentTimeMillis(),
)
