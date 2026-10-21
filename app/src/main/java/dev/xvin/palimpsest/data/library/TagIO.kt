package dev.xvin.palimpsest.data.library

import android.media.MediaMetadataRetriever
import android.util.Log
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.id3.framebody.FrameBodyWXXX
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** Everything we read out of an audio file. */
data class TagData(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val year: Int?,
    val genre: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val url: String?,
    val lyrics: String?,
    val replayGainTrackDb: Float?,
    val replayGainAlbumDb: Float?,
    val hasCover: Boolean,
    val codec: String?,
    val bitrateKbps: Int?,
    val sampleRate: Int?,
    val bitsPerSample: Int?,
)

/** Fields the tag editor can change. null = leave as is, "" = clear. */
data class TagEdit(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val trackNumber: String? = null,
    val discNumber: String? = null,
    val lyrics: String? = null,
    val url: String? = null,
    /** New cover image bytes; empty array removes the cover. */
    val cover: ByteArray? = null,
    val coverMime: String = "image/jpeg",
    /** Free-form fields (REPLAYGAIN_TRACK_GAIN…): Vorbis comments, MP4 «----» atoms, ID3 TXXX. */
    val extra: Map<String, String> = emptyMap(),
)

object TagIO {
    private const val TAG = "TagIO"

