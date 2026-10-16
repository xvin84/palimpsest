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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr

/**
 * Lyrics for a track. Synced (timed) lyrics are preferred everywhere: among what's on the phone
 * (saved in the app, the file's tag, a sidecar `.lrc`) and online (Yandex, LRCLIB, NetEase, then
 * Genius for plain text). A source the user picks by hand is kept as is.
 */
class LyricsRepository(
    private val db: AppDatabase,
    private val yandex: YandexMusicProvider,
    private val lrclib: LrcLibClient,
    private val netEase: dev.xvin.palimpsest.core.lyrics.NetEaseLyrics,
    private val genius: dev.xvin.palimpsest.core.lyrics.GeniusLyrics,
    private val translator: dev.xvin.palimpsest.core.translate.Translator,
    private val translationsDir: File,
) {
    /** Translated lines (same count as [lines]) into the app's language, cached per track, text and language. null = already in it. */
    suspend fun translate(trackId: Long, lines: List<String>): List<String>? = withContext(Dispatchers.IO) {
        val target = java.util.Locale.getDefault().language.ifEmpty { "en" }
        val key = lines.joinToString("\n").hashCode()
        val cache = File(translationsDir, "${trackId}_${target}_$key.txt")
        if (cache.isFile) {
            val txt = cache.readText()
            return@withContext if (txt == SAME_LANGUAGE) null else txt.split("\n")
        }
        val r = translator.translate(lines, target)
        translationsDir.mkdirs()
        if (r.sourceLanguage == target) { cache.writeText(SAME_LANGUAGE); return@withContext null }
        cache.writeText(r.lines.joinToString("\n"))
        r.lines
    }

    private companion object {
        const val SAME_LANGUAGE = "\u0000same"
    }

    /** Tracks whose lyrics the user picked or edited by hand: automatic upgrades leave them alone. */
    private val pinnedFile = File(translationsDir.parentFile, "lyrics_pinned.txt")

    private fun pinned(): Set<Long> = runCatching { pinnedFile.readLines().mapNotNull { it.toLongOrNull() }.toSet() }.getOrDefault(emptySet())

    fun isPinned(trackId: Long) = trackId in pinned()

    private fun pin(trackId: Long) = runCatching { if (!isPinned(trackId)) pinnedFile.appendText("$trackId\n") }

    /** What's on the phone: saved in the app, the file's tag, a sidecar `.lrc`. */
    private suspend fun localAll(track: TrackEntity): List<Lyrics> = buildList {
        db.lyrics().get(track.id)?.let { add(Lyrics(it.plain, it.synced, it.source)) }
        val path = track.path ?: return@buildList
        val file = File(path)
        runCatching { TagIO.read(file).lyrics }.getOrNull()?.takeIf { it.isNotBlank() }?.let { add(fromText(it, tr(R.string.lyrics_src_tag))) }
        val lrc = File(file.parentFile, file.nameWithoutExtension + ".lrc")
        if (lrc.isFile) add(fromText(lrc.readText(), tr(R.string.lyrics_src_lrc)))
    }

    /** The lyrics to show: a hand-picked choice as is, otherwise synced ones win over plain. */
    suspend fun local(track: TrackEntity): Lyrics? = withContext(Dispatchers.IO) {
        val all = localAll(track)
        if (isPinned(track.id)) return@withContext all.firstOrNull()
        all.firstOrNull { it.synced != null } ?: all.firstOrNull()
    }

    /** Every online source in parallel, in priority order (Yandex, LRCLIB, NetEase, Genius); failures are skipped. */
    private suspend fun online(track: TrackEntity, includePlainOnly: Boolean = true): List<Lyrics> = coroutineScope {
        val title = cleanTitle(track.title)
        val sources: List<suspend () -> Lyrics?> = buildList {
            add { if (track.service == Service.YANDEX && track.remoteId != null && yandex.isReady) yandex.lyrics(track.remoteId) else null }
            add { lrclib.find(track.artist, title, track.album, (track.durationMs / 1000).toInt().takeIf { it > 0 }) }
            add { netEase.find(track.artist, title, track.durationMs) }
            if (includePlainOnly) add { genius.find(track.artist, title) }
        }
        sources.map { source -> async(Dispatchers.IO) { runCatching { withTimeout(20_000) { source() } }.getOrNull() } }.awaitAll().filterNotNull()
    }

    /**
     * Synced lyrics win: Yandex (for its own tracks), LRCLIB, NetEase. If no one has timings, the
     * first plain text from any of them, with Genius (plain only) as the last resort.
     */
    suspend fun fetchOnline(track: TrackEntity): Lyrics? = withContext(Dispatchers.IO) {
        val all = online(track)
        val found = all.firstOrNull { it.synced != null } ?: all.firstOrNull()
        if (found != null) db.lyrics().put(LyricsEntity(track.id, found.synced, found.plain, found.source))
        found
    }

    /**
     * Quietly looks for timed lyrics when the track has none or only plain text (and the user
     * hasn't picked something on purpose). Saves and returns them when found.
     */
    suspend fun findSynced(track: TrackEntity): Lyrics? = withContext(Dispatchers.IO) {
        if (isPinned(track.id)) return@withContext null
        val found = online(track, includePlainOnly = false).firstOrNull { it.synced != null } ?: return@withContext null
        db.lyrics().put(LyricsEntity(track.id, found.synced, found.plain, found.source))
        found
    }

    /** Everything available for the «choose a source» list: local and online, duplicates dropped. */
    suspend fun candidates(track: TrackEntity): List<Lyrics> = withContext(Dispatchers.IO) {
        (localAll(track) + online(track)).distinctBy { (it.synced ?: it.plain).orEmpty().trim() }
    }

    /** The user's pick from [candidates]: becomes the track's lyrics and stays until changed. */
    suspend fun choose(track: TrackEntity, lyrics: Lyrics) = withContext(Dispatchers.IO) {
        db.lyrics().put(LyricsEntity(track.id, lyrics.synced, lyrics.plain, lyrics.source))
        pin(track.id)
    }

    /** Saves edited lyrics; optionally also writes them into the file's tag. */
    suspend fun save(track: TrackEntity, text: String, embed: Boolean) = withContext(Dispatchers.IO) {
        pin(track.id)
        val l = fromText(text, tr(R.string.lyrics_src_manual))
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
