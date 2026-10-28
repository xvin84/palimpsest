package dev.xvin.palimpsest.playback

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.core.sync.HandoffTrack
import dev.xvin.palimpsest.core.sync.LanTransport
import dev.xvin.palimpsest.core.sync.RemoteFile
import kotlinx.serialization.Serializable
import java.io.IOException

/** A song handed over from the computer that this phone doesn't have: streamed from it. */
@Serializable
data class LanItem(val host: String, val port: Int, val token: String, val track: HandoffTrack) {
    fun uri(): Uri = Uri.Builder().scheme(SCHEME).encodedAuthority("$host:$port")
        .appendPath(track.fileKey.orEmpty()).appendQueryParameter("t", token).build()

    /** The queue keeps only ids: the whole description rides in it. */
    fun mediaId(): String = MediaItems.LAN_PREFIX + Base64.encodeToString(Http.json.encodeToString(serializer(), this).toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        const val SCHEME = "palimpsest-lan"

        /**
         * Covers of handed-over songs, by [mediaId]: they stay out of the id itself, which travels
         * between the app and the playback service with the whole queue.
         */
        val covers = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

        /** The item for [track], its cover put aside in [covers]. */
        fun of(host: String, port: Int, token: String, track: HandoffTrack): LanItem {
            val item = LanItem(host, port, token, track.copy(cover = null))
            track.cover?.let { b64 -> runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()?.let { covers[item.mediaId()] = it } }
            return item
        }

        fun fromMediaId(id: String): LanItem? = runCatching {
            Http.json.decodeFromString(serializer(), String(Base64.decode(id.removePrefix(MediaItems.LAN_PREFIX), Base64.URL_SAFE)))
        }.getOrNull()
    }
}

/**
 * Reads a [LanItem] from the computer over the LAN socket, from any byte — so seeking works —
 * without the stream cache: it's someone else's file, there for one listen.
 */
@UnstableApi
class LanDataSource : BaseDataSource(true) {
    private var file: RemoteFile? = null
    private var uri: Uri? = null
    private var left = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val u = dataSpec.uri
        val key = u.lastPathSegment ?: throw IOException("no key in $u")
        val token = u.getQueryParameter("t") ?: throw IOException("no token in $u")
        val f = try {
            LanTransport.openFile(u.host ?: throw IOException("no host"), u.port, token, key, dataSpec.position)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: e.toString(), e)
        }
        file = f
        uri = u
        val available = (f.length - dataSpec.position).coerceAtLeast(0)
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, available) else available
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val n = file?.input?.read(buffer, offset, minOf(length.toLong(), left).toInt()) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) {
            if (left > 0) throw IOException("the computer stopped sending")
            return C.RESULT_END_OF_INPUT
        }
        left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val f = file ?: return
        file = null
        uri = null
        runCatching { f.input.close() }
        transferEnded()
    }
}
