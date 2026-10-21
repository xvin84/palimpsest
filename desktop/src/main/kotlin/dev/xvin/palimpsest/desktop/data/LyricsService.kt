package dev.xvin.palimpsest.desktop.data

import dev.xvin.palimpsest.core.lyrics.Lrc
import dev.xvin.palimpsest.core.model.Lyrics
import dev.xvin.palimpsest.core.model.Service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Lyrics like on the phone: the file's tag or a `.lrc` first, then Yandex, LRCLIB, NetEase and
 * Genius; timed lyrics win. What's found is cached and (by default) written into the file's tag.
 */
class LyricsService(private val graph: Graph) {
    private val cacheFile = JsonFile(File(Paths.config, "lyrics.json"), MapSerializer(String.serializer(), String.serializer())) { emptyMap() }
    private val cache = cacheFile.load().toMutableMap()

    private fun fromText(text: String, source: String) = if (Lrc.looksSynced(text)) Lyrics(null, text, source) else Lyrics(text, null, source)

    suspend fun local(t: Track): Lyrics? = withContext(Dispatchers.IO) {
        cache[t.id]?.let { return@withContext fromText(it, "Palimpsest") }
        val path = t.path ?: return@withContext null
        val f = File(path)
        val tag = runCatching { AudioFileIO.read(f).tag?.getFirst(FieldKey.LYRICS) }.getOrNull()?.takeIf { it.isNotBlank() }
        val lrc = File(f.parentFile, f.nameWithoutExtension + ".lrc").takeIf { it.isFile }?.readText()
        listOfNotNull(tag?.let { fromText(it, "tag") }, lrc?.let { fromText(it, ".lrc") }).let { all -> all.firstOrNull { it.synced != null } ?: all.firstOrNull() }
    }

    /** Every online source in parallel; timed lyrics first. */
    suspend fun online(t: Track): List<Lyrics> = coroutineScope {
        val title = t.title.replace(Regex("""\s*[(\[](?:feat|ft|prod)\.?[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "").trim()
        val sources: List<suspend () -> Lyrics?> = listOf(
            { if (t.service == Service.YANDEX && t.remoteId != null && graph.yandex.isReady) graph.yandex.lyrics(t.remoteId) else null },
            { graph.lrclib.find(t.artist, title, t.album, (t.durationMs / 1000).toInt().takeIf { it > 0 }) },
            { graph.netEase.find(t.artist, title, t.durationMs) },
            { graph.genius.find(t.artist, title) },
        )
        sources.map { s -> async(Dispatchers.IO) { runCatching { withTimeout(20_000) { s() } }.getOrNull() } }.awaitAll().filterNotNull()
    }

    /** What to show: what's on disk if it's timed, else the best online variant (saved). */
    suspend fun best(t: Track): Lyrics? {
        val l = local(t)
        if (l?.synced != null) return l
        val found = online(t).let { all -> all.firstOrNull { it.synced != null } ?: all.firstOrNull() } ?: return l
        if (l != null && found.synced == null) return l
        save(t, found)
        return found
    }

    fun save(t: Track, l: Lyrics) {
        val text = l.synced ?: l.plain ?: return
        cache[t.id] = text
        cacheFile.save(cache)
        if (graph.settings.value.embedLyrics) t.path?.let { p -> Tags.writeLyrics(File(p), text) }
    }
}
