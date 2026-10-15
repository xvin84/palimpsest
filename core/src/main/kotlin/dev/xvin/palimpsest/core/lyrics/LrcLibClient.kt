package dev.xvin.palimpsest.core.lyrics

import dev.xvin.palimpsest.core.model.Lyrics
import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.core.net.await
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Client for lrclib.net — a free, open database of synced lyrics. No key needed. */
class LrcLibClient(private val http: OkHttpClient = Http.client) {

    @Serializable
    private data class Entry(
        val id: Long = 0,
        val trackName: String? = null,
        val artistName: String? = null,
        val albumName: String? = null,
        val duration: Double? = null,
        val instrumental: Boolean = false,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    )

    suspend fun find(artist: String, title: String, album: String?, durationSec: Int?): Lyrics? {
        // Exact match first (needs duration within ±2 s), then a fuzzy search.
        if (durationSec != null && durationSec > 0) {
            val url = "$BASE/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("artist_name", artist)
                .addQueryParameter("track_name", title)
                .apply { if (!album.isNullOrBlank()) addQueryParameter("album_name", album) }
                .addQueryParameter("duration", durationSec.toString())
                .build()
            val exact = runCatching {
                http.newCall(request(url.toString())).await().use { r ->
                    if (r.isSuccessful) Http.json.decodeFromString<Entry>(r.body.string()) else null
                }
            }.getOrNull()
            exact?.toLyrics()?.let { return it }
        }
        val url = "$BASE/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build()
        val results = http.newCall(request(url.toString())).await().use { r ->
            if (!r.isSuccessful) return null
            Http.json.decodeFromString<List<Entry>>(r.body.string())
        }
        val best = results
            .filter { it.syncedLyrics != null || it.plainLyrics != null }
            .minByOrNull { e ->
                val dd = if (durationSec != null && e.duration != null) kotlin.math.abs(e.duration - durationSec) else 30.0
                // Prefer synced lyrics, then closest duration.
                dd + if (e.syncedLyrics == null) 100 else 0
            }
        return best?.toLyrics()
    }

    private fun Entry.toLyrics(): Lyrics? =
        if (syncedLyrics == null && plainLyrics == null) null
        else Lyrics(plain = plainLyrics, synced = syncedLyrics, source = "LRCLIB")

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "Palimpsest/0.1 (personal music player)")
        .build()

    private companion object {
        const val BASE = "https://lrclib.net"
    }
}
