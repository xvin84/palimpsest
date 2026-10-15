package dev.xvin.palimpsest.core.youtube

import dev.xvin.palimpsest.core.model.MusicProvider
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
import org.schabi.newpipe.extractor.stream.StreamInfo as NpStreamInfo

/**
 * YouTube / YouTube Music via NewPipe Extractor — no account and no API key needed.
 * Search goes to the YouTube Music "songs" filter so results are actual tracks, not videos.
 */
class YouTubeProvider(http: OkHttpClient = Http.client) : MusicProvider {
    override val service = Service.YOUTUBE
    override val isReady = true

    init {
        initNewPipe(http)
    }

    override suspend fun search(query: String, page: Int): List<RemoteTrack> = withContext(Dispatchers.IO) {
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

    override suspend fun resolveStream(id: String, quality: Quality): StreamInfo = withContext(Dispatchers.IO) {
        val info = NpStreamInfo.getInfo(ServiceList.YouTube, watchUrl(id))
        val stream = pickAudio(info.audioStreams, quality)
            ?: throw ProviderException("YouTube: нет аудиопотока для $id")
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
