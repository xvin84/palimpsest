package dev.xvin.palimpsest.core.spotify

import dev.xvin.palimpsest.core.model.ProviderException
import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.core.net.fetch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads public Spotify playlists and albums without an account, from the embed page that
 * Spotify serves for widgets. Spotify never gives audio to third-party apps, so the app then
 * finds each song on Yandex Music or YouTube.
 *
 * Limitation: the embed page lists at most ~100 tracks.
 */
class SpotifyImporter(private val http: OkHttpClient = Http.client) {
    data class Item(val title: String, val artists: String, val durationMs: Long)
    data class Collection(val kind: String, val id: String, val name: String, val coverUrl: String?, val items: List<Item>)

    suspend fun fetch(urlOrUri: String): Collection {
        val (kind, id) = parse(urlOrUri) ?: throw ProviderException("Не похоже на ссылку на плейлист или альбом Spotify")
        val resp = http.newCall(
            Request.Builder().url("https://open.spotify.com/embed/$kind/$id")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36")
                .build(),
        ).fetch()
        if (!resp.isSuccessful) throw ProviderException("Spotify ответил ${resp.code}")
        return parseEmbed(resp.body, kind, id)
    }

    companion object {
        private val urlRe = Regex("""(?:open\.spotify\.com/(?:intl-[a-z]+/)?(?:embed/)?|spotify:)(playlist|album)[/:]([A-Za-z0-9]{22})""")

        fun parse(s: String): Pair<String, String>? = urlRe.find(s.trim())?.let { it.groupValues[1] to it.groupValues[2] }

        internal fun parseEmbed(html: String, kind: String, id: String): Collection {
            val json = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: throw ProviderException("Spotify: не нашёл данные на странице")
            val entity = Http.json.parseToJsonElement(json).jsonObject
                .obj("props")?.obj("pageProps")?.obj("state")?.obj("data")?.obj("entity")
                ?: throw ProviderException("Spotify: неожиданный формат страницы")
            val items = entity["trackList"]?.jsonArray.orEmpty().mapNotNull { el ->
                val o = el.jsonObject
                val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Item(
                    title = title,
                    artists = o["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty().replace(" ", " "),
                    durationMs = o["duration"]?.jsonPrimitive?.longOrNull ?: 0,
                )
            }
            // Playlists carry coverArt.sources; albums only visualIdentity.image (largest last).
            val cover = entity.obj("coverArt")?.get("sources")?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                ?: entity.obj("visualIdentity")?.get("image")?.jsonArray?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            val name = entity["name"]?.jsonPrimitive?.contentOrNull ?: entity["title"]?.jsonPrimitive?.contentOrNull ?: "Spotify"
            return Collection(kind, id, name, cover, items)
        }

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    }
}
