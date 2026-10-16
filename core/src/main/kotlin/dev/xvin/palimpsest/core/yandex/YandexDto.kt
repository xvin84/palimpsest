package dev.xvin.palimpsest.core.yandex

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire format of api.music.yandex.net. Only the fields we use; everything else is ignored.
// Ids are Strings on purpose: the API mixes numbers and strings, and lenient JSON reads both into a String.

@Serializable
internal data class YEnvelope(val result: JsonElement? = null, val error: JsonElement? = null)

@Serializable
internal data class YArtist(val id: String? = null, val name: String = "")

@Serializable
internal data class YTrackPosition(val volume: Int? = null, val index: Int? = null)

@Serializable
internal data class YAlbum(
    val id: String? = null,
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
internal data class YOwner(val uid: String? = null, val login: String? = null, val name: String? = null)

@Serializable
internal data class YCover(val type: String? = null, val uri: String? = null, val itemsUri: List<String>? = null)

@Serializable
internal data class YPlaylistTrack(val id: String? = null, val track: YTrack? = null)

@Serializable
internal data class YPlaylist(
    val uid: String? = null,
    val kind: String,
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
internal data class YAccount(val uid: String? = null, val login: String? = null, val displayName: String? = null, val fullName: String? = null)

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

@Serializable
internal data class YDescription(val text: String? = null)

@Serializable
internal data class YArtistFull(
    val id: String? = null,
    val name: String = "",
    val cover: YCover? = null,
    val ogImage: String? = null,
    val genres: List<String> = emptyList(),
    val likesCount: Int? = null,
    val description: YDescription? = null,
)

@Serializable
internal data class YAlbumFull(
    val id: String? = null,
    val title: String = "",
    val year: Int? = null,
    val coverUri: String? = null,
    val artists: List<YArtist> = emptyList(),
    val trackCount: Int = 0,
    val type: String? = null,
    val volumes: List<List<YTrack>>? = null,
)

@Serializable
internal data class YBriefInfo(
    val artist: YArtistFull,
    val albums: List<YAlbumFull> = emptyList(),
    val alsoAlbums: List<YAlbumFull> = emptyList(),
    val popularTracks: List<YTrack> = emptyList(),
    val similarArtists: List<YArtistFull> = emptyList(),
)

@Serializable
internal data class YSimilar(val similarTracks: List<YTrack> = emptyList())

@Serializable
internal data class YSearchArtists(val results: List<YArtistFull> = emptyList())

@Serializable
internal data class YSearchArtistsResult(val artists: YSearchArtists? = null)

@Serializable
internal data class YSequence(val type: String? = null, val track: YTrack? = null)

@Serializable
internal data class YRotorSession(
    val radioSessionId: String? = null,
    val batchId: String? = null,
    val sequence: List<YSequence> = emptyList(),
)
