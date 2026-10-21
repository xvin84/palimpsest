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

    fun write(f: File, title: String, artist: String, album: String?, year: Int?, track: Int?, lyrics: String?, url: String?, cover: ByteArray?) {
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
