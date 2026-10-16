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
 * NetEase Cloud Music: a huge catalogue with synced lyrics, strong on Asian music and often the
 * only source for anime openings and K-pop. Public endpoints, no account.
 */
class NetEaseLyrics(private val http: OkHttpClient = Http.client) {

    @Serializable private data class SearchResp(val result: SearchResult? = null)
    @Serializable private data class SearchResult(val songs: List<Song> = emptyList())
    @Serializable private data class Song(val id: Long, val name: String = "", val ar: List<Artist> = emptyList(), val dt: Long = 0)
    @Serializable private data class Artist(val name: String = "")
    @Serializable private data class LyricResp(val lrc: LrcBody? = null, val nolyric: Boolean = false, val uncollected: Boolean = false)
    @Serializable private data class LrcBody(val lyric: String? = null)

    suspend fun find(artist: String, title: String, durationMs: Long): Lyrics? {
        val url = "$BASE/api/cloudsearch/pc".toHttpUrl().newBuilder()
            .addQueryParameter("s", "$artist $title")
            .addQueryParameter("type", "1")
            .addQueryParameter("limit", "10")
            .build()
        val r = http.newCall(request(url.toString())).fetch()
        if (!r.isSuccessful) return null
        val songs = Http.json.decodeFromString<SearchResp>(r.body).result?.songs.orEmpty()
        val best = songs
            .map { it to TrackMatcher.score(title, artist, durationMs, it.name, it.ar.joinToString(", ") { a -> a.name }, it.dt) }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { it.second }?.first ?: return null
        val lr = http.newCall(request("$BASE/api/song/lyric?id=${best.id}&lv=1&tv=-1")).fetch()
        if (!lr.isSuccessful) return null
        val resp = Http.json.decodeFromString<LyricResp>(lr.body)
        val text = resp.lrc?.lyric?.let(::clean)?.takeIf { it.isNotBlank() } ?: return null
        if (INSTRUMENTAL in text) return null
        return if (Lrc.looksSynced(text)) Lyrics(plain = null, synced = text, source = "NetEase") else Lyrics(plain = text, synced = null, source = "NetEase")
    }

    private fun request(url: String) = Request.Builder().url(url)
        .header("Referer", "$BASE/")
        .header("User-Agent", Http.BROWSER_UA)
        .build()

    companion object {
        private const val BASE = "https://music.163.com"
        private const val MIN_SCORE = 0.62
        private const val INSTRUMENTAL = "纯音乐"

        /** Credit lines NetEase puts at the top ("作词 : …", "作曲 : …") aren't lyrics. */
        private val credit = Regex("""^\[[\d:.]+]\s*(作词|作曲|编曲|制作人|混音|母带|和声|吉他|贝斯|鼓|弦乐|录音|监制|出品|Lyricist|Composer|Arranger|Producer)\s*[:：].*$""", RegexOption.IGNORE_CASE)

        internal fun clean(lrc: String): String = lrc.lineSequence()
            .filterNot { credit.matches(it.trim()) }
            .joinToString("\n")
            .trim()
    }
}
