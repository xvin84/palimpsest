package dev.xvin.palimpsest.desktop.data

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.io.File

/** Writing tags with jaudiotagger (the same library the Android app uses). */
object Tags {
    private val taggable = setOf("mp3", "flac", "m4a", "mp4", "aac", "ogg", "oga", "opus", "wav", "aif", "aiff", "wma", "dsf")
    fun canEdit(f: File) = f.extension.lowercase() in taggable

    fun writeLyrics(f: File, text: String) {
        if (!f.isFile || !canEdit(f)) return
        runCatching {
            val af = AudioFileIO.read(f)
            val tag = af.tagOrCreateAndSetDefault
            if (tag.getFirst(FieldKey.LYRICS).trim() == text.trim()) return
            tag.setField(FieldKey.LYRICS, text)
            af.commit()
        }
    }

    private val copied = listOf(
        FieldKey.TITLE, FieldKey.ARTIST, FieldKey.ALBUM, FieldKey.ALBUM_ARTIST, FieldKey.YEAR, FieldKey.GENRE,
        FieldKey.TRACK, FieldKey.TRACK_TOTAL, FieldKey.DISC_NO, FieldKey.DISC_TOTAL, FieldKey.COMPOSER, FieldKey.LYRICS, FieldKey.COMMENT,
    )

    /**
     * The file's tags and cover onto its new FLAC (a new version of the same song keeps the user's
     * tags); [url] links it to its service track, [fallbackCover] goes in when the old file had none.
     */
    fun copy(from: File, to: File, url: String?, fallbackCover: ByteArray?) {
        val src = runCatching { AudioFileIO.read(from).tag }.getOrNull()
        val af = AudioFileIO.read(to)
        val tag = af.tagOrCreateAndSetDefault
        if (src != null) for (k in copied) {
            val v = runCatching { src.getFirst(k) }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            runCatching { tag.setField(k, v) }
        }
        if (url != null) {
            val v = if (tag is FlacTag) tag.vorbisCommentTag else tag
            if (v is VorbisCommentTag) runCatching { v.setField("URL", url) }
        }
        val cover = runCatching { src?.firstArtwork?.binaryData }.getOrNull()?.takeIf { it.size > 100 } ?: fallbackCover
        if (cover != null) {
            val tmp = File.createTempFile("cover", ".jpg").apply { writeBytes(cover) }
            runCatching { tag.deleteArtworkField(); tag.setField(ArtworkFactory.createArtworkFromFile(tmp)) }
            tmp.delete()
        }
        af.commit()
    }

    /** The fields the tag editor shows. */
    enum class Field(val key: FieldKey) {
        TITLE(FieldKey.TITLE), ARTIST(FieldKey.ARTIST), ALBUM(FieldKey.ALBUM), ALBUM_ARTIST(FieldKey.ALBUM_ARTIST),
        YEAR(FieldKey.YEAR), TRACK(FieldKey.TRACK), DISC(FieldKey.DISC_NO), GENRE(FieldKey.GENRE), LYRICS(FieldKey.LYRICS),
    }

    /** What the file says for each [Field] (blank when nothing), and its picture. */
    fun read(f: File): Pair<Map<Field, String>, ByteArray?> = runCatching {
        val tag = AudioFileIO.read(f).tag
        Field.entries.associateWith { runCatching { tag?.getFirst(it.key) }.getOrNull().orEmpty() } to
            runCatching { tag?.firstArtwork?.binaryData }.getOrNull()?.takeIf { it.size > 100 }
    }.getOrElse { Field.entries.associateWith { "" } to null }

    /** Sets the [changes] (a blank value clears that field) and, when given, the cover. Throws when the file can't be written. */
    fun update(f: File, changes: Map<Field, String>, cover: ByteArray?) {
        val af = AudioFileIO.read(f)
        val tag = af.tagOrCreateAndSetDefault
        for ((field, value) in changes) {
            if (value.isBlank()) runCatching { tag.deleteField(field.key) } else tag.setField(field.key, value.trim())
        }
        if (cover != null) {
            val tmp = File.createTempFile("cover", ".jpg").apply { writeBytes(cover) }
            try { tag.deleteArtworkField(); tag.setField(ArtworkFactory.createArtworkFromFile(tmp)) } finally { tmp.delete() }
        }
        af.commit()
    }

    fun write(f: File, title: String,artist: String, album: String?, year: Int?, track: Int?, lyrics: String?, url: String?, cover: ByteArray?) {
        if (!canEdit(f)) return
        runCatching {
            val af = AudioFileIO.read(f)
            val tag = af.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, title)
            tag.setField(FieldKey.ARTIST, artist)
            album?.let { tag.setField(FieldKey.ALBUM, it) }
            year?.let { tag.setField(FieldKey.YEAR, it.toString()) }
            track?.let { tag.setField(FieldKey.TRACK, it.toString()) }
            lyrics?.let { tag.setField(FieldKey.LYRICS, it) }
            if (url != null) {
                val v = if (tag is FlacTag) tag.vorbisCommentTag else tag
                if (v is VorbisCommentTag) v.setField("URL", url) else runCatching { tag.setField(FieldKey.URL_OFFICIAL_RELEASE_SITE, url) }
            }
            if (cover != null) {
                val tmp = File.createTempFile("cover", ".jpg").apply { writeBytes(cover) }
                runCatching { tag.deleteArtworkField(); tag.setField(ArtworkFactory.createArtworkFromFile(tmp)) }
                tmp.delete()
            }
            af.commit()
        }
    }
}
