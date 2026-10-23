package dev.xvin.palimpsest.data.library

import dev.xvin.palimpsest.core.covers.OpenCatalogs
import dev.xvin.palimpsest.core.library.FileNames
import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.yandex.YandexMusicProvider
import dev.xvin.palimpsest.data.db.TrackEntity
import kotlinx.coroutines.CancellationException

/** What a service or catalog knows about a local file's song. */
data class SongInfo(
    val title: String,
    val artist: String,
    val album: String?,
    val year: Int?,
    val genre: String?,
    val trackNumber: Int?,
    val coverUrl: String?,
    /** The service link for the URL tag (Yandex only). */
    val webUrl: String?,
    val source: String,
)

/**
 * Finds a local file's song for its cover and missing tags: the linked Yandex track, a Yandex
 * search, then Deezer and iTunes — open catalogs that know a lot of older music and, unlike
 * MusicBrainz, answer from Russia without a proxy. Old downloads often carry junk tags, so the
 * query falls back to what the file name says.
 */
class MetaLookup(private val yandex: YandexMusicProvider, private val catalogs: OpenCatalogs) {
    /** Artist (null when unknown) and title to search with. */
    fun query(t: TrackEntity): Pair<String?, String>? {
        var artist = t.artist.takeUnless { isUnknownArtist(it) }
        var title = t.title.trim()
        if (artist == null) {
            // «Artist - Title» left in the title by a tagger, or only in the file name.
            val fromName = t.path?.let { FileNames.guess(it.substringAfterLast('/')) }
            val dash = title.indexOf(" - ")
            when {
                dash > 0 -> { artist = title.substring(0, dash).trim(); title = title.substring(dash + 3).trim() }
                fromName?.artist != null -> { artist = fromName.artist; title = fromName.title }
            }
        }
        title = title.replace(numberPrefix, "").trim()
        if (title.isEmpty() || junkTitle.matches(title)) return null
        return artist to title
    }

    suspend fun find(t: TrackEntity): SongInfo? {
        val (artist, title) = query(t) ?: return null
        if (t.service == Service.YANDEX && t.remoteId != null && yandex.isReady) {
            safe { yandex.track(t.remoteId) }?.let { return it.toInfo() }
        }
        val q = listOfNotNull(artist, TrackMatcher.normalizeTitle(title).ifEmpty { title }).joinToString(" ")
        if (yandex.isReady) {
            val hits = safe { yandex.search(q) }.orEmpty().take(10)
            OpenCatalogs.best(title, artist, t.durationMs, hits, { it.title }, { it.artistLine }, { it.durationMs })?.let { return it.toInfo() }
        }
        // Deezer first; iTunes allows only ~20 searches a minute, so it's asked only when Deezer had nothing.
        for (search in listOf(catalogs::deezer, catalogs::itunes)) {
            val hits = safe { search(q) }.orEmpty()
            OpenCatalogs.best(title, artist, t.durationMs, hits, { it.title }, { it.artist }, { it.durationMs })?.let { c ->
                return SongInfo(c.title, c.artist, c.album, c.year, c.genre, c.trackNumber, c.coverUrl, null, c.source)
            }
        }
        return null
    }

    private fun dev.xvin.palimpsest.core.model.RemoteTrack.toInfo() =
        SongInfo(title, artistLine, album, year, genre, trackNumber, coverUrl, webUrl, "Яндекс")

    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        private val numberPrefix = Regex("""^\d{1,3}\s*[.\-_)]\s*""")
        private val junkTitle = Regex("""^(track|дорожка|audiotrack|аудиодорожка|untitled|без названия)\s*\d*$""", RegexOption.IGNORE_CASE)
        private val unknownArtists = setOf("unknown artist", "unknown", "неизвестный исполнитель", "неизвестен", "various artists", "various", "va", "artist", "")

        fun isUnknownArtist(a: String?): Boolean =
            a == null || a == LibraryScanner.UNKNOWN_ARTIST || TrackMatcher.normalize(a) in unknownArtists || a.trim('—', '-', ' ').isEmpty()
    }
}
