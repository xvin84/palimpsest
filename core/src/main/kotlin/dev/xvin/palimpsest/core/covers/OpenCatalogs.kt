package dev.xvin.palimpsest.core.covers

import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A song as a public catalog knows it: for covers and for filling in missing tags. */
data class CatalogTrack(
    val title: String,
    val artist: String,
    val album: String?,
    val year: Int?,
    val trackNumber: Int?,
    val genre: String?,
    val durationMs: Long,
    val coverUrl: String?,
    val source: String,
)

/**
 * Deezer and iTunes search: free, no account, and — unlike MusicBrainz — reachable without a
 * proxy from Russia. Both know a lot of older music, with covers up to 1000 px.
 */
class OpenCatalogs(private val http: OkHttpClient = Http.client) {
    @Serializable private data class DzArtist(val name: String = "")
    @Serializable private data class DzAlbum(val title: String = "", @SerialName("cover_xl") val coverXl: String? = null, @SerialName("cover_big") val coverBig: String? = null)
    @Serializable private data class DzTrack(val title: String = "", val duration: Long = 0, val artist: DzArtist = DzArtist(), val album: DzAlbum = DzAlbum())
    @Serializable private data class DzSearch(val data: List<DzTrack> = emptyList())

    @Serializable private data class ItTrack(
        val trackName: String = "",
        val artistName: String = "",
        val collectionName: String? = null,
        val releaseDate: String? = null,
        val trackNumber: Int? = null,
        val primaryGenreName: String? = null,
        val trackTimeMillis: Long = 0,
        val artworkUrl100: String? = null,
    )
    @Serializable private data class ItSearch(val results: List<ItTrack> = emptyList())

    /** Deezer's results, then iTunes' (the Russian store for Cyrillic queries: it keeps names as they are). */
    suspend fun search(query: String): List<CatalogTrack> = deezer(query) + itunes(query)

    suspend fun deezer(query: String): List<CatalogTrack> = withContext(Dispatchers.IO) {
        val url = "https://api.deezer.com/search".toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("limit", "10").build()
        val body = get(url.toString()) ?: return@withContext emptyList()
        runCatching { Http.json.decodeFromString<DzSearch>(body).data }.getOrDefault(emptyList()).map {
            CatalogTrack(it.title, it.artist.name, it.album.title.ifBlank { null }, null, null, null, it.duration * 1000, it.album.coverXl ?: it.album.coverBig, "Deezer")
        }
    }

    suspend fun itunes(query: String): List<CatalogTrack> = withContext(Dispatchers.IO) {
        val country = if (query.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }) "RU" else "US"
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", query).addQueryParameter("entity", "song").addQueryParameter("limit", "10")
            .addQueryParameter("country", country).build()
        val body = get(url.toString()) ?: return@withContext emptyList()
        runCatching { Http.json.decodeFromString<ItSearch>(body).results }.getOrDefault(emptyList()).map {
            CatalogTrack(
                it.trackName, it.artistName, it.collectionName, it.releaseDate?.take(4)?.toIntOrNull(), it.trackNumber, it.primaryGenreName,
                it.trackTimeMillis, it.artworkUrl100?.replace("100x100bb", "1000x1000bb"), "iTunes",
            )
        }
    }

    suspend fun bytes(url: String): ByteArray? = withContext(Dispatchers.IO) { getBytes(url) }

    private suspend fun get(url: String): String? = getBytes(url)?.decodeToString()

    private suspend fun getBytes(url: String): ByteArray? = runInterruptible {
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Palimpsest (personal music player)").build()).execute().use { r ->
                if (r.isSuccessful) r.body.bytes() else null
            }
        }.getOrNull()
    }

    companion object {
        private val live = Regex("""\b(live|концерт|remix|karaoke|instrumental|минус)\b""", RegexOption.IGNORE_CASE)

        /**
         * The catalog track that is this song, or null. With [artist] unknown, the title has to be
         * near-exact and the length within a few seconds. The length isn't required otherwise: an
         * old rip is often a little longer or shorter than the release, and its cover is the same.
         * Live versions and remixes lose unless the file is one too.
         */
        fun <T> best(
            title: String, artist: String?, durationMs: Long, items: List<T>,
            titleOf: (T) -> String, artistOf: (T) -> String, durationOf: (T) -> Long,
        ): T? {
            val t = TrackMatcher.normalizeTitle(title)
            if (t.isEmpty()) return null
            val localArtists = artist?.let { TrackMatcher.artistSet(it) }.orEmpty()
            val fileIsLive = live.containsMatchIn(title)
            return items.mapNotNull { item ->
                val ts = TrackMatcher.similarity(t, TrackMatcher.normalizeTitle(titleOf(item)))
                val d = durationOf(item)
                val close = durationMs > 0 && d > 0 && kotlin.math.abs(durationMs - d) <= 4_000
                val ok = if (localArtists.isEmpty()) ts >= 0.92 && close else {
                    val theirs = TrackMatcher.artistSet(artistOf(item))
                    val a = if (theirs.isEmpty()) 0.0 else localArtists.intersect(theirs).size.toDouble() / minOf(localArtists.size, theirs.size)
                    ts >= 0.8 && a >= 0.5
                }
                if (!ok) return@mapNotNull null
                val penalty = if (!fileIsLive && live.containsMatchIn(titleOf(item))) 0.3 else 0.0
                item to (ts + (if (close) 0.2 else 0.0) - penalty)
            }.maxByOrNull { it.second }?.first
        }
    }
}
