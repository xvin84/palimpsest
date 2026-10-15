package dev.xvin.palimpsest.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import dev.xvin.palimpsest.core.crypto.AesCtr
import dev.xvin.palimpsest.core.model.MusicProvider
import dev.xvin.palimpsest.core.model.Quality
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.model.StreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher

/** `palimpsest://YANDEX/12345` → a real, short-lived stream URL. */
class StreamResolver(
    private val providers: Map<Service, MusicProvider>,
    private val quality: () -> Quality,
) {
    private data class Entry(val info: StreamInfo, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun resolve(service: Service, id: String): StreamInfo {
        val key = "$service:$id"
        cache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.info }
        val provider = providers[service] ?: throw IOException("Нет провайдера для $service")
        val info = provider.resolveStream(id, quality())
        // Signed links live for a while; refresh well before they expire.
        cache[key] = Entry(info, System.currentTimeMillis() + 10 * 60_000)
        return info
    }

    fun invalidate(service: Service, id: String) { cache.remove("$service:$id") }

    companion object {
        const val SCHEME = "palimpsest"

        fun uriFor(service: Service, remoteId: String): Uri = Uri.parse("$SCHEME://${service.name}/$remoteId")

        fun parse(uri: Uri): Pair<Service, String>? {
            if (uri.scheme != SCHEME) return null
            val service = runCatching { Service.valueOf(uri.host ?: return null) }.getOrNull() ?: return null
            val id = uri.path?.trimStart('/')?.takeIf { it.isNotEmpty() } ?: return null
            return service to id
        }

        fun cacheKey(service: Service, remoteId: String) = "${service.name}:$remoteId"
    }
}

/**
 * Reads a remote track: resolves the `palimpsest://` URI, opens the real URL and decrypts
 * AES-CTR streams on the fly (seek-safe).
 */
@UnstableApi
class ResolvingStreamDataSource(
    private val resolver: StreamResolver,
    private val upstreamFactory: DataSource.Factory,
) : DataSource {
    private var upstream: DataSource? = null
    private var cipher: Cipher? = null
    private var uri: Uri? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }

    override fun open(dataSpec: DataSpec): Long {
        val (service, id) = StreamResolver.parse(dataSpec.uri) ?: throw IOException("Неизвестный URI ${dataSpec.uri}")
        val info = try {
            runBlocking(Dispatchers.IO) { resolver.resolve(service, id) }
        } catch (e: Exception) {
            throw IOException("Не удалось получить поток: ${e.message}", e)
        }
        val real = dataSpec.buildUpon()
            .setUri(Uri.parse(info.url))
            .setHttpRequestHeaders(info.headers)
            .build()
        val src = upstreamFactory.createDataSource().also { s -> listeners.forEach(s::addTransferListener) }
        upstream = src
        uri = real.uri
        val length = try {
            src.open(real)
        } catch (e: IOException) {
            // Signed URL may have expired between resolve and open.
            resolver.invalidate(service, id)
            throw e
        }
        cipher = info.aesCtrKey?.let { AesCtr.cipherAt(it, dataSpec.position) }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream?.read(buffer, offset, length) ?: return C.RESULT_END_OF_INPUT
        if (n > 0) cipher?.update(buffer, offset, n, buffer, offset)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()

    override fun close() {
        try { upstream?.close() } finally { upstream = null; cipher = null }
    }
}

/**
 * Top-level source for the player: local files and content:// go straight to disk, service
 * tracks go through the stream cache so replays don't hit the network.
 */
@UnstableApi
class PalimpsestDataSource(
    private val local: DataSource,
    private val remote: DataSource,
) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        remote.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val src = if (dataSpec.uri.scheme == StreamResolver.SCHEME) remote else local
        current = src
        return src.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)
    override fun getUri(): Uri? = current?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
    override fun close() { try { current?.close() } finally { current = null } }

    class Factory(
        private val localFactory: DataSource.Factory,
        private val resolver: StreamResolver,
        private val cache: Cache,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val resolving = ResolvingStreamDataSource(resolver, localFactory)
            val cached = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory { resolving }
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
            return PalimpsestDataSource(localFactory.createDataSource(), cached)
        }
    }
}
