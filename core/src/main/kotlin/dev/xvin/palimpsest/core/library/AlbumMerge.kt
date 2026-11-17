package dev.xvin.palimpsest.core.library

import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.match.TrackMatcher

/**
 * One album page out of two sources: the songs you have and the service's tracklist. Used to be
 * two pages («Альбом» with your one liked song, «Альбом в Яндексе» with all twenty), now it's the
 * service's order with your copies in place, and whatever only you have (a bonus track, a file the
 * service doesn't know) after it.
 */
object AlbumMerge {
    sealed interface Row<out L> {
        /** A song you have; [remote] is its place in the service's tracklist, if it has one. */
        data class Mine<L>(val track: L, val remote: RemoteTrack?) : Row<L>
        /** Only on the service. */
        data class Theirs(val track: RemoteTrack) : Row<Nothing>
    }

    fun <L> merge(remote: List<RemoteTrack>, mine: List<L>, remoteIdOf: (L) -> String?, titleOf: (L) -> String): List<Row<L>> {
        val left = mine.toMutableList()
        val ids = remote.mapTo(HashSet()) { it.id }
        val rows = remote.map { r ->
            // The service id first (a downloaded or liked track), then the title for files you
            // added yourself; each of yours is used once, so two «Intro»s don't collapse.
            val i = left.indexOfFirst { remoteIdOf(it) == r.id }.takeIf { it >= 0 }
                ?: TrackMatcher.normalizeTitle(r.title).let { key -> left.indexOfFirst { remoteIdOf(it) !in ids && TrackMatcher.normalizeTitle(titleOf(it)) == key } }
            if (i >= 0) Row.Mine(left.removeAt(i), r) else Row.Theirs(r)
        }
        return rows + left.map { Row.Mine(it, null) }
    }
}
