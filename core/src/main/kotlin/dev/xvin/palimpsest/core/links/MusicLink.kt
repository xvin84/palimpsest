package dev.xvin.palimpsest.core.links

import dev.xvin.palimpsest.core.model.Service
import java.net.URI
import java.net.URLDecoder

/**
 * A link to something on a music service, in the ids the app's providers use: Yandex numeric ids
 * («owner:kind» for playlists), YouTube video/list/channel ids, Bandcamp URLs without the scheme.
 */
data class MusicLink(val service: Service, val kind: Kind, val id: String) {
    enum class Kind { TRACK, ALBUM, ARTIST, PLAYLIST }

    companion object {
        private val URL_IN_TEXT = Regex("""https?://\S+""")

        /** The first link the app understands in [text] — a bare URL or a shared «Title — https://…» message. */
        fun find(text: String): MusicLink? = URL_IN_TEXT.findAll(text).firstNotNullOfOrNull { parse(it.value.trimEnd('.', ',', ')', '»', '"')) }

        fun parse(url: String): MusicLink? {
            val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val host = u.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
            val segs = u.path.orEmpty().split('/').filter { it.isNotEmpty() }
            val query = u.rawQuery.orEmpty().split('&').mapNotNull { p ->
                p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to URLDecoder.decode(it[1], "UTF-8") }
            }.toMap()
            return when {
                host.startsWith("music.yandex.") -> yandex(segs)
                host == "youtu.be" -> segs.firstOrNull()?.let { yt(it) }
                host == "youtube.com" || host == "music.youtube.com" -> when {
                    segs.firstOrNull() == "watch" && query["v"] != null -> yt(query["v"]!!)
                    segs.firstOrNull() == "shorts" && segs.size > 1 -> yt(segs[1])
                    segs.firstOrNull() == "playlist" && query["list"] != null ->
                        MusicLink(Service.YOUTUBE, Kind.PLAYLIST, query["list"]!!)
                    segs.firstOrNull() == "channel" && segs.size > 1 -> MusicLink(Service.YOUTUBE, Kind.ARTIST, segs[1])
                    segs.firstOrNull()?.startsWith("@") == true -> MusicLink(Service.YOUTUBE, Kind.ARTIST, "https://www.youtube.com/${segs[0]}")
                    else -> null
                }
                host.endsWith(".bandcamp.com") -> when (segs.firstOrNull()) {
                    "track" -> if (segs.size > 1) MusicLink(Service.BANDCAMP, Kind.TRACK, "$host/track/${segs[1]}") else null
                    "album" -> if (segs.size > 1) MusicLink(Service.BANDCAMP, Kind.ALBUM, "$host/album/${segs[1]}") else null
                    null, "music" -> MusicLink(Service.BANDCAMP, Kind.ARTIST, host)
                    else -> null
                }
                else -> null
            }
        }

        private fun yt(id: String) = id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
            ?.let { MusicLink(Service.YOUTUBE, Kind.TRACK, it) }

        /** /album/A/track/T, /track/T, /album/A, /artist/R, /users/OWNER/playlists/KIND. */
        private fun yandex(s: List<String>): MusicLink? {
            fun num(i: Int) = s.getOrNull(i)?.takeIf { it.all(Char::isDigit) }
            return when {
                s.getOrNull(0) == "album" && s.getOrNull(2) == "track" && num(3) != null -> MusicLink(Service.YANDEX, Kind.TRACK, num(3)!!)
                s.getOrNull(0) == "track" && num(1) != null -> MusicLink(Service.YANDEX, Kind.TRACK, num(1)!!)
                s.getOrNull(0) == "album" && num(1) != null -> MusicLink(Service.YANDEX, Kind.ALBUM, num(1)!!)
                s.getOrNull(0) == "artist" && num(1) != null -> MusicLink(Service.YANDEX, Kind.ARTIST, num(1)!!)
                s.getOrNull(0) == "users" && s.getOrNull(2) == "playlists" && num(3) != null ->
                    MusicLink(Service.YANDEX, Kind.PLAYLIST, "${s[1]}:${s[3]}")
                else -> null
            }
        }
    }
}
