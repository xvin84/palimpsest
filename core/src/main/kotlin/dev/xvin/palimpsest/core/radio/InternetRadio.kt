package dev.xvin.palimpsest.core.radio

import dev.xvin.palimpsest.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocketFactory

/** An internet radio station: a stream that never ends, from the catalog or a link the user added. */
@Serializable
data class RadioStation(
    val name: String,
    val url: String,
    val favicon: String? = null,
    val tags: String? = null,
    val country: String? = null,
    val codec: String? = null,
    val bitrate: Int = 0,
    /** Radio Browser's id, for stations from the catalog. */
    val uuid: String? = null,
) {
    /** Stable across runs: the catalog id, or the link. */
    val key: String get() = uuid ?: url
}

/**
 * Radio Browser (radio-browser.info): an open catalog of ~50 000 stations, no account. Any of its
 * mirrors answers the same; the first one that does is used.
 */
class RadioBrowser {
    private val mirrors = listOf("de1", "at1", "de2", "all").map { "https://$it.api.radio-browser.info" }
    @Volatile private var working: String? = null

    @Serializable
    private data class Row(
        val stationuuid: String = "",
        val name: String = "",
        val url: String = "",
        @SerialName("url_resolved") val urlResolved: String = "",
        val favicon: String = "",
        val tags: String = "",
        val countrycode: String = "",
        val codec: String = "",
        val bitrate: Int = 0,
        val lastcheckok: Int = 1,
    )

    @Serializable
    data class Tag(val name: String, val stationcount: Int = 0)

    suspend fun search(name: String = "", tag: String = "", country: String = "", limit: Int = 60): List<RadioStation> =
        stations("/json/stations/search", mapOf("name" to name, "tag" to tag, "countrycode" to country, "limit" to "$limit", "order" to "clickcount", "reverse" to "true", "hidebroken" to "true"))

    suspend fun top(limit: Int = 60, country: String = ""): List<RadioStation> =
        if (country.isBlank()) stations("/json/stations/topclick/$limit", mapOf("hidebroken" to "true"))
        else search(country = country, limit = limit)

    /** The most used tags (genres), for the chips above the list. */
    suspend fun tags(limit: Int = 40): List<Tag> = get("/json/tags", mapOf("order" to "stationcount", "reverse" to "true", "limit" to "$limit", "hidebroken" to "true")) {
        Http.json.decodeFromString<List<Tag>>(it).filter { t -> t.name.length in 2..24 }
    }

    /** Counts a listen, as the catalog asks of its apps (keeps «popular» honest). Best effort. */
    suspend fun clicked(uuid: String) {
        runCatching { get("/json/url/$uuid", emptyMap()) { } }
    }

    private suspend fun stations(path: String, query: Map<String, String>): List<RadioStation> = get(path, query) { body ->
        Http.json.decodeFromString<List<Row>>(body).filter { it.lastcheckok == 1 && (it.urlResolved.isNotBlank() || it.url.isNotBlank()) }.map {
            RadioStation(
                name = it.name.trim().ifBlank { it.url }, url = it.urlResolved.ifBlank { it.url },
                favicon = it.favicon.takeIf { f -> f.startsWith("http") }, tags = it.tags.takeIf { t -> t.isNotBlank() },
                country = it.countrycode.takeIf { c -> c.isNotBlank() }, codec = it.codec.takeIf { c -> c.isNotBlank() },
                bitrate = it.bitrate, uuid = it.stationuuid.takeIf { u -> u.isNotBlank() },
            )
        }.distinctBy { it.url }
    }

    /**
     * Asks the mirrors in turn, each over https and then plain http: on some networks (the
     * emulator's among them) TLS to the catalog stalls while plain http answers at once — and the
     * catalog is public data anyway. Our own small client, since Android's refuses plain http.
     */
    private suspend fun <T> get(path: String, query: Map<String, String>, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()
        var last: Throwable? = null
        for (base in listOfNotNull(working) + mirrors.filter { it != working }) {
            for (scheme in listOf("https", "http")) {
                try {
                    val url = (base.replace("https:", "$scheme:") + path).toHttpUrl().newBuilder()
                        .apply { query.forEach { (k, v) -> if (v.isNotBlank()) addQueryParameter(k, v) } }.build().toString()
                    val body = RadioHttp.open(url, mapOf("Accept" to "application/json"), connectMs = 6_000, readMs = 12_000).use { r ->
                        r.input.readBytes().decodeToString()
                    }
                    working = base
                    return@withContext parse(body)
                } catch (e: Exception) {
                    last = e
                    errors += "$scheme://" + base.substringAfter("//").substringBefore('.') + ": " + (e.message ?: e.javaClass.simpleName)
                }
            }
        }
        throw IOException("The radio catalog doesn't answer (${errors.joinToString("; ")})", last)
    }
}

