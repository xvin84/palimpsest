package dev.xvin.palimpsest.core.library

import dev.xvin.palimpsest.core.model.Service

/** A track's page on its service, to open or to send to someone. */
object TrackLinks {
    fun web(service: Service, remoteId: String?, remoteAlbumId: String? = null): String? {
        val id = remoteId ?: return null
        return when (service) {
            Service.YANDEX -> YandexLinks.trackUrl(remoteAlbumId, id)
            Service.YOUTUBE -> "https://music.youtube.com/watch?v=$id"
            Service.BANDCAMP -> if (id.startsWith("http")) id else "https://$id"
            else -> null
        }
    }
}

/** One song of a playlist as it leaves the app: a file on this device and/or a link on a service. */
data class PlaylistItem(
    val title: String,
    val artist: String,
    val durationMs: Long = 0,
    val path: String? = null,
    val link: String? = null,
)

/**
 * A whole playlist sent elsewhere: as a message (one song per line, with its link where there is
 * one), or as an `.m3u8` another player — or Palimpsest on another device — can open.
 */
object PlaylistExport {
    fun text(name: String, items: List<PlaylistItem>): String = buildString {
        appendLine(name)
        appendLine()
        items.forEachIndexed { i, t ->
            append(i + 1).append(". ").append(t.artist).append(" — ").append(t.title)
            t.link?.let { append("\n   ").append(it) }
            appendLine()
        }
    }.trimEnd()

    /**
     * Local songs are written by file name only: absolute paths from this device mean nothing on
     * another one, while [M3u.match] finds a file by its name in any library. Songs that exist only
     * on a service are written as their link, which players that stream understand.
     */
    fun m3u(name: String, items: List<PlaylistItem>): String = buildString {
        append("#EXTM3U\n")
        append("#PLAYLIST:").append(name.oneLine()).append('\n')
        for (t in items) {
            val location = t.path?.replace('\\', '/')?.substringAfterLast('/') ?: t.link ?: continue
            append("#EXTINF:").append(if (t.durationMs > 0) t.durationMs / 1000 else -1)
                .append(',').append(t.artist.oneLine()).append(" - ").append(t.title.oneLine()).append('\n')
            append(location).append('\n')
        }
    }

    /** A name safe to use as a file name on any system. */
    fun fileName(name: String, extension: String): String =
        (name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "playlist" }) + "." + extension

    private fun String.oneLine() = replace('\n', ' ').replace('\r', ' ')
}
