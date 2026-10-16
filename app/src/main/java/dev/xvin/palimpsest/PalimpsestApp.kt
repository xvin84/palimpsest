package dev.xvin.palimpsest

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.xvin.palimpsest.core.lyrics.LrcLibClient
import dev.xvin.palimpsest.core.model.MusicProvider
import dev.xvin.palimpsest.core.model.Quality
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.yandex.YandexAuth
import dev.xvin.palimpsest.core.yandex.YandexMusicProvider
import dev.xvin.palimpsest.core.youtube.YouTubeProvider
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.library.LibraryScanner
import dev.xvin.palimpsest.data.library.VersionRepository
import dev.xvin.palimpsest.data.lyrics.LyricsRepository
import dev.xvin.palimpsest.data.remote.Downloads
import dev.xvin.palimpsest.data.remote.RemoteRepository
import dev.xvin.palimpsest.data.settings.Settings
import dev.xvin.palimpsest.playback.AudioEffects
import dev.xvin.palimpsest.playback.PlayerConnection
import dev.xvin.palimpsest.playback.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

class PalimpsestApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        graph = AppGraph(this)
        dev.xvin.palimpsest.data.remote.SyncWorker.schedule(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .diskCache { DiskCache.Builder().directory(File(cacheDir, "coil")).maxSizeBytes(128L shl 20).build() }
            .crossfade(180)
            // Same client as everything else, so remote covers honour the YouTube proxy.
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
            .build()
}

/** Hand-rolled dependency container: everything long-lived, created once per process. */
@OptIn(UnstableApi::class)
class AppGraph(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val errors = dev.xvin.palimpsest.errors.ErrorCenter(app)
    val db = AppDatabase.create(app)
    val settings = Settings(app, appScope)

    /** Optional proxy used only for YouTube/Spotify hosts (often unreachable directly). */
    private val proxySelector = object : ProxySelector() {
        private val hosts = listOf("youtube.com", "googlevideo.com", "ytimg.com", "youtu.be", "ggpht.com", "googleapis.com", "googleusercontent.com", "spotify.com", "scdn.co", "spotifycdn.com")

        override fun select(uri: URI?): List<Proxy> {
            val proxy = settings.state.value?.proxy
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val effects = AudioEffects()
    val outputs = dev.xvin.palimpsest.playback.OutputDevices(app)
    val waveform = dev.xvin.palimpsest.playback.Waveform(app.cacheDir)
    val artistIndex = dev.xvin.palimpsest.data.library.ArtistIndex(db)
    val scanner = LibraryScanner(db, settings, artistIndex)
    val versions = VersionRepository(db, settings, scanner)

    val yandexAuth = YandexAuth(http)
    val yandex = YandexMusicProvider({ settings.state.value?.yandexToken }, http)
    val youtube: YouTubeProvider by lazy { YouTubeProvider(http) }

    /** Lazy view so YouTube/NewPipe only initialises when first used. */
    val providers: Map<Service, MusicProvider> = object : AbstractMap<Service, MusicProvider>() {
        override val entries: Set<Map.Entry<Service, MusicProvider>>
            get() = setOf(
                java.util.AbstractMap.SimpleEntry(Service.YANDEX, yandex as MusicProvider),
                java.util.AbstractMap.SimpleEntry(Service.YOUTUBE, youtube as MusicProvider),
            )

        override fun get(key: Service): MusicProvider? = when (key) {
            Service.YANDEX -> yandex
            Service.YOUTUBE -> youtube
            else -> null
        }
    }

    val streamResolver = StreamResolver(providers) { settings.state.value?.quality ?: Quality.LOSSLESS }

    val streamCache: SimpleCache by lazy {
        SimpleCache(
            File(app.cacheDir, "stream"),
            LeastRecentlyUsedCacheEvictor(1L shl 30),
            StandaloneDatabaseProvider(app),
        )
    }

    val remote = RemoteRepository(db, settings, yandexAuth, yandex, providers, artistIndex) { e, ctx -> errors.report(e, ctx) }
    val lyrics = LyricsRepository(db, yandex, LrcLibClient(http))
    val spotify = dev.xvin.palimpsest.core.spotify.SpotifyImporter(http)
    val listenBrainz = dev.xvin.palimpsest.core.scrobble.ListenBrainz(http)
    val scrobbler = dev.xvin.palimpsest.data.remote.Scrobbler(db, settings, listenBrainz)
    val downloads = Downloads(app, db, settings, http, remote, versions) { e, ctx -> errors.report(e, ctx) }

    val player = PlayerConnection(app, mainScope) { settings.loadQueue() }.also { p ->
        p.onError = { e -> errors.report(e, "Воспроизведение") }
    }
    val radio = dev.xvin.palimpsest.playback.RadioController(remote, db, player, mainScope)

    /** ♥: likes locally and in Yandex, and (by default) downloads the track right away. */
    suspend fun setLiked(track: dev.xvin.palimpsest.data.db.TrackEntity, liked: Boolean): Result<Unit> {
        val result = remote.setLiked(track, liked)
        if (liked && settings.state.value?.autoDownloadLikes != false && track.remoteId != null && track.path == null) {
            downloads.enqueue(listOf(track.id))
        }
        return result
    }

    /** Pulls likes/playlists from Yandex and downloads newly liked tracks if that's switched on. */
    suspend fun syncYandex() {
        remote.syncYandex()
        if (settings.state.value?.autoDownloadLikes != false) {
            downloads.enqueue(db.tracks().linkedTo(Service.YANDEX).filter { it.liked && it.path == null }.map { it.id })
        }
    }
}
