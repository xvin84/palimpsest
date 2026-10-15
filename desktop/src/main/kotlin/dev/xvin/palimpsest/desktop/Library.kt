package dev.xvin.palimpsest.desktop

import dev.xvin.palimpsest.core.library.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

data class DesktopTrack(
    val file: File,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val lyrics: String?,
)

/** Reads a music folder the same way the Android scanner does (tags first, file name as fallback). */
object Library {
    init {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    suspend fun scan(root: File): List<DesktopTrack> = withContext(Dispatchers.IO) {
        val files = root.walkTopDown().onEnter { it == root || !it.name.startsWith(".") }
            .filter { it.isFile && FileNames.isAudio(it.name) }.toList()
        val io = Dispatchers.IO.limitedParallelism(8)
        coroutineScope { files.map { f -> async(io) { read(root, f) } }.awaitAll() }
            .sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
    }

    private fun read(root: File, f: File): DesktopTrack {
        val guess = FileNames.guess(f.relativeTo(root).path)
        return runCatching {
            val af = AudioFileIO.read(f)
            val tag = af.tag
            fun t(k: FieldKey) = runCatching { tag?.getFirst(k) }.getOrNull()?.takeIf { it.isNotBlank() }
            DesktopTrack(
                file = f,
                title = t(FieldKey.TITLE) ?: guess.title,
                artist = t(FieldKey.ARTIST) ?: guess.artist ?: "Неизвестный исполнитель",
                album = t(FieldKey.ALBUM) ?: guess.album,
                durationMs = (af.audioHeader.preciseTrackLength * 1000).toLong(),
                lyrics = t(FieldKey.LYRICS),
            )
        }.getOrElse { DesktopTrack(f, guess.title, guess.artist ?: "Неизвестный исполнитель", guess.album, 0, null) }
    }

    fun cover(f: File): ByteArray? = runCatching { AudioFileIO.read(f).tag?.firstArtwork?.binaryData }.getOrNull()
}
