package dev.xvin.palimpsest.core.youtube

import dev.xvin.palimpsest.core.model.MusicProvider
import dev.xvin.palimpsest.core.model.ArtistInfo
import dev.xvin.palimpsest.core.model.AlbumWithTracks
import dev.xvin.palimpsest.core.model.ProviderException
import dev.xvin.palimpsest.core.model.Quality
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.model.StreamInfo
import dev.xvin.palimpsest.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.core.model.RemoteAlbum
import dev.xvin.palimpsest.core.model.RemoteArtist
import dev.xvin.palimpsest.core.model.RemotePlaylist
import dev.xvin.palimpsest.core.model.SearchHit
import dev.xvin.palimpsest.core.model.SearchResults
import dev.xvin.palimpsest.core.model.SearchType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.schabi.newpipe.extractor.stream.StreamInfo as NpStreamInfo

/**
 * YouTube / YouTube Music via NewPipe Extractor — no account and no API key needed.
 * Search goes to the YouTube Music "songs" filter so results are actual tracks, not videos.
 */
class YouTubeProvider(http: OkHttpClient = Http.client) : MusicProvider, dev.xvin.palimpsest.core.model.BrowsableProvider {
    override val service = Service.YOUTUBE
    override val isReady = true

    init {
        initNewPipe(http)
    }

    override suspend fun search(query: String, page: Int): List<RemoteTrack> = guarded { searchImpl(query, page) }

    /**
     * YouTube Music search by kind. ALL asks songs, artists, albums and playlists at once; the
     * best match is an artist whose name is the query, else the top song.
     */
    override suspend fun searchAll(query: String, type: SearchType, page: Int): SearchResults = guarded {
        when (type) {
            SearchType.TRACKS -> SearchResults(tracks = searchImpl(query, page))
            SearchType.ARTISTS -> SearchResults(artists = artistsFor(query, page))
            SearchType.ALBUMS -> SearchResults(albums = albumsFor(query, page))
            SearchType.PLAYLISTS -> SearchResults(playlists = playlistsFor(query, page))
            SearchType.ALL -> coroutineScope {
                val songs = async { runCatching { searchImpl(query, 0) }.getOrDefault(emptyList()) }
                val artists = async { runCatching { artistsFor(query, 0) }.getOrDefault(emptyList()) }
                val albums = async { runCatching { albumsFor(query, 0) }.getOrDefault(emptyList()) }
                val lists = async { runCatching { playlistsFor(query, 0) }.getOrDefault(emptyList()) }
                val t = songs.await()
                val ar = artists.await()
                val wanted = TrackMatcher.normalize(query)
                val best = ar.firstOrNull { TrackMatcher.normalize(it.name) == wanted }?.let { SearchHit.Artist(it) }
                    ?: t.firstOrNull()?.let { SearchHit.Track(it) }
                SearchResults(best, t.take(10), ar.take(10), albums.await().take(10), lists.await().take(10))
            }
        }
    }

    private suspend fun artistsFor(query: String, page: Int) =
        items(query, YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS, page).filterIsInstance<ChannelInfoItem>().map { it.toArtist() }

    private suspend fun albumsFor(query: String, page: Int) =
        items(query, YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS, page).filterIsInstance<PlaylistInfoItem>().map { it.toAlbum() }

    private suspend fun playlistsFor(query: String, page: Int) =
        items(query, YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS, page).filterIsInstance<PlaylistInfoItem>().map { it.toPlaylist() }

    /** Songs of a YouTube Music album or playlist (both are playlists there). */
    override suspend fun playlistTracks(playlistId: String): List<RemoteTrack> = album(playlistId).tracks

    /** An album (or any playlist) with its songs; tracks without an album get this one's title and cover. */
    override suspend fun album(id: String): AlbumWithTracks = albumImpl(id)

