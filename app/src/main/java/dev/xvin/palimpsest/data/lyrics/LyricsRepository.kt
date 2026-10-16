package dev.xvin.palimpsest.data.lyrics

import dev.xvin.palimpsest.core.lyrics.LrcLibClient
import dev.xvin.palimpsest.core.model.Lyrics
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.yandex.YandexMusicProvider
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.db.LyricsEntity
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.library.TagEdit
import dev.xvin.palimpsest.data.library.TagIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lyrics lookup order: what the user saved in the app → the file's LYRICS tag → a sidecar
 * `.lrc` next to the file. [fetchOnline] then tries Yandex (for Yandex tracks) and LRCLIB.
 */
class LyricsRepository(
    private val db: AppDatabase,
    private val yandex: YandexMusicProvider,
    private val lrclib: LrcLibClient,
    private val translator: dev.xvin.palimpsest.core.translate.Translator,
    private val translationsDir: File,
) {
    /** Translated lines (same count as [lines]), cached per track and text. null = already in Russian. */
    suspend fun translate(trackId: Long, lines: List<String>): List<String>? = withContext(Dispatchers.IO) {
        val key = lines.joinToString("\n").hashCode()
        val cache = File(translationsDir, "${trackId}_$key.txt")
        if (cache.isFile) {
            val txt = cache.readText()
            return@withContext if (txt == SAME_LANGUAGE) null else txt.split("\n")
        }
        val r = translator.translate(lines, "ru")
        translationsDir.mkdirs()
        if (r.sourceLanguage == "ru") { cache.writeText(SAME_LANGUAGE); return@withContext null }
        cache.writeText(r.lines.joinToString("\n"))
        r.lines
    }

    private companion object {
        const val SAME_LANGUAGE = "\u0000same"
    }

    suspend fun local(track: TrackEntity): Lyrics? = withContext(Dispatchers.IO) {
        db.lyrics().get(track.id)?.let { return@withContext Lyrics(it.plain, it.synced, it.source) }
        val path = track.path ?: return@withContext null
        val file = File(path)
        runCatching { TagIO.read(file).lyrics }.getOrNull()?.let { return@withContext fromText(it, "Тег файла") }
        val lrc = File(file.parentFile, file.nameWithoutExtension + ".lrc")
        if (lrc.isFile) return@withContext fromText(lrc.readText(), ".lrc файл")
        null
    }

    suspend fun fetchOnline(track: TrackEntity): Lyrics? = withContext(Dispatchers.IO) {
        val fromYandex = if (track.service == Service.YANDEX && track.remoteId != null && yandex.isReady) {
            runCatching { yandex.lyrics(track.remoteId) }.getOrNull()
        } else null
        val found = fromYandex?.takeIf { it.synced != null }
            ?: runCatching {
                lrclib.find(track.artist, cleanTitle(track.title), track.album, (track.durationMs / 1000).toInt().takeIf { it > 0 })
            }.getOrNull()
            ?: fromYandex
        if (found != null) db.lyrics().put(LyricsEntity(track.id, found.synced, found.plain, found.source))
        found
    }

    /** Saves edited lyrics; optionally also writes them into the file's tag. */
    suspend fun save(track: TrackEntity, text: String, embed: Boolean) = withContext(Dispatchers.IO) {
        val l = fromText(text, "Изменено вручную")
        if (text.isBlank()) db.lyrics().delete(track.id)
        else db.lyrics().put(LyricsEntity(track.id, l.synced, l.plain, l.source))
        if (embed && track.path != null && TagIO.canEdit(File(track.path))) {
            TagIO.write(File(track.path), TagEdit(lyrics = text))
        }
    }

    private fun fromText(text: String, source: String): Lyrics =
        if (dev.xvin.palimpsest.core.lyrics.Lrc.looksSynced(text)) Lyrics(null, text, source) else Lyrics(text, null, source)

    private fun cleanTitle(t: String) = t.replace(Regex("""\s*[(\[](?:feat|ft|prod)\.?[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "").trim()
}
