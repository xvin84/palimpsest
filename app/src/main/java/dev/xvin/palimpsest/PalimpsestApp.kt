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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import dev.xvin.palimpsest.i18n.tr

class PalimpsestApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        dev.xvin.palimpsest.i18n.Strings.init(this)
    }

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
            .apply { if (BuildConfig.DEBUG) logger(coil3.util.DebugLogger()) }
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
        private val hosts = listOf("youtube.com", "googlevideo.com", "ytimg.com", "youtu.be", "ggpht.com", "googleapis.com", "googleusercontent.com", "spotify.com", "scdn.co", "spotifycdn.com", "musicbrainz.org", "coverartarchive.org", "archive.org", "bandcamp.com", "bcbits.com")

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
        // IPv4 first: on networks with broken IPv6 (some mobile carriers, the emulator) a CDN's
        // v6 address only times out. IPv6-only networks still get their v6 addresses.
        .dns { host -> okhttp3.Dns.SYSTEM.lookup(host).sortedBy { it is java.net.Inet6Address } }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val effects = AudioEffects()
    val outputs = dev.xvin.palimpsest.playback.OutputDevices(app)
    val waveform = dev.xvin.palimpsest.playback.Waveform(app.cacheDir)
    val artistIndex = dev.xvin.palimpsest.data.library.ArtistIndex(db)
    val scanner = LibraryScanner(db, settings, artistIndex)
    val versions = VersionRepository(db, settings, scanner)
    val duplicates = dev.xvin.palimpsest.data.library.Duplicates(db, versions, artistIndex)
    val trash = dev.xvin.palimpsest.data.library.Trash(db, settings, scanner, File(app.filesDir, "trash.json")).also { t ->
        appScope.launch { t.enforceLimits() }
    }

    val yandexAuth = YandexAuth(http)
    val yandex = YandexMusicProvider({ settings.state.value?.yandexToken }, http)
    val youtube: YouTubeProvider by lazy { YouTubeProvider(http) }
    val bandcamp: dev.xvin.palimpsest.core.newpipe.NewPipeProvider by lazy { dev.xvin.palimpsest.core.newpipe.NewPipeProvider.bandcamp(http) }

    /** Lazy view so YouTube/NewPipe only initialises when first used. */
    val providers: Map<Service, MusicProvider> = object : AbstractMap<Service, MusicProvider>() {
        override val entries: Set<Map.Entry<Service, MusicProvider>>
            get() = setOf(
                java.util.AbstractMap.SimpleEntry(Service.YANDEX, yandex as MusicProvider),
                java.util.AbstractMap.SimpleEntry(Service.YOUTUBE, youtube as MusicProvider),
                java.util.AbstractMap.SimpleEntry(Service.BANDCAMP, bandcamp as MusicProvider),
            )

        override fun get(key: Service): MusicProvider? = when (key) {
            Service.YANDEX -> yandex
            Service.YOUTUBE -> youtube
            Service.BANDCAMP -> bandcamp
            else -> null
        }
    }

    val streamResolver = StreamResolver(providers) { settings.state.value?.quality ?: Quality.LOSSLESS }

    val streamCache: SimpleCache by lazy {
        val limitMb = kotlinx.coroutines.runBlocking { settings.state.filterNotNull().first().streamCacheMb }
        SimpleCache(
            File(app.cacheDir, "stream"),
            LeastRecentlyUsedCacheEvictor(limitMb.toLong() shl 20),
            StandaloneDatabaseProvider(app),
        )
    }

    val remote = RemoteRepository(db, settings, yandexAuth, yandex, providers, artistIndex) { e, ctx -> errors.report(e, ctx) }
    val lyrics = LyricsRepository(db, yandex, LrcLibClient(http), dev.xvin.palimpsest.core.lyrics.NetEaseLyrics(http), dev.xvin.palimpsest.core.lyrics.GeniusLyrics(http), dev.xvin.palimpsest.core.translate.Translator(http), File(app.filesDir, "translations"),
        appScope, embedEnabled = { settings.state.value?.embedLyrics != false }, rescan = { scanner.rescanFile(it) })
    val spotify = dev.xvin.palimpsest.core.spotify.SpotifyImporter(http)
    val releases = dev.xvin.palimpsest.data.remote.ReleaseWatcher(app, db, yandex)
    val covers = dev.xvin.palimpsest.data.library.CoverFinder(db, yandex, dev.xvin.palimpsest.core.covers.CoverArtArchive(http), http, scanner, app.cacheDir)
    val listenBrainz = dev.xvin.palimpsest.core.scrobble.ListenBrainz(http)
    val scrobbler = dev.xvin.palimpsest.data.remote.Scrobbler(db, settings, listenBrainz)
    val downloads = Downloads(app, db, settings, http, remote, versions) { e, ctx -> errors.report(e, ctx) }

    val player = PlayerConnection(app, mainScope, savedQueue = { settings.loadQueue() }, offlinePlayable = ::offlinePlayable).also { p ->
        p.onError = { e -> errors.report(e, tr(R.string.ctx_playback)) }
    }

    /** True when there's a network that claims internet access. */
    fun isOnline(): Boolean = runCatching {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    /** Whether [track] can play with no network: a file on the phone, or a stream already cached whole. */
    fun offlinePlayable(track: dev.xvin.palimpsest.data.db.TrackEntity): Boolean = when {
        track.path != null -> File(track.path).isFile
        track.remoteId != null -> isFullyCached(StreamResolver.uriFor(track.service, track.remoteId).toString())
        else -> false
    }

    /** The stream cache holds every byte of [key] (the item's `palimpsest://` URI). */
    fun isFullyCached(key: String): Boolean = runCatching {
        val length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(streamCache.getContentMetadata(key))
        length > 0 && streamCache.isCached(key, 0, length)
    }.getOrDefault(false)
    val radio = dev.xvin.palimpsest.playback.RadioController(remote, db, player, mainScope)
    val offlineWave = dev.xvin.palimpsest.playback.OfflineWave(db)
    val backups = dev.xvin.palimpsest.data.Backups(app, db, settings)
    val updater = dev.xvin.palimpsest.data.update.AppUpdater(app, http)
    val storage = dev.xvin.palimpsest.data.Storage(app, db, versions, backups) { streamCache }

    /** Plays the offline mix; false if nothing is downloaded yet. */
    suspend fun startOfflineWave(): Boolean {
        val list = offlineWave.build()
        if (list.isEmpty()) return false
        player.play(list)
        return true
    }

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
