package dev.xvin.palimpsest.playback

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.core.radio.RadioHttp
import dev.xvin.palimpsest.core.radio.RadioStation
import dev.xvin.palimpsest.core.radio.StreamResponse
import java.io.IOException

/** Internet radio in the queue: `iradio:<station>`; the player plays its stream and shows what's on air. */
object InternetRadioItems {
    const val PREFIX = "iradio:"
    const val SCHEME = "palimpsest-radio"

    /** What's on air, by stream link: «Artist - Song» from the stream's ICY metadata. */
    val onAir = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)

    /** The stream link behind an item's uri, for matching [onAir] to the playing item. */
    fun streamOf(uri: Uri?): String? = uri?.takeIf { it.scheme == SCHEME }?.getQueryParameter("u")

    fun mediaId(s: RadioStation): String =
        PREFIX + Base64.encodeToString(Http.json.encodeToString(RadioStation.serializer(), s).toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun station(mediaId: String): RadioStation? = runCatching {
        Http.json.decodeFromString(RadioStation.serializer(), String(Base64.decode(mediaId.removePrefix(PREFIX), Base64.URL_SAFE)))
    }.getOrNull()

    /** The playable item; call off the main thread: a playlist link is opened to find its stream. */
    fun item(s: RadioStation): MediaItem {
        val url = RadioHttp.resolve(s.url)
        val hls = url.substringBefore('?').endsWith(".m3u8", true)
        // Streams go through our own socket client: Android's stacks refuse plain http, and it reads
        // what's on air out of the stream itself. HLS is the player's own business.
        val uri = if (!hls) Uri.Builder().scheme(SCHEME).authority("stream").appendQueryParameter("u", url).build() else Uri.parse(url)
        val meta = MediaMetadata.Builder()
            .setTitle(s.name)
            .setArtist(s.name)
            .setStation(s.name)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .apply { s.favicon?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        return MediaItem.Builder().setMediaId(mediaId(s)).setUri(uri).setMediaMetadata(meta)
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
    }
}

/** A radio stream over [RadioHttp], the ICY titles taken out of it here and published to [InternetRadioItems.onAir]. */
@UnstableApi
class InternetRadioDataSource : BaseDataSource(true) {
    private var stream: StreamResponse? = null
    private var input: java.io.InputStream? = null
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val url = dataSpec.uri.getQueryParameter("u") ?: throw IOException("no stream in ${dataSpec.uri}")
        val s = try { RadioHttp.open(url, mapOf("Icy-MetaData" to "1")) } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException(e.message ?: e.toString(), e) }
        stream = s
        input = dev.xvin.palimpsest.core.radio.IcyInputStream(s.input, s.metaInt) { InternetRadioItems.onAir.value = url to it }
        uri = dataSpec.uri
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = input?.read(buffer, offset, length) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
    }

    // Without «icy-metaint»: the metadata is already out of the bytes the player gets.
    override fun getResponseHeaders(): Map<String, List<String>> = stream?.headers?.filterKeys { it != "icy-metaint" }?.mapValues { listOf(it.value) } ?: emptyMap()

    override fun getUri(): Uri? = uri

    override fun close() {
        val s = stream ?: return
        stream = null
        input = null
        uri = null
        s.close()
        transferEnded()
    }
}
