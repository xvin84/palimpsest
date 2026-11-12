package dev.xvin.palimpsest.core.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream

/**
 * An HLS stream as one file: the init part and every media part, in order, joined — for fMP4 (what
 * SoundCloud serves) that is a playable `.m4a`. Encrypted streams aren't handled: SoundCloud's
 * free songs aren't encrypted, and a key line stops the fetch rather than writing noise.
 */
object HlsFetch {
    /** The media playlist's parts: the init part (if any) first, then the segments. */
    fun parts(playlistUrl: String, text: String): List<String> {
        val base = playlistUrl.toHttpUrl()
        fun abs(u: String) = base.resolve(u)?.toString() ?: u
        val lines = text.lines().map { it.trim() }
        if (lines.any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }) throw IOException("encrypted HLS")
        val out = ArrayList<String>()
        lines.firstOrNull { it.startsWith("#EXT-X-MAP") }?.let { m -> Regex("URI=\"([^\"]+)\"").find(m)?.groupValues?.get(1)?.let { out += abs(it) } }
        lines.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { out += abs(it) }
        return out
    }

    /** Writes the whole stream to [out]; [onProgress] gets 0..1 by parts. Follows a master playlist to its best variant. */
    fun download(http: OkHttpClient, playlistUrl: String, out: OutputStream, onProgress: (Float) -> Unit = {}) {
        fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body.string()
        }
        var url = playlistUrl
        var text = get(url)
        if (text.contains("#EXT-X-STREAM-INF")) {
            // A master playlist: the variant with the highest bandwidth.
            val lines = text.lines()
            val best = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }
                .maxByOrNull { Regex("BANDWIDTH=(\\d+)").find(lines[it])?.groupValues?.get(1)?.toLongOrNull() ?: 0 }
                ?.let { lines.getOrNull(it + 1)?.trim() } ?: throw IOException("empty master playlist")
            url = url.toHttpUrl().resolve(best).toString()
            text = get(url)
        }
        val parts = parts(url, text)
        if (parts.isEmpty()) throw IOException("empty playlist")
        val buf = ByteArray(64 * 1024)
        parts.forEachIndexed { i, part ->
            http.newCall(Request.Builder().url(part).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code} for part ${i + 1} of ${parts.size}")
                r.body.byteStream().use { input -> while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) } }
            }
            onProgress((i + 1f) / parts.size)
        }
    }
}
