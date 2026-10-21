package dev.xvin.palimpsest.desktop.data

import dev.xvin.palimpsest.core.lyrics.GeniusLyrics
import dev.xvin.palimpsest.core.lyrics.LrcLibClient
import dev.xvin.palimpsest.core.lyrics.NetEaseLyrics
import dev.xvin.palimpsest.core.model.MusicProvider
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.newpipe.NewPipeProvider
import dev.xvin.palimpsest.core.translate.Translator
import dev.xvin.palimpsest.core.yandex.YandexAuth
import dev.xvin.palimpsest.core.yandex.YandexMusicProvider
import dev.xvin.palimpsest.core.youtube.YouTubeProvider
import dev.xvin.palimpsest.desktop.playback.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/** Everything long-lived, created once (the desktop twin of the Android AppGraph). */
class Graph {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore()
    val library = LibraryStore(settings)
    val history = HistoryStore()
    val playlists = PlaylistStore()

    /** The proxy only for hosts that are often blocked (YouTube, Spotify, Bandcamp, cover databases). */
    private val proxySelector = object : ProxySelector() {
        private val hosts = listOf(
            "youtube.com", "googlevideo.com", "ytimg.com", "youtu.be", "ggpht.com", "googleapis.com", "googleusercontent.com",
            "spotify.com", "scdn.co", "spotifycdn.com", "musicbrainz.org", "coverartarchive.org", "archive.org", "bandcamp.com", "bcbits.com",
        )
        override fun select(uri: URI?): List<Proxy> {
            val proxy = settings.value.proxy
            val host = uri?.host.orEmpty()
            if (proxy.isNullOrBlank() || hosts.none { host == it || host.endsWith(".$it") }) return listOf(Proxy.NO_PROXY)
            val (h, p) = proxy.removePrefix("http://").removePrefix("socks://").split(':').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 1080) }
            val type = if (proxy.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
            return listOf(Proxy(type, InetSocketAddress.createUnresolved(h, p)))
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .proxySelector(proxySelector)
        .dns { host -> okhttp3.Dns.SYSTEM.lookup(host).sortedBy { it is java.net.Inet6Address } }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val yandexAuth = YandexAuth(http)
    val yandex = YandexMusicProvider({ settings.value.yandexToken }, http)
    val youtube by lazy { YouTubeProvider(http) }
    val bandcamp by lazy { NewPipeProvider.bandcamp(http) }

    fun provider(s: Service): MusicProvider? = when (s) {
        Service.YANDEX -> yandex
        Service.YOUTUBE -> youtube
        Service.BANDCAMP -> bandcamp
        else -> null
    }

    val lrclib = LrcLibClient(http)
    val netEase = NetEaseLyrics(http)
    val genius = GeniusLyrics(http)
    val translator = Translator(http)
    val covers = Covers(this)
    val lyrics = LyricsService(this)
    val downloads = Downloads(this)
    val player = Player(this)
    val lanSync = LanSyncServer(this).also { if (settings.value.lanSync) it.setEnabled(true) }
}