    init {
        TagOptionSingleton.getInstance().isAndroid = true
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    /** Formats jaudiotagger can both read and write. */
    private val TAGGABLE = setOf("mp3", "flac", "m4a", "mp4", "aac", "ogg", "oga", "opus", "wav", "aif", "aiff", "wma", "dsf")

    fun canEdit(file: File) = file.extension.lowercase() in TAGGABLE

    fun read(file: File): TagData {
        if (canEdit(file)) {
            runCatching { return readJAudioTagger(file) }
                .onFailure { Log.w(TAG, "jaudiotagger failed on ${file.name}: ${it.message}") }
        }
        return readRetriever(file)
    }

    private fun readJAudioTagger(file: File): TagData {
        val af = AudioFileIO.read(file)
        val h = af.audioHeader
        val tag: Tag? = af.tag
        fun f(k: FieldKey) = runCatching { tag?.getFirst(k) }.getOrNull()?.takeIf { it.isNotBlank() }?.trim()
        val url = custom(tag, "URL") ?: custom(tag, "WWWAUDIOFILE") ?: f(FieldKey.URL_OFFICIAL_ARTIST_SITE)
            ?: f(FieldKey.URL_OFFICIAL_RELEASE_SITE) // where older versions of this app put it
            ?: f(FieldKey.COMMENT)?.takeIf { it.startsWith("http") && ' ' !in it }
        val lyrics = f(FieldKey.LYRICS) ?: custom(tag, "UNSYNCEDLYRICS") ?: custom(tag, "SYNCEDLYRICS")
        val hasCover = runCatching { tag?.firstArtwork != null }.getOrDefault(false)
        val durationMs = (h.preciseTrackLength * 1000).toLong()
        return TagData(
            title = f(FieldKey.TITLE),
            artist = f(FieldKey.ARTIST)?.let { joinMulti(tag, FieldKey.ARTIST) ?: it },
            album = f(FieldKey.ALBUM),
            albumArtist = f(FieldKey.ALBUM_ARTIST),
            year = f(FieldKey.YEAR)?.take(4)?.toIntOrNull(),
            genre = f(FieldKey.GENRE),
            trackNumber = f(FieldKey.TRACK)?.substringBefore('/')?.toIntOrNull(),
            discNumber = f(FieldKey.DISC_NO)?.substringBefore('/')?.toIntOrNull(),
            durationMs = durationMs,
            url = url,
            lyrics = lyrics,
            replayGainTrackDb = parseGain(custom(tag, "REPLAYGAIN_TRACK_GAIN")),
            replayGainAlbumDb = parseGain(custom(tag, "REPLAYGAIN_ALBUM_GAIN")),
            hasCover = hasCover,
            codec = h.format?.substringBefore(' ')?.lowercase() ?: file.extension.lowercase(),
            bitrateKbps = h.bitRateAsNumber.toInt().takeIf { it > 0 },
            sampleRate = h.sampleRateAsNumber.takeIf { it > 0 },
            bitsPerSample = h.bitsPerSample.takeIf { it > 0 },
        )
    }

    /** Vorbis comments often store several ARTIST fields instead of "A, B". */
    private fun joinMulti(tag: Tag?, key: FieldKey): String? {
        val all = runCatching { tag?.getAll(key) }.getOrNull()?.filter { it.isNotBlank() } ?: return null
        return if (all.size > 1) all.joinToString(", ") else null
    }

    private fun parseGain(s: String?): Float? =
        s?.replace("dB", "", ignoreCase = true)?.trim()?.replace(',', '.')?.toFloatOrNull()

    /** Reads a non-standard field (TXXX/WXXX in ID3, free-form in Vorbis/MP4). */
    private fun custom(tag: Tag?, name: String): String? {
        val t = when (tag) {
            is FlacTag -> tag.vorbisCommentTag
            else -> tag
        } ?: return null
        val v = when (t) {
            is VorbisCommentTag -> t.getFirst(name)
            is Mp4Tag -> t.getFirst("----:com.apple.iTunes:$name").ifEmpty { t.getFirst("----:com.apple.iTunes:${name.lowercase()}") }
            is AbstractID3v2Tag -> id3Custom(t, name)
            else -> null
        }
        return v?.takeIf { it.isNotBlank() }?.trim()
    }

    private fun id3Custom(t: AbstractID3v2Tag, name: String): String? {
        for (frameId in listOf("TXXX", "WXXX")) {
            val frames = runCatching { t.getFields(frameId) }.getOrNull() ?: continue
            for (fr in frames) {
                val body = (fr as? AbstractID3v2Frame)?.body ?: continue
                when (body) {
                    is FrameBodyTXXX -> if (body.description.equals(name, true)) return body.text
                    is FrameBodyWXXX -> if (body.description.equals(name, true) || name == "URL") return body.urlLinkWithoutTrailingNulls
                }
            }
        }
        return null
    }

    private fun readRetriever(file: File): TagData {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            fun m(k: Int) = r.extractMetadata(k)?.takeIf { it.isNotBlank() }?.trim()
            return TagData(
                title = m(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = m(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = m(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                year = m(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull(),
                genre = m(MediaMetadataRetriever.METADATA_KEY_GENRE),
                trackNumber = m(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.toIntOrNull(),
                discNumber = m(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.toIntOrNull(),
                durationMs = m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                url = null,
                lyrics = null,
                replayGainTrackDb = null,
                replayGainAlbumDb = null,
                hasCover = r.embeddedPicture != null,
                codec = file.extension.lowercase(),
                bitrateKbps = m(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.div(1000),
                sampleRate = m(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull(),
                bitsPerSample = m(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "retriever failed on ${file.name}: ${e.message}")
            return TagData(null, null, null, null, null, null, null, null, 0, null, null, null, null, false, file.extension, null, null, null)
        } finally {
            runCatching { r.release() }
        }
    }

    /** Embedded cover bytes, or a cover.jpg/folder.jpg next to the file. */
    fun coverBytes(file: File): ByteArray? {
        val r = MediaMetadataRetriever()
        val embedded = try {
            r.setDataSource(file.absolutePath); r.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
        if (embedded != null) return embedded
        val dir = file.parentFile ?: return null
        val names = listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "album.jpg", "Cover.jpg", "Folder.jpg")
        return names.asSequence().map { File(dir, it) }.firstOrNull { it.isFile }?.readBytes()
    }

    fun write(file: File, edit: TagEdit) {
        val af: AudioFile = AudioFileIO.read(file)
        val tag = af.tagOrCreateAndSetDefault
        fun set(key: FieldKey, v: String?) {
            when {
                v == null -> Unit
                v.isBlank() -> runCatching { tag.deleteField(key) }
                else -> tag.setField(key, v)
            }
        }
        set(FieldKey.TITLE, edit.title)
        set(FieldKey.ARTIST, edit.artist)
        set(FieldKey.ALBUM, edit.album)
        set(FieldKey.ALBUM_ARTIST, edit.albumArtist)
        set(FieldKey.YEAR, edit.year)
        set(FieldKey.GENRE, edit.genre)
        set(FieldKey.TRACK, edit.trackNumber)
        set(FieldKey.DISC_NO, edit.discNumber)
        set(FieldKey.LYRICS, edit.lyrics)
        if (edit.url != null) writeCustom(tag, "URL", edit.url)
        edit.extra.forEach { (k, v) -> writeCustom(tag, k, v) }
        edit.cover?.let { bytes ->
            tag.deleteArtworkField()
            if (bytes.isNotEmpty() && tag is FlacTag) {
                // FLAC: a PICTURE block built directly — the generic path asks ImageIO (absent on
                // Android) for the image size and throws, so covers never made it into FLACs.
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                tag.setField(tag.createArtworkField(bytes, 3, edit.coverMime, "", o.outWidth.coerceAtLeast(0), o.outHeight.coerceAtLeast(0), 24, 0))
            } else if (bytes.isNotEmpty()) {
                val art = AndroidArtwork().apply {
                    binaryData = bytes
                    mimeType = edit.coverMime
                    pictureType = 3 // front cover
                    description = ""
                }
                tag.setField(art)
            }
        }
        af.commit()
    }

    /**
     * A non-standard field, written where [custom] reads it back: a Vorbis comment, an MP4
     * `----:com.apple.iTunes:NAME` atom, an ID3 WOAR frame for the link. Links used to go into a field nothing read back, and MP3/M4A downloads lost
     * their service link on a full rescan.
     */
    private fun writeCustom(tag: Tag, name: String, value: String) {
        val t = if (tag is FlacTag) tag.vorbisCommentTag else tag
        runCatching {
            when (t) {
                is VorbisCommentTag -> if (value.isBlank()) t.deleteField(name) else t.setField(name, value)
                is Mp4Tag -> {
                    val id = "----:com.apple.iTunes:$name"
                    t.deleteField(id)
                    if (value.isNotBlank()) t.setField(org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField(id, "com.apple.iTunes", name, value))
                }
                // ID3: only the link (WOAR). Other free-form fields stay untouched — jaudiotagger keeps
                // all TXXX frames under one id, and setting one would wipe the others.
                is AbstractID3v2Tag -> if (name == "URL" && value.isNotBlank()) t.setField(FieldKey.URL_OFFICIAL_ARTIST_SITE, value)
                else -> if (value.isNotBlank()) tag.setField(FieldKey.URL_OFFICIAL_ARTIST_SITE, value)
            }
        }
    }
}
