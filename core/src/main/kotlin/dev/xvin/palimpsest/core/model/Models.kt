package dev.xvin.palimpsest.core.model

import kotlinx.serialization.Serializable

/** A music service a track can come from. LOCAL means "only a file on this device". */
@Serializable
enum class Service(val displayName: String) {
    LOCAL("Локальные"),
    YANDEX("Яндекс.Музыка"),
    YOUTUBE("YouTube"),
    SPOTIFY("Spotify"),
}

/** A track as a remote service describes it, before it enters the local library. */
@Serializable
data class RemoteTrack(
    val service: Service,
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val albumId: String? = null,
    val durationMs: Long = 0,
    val coverUrl: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val available: Boolean = true,
    val hasLyrics: Boolean = false,
    val hasSyncedLyrics: Boolean = false,
    /** Canonical web link; written into the URL tag on download so the file stays linked. */
    val webUrl: String? = null,
) {
    val artistLine: String get() = artists.joinToString(", ")
}

@Serializable
data class RemotePlaylist(
    val service: Service,
    val id: String,
    val title: String,
    val owner: String? = null,
    val trackCount: Int = 0,
    val coverUrl: String? = null,
)

/** A resolved, directly playable/downloadable stream. */
data class StreamInfo(
    val url: String,
    val codec: String,
    val bitrateKbps: Int = 0,
    val headers: Map<String, String> = emptyMap(),
    /** AES-128-CTR key (zero IV) for services that serve encrypted streams. */
    val aesCtrKey: ByteArray? = null,
) {
    val extension: String
        get() = when {
            codec.startsWith("flac-mp4") || codec.endsWith("-mp4") -> "m4a"
            codec.startsWith("flac") -> "flac"
            codec.startsWith("mp3") -> "mp3"
            codec.contains("aac") -> "m4a"
            codec.contains("opus") || codec.contains("webm") -> "webm"
            else -> "bin"
        }
}

data class Lyrics(
    val plain: String?,
    /** Raw LRC text with timestamps, when available. */
    val synced: String?,
    val source: String,
)

enum class Quality { LOSSLESS, HIGH, NORMAL }

class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)
class AuthRequiredException(service: Service) : Exception("${service.displayName}: требуется вход")
