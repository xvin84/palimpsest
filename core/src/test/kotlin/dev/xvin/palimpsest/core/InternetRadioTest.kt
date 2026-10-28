package dev.xvin.palimpsest.core

import dev.xvin.palimpsest.core.radio.IcyInputStream
import dev.xvin.palimpsest.core.radio.RadioHttp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class InternetRadioTest {
    private fun meta(title: String): ByteArray {
        val text = "StreamTitle='$title';".toByteArray()
        val blocks = (text.size + 15) / 16
        return byteArrayOf(blocks.toByte()) + text + ByteArray(blocks * 16 - text.size)
    }

    @Test fun icyMetadataIsTakenOutAndTitlesReported() {
        val audio = ByteArray(100) { it.toByte() }
        val stream = ByteArrayOutputStream().apply {
            write(audio, 0, 40); write(meta("Земфира - Искала"))
            write(audio, 40, 40); write(byteArrayOf(0)) // an empty block: no change
            write(audio, 80, 20)
        }.toByteArray()
        val titles = ArrayList<String>()
        val out = IcyInputStream(stream.inputStream(), 40) { titles += it }.readBytes()
        assertArrayEquals(audio, out)
        assertEquals(listOf("Земфира - Искала"), titles)
        assertNull(IcyInputStream.parseTitle("StreamTitle='';".toByteArray()))
    }

    @Test fun playlistsGiveTheirFirstStream() {
        assertEquals("http://a.example/live", RadioHttp.parsePlaylist("#EXTM3U\n#EXTINF:-1,Radio\nhttp://a.example/live\nhttp://b"))
        assertEquals("https://x.example:8000/s.mp3", RadioHttp.parsePlaylist("[playlist]\nNumberOfEntries=1\nFile1=https://x.example:8000/s.mp3\nTitle1=X"))
        assertNull(RadioHttp.parsePlaylist("nothing here"))
    }

    @Test fun anIcyServerWithARedirect() {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            repeat(2) { n ->
                server.accept().use { c ->
                    val req = c.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine()?.takeIf { it.isNotEmpty() } }.toList() }
                    val out = c.getOutputStream()
                    if (n == 0) out.write("HTTP/1.0 302 Found\r\nLocation: /live\r\n\r\n".toByteArray())
                    else {
                        check(req.first().startsWith("GET /live HTTP/1.0") && req.any { it == "Icy-MetaData: 1" })
                        out.write("ICY 200 OK\r\nicy-name: Тест FM\r\nicy-metaint: 4\r\ncontent-type: audio/mpeg\r\n\r\n".toByteArray())
                        out.write(byteArrayOf(1, 2, 3, 4)); out.write(meta("A - B")); out.write(byteArrayOf(5, 6, 7, 8))
                    }
                    out.flush()
                }
            }
        }
        RadioHttp.open("http://127.0.0.1:${server.localPort}/stream").use { r ->
            assertEquals("Тест FM", r.stationName)
            assertEquals(4, r.metaInt)
            var title: String? = null
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), IcyInputStream(r.input, r.metaInt) { title = it }.readBytes())
            assertEquals("A - B", title)
        }
        server.close()
    }
}
