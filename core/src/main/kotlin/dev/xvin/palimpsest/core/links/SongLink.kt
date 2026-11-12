package dev.xvin.palimpsest.core.links

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI

/**
 * Links to services the app doesn't play (Spotify, Apple Music, Deezer, SoundCloud, Tidal…), turned
 * into the same song on one it does, through song.link: its page for a link lists that song on
 * every service. (Its JSON API needs a key since 2026; the page doesn't.) Yandex first — it's the
 * one that streams in full here — then YouTube Music.
 */
object SongLink {
    private val HOSTS = listOf(
        "open.spotify.com", "spotify.link", "music.apple.com", "itunes.apple.com", "deezer.com", "deezer.page.link", "link.deezer.com",
        "on.soundcloud.com", "tidal.com", "listen.tidal.com", "music.amazon.com", "song.link", "album.link", "odesli.co",
        "audiomack.com", "boomplay.com", "pandora.com", "napster.com",
    )
    private val URL_IN_TEXT = Regex("""https?://\S+""")

    /** The first link in [text] to a service song.link knows and the app doesn't play itself. */
    fun find(text: String): String? = URL_IN_TEXT.findAll(text).map { it.value.trimEnd('.', ',', ')', '»', '"') }.firstOrNull { url ->
        val host = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return@firstOrNull false
        HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** What a song.link page says: the song on a service that plays here, and its name to search by. */
    data class Found(val link: MusicLink?, val title: String?, val artist: String?)

    suspend fun resolve(http: OkHttpClient, url: String, yandexFirst: Boolean = true): Found? = withContext(Dispatchers.IO) {
        val page = if (runCatching { URI(url).host }.getOrNull()?.let { "song.link" in it || "album.link" in it || "odesli.co" in it } == true) url else "https://song.link/$url"
        val req = Request.Builder().url(page).header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64)").build()
        val html = http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return@withContext null
        val name = Regex(""""title":"([^"]+)","artistName":"([^"]+)"""").find(html)?.groupValues
        Found(pick(html, yandexFirst), name?.get(1), name?.get(2))
    }

    /** The song on Yandex Music, else on YouTube (Music), from a song.link page; YouTube first without a Yandex sign-in. */
    fun pick(html: String, yandexFirst: Boolean = true): MusicLink? {
        val yandex = Regex("""https://music\.yandex\.[a-z]+/(?:album/\d+/)?(?:track|album|artist)/\d+""").findAll(html)
            .firstNotNullOfOrNull { MusicLink.parse(it.value) }
        if (yandex != null && yandexFirst) return yandex
        return youtube(html) ?: yandex
    }

    private fun youtube(html: String): MusicLink? =
        Regex("""https://(?:music\.)?youtube\.com/(?:watch\?v=|playlist\?list=)[A-Za-z0-9_-]+""").findAll(html)
            .firstNotNullOfOrNull { MusicLink.parse(it.value) }
            ?: Regex("""youtube\|song\|([A-Za-z0-9_-]{6,20})""").find(html)?.let { MusicLink.parse("https://youtu.be/${it.groupValues[1]}") }
}