/** An open stream: the server's headers (lower-case names) and the bytes. */
class StreamResponse(val status: Int, val headers: Map<String, String>, val input: InputStream, private val socket: Socket) : java.io.Closeable {
    val metaInt: Int get() = headers["icy-metaint"]?.toIntOrNull() ?: 0
    val contentType: String get() = headers["content-type"].orEmpty()
    val stationName: String? get() = headers["icy-name"]?.takeIf { it.isNotBlank() }
    override fun close() { runCatching { socket.close() } }
}

/**
 * A bare HTTP/1.0 client over a socket for radio streams. Many stations only speak plain http://,
 * and some answer «ICY 200 OK» — Android refuses cleartext through its HTTP stacks (on purpose,
 * for everything else in the app), and neither stack takes ICY. HTTP/1.0 also keeps servers from
 * chunking the stream.
 */
object RadioHttp {
    fun open(url: String, headers: Map<String, String> = mapOf("Icy-MetaData" to "1"), redirects: Int = 5, connectMs: Int = 10_000, readMs: Int = 20_000): StreamResponse {
        val u = URI(url)
        val https = u.scheme.equals("https", true)
        val host = u.host ?: throw IOException("no host in $url")
        val port = if (u.port > 0) u.port else if (https) 443 else 80
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectMs)
            socket.soTimeout = readMs
            val s = if (https) (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true) else socket
            val path = (u.rawPath?.ifBlank { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
            val req = buildString {
                append("GET $path HTTP/1.0\r\nHost: $host${if (u.port > 0) ":${u.port}" else ""}\r\n")
                append("User-Agent: Palimpsest/1 (+https://github.com/xvin84/palimpsest)\r\nConnection: close\r\n")
                if (headers.keys.none { it.equals("Accept", true) }) append("Accept: */*\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                append("\r\n")
            }
            s.getOutputStream().apply { write(req.toByteArray()); flush() }
            val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
            val statusLine = readLine(input)
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("not a stream: $statusLine")
            val head = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) head[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            if (status in 300..399 && redirects > 0) {
                val to = head["location"] ?: throw IOException("redirect without a location")
                s.close()
                return open(u.resolve(to).toString(), headers, redirects - 1, connectMs, readMs)
            }
            if (status !in 200..299) { s.close(); throw IOException("HTTP $status from $host") }
            return StreamResponse(status, head, input, s)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw if (e is IOException) e else IOException(e.message ?: e.toString(), e)
        }
    }

    private fun readLine(input: InputStream): String {
        val b = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) { if (b.isEmpty()) throw IOException("the station closed the connection"); break }
            if (c == '\n'.code) break
            if (c != '\r'.code) b.append(c.toChar())
            if (b.length > 8192) throw IOException("header line too long")
        }
        return String(b.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
    }

    /**
     * The link a station's page or the user gave may be a playlist (.m3u, .pls) rather than the
     * stream: the first stream in it, else the link itself.
     */
    fun resolve(url: String): String {
        val lower = url.substringBefore('?').lowercase()
        val looksLikeList = lower.endsWith(".m3u") || lower.endsWith(".pls") || lower.endsWith(".asx")
        if (!looksLikeList) return url
        return runCatching {
            open(url, emptyMap()).use { r ->
                val text = r.input.readNBytes(64 * 1024).decodeToString()
                parsePlaylist(text)
            }
        }.getOrNull() ?: url
    }

    /** First http(s) entry of an M3U, PLS or ASX playlist. */
    fun parsePlaylist(text: String): String? = text.lineSequence().map { it.trim() }.mapNotNull { line ->
        when {
            line.startsWith("http", true) -> line
            line.matches(Regex("(?i)file\\d+=.*")) -> line.substringAfter('=').trim()
            line.contains("href=\"", true) -> Regex("(?i)href=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
            else -> null
        }
    }.firstOrNull { it.startsWith("http", true) }
}

/**
 * Takes the ICY metadata out of a stream that has it ([metaInt] bytes of audio, then a block of
 * text): what's left is plain audio, and every «StreamTitle» goes to [onTitle].
 */
class IcyInputStream(input: InputStream, private val metaInt: Int, private val onTitle: (String) -> Unit) : FilterInputStream(input) {
    private var left = metaInt

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (metaInt <= 0) return super.read(b, off, len)
        if (left == 0) { readMeta(); left = metaInt }
        val n = super.read(b, off, minOf(len, left))
        if (n > 0) left -= n
        return n
    }

    private fun readMeta() {
        val size = super.read().takeIf { it >= 0 }?.times(16) ?: return
        if (size == 0) return
        val block = ByteArray(size)
        var got = 0
        while (got < size) { val n = super.read(block, got, size - got); if (n < 0) return; got += n }
        parseTitle(block)?.let(onTitle)
    }

    companion object {
        /** «StreamTitle='Artist - Song';…» → «Artist - Song»; empty or missing → null. */
        fun parseTitle(block: ByteArray): String? {
            val raw = block.decodeToString().trimEnd('\u0000')
            val text = if ('�' in raw) String(block, Charsets.ISO_8859_1).trimEnd('\u0000') else raw
            return Regex("StreamTitle='(.*?)';", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it != "-" }
        }
    }
}
