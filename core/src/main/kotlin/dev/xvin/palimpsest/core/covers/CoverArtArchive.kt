package dev.xvin.palimpsest.core.covers

import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Album art from MusicBrainz + Cover Art Archive: open, free, no account. MusicBrainz asks for a
 * descriptive User-Agent and at most ~1 request per second.
 */
class CoverArtArchive(private val http: OkHttpClient = Http.client) {
    @Serializable private data class Artist(val name: String = "")
    @Serializable private data class Credit(val name: String = "", val artist: Artist? = null)
    @Serializable private data class Group(val id: String)
    @Serializable private data class Release(
        val id: String,
        val title: String = "",
        @kotlinx.serialization.SerialName("release-group") val group: Group? = null,
    )
    @Serializable private data class Recording(
        val title: String = "",
        val length: Long? = null,
        val score: Int = 0,
        @kotlinx.serialization.SerialName("artist-credit") val credit: List<Credit> = emptyList(),
        val releases: List<Release> = emptyList(),
    )
    @Serializable private data class Search(val recordings: List<Recording> = emptyList())

    /** Returns image bytes for the best-matching release, or null. */
    suspend fun find(artist: String, title: String, durationMs: Long, album: String? = null): ByteArray? = withContext(Dispatchers.IO) {
        fun esc(x: String) = x.replace("\"", "").replace("\\", "")
        // Official albums/singles only: otherwise bootleg live recordings (no art) win the ranking.
        val q = buildString {
            append("recording:\"${esc(title)}\" AND artist:\"${esc(artist)}\" AND status:official AND (primarytype:album OR primarytype:single OR primarytype:ep)")
            if (!album.isNullOrBlank()) append(" AND release:\"${esc(album)}\"")
        }
        val url = "https://musicbrainz.org/ws/2/recording/".toHttpUrl().newBuilder()
            .addQueryParameter("query", q).addQueryParameter("fmt", "json").addQueryParameter("limit", "10").build()
        val body = get(url.toString())?.decodeToString() ?: return@withContext null
        val recs = runCatching { Http.json.decodeFromString<Search>(body).recordings }.getOrDefault(emptyList())
        val matches = recs
            .map { r -> r to TrackMatcher.score(title, artist, durationMs, r.title, r.credit.joinToString(", ") { it.artist?.name ?: it.name }, r.length ?: 0) }
            .filter { it.second >= 0.75 }
            .sortedByDescending { it.second }
            .map { it.first }
        // Album-level art (release group) exists far more often than art for one specific pressing.
        val groups = matches.flatMap { r -> r.releases.mapNotNull { it.group?.id } }.distinct().take(4)
        for (g in groups) {
            delay(1_000) // be polite to the free service
            get("https://coverartarchive.org/release-group/$g/front-500")?.let { return@withContext it }
        }
        for (rel in matches.flatMap { it.releases }.map { it.id }.distinct().take(2)) {
            delay(1_000)
            get("https://coverartarchive.org/release/$rel/front-500")?.let { return@withContext it }
        }
        null
    }

    private suspend fun get(url: String): ByteArray? = runInterruptible {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Palimpsest/0.2 (personal music player)").build()).execute().use { r ->
            if (r.isSuccessful) r.body.bytes() else null
        }
    }
}