    private suspend fun albumImpl(playlistId: String): AlbumWithTracks = guarded {
        withContext(Dispatchers.IO) {
            val url = playlistUrl(playlistId)
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
            val all = info.relatedItems.toMutableList()
            var next = info.nextPage
            while (next != null && all.size < 500) {
                val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next)
                all += more.items
                next = more.nextPage
            }
            val cover = large(info.thumbnails.maxByOrNull { it.height }?.url)
            val items = all.filterIsInstance<StreamInfoItem>()
            // No uploader on album pages: the artist most of the songs are by.
            val artist = info.uploaderName?.removeSuffix(" - Topic")?.takeIf { it.isNotBlank() }
                ?: items.mapNotNull { it.uploaderName?.removeSuffix(" - Topic")?.takeIf { n -> n.isNotBlank() } }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val album = RemoteAlbum(
                service = Service.YOUTUBE,
                id = playlistId,
                // «Album – Title», «Single – Title», «EP – Title»: the kind goes, the title stays.
                title = info.name.replace(Regex("^(Album|Single|EP|Альбом|Сингл)\\s*[-–—]\\s*", RegexOption.IGNORE_CASE), ""),
                artists = listOfNotNull(artist),
                coverUrl = cover,
                trackCount = all.size,
            )
            val tracks = items.mapIndexed { i, item ->
                item.toRemote().let { t -> t.copy(album = t.album ?: album.title, coverUrl = t.coverUrl ?: cover, trackNumber = i + 1) }
            }
            AlbumWithTracks(album, tracks)
        }
    }

    /**
     * YouTube Music's endless mix from a song («RDAMVM» + its id): one page of songs and where the
     * next one starts (null at the end, when the mix has to be started again from another song).
     */
    suspend fun mix(videoId: String, page: org.schabi.newpipe.extractor.Page? = null): Pair<List<RemoteTrack>, org.schabi.newpipe.extractor.Page?> = guarded {
        withContext(Dispatchers.IO) {
            val url = "https://www.youtube.com/watch?v=$videoId&list=RDAMVM$videoId"
            if (page == null) {
                val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
                info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toRemote() } to info.nextPage
            } else {
                val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, page)
                more.items.filterIsInstance<StreamInfoItem>().map { it.toRemote() } to more.nextPage
            }
        }
    }

    /** An artist page: songs and albums found under the artist's name, and the channel's description. */
    override suspend fun artist(id: String, name: String): ArtistInfo = artistImpl(id, name)

    private suspend fun artistImpl(channelId: String, name: String): ArtistInfo = guarded {
        coroutineScope {
            val wanted = TrackMatcher.normalize(name)
            val channel = async(Dispatchers.IO) { runCatching { ChannelInfo.getInfo(ServiceList.YouTube, channelUrl(channelId)) }.getOrNull() }
            val songs = async { runCatching { searchImpl(name, 0) }.getOrDefault(emptyList()) }
            val albums = async { runCatching { albumsFor(name, 0) }.getOrDefault(emptyList()) }
            val c = channel.await()
            val found = songs.await()
            val mine = found.filter { s -> s.artists.any { TrackMatcher.normalize(it) == wanted } }
            ArtistInfo(
                artist = RemoteArtist(Service.YOUTUBE, channelId, c?.name?.removeSuffix(" - Topic") ?: name, large(c?.avatars?.maxByOrNull { it.height }?.url)),
                genres = emptyList(),
                description = c?.description?.takeIf { it.isNotBlank() },
                likes = null,
                popular = mine.ifEmpty { found.take(10) },
                albums = albums.await().filter { a -> a.artists.any { TrackMatcher.normalize(it) == wanted } },
                similar = emptyList(),
            )
        }
    }

    private suspend fun items(query: String, filter: String, page: Int): List<InfoItem> = withContext(Dispatchers.IO) {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        extractor.fetchPage()
        var items = extractor.initialPage
        repeat(page) {
            if (!items.hasNextPage()) return@withContext emptyList()
            items = extractor.getPage(items.nextPage)
        }
        items.items
    }

    private fun ChannelInfoItem.toArtist() =
        RemoteArtist(Service.YOUTUBE, url.substringAfterLast('/'), name.removeSuffix(" - Topic"), large(thumbnails.maxByOrNull { it.height }?.url))

    private fun PlaylistInfoItem.toAlbum() = RemoteAlbum(
        service = Service.YOUTUBE,
        id = listId(url),
        title = name,
        artists = listOfNotNull(uploaderName?.removeSuffix(" - Topic")?.takeIf { it.isNotBlank() }),
        coverUrl = large(thumbnails.maxByOrNull { it.height }?.url),
        trackCount = streamCount.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: 0,
    )

    private fun PlaylistInfoItem.toPlaylist() = RemotePlaylist(
        service = Service.YOUTUBE,
        id = listId(url),
        title = name,
        owner = uploaderName?.takeIf { it.isNotBlank() },
        trackCount = streamCount.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: 0,
        coverUrl = thumbnails.maxByOrNull { it.height }?.url,
    )

    /** YouTube Music art comes as tiny `=w120-h120` thumbnails; the same URL serves any size. */
    private fun large(url: String?) = url?.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")

    private fun listId(url: String) = Regex("[?&]list=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1) ?: url

    override suspend fun resolveStream(id: String, quality: Quality, forDownload: Boolean): StreamInfo =
        guarded { resolveImpl(id, quality, forDownload) }

    /** NewPipe can throw Errors (e.g. on unexpected page layouts); report them as ordinary failures. */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw e
    } catch (e: Throwable) {
        throw ProviderException("YouTube: ${e.javaClass.simpleName}: ${e.message}", e)
    }

    private suspend fun searchImpl(query: String, page: Int): List<RemoteTrack> = withContext(Dispatchers.IO) {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        extractor.fetchPage()
        var items = extractor.initialPage
        repeat(page) {
            if (!items.hasNextPage()) return@withContext emptyList()
            items = extractor.getPage(items.nextPage)
        }
        items.items.filterIsInstance<StreamInfoItem>().map { it.toRemote() }
    }

    override suspend fun track(id: String): RemoteTrack? = withContext(Dispatchers.IO) {
        val info = NpStreamInfo.getInfo(ServiceList.YouTube, watchUrl(id))
        RemoteTrack(
            service = Service.YOUTUBE,
            id = id,
            title = info.name,
            artists = listOfNotNull(info.uploaderName?.removeSuffix(" - Topic")),
            durationMs = info.duration * 1000,
            coverUrl = info.thumbnails.maxByOrNull { it.height }?.url,
            webUrl = watchUrl(id),
        )
    }

    private suspend fun resolveImpl(id: String, quality: Quality, forDownload: Boolean): StreamInfo = withContext(Dispatchers.IO) {
        val info = NpStreamInfo.getInfo(ServiceList.YouTube, watchUrl(id))
        val streams = info.audioStreams
        // Opus/WebM sounds better, but M4A can hold tags and a cover, which matters for kept files.
        val m4a = streams.filter { it.format?.suffix == "m4a" }
        val stream = pickAudio(if (forDownload && m4a.isNotEmpty()) m4a else streams, quality)
            ?: throw ProviderException("YouTube: no audio stream for $id")
        StreamInfo(
            url = stream.content,
            codec = stream.format?.suffix ?: "webm",
            bitrateKbps = stream.averageBitrate.coerceAtLeast(0),
        )
    }

    private fun pickAudio(streams: List<AudioStream>, quality: Quality): AudioStream? {
        val direct = streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        // Skip dubbed/descriptive tracks when the original is there.
        val original = direct.filter { it.audioTrackType?.name.let { t -> t == null || t == "ORIGINAL" } }.ifEmpty { direct }
        return when (quality) {
            Quality.NORMAL -> original.minByOrNull { it.averageBitrate }
            else -> original.maxByOrNull { it.averageBitrate }
        }
    }

    private fun StreamInfoItem.toRemote(): RemoteTrack {
        val id = Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1) ?: url
        return RemoteTrack(
            service = Service.YOUTUBE,
            id = id,
            title = name,
            artists = listOfNotNull(uploaderName?.removeSuffix(" - Topic")),
            durationMs = duration.coerceAtLeast(0) * 1000,
            coverUrl = thumbnails.maxByOrNull { it.height }?.url,
            webUrl = watchUrl(id),
        )
    }

    companion object {
        fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
        fun playlistUrl(id: String) = if (id.startsWith("http")) id else "https://music.youtube.com/playlist?list=$id"
        fun channelUrl(id: String) = if (id.startsWith("http")) id else "https://www.youtube.com/channel/$id"

        @Volatile private var initialized = false

        @Synchronized
        fun initNewPipe(http: OkHttpClient) {
            if (initialized) return
            NewPipe.init(OkHttpDownloader(http), Localization("ru", "RU"))
            initialized = true
        }
    }
}

/** Bridges NewPipe's blocking downloader interface to OkHttp. */
private class OkHttpDownloader(private val http: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        http.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 429) throw org.schabi.newpipe.extractor.exceptions.ReCaptchaException("reCaptcha", request.url())
            return Response(resp.code, resp.message, resp.headers.toMultimap(), resp.body.string(), resp.request.url.toString())
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}
