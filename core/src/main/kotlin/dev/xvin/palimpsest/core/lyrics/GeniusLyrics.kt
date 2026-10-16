package dev.xvin.palimpsest.core.lyrics

import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.core.model.Lyrics
import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.core.net.fetch
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Genius: the biggest community lyrics site (huge on rap, Russian rap included). Lyrics are
 * plain text without timings, read from the song page since the API doesn't serve them.
 */
class GeniusLyrics(private val http: OkHttpClient = Http.client) {

    @Serializable private data class SearchResp(val response: Response? = null)
    @Serializable private data class Response(val sections: List<Section> = emptyList())
    @Serializable private data class Section(val type: String = "", val hits: List<Hit> = emptyList())
    @Serializable private data class Hit(val type: String = "", val result: Song? = null)
    @Serializable private data class Song(val title: String = "", val url: String = "", val primary_artist: Artist? = null, val artist_names: String? = null, val lyrics_state: String? = null)
    @Serializable private data class Artist(val name: String = "")

    suspend fun find(artist: String, title: String): Lyrics? {
        val url = "$BASE/api/search/song".toHttpUrl().newBuilder()
            .addQueryParameter("q", "$artist $title")
            .addQueryParameter("per_page", "10")
            .build()
        val r = http.newCall(request(url.toString())).fetch()
        if (!r.isSuccessful) return null
        val songs = Http.json.decodeFromString<SearchResp>(r.body).response?.sections.orEmpty()
            .flatMap { it.hits }.mapNotNull { it.result }
            .filter { it.url.isNotEmpty() && it.lyrics_state != "unreleased" }
        val best = songs
            .map { it to TrackMatcher.score(title, artist, 0, it.title, it.artist_names ?: it.primary_artist?.name.orEmpty(), 0) }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { it.second }?.first ?: return null
        val page = http.newCall(request(best.url)).fetch()
        if (!page.isSuccessful) return null
        val text = extract(page.body)?.takeIf { it.isNotBlank() } ?: return null
        return Lyrics(plain = text, synced = null, source = "Genius")
    }

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", Http.BROWSER_UA).build()

    companion object {
        private const val BASE = "https://genius.com"
        private const val MIN_SCORE = 0.6
        private const val CONTAINER = "data-lyrics-container=\"true\""
        /** Localized pages open with a title line: "[Текст песни «…»]", "[Paroles de …]". */
        private val pageTitle = Regex("""^\[(Текст песни|Paroles de|Letra de|Songtext zu|Testo di).*]$""")

        /** Pulls the lyrics out of a Genius song page: every lyrics container, minus page furniture. */
        internal fun extract(html: String): String? {
            val parts = ArrayList<String>()
            var from = 0
            while (true) {
                val attr = html.indexOf(CONTAINER, from).takeIf { it >= 0 } ?: break
                val start = html.lastIndexOf("<div", attr)
                val end = closingDiv(html, start)
                parts += html.substring(html.indexOf('>', attr) + 1, end)
                from = end
            }
            if (parts.isEmpty()) return null
            val raw = parts.joinToString("<br/>")
            return htmlToText(removeExcluded(raw))
                .lineSequence().map { it.trim() }
                .dropWhile { it.isEmpty() || pageTitle.matches(it) }
                .joinToString("\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }

        /** Index just past the `</div>` that closes the `<div` at [start], counting nested divs. */
        private fun closingDiv(html: String, start: Int): Int {
            var depth = 0
            var i = start
            while (i < html.length) {
                when {
                    html.startsWith("<div", i) -> { depth++; i += 4 }
                    html.startsWith("</div>", i) -> { depth--; i += 6; if (depth == 0) return i - 6 }
                    else -> i++
                }
            }
            return html.length
        }

        /** Headers like "12 Contributors … Lyrics" are marked data-exclude-from-selection. */
        private fun removeExcluded(html: String): String {
            var s = html
            while (true) {
                val attr = s.indexOf("data-exclude-from-selection=\"true\"").takeIf { it >= 0 } ?: return s
                val start = s.lastIndexOf("<div", attr).takeIf { it >= 0 } ?: return s
                val end = closingDiv(s, start)
                s = s.removeRange(start, (end + 6).coerceAtMost(s.length))
            }
        }

        private fun htmlToText(html: String): String = html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace("&amp;", "&")
    }
}
