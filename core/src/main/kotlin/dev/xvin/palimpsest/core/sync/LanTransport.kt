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

/** One request over the LAN socket: pairing or a sync. */
@Serializable
data class LanMessage(val token: String? = null, val pair: PairRequest? = null, val sync: SyncRequest? = null)

@Serializable
data class LanReply(val error: String? = null, val pair: PairResponse? = null, val sync: SyncResponse? = null)

/** A desktop heard on the network. */
data class LanPeer(val host: String, val port: Int, val name: String)

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
