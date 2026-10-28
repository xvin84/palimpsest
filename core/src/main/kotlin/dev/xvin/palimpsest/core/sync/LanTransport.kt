package dev.xvin.palimpsest.core.sync

import dev.xvin.palimpsest.core.net.Http
import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * One request over the LAN socket: pairing, a sync, or handing playback over ([Handoff]) — a phone
 * saying where it listens ([hello]), playing something sent over ([handoff]), giving up what
 * plays to the asker ([pull]) or a file for a handed-over track ([file]).
 */
@Serializable
data class LanMessage(
    val token: String? = null,
    val pair: PairRequest? = null,
    val sync: SyncRequest? = null,
    val hello: Hello? = null,
    val handoff: Handoff? = null,
    val pull: PullRequest? = null,
    val file: FileRequest? = null,
)

@Serializable
data class LanReply(
    val error: String? = null,
    val pair: PairResponse? = null,
    val sync: SyncResponse? = null,
    val handoff: Handoff? = null,
    val file: FileReply? = null,
    val ok: Boolean = false,
)

/** A desktop heard on the network. */
data class LanPeer(val host: String, val port: Int, val name: String)

/** What a server answers: the reply, and for a file request the file to send after it, from a byte. */
class LanAnswer(val reply: LanReply, val body: java.io.File? = null, val from: Long = 0)

/** A file streaming in from the other device. */
class RemoteFile(val length: Long, val ext: String, val input: java.io.InputStream)

/**
 * The wire: a TCP connection carrying one gzipped JSON message each way, length-prefixed. Plain
 * sockets, so Android's ban on cleartext HTTP stays on for everything else.
 */
object LanTransport {
    private const val MAX = 64 shl 20

    fun <T> write(out: DataOutputStream, value: T, serializer: kotlinx.serialization.KSerializer<T>) {
        val bytes = java.io.ByteArrayOutputStream().also { b ->
            GZIPOutputStream(b).use { it.write(Http.json.encodeToString(serializer, value).toByteArray()) }
        }.toByteArray()
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    fun <T> read(input: DataInputStream, serializer: kotlinx.serialization.KSerializer<T>): T {
        val n = input.readInt()
        require(n in 1..MAX) { "bad frame" }
        val bytes = ByteArray(n).also { input.readFully(it) }
        val text = GZIPInputStream(bytes.inputStream()).use { it.readBytes().decodeToString() }
        return Http.json.decodeFromString(serializer, text)
    }

    /** Sends [msg] to a desktop and waits for its reply. */
    fun call(host: String, port: Int, msg: LanMessage, timeoutMs: Int = 60_000): LanReply =
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 5_000)
            s.soTimeout = timeoutMs
            write(DataOutputStream(s.getOutputStream().buffered()), msg, LanMessage.serializer())
            read(DataInputStream(s.getInputStream().buffered()), LanReply.serializer())
        }

    /**
     * Opens a handed-over track's file on the other device from byte [from]: its length and type,
     * then the raw bytes on the same socket (closing the stream closes the socket).
     */
    fun openFile(host: String, port: Int, token: String, key: String, from: Long = 0): RemoteFile {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 5_000)
            s.soTimeout = 30_000
            val out = DataOutputStream(s.getOutputStream().buffered())
            write(out, LanMessage(token = token, file = FileRequest(key, from)), LanMessage.serializer())
            val input = DataInputStream(s.getInputStream().buffered(256 * 1024))
            val reply = read(input, LanReply.serializer())
            val f = reply.file ?: error(reply.error ?: "no file")
            return RemoteFile(f.length, f.ext, object : java.io.FilterInputStream(input) {
                override fun close() { runCatching { super.close() }; s.close() }
            })
        } catch (e: Throwable) {
            s.close()
            throw e
        }
    }

    /**
     * Serves one connection: reads the request, answers it with [handle] and, for a file, sends
     * its bytes after the reply. Both the desktop and the phone listen with this.
     */
    fun serve(socket: Socket, handle: (LanMessage, host: String) -> LanAnswer) {
        socket.use { c ->
            c.soTimeout = 60_000
            val out = DataOutputStream(c.getOutputStream().buffered(256 * 1024))
            val answer = runCatching {
                handle(read(DataInputStream(c.getInputStream().buffered()), LanMessage.serializer()), c.inetAddress.hostAddress)
            }.getOrElse { LanAnswer(LanReply(error = it.message ?: it.toString())) }
            runCatching {
                write(out, answer.reply, LanReply.serializer())
                answer.body?.let { f ->
                    java.io.RandomAccessFile(f, "r").use { raf ->
                        raf.seek(answer.from)
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = raf.read(buf); if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    out.flush()
                }
            }
        }
    }

    /** Listens for desktops' beacons for [timeoutMs]. */
    fun discover(timeoutMs: Int = 3_000): List<LanPeer> {
        val found = LinkedHashMap<String, LanPeer>()
        DatagramSocket(null).use { s ->
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(LanSync.BEACON_PORT))
            val until = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(512)
            while (System.currentTimeMillis() < until) {
                s.soTimeout = (until - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { break }
                parseBeacon(String(p.data, 0, p.length), p.address.hostAddress)?.let { found[it.host] = it }
            }
        }
        return found.values.toList()
    }

    fun beacon(port: Int, name: String) = "${LanSync.BEACON_PREFIX} $port $name"

    fun parseBeacon(text: String, host: String): LanPeer? {
        val parts = text.split(' ', limit = 3)
        if (parts.size < 2 || parts[0] != LanSync.BEACON_PREFIX) return null
        return LanPeer(host, parts[1].toIntOrNull() ?: return null, parts.getOrNull(2).orEmpty())
    }

    /** Announces this desktop once (call every couple of seconds). */
    fun announce(port: Int, name: String) {
        DatagramSocket().use { s ->
            s.broadcast = true
            val data = beacon(port, name).toByteArray()
            s.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), LanSync.BEACON_PORT))
        }
    }
}
