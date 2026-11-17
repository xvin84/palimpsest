package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.xvin.palimpsest.core.library.AlbumMerge
import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.core.model.AlbumWithTracks
import dev.xvin.palimpsest.core.model.BrowsableProvider
import dev.xvin.palimpsest.core.model.SearchType
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.desktop.data.Track

/**
 * One album, like the artist page: the service's whole tracklist with the songs you have in their
 * places, plus anything only you have. Used to be two pages — «Альбом» with your one liked song
 * and «Альбом на Яндексе» with all of them.
 */
@Composable
fun AlbumScreen(album: String?, artist: String?, remoteId: String?, service: Service) {
    val graph = LocalGraph.current
    val all by graph.library.tracks.collectAsState()
    val liked by graph.library.liked.collectAsState()
    val byName = remember(all, album, artist) {
        if (album == null || artist == null) emptyList()
        else all.filter { it.album.equals(album, ignoreCase = true) && (it.albumArtist ?: it.artist).equals(artist, ignoreCase = true) }
    }
    val hint = remoteId?.let { it to service } ?: byName.firstNotNullOfOrNull { t -> t.remoteAlbumId?.takeIf { t.remoteId != null }?.let { it to t.service } }
    val remote by produceState<Load<AlbumWithTracks?>>(Load.Loading, hint, album, artist) {
        suspend fun fetch(id: String, svc: Service) = if (svc == Service.YANDEX) graph.yandex.albumWithTracks(id) else (graph.provider(svc) as BrowsableProvider).album(id)
        value = load {
            hint?.let { (id, svc) -> runCatching { fetch(id, svc) }.getOrNull() }?.let { return@load it }
            // No link, or its service is out of reach (Yandex signed out): look the album up by
            // name, on Yandex when signed in, otherwise on YouTube Music (it needs no account).
            if (album == null || artist == null) return@load null
            val svc = if (graph.yandex.isReady) Service.YANDEX else Service.YOUTUBE
            graph.provider(svc)!!.searchAll("$artist $album", SearchType.ALBUMS, 0).albums.firstOrNull { a ->
                TrackMatcher.normalize(a.title) == TrackMatcher.normalize(album) && a.artists.any { TrackMatcher.normalize(it) == TrackMatcher.normalize(artist) }
            }?.let { fetch(it.id, svc) }
        }
    }
    val loaded = (remote as? Load.Ok)?.value
    val mine = remember(all, byName, loaded) {
        val a = loaded?.album ?: return@remember byName
        val named = byName.ifEmpty { all.filter { it.album.equals(a.title, ignoreCase = true) && a.artists.any { n -> (it.albumArtist ?: it.artist).equals(n, ignoreCase = true) } } }
        (named + all.filter { it.service == a.service && it.remoteAlbumId == a.id }).distinctBy { it.id }
            .sortedWith(compareBy({ it.trackNumber ?: 999 }, { it.title.lowercase() }))
    }
    val rows = remember(loaded, mine) { AlbumMerge.merge(loaded?.tracks.orEmpty(), mine, { it.remoteId }, { it.title }) }
    // Service songs you don't have yet: shown and played as they are, added to the library only when played.
    val tracks = remember(rows) {
        rows.mapIndexed { i, r ->
            when (r) {
                is AlbumMerge.Row.Mine -> if (r.remote != null) r.track.copy(trackNumber = i + 1) else r.track
                is AlbumMerge.Row.Theirs -> r.track.let { x ->
                    graph.library.get("${x.service}:${x.id}")?.copy(trackNumber = i + 1) ?: Track(
                        id = "${x.service}:${x.id}", title = x.title, artist = x.artistLine.ifBlank { "—" }, album = x.album, year = x.year,
                        durationMs = x.durationMs, service = x.service, remoteId = x.id, remoteAlbumId = x.albumId, coverUrl = x.coverUrl, trackNumber = i + 1,
                    )
                }
            }
        }
    }
    val title = loaded?.album?.title ?: album.orEmpty()
    val who = loaded?.album?.artists?.joinToString(", ")?.ifBlank { null } ?: artist
    val year = loaded?.album?.year ?: mine.firstNotNullOfOrNull { it.year }
    val have = rows.count { it is AlbumMerge.Row.Mine }
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 20.dp, top = 16.dp)) {
        if (tracks.isEmpty() && remote is Load.Loading) { ListHeader(title, who, t("Альбом", "Album")); Spinner(Modifier.padding(24.dp)); return@Column }
        TrackTable(tracks, liked, numbered = true, header = {
            ListHeader(
                title,
                listOfNotNull(
                    who, year?.toString(), tracksCount(tracks.size), fmtTotal(tracks.sumOf { it.durationMs }),
                    if (loaded != null && have in 1 until tracks.size) t("у тебя $have из ${tracks.size}", "you have $have of ${tracks.size}") else null,
                ).joinToString(" · "),
                t("Альбом", "Album"),
            ) {
                val cover = loaded?.album?.coverUrl
                if (cover != null) Artwork(cover, title, Modifier.fillMaxSize(), Radius.lg) else TrackArt(mine.firstOrNull(), Modifier.fillMaxSize(), Radius.lg)
            }
        })
    }
}
