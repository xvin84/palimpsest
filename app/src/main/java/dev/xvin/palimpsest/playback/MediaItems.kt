package dev.xvin.palimpsest.playback

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.library.ArtProvider
import java.io.File
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.i18n.label

object MediaItems {
    const val EXTRA_TRACK_ID = "track_id"
    const val EXTRA_RG_TRACK = "rg_track"
    const val EXTRA_RG_ALBUM = "rg_album"
    const val EXTERNAL_PREFIX = "external:"
    /** An archived version of a library track: `ver:<versionId>`. */
    const val VERSION_PREFIX = "ver:"
    /** The service's current (newer) audio of a track, streamed without downloading: `new:<trackId>`. */
    const val NEW_PREFIX = "new:"
    const val EXTRA_VARIANT = "variant"

    fun from(track: TrackEntity): MediaItem {
        val uri = when {
            track.path != null -> Uri.fromFile(File(track.path))
            track.remoteId != null -> StreamResolver.uriFor(track.service, track.remoteId)
            else -> Uri.EMPTY
        }
        val extras = Bundle().apply {
            putLong(EXTRA_TRACK_ID, track.id)
            track.replayGainTrackDb?.let { putFloat(EXTRA_RG_TRACK, it) }
            track.replayGainAlbumDb?.let { putFloat(EXTRA_RG_ALBUM, it) }
        }
        val meta = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setAlbumArtist(track.albumArtist)
            .setReleaseYear(track.year)
            .setGenre(track.genre)
            .setTrackNumber(track.trackNumber)
            .setDurationMs(track.durationMs.takeIf { it > 0 })
            .setArtworkUri(ArtProvider.uriFor(track.id, track.modifiedAt))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(uri)
            .setMediaMetadata(meta)
            .build()
    }

    /** A file opened from a file manager that isn't (yet) in the library. */
    fun fromExternal(context: Context, uri: Uri): MediaItem {
        val r = MediaMetadataRetriever()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var art: ByteArray? = null
        try {
            r.setDataSource(context, uri)
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            art = r.embeddedPicture
        } catch (_: Exception) {
        } finally {
            runCatching { r.release() }
        }
        if (title.isNullOrBlank()) title = displayName(context, uri)?.substringBeforeLast('.') ?: uri.lastPathSegment
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply { if (art != null) setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            .build()
        return MediaItem.Builder()
            .setMediaId(EXTERNAL_PREFIX + uri)
            .setUri(uri)
            .setMediaMetadata(meta)
            .build()
    }

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    fun trackId(item: MediaItem?): Long? =
        item?.mediaId?.toLongOrNull() ?: item?.mediaMetadata?.extras?.takeIf { it.containsKey(EXTRA_TRACK_ID) }?.getLong(EXTRA_TRACK_ID)

    /** Archived version: same track, its own file, labelled with the version date. */
    fun fromVersion(track: TrackEntity, version: dev.xvin.palimpsest.data.db.TrackVersionEntity, label: String): MediaItem {
        val base = from(track)
        val meta = base.mediaMetadata.buildUpon()
            .setAlbumTitle(label)
            .setExtras(Bundle(base.mediaMetadata.extras).apply { putString(EXTRA_VARIANT, label) })
            .build()
        return base.buildUpon().setMediaId(VERSION_PREFIX + version.id).setUri(Uri.fromFile(File(version.path))).setMediaMetadata(meta).build()
    }

    /** The newer audio on the service, streamed (useful to compare before downloading). */
    fun fromRemoteNew(track: TrackEntity): MediaItem? {
        val rid = track.remoteId ?: return null
        val base = from(track)
        val label = tr(R.string.variant_new_from, track.service.label)
        val meta = base.mediaMetadata.buildUpon()
            .setAlbumTitle(label)
            .setDurationMs(track.remoteDurationMs)
            .setExtras(Bundle(base.mediaMetadata.extras).apply { putString(EXTRA_VARIANT, label) })
            .build()
        return base.buildUpon().setMediaId(NEW_PREFIX + track.id).setUri(StreamResolver.uriFor(track.service, rid)).setMediaMetadata(meta).build()
    }
}
