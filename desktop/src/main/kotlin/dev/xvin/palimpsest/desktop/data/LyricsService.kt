package dev.xvin.palimpsest.desktop.data

import dev.xvin.palimpsest.core.lyrics.Lrc
import dev.xvin.palimpsest.core.lyrics.cleaned
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

    /**
     * Lyrics from text, without what services put in place of lyrics — a stand-in («subscribe to Plus»)
     * may have been saved or written into a file before it was recognised; then there's nothing.
     */
    private fun fromText(text: String, source: String) = (if (Lrc.looksSynced(text)) Lyrics(null, text, source) else Lyrics(text, null, source)).cleaned()

    suspend fun local(t: Track): Lyrics? = withContext(Dispatchers.IO) {
        cache[t.id]?.let { fromText(it, cache[SOURCE + t.id] ?: SAVED) }?.let { return@withContext it }
        inFiles(t).let { all -> all.firstOrNull { it.synced != null } ?: all.firstOrNull() }
    }

    /** The file's tag and a `.lrc` next to it. */
    private fun inFiles(t: Track): List<Lyrics> {
        val f = File(t.path ?: return emptyList())
        val tag = runCatching { AudioFileIO.read(f).tag?.getFirst(FieldKey.LYRICS) }.getOrNull()?.takeIf { it.isNotBlank() }
        val lrc = File(f.parentFile, f.nameWithoutExtension + ".lrc").takeIf { it.isFile }?.readText()
        return listOfNotNull(tag?.let { fromText(it, TAG) }, lrc?.let { fromText(it, LRC) })
    }

    /** Every variant there is, to choose from: what's saved, the files, then each online source. */
    suspend fun variants(t: Track): List<Lyrics> {
        val saved = cache[t.id]?.let { fromText(it, cache[SOURCE + t.id] ?: SAVED) }
        val files = withContext(Dispatchers.IO) { inFiles(t) }
        val all = listOfNotNull(saved) + files + online(t)
        return all.distinctBy { (it.synced ?: it.plain).orEmpty().trim() }
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
        sources.map { s -> async(Dispatchers.IO) { runCatching { withTimeout(20_000) { s() } }.getOrNull() } }.awaitAll().mapNotNull { it?.cleaned() }
    }

    /** What to show: what's on disk if it's timed, else the best online variant (saved). */
    suspend fun best(t: Track): Lyrics? {
        val l = local(t)
        if (l?.synced != null) return l
        val all = online(t)
        // The source picked in Settings first (timed or not), then any timed lyrics, then any.
        val preferred = graph.settings.value.lyricsSource.takeIf { it.isNotEmpty() }?.let { src -> all.filter { it.source == src } }.orEmpty()
        val found = preferred.firstOrNull { it.synced != null } ?: preferred.firstOrNull() ?: all.firstOrNull { it.synced != null } ?: all.firstOrNull() ?: return l
        if (l != null && found.synced == null) return l
        save(t, found)
        return found
    }

    fun save(t: Track, l: Lyrics) {
        val text = l.synced ?: l.plain ?: return
        cache[t.id] = text
        cache[SOURCE + t.id] = l.source
        cacheFile.save(cache)
        if (graph.settings.value.embedLyrics) t.path?.let { p -> Tags.writeLyrics(File(p), text) }
    }

    companion object {
        /** Labels of variants that aren't an online source. */
        const val SAVED = "Palimpsest"
        const val TAG = "tag"
        const val LRC = ".lrc"
        /** Online sources, as their clients name them. */
        val ONLINE = listOf("LRCLIB", "NetEase", "Genius", "Yandex Music")
        private const val SOURCE = "source:"
    }
}
