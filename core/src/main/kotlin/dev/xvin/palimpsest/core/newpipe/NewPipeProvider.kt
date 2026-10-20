package dev.xvin.palimpsest.core.newpipe

import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.core.model.AlbumWithTracks
import dev.xvin.palimpsest.core.model.ArtistInfo
import dev.xvin.palimpsest.core.model.BrowsableProvider
import dev.xvin.palimpsest.core.model.MusicProvider
import dev.xvin.palimpsest.core.model.ProviderException
import dev.xvin.palimpsest.core.model.Quality
import dev.xvin.palimpsest.core.model.RemoteAlbum
import dev.xvin.palimpsest.core.model.RemoteArtist
import dev.xvin.palimpsest.core.model.RemotePlaylist
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.model.SearchHit
import dev.xvin.palimpsest.core.model.SearchResults
import dev.xvin.palimpsest.core.model.SearchType
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.model.StreamInfo
import dev.xvin.palimpsest.core.youtube.YouTubeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo as NpStreamInfo

/**
 * Any service NewPipe Extractor understands, beyond YouTube: search, streams, artist and album
 * pages. Used for Bandcamp. Ids are the item's URL without `https://` (so they read well in a
 * `palimpsest://` URI and a file's URL tag points straight at the page).
 *
 * Only direct (progressive) audio is used: the player and downloads read a plain file, not HLS.
 */
