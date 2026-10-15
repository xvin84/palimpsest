package dev.xvin.palimpsest.core.yandex

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire format of api.music.yandex.net. Only the fields we use; everything else is ignored.

@Serializable
internal data class YEnvelope(val result: JsonElement? = null, val error: JsonElement? = null)

@Serializable
internal data class YArtist(val id: Long? = null, val name: String = "")

@Serializable
internal data class YTrackPosition(val volume: Int? = null, val index: Int? = null)

@Serializable
internal data class YAlbum(
    val id: Long? = null,
    val title: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverUri: String? = null,
    val trackPosition: YTrackPosition? = null,
)

@Serializable
internal data class YLyricsInfo(val hasAvailableSyncLyrics: Boolean = false, val hasAvailableTextLyrics: Boolean = false)

@Serializable
internal data class YTrack(
    val id: String,
    val realId: String? = null,
    val title: String = "",
    val version: String? = null,
    val durationMs: Long = 0,
    val available: Boolean = true,
    val artists: List<YArtist> = emptyList(),
    val albums: List<YAlbum> = emptyList(),
    val coverUri: String? = null,
    val lyricsInfo: YLyricsInfo? = null,
)

@Serializable
internal data class YLikedTrack(val id: String, val albumId: String? = null, val timestamp: String? = null)

@Serializable
internal data class YLibrary(val tracks: List<YLikedTrack> = emptyList(), val revision: Long? = null)

@Serializable
internal data class YLikes(val library: YLibrary)

@Serializable
internal data class YOwner(val uid: Long? = null, val login: String? = null, val name: String? = null)

@Serializable
internal data class YCover(val type: String? = null, val uri: String? = null, val itemsUri: List<String>? = null)

@Serializable
internal data class YPlaylistTrack(val id: String? = null, val track: YTrack? = null)

@Serializable
internal data class YPlaylist(
    val uid: Long? = null,
    val kind: Long,
    val title: String = "",
    val trackCount: Int = 0,
    val owner: YOwner? = null,
    val cover: YCover? = null,
    val ogImage: String? = null,
    val tracks: List<YPlaylistTrack>? = null,
)

@Serializable
internal data class YSearchTracks(val results: List<YTrack> = emptyList(), val total: Int = 0)

@Serializable
internal data class YSearch(val tracks: YSearchTracks? = null)

@Serializable
internal data class YAccount(val uid: Long? = null, val login: String? = null, val displayName: String? = null, val fullName: String? = null)

@Serializable
internal data class YPlus(val hasPlus: Boolean = false)

@Serializable
internal data class YStatus(val account: YAccount, val plus: YPlus? = null)

@Serializable
internal data class YFileDownloadInfo(
    val trackId: String? = null,
    val quality: String? = null,
    val codec: String = "",
    val bitrate: Int = 0,
    val transport: String? = null,
    val url: String? = null,
    val urls: List<String> = emptyList(),
    val key: String? = null,
)

@Serializable
internal data class YFileInfo(val downloadInfo: YFileDownloadInfo? = null)

@Serializable
internal data class YLegacyDownloadInfo(
    val codec: String,
    val bitrateInKbps: Int = 0,
    val preview: Boolean = false,
    val downloadInfoUrl: String,
)

@Serializable
internal data class YLyrics(val downloadUrl: String? = null)

@Serializable
data class YDeviceCode(
    val device_code: String,
    val user_code: String,
    val verification_url: String = "https://ya.ru/device",
    val interval: Int = 5,
    val expires_in: Int = 300,
)

@Serializable
data class YToken(
    val access_token: String,
    val refresh_token: String? = null,
    val expires_in: Long? = null,
)

@Serializable
internal data class YOAuthError(val error: String? = null, val error_description: String? = null)
