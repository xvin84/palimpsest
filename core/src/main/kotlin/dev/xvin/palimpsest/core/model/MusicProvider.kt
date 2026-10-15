package dev.xvin.palimpsest.core.model

/**
 * Everything the app needs from a music service. A provider only talks to the network;
 * the app decides what to store, cache or download.
 */
interface MusicProvider {
    val service: Service

    /** Whether the provider can be used right now (e.g. user is logged in, if login is required). */
    val isReady: Boolean

    suspend fun search(query: String, page: Int = 0): List<RemoteTrack>

    suspend fun likedTracks(): List<RemoteTrack> = emptyList()

    suspend fun playlists(): List<RemotePlaylist> = emptyList()

    suspend fun playlistTracks(playlistId: String): List<RemoteTrack> = emptyList()

    /** Fresh metadata for a single track, used to detect that a track was re-uploaded. */
    suspend fun track(id: String): RemoteTrack?

    /**
     * A direct stream. [forDownload] asks for a format that can carry tags (e.g. M4A instead of
     * WebM on YouTube) since the file will be kept.
     */
    suspend fun resolveStream(id: String, quality: Quality = Quality.LOSSLESS, forDownload: Boolean = false): StreamInfo

    suspend fun lyrics(id: String): Lyrics? = null
}