class NewPipeProvider private constructor(
    override val service: Service,
    private val np: StreamingService,
    http: OkHttpClient,
) : MusicProvider, BrowsableProvider {
    override val isReady = true

    companion object {
        fun bandcamp(http: OkHttpClient) = NewPipeProvider(Service.BANDCAMP, org.schabi.newpipe.extractor.ServiceList.Bandcamp, http)
    }

    init {
        YouTubeProvider.initNewPipe(http)
    }

    /** Bandcamp art URLs end in a size code (`_171.jpg`); `_16` is the 700 px version. */
    private fun big(url: String?) = url?.replace(Regex("_\\d+\\.(jpg|png)$"), "_16.jpg")

    /** Bandcamp says «by Artist» in search results. */
    private fun artistName(s: String?) = s?.removePrefix("by ")?.trim()?.takeIf { it.isNotBlank() }

    private fun url(id: String) = if (id.startsWith("http")) id else "https://$id"
    private fun id(url: String) = url.removePrefix("https://").removePrefix("http://").trimEnd('/')

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw e
    } catch (e: Throwable) {
        throw ProviderException("${service.displayName}: ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A page of mixed results; [filter] narrows it where the service supports content filters. */
    private suspend fun page(query: String, filter: String?, page: Int): List<InfoItem> = withContext(Dispatchers.IO) {
        val filters = np.searchQHFactory.availableContentFilter.toList()
        val extractor = np.getSearchExtractor(query, listOfNotNull(filter?.takeIf { it in filters }), "")
        extractor.fetchPage()
        var items = extractor.initialPage
        repeat(page) {
            if (!items.hasNextPage()) return@withContext emptyList()
            items = extractor.getPage(items.nextPage)
        }
        items.items
    }

    override suspend fun search(query: String, page: Int): List<RemoteTrack> = guarded {
        page(query, "tracks", page).filterIsInstance<StreamInfoItem>().map { it.toRemote() }
    }

    override suspend fun searchAll(query: String, type: SearchType, page: Int): SearchResults = guarded {
        val filter = when (type) {
            SearchType.TRACKS -> "tracks"
            SearchType.ARTISTS -> "users"
            SearchType.PLAYLISTS, SearchType.ALBUMS -> "playlists"
            SearchType.ALL -> null
        }
        // Bandcamp has no content filters: one mixed search, split by kind (two pages for «all»).
        val items = page(query, filter, page) + if (type == SearchType.ALL) runCatching { page(query, null, 1) }.getOrDefault(emptyList()) else emptyList()
        val tracks = items.filterIsInstance<StreamInfoItem>().map { it.toRemote() }.distinctBy { it.id }
        val artists = items.filterIsInstance<ChannelInfoItem>().map { it.toArtist() }.distinctBy { it.id }
        val lists = items.filterIsInstance<PlaylistInfoItem>()
        val albums = lists.map { it.toAlbum() }.distinctBy { it.id }
        val wanted = TrackMatcher.normalize(query)
        val best = artists.firstOrNull { TrackMatcher.normalize(it.name) == wanted }?.let { SearchHit.Artist(it) }
            ?: albums.firstOrNull { TrackMatcher.normalize(it.title) == wanted }?.let { SearchHit.Album(it) }
            ?: tracks.firstOrNull()?.let { SearchHit.Track(it) }
        when (type) {
            SearchType.ALL -> SearchResults(best, tracks, artists, albums, emptyList())
            SearchType.TRACKS -> SearchResults(tracks = tracks)
            SearchType.ARTISTS -> SearchResults(artists = artists)
            SearchType.ALBUMS -> SearchResults(albums = albums)
            SearchType.PLAYLISTS -> SearchResults(playlists = lists.map { it.toPlaylist() })
        }
    }

    override suspend fun track(id: String): RemoteTrack? = guarded {
        withContext(Dispatchers.IO) {
            val info = NpStreamInfo.getInfo(np, url(id))
            RemoteTrack(
                service = service,
                id = id,
                title = info.name,
                artists = listOfNotNull(artistName(info.uploaderName)),
                durationMs = info.duration * 1000,
                coverUrl = big(info.thumbnails.maxByOrNull { it.height }?.url),
                webUrl = url(id),
            )
        }
    }

    override suspend fun resolveStream(id: String, quality: Quality, forDownload: Boolean): StreamInfo = guarded {
        withContext(Dispatchers.IO) {
            val info = try {
                NpStreamInfo.getInfo(np, url(id))
            } catch (e: org.schabi.newpipe.extractor.exceptions.PaidContentException) {
                throw ProviderException("${service.displayName}: this track can only be heard after buying it", e)
            }
            val direct = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            val s = (if (quality == Quality.NORMAL) direct.minByOrNull { it.averageBitrate } else direct.maxByOrNull { it.averageBitrate })
                ?: throw ProviderException("${service.displayName}: no direct audio for $id")
            StreamInfo(url = s.content, codec = s.format?.suffix ?: "mp3", bitrateKbps = s.averageBitrate.coerceAtLeast(0))
        }
    }

    override suspend fun playlistTracks(playlistId: String): List<RemoteTrack> = album(playlistId).tracks

    override suspend fun album(id: String): AlbumWithTracks = guarded {
        withContext(Dispatchers.IO) {
            val info = PlaylistInfo.getInfo(np, url(id))
            val all = info.relatedItems.toMutableList()
            var next = info.nextPage
            while (next != null && all.size < 500) {
                val more = PlaylistInfo.getMoreItems(np, url(id), next)
                all += more.items
                next = more.nextPage
            }
            val cover = big(info.thumbnails.maxByOrNull { it.height }?.url)
            val album = RemoteAlbum(
                service = service, id = id, title = info.name,
                artists = listOfNotNull(artistName(info.uploaderName)),
                coverUrl = cover, trackCount = all.size,
            )
            val tracks = all.filterIsInstance<StreamInfoItem>().mapIndexed { i, item ->
                item.toRemote().let { t ->
                    t.copy(
                        album = t.album ?: album.title, coverUrl = t.coverUrl ?: cover, trackNumber = i + 1,
                        artists = t.artists.ifEmpty { album.artists },
                    )
                }
            }
            AlbumWithTracks(album, tracks)
        }
    }

    /** An artist's page: their tracks and releases from the channel's tabs. */
    override suspend fun artist(id: String, name: String): ArtistInfo = guarded {
        withContext(Dispatchers.IO) {
            val info = ChannelInfo.getInfo(np, url(id))
            val items = mutableListOf<InfoItem>()
            info.tabs.take(3).forEach { tab -> runCatching { items += ChannelTabInfo.getInfo(np, tab).relatedItems } }
            ArtistInfo(
                artist = RemoteArtist(service, id, info.name ?: name, big(info.avatars.maxByOrNull { it.height }?.url) ?: big(info.banners.maxByOrNull { it.height }?.url)),
                genres = info.tags.take(3),
                description = info.description?.takeIf { it.isNotBlank() },
                likes = null,
                popular = items.filterIsInstance<StreamInfoItem>().map { it.toRemote() }.distinctBy { it.id }.take(30),
                albums = items.filterIsInstance<PlaylistInfoItem>().map { it.toAlbum() }.distinctBy { it.id },
                similar = emptyList(),
            )
        }
    }

    private fun StreamInfoItem.toRemote() = RemoteTrack(
        service = service,
        id = id(url),
        title = name,
        artists = listOfNotNull(artistName(uploaderName)),
        durationMs = duration.coerceAtLeast(0) * 1000,
        coverUrl = big(thumbnails.maxByOrNull { it.height }?.url),
        webUrl = url,
        artistIds = listOf(uploaderUrl?.let(::id)),
    )

    private fun ChannelInfoItem.toArtist() = RemoteArtist(service, id(url), name, big(thumbnails.maxByOrNull { it.height }?.url))

    private fun PlaylistInfoItem.toAlbum() = RemoteAlbum(
        service = service, id = id(url), title = name,
        artists = listOfNotNull(artistName(uploaderName)),
        coverUrl = big(thumbnails.maxByOrNull { it.height }?.url),
        trackCount = streamCount.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: 0,
    )

    private fun PlaylistInfoItem.toPlaylist() = RemotePlaylist(
        service = service, id = id(url), title = name, owner = artistName(uploaderName),
        trackCount = streamCount.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: 0,
        coverUrl = big(thumbnails.maxByOrNull { it.height }?.url),
    )
}
