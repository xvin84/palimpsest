package dev.xvin.palimpsest.core

import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.radio.SavedStation
import dev.xvin.palimpsest.core.radio.StationSpec
import dev.xvin.palimpsest.core.sync.FileReply
import dev.xvin.palimpsest.core.sync.Handoff
import dev.xvin.palimpsest.core.sync.HandoffTrack
import dev.xvin.palimpsest.core.sync.LanAnswer
import dev.xvin.palimpsest.core.sync.LanMessage
import dev.xvin.palimpsest.core.sync.LanReply
import dev.xvin.palimpsest.core.sync.LanTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

class HandoffTest {
    private fun server(handle: (LanMessage, String) -> LanAnswer): ServerSocket {
        val s = ServerSocket(0)
        thread(isDaemon = true) { while (!s.isClosed) { val c = runCatching { s.accept() }.getOrNull() ?: break; thread(isDaemon = true) { LanTransport.serve(c, handle) } } }
        return s
    }

    @Test fun filesStreamFromAnyByteAndOnlyWithTheToken() {
        val data = ByteArray(300_000) { (it * 31 % 251).toByte() }
        val f = File.createTempFile("handoff", ".flac").apply { writeBytes(data); deleteOnExit() }
        val s = server { msg, _ ->
            if (msg.token != "secret") LanAnswer(LanReply(error = "not paired"))
            else LanAnswer(LanReply(file = FileReply(f.length(), f.extension)), f, msg.file!!.from)
        }
        s.use {
            val whole = LanTransport.openFile("127.0.0.1", s.localPort, "secret", "1")
            assertEquals(data.size.toLong(), whole.length)
            assertEquals("flac", whole.ext)
            assertArrayEquals(data, whole.input.use { it.readBytes() })
            // A seek: from the middle on.
            val tail = LanTransport.openFile("127.0.0.1", s.localPort, "secret", "1", from = 123_456)
            assertArrayEquals(data.copyOfRange(123_456, data.size), tail.input.use { it.readBytes() })
            val refused = runCatching { LanTransport.openFile("127.0.0.1", s.localPort, "wrong", "1") }
            assertTrue(refused.exceptionOrNull()?.message == "not paired")
        }
    }

    @Test fun aHandoffTravelsWhole() {
        val h = Handoff(
            from = "realme RMX3852",
            queue = listOf(
                HandoffTrack("земфира|искала", "Искала", "Земфира", durationMs = 200_000, fileKey = "42", ext = "flac", cover = "AAEC"),
                HandoffTrack("x|y", "Y", "X", service = Service.YANDEX, remoteId = "123:456"),
            ),
            positionMs = 61_500,
            station = SavedStation(StationSpec(StationSpec.YANDEX, listOf(StationSpec.MY_WAVE), "Моя волна"), listOf("YANDEX:1")),
            port = 47825,
        )
        var got: Handoff? = null
        val s = server { msg, host -> got = msg.handoff; assertEquals("127.0.0.1", host); LanAnswer(LanReply(ok = true)) }
        s.use {
            val r = LanTransport.call("127.0.0.1", s.localPort, LanMessage(token = "t", handoff = h))
            assertTrue(r.ok)
        }
        assertEquals(h, got)
        assertEquals("Искала", got!!.current!!.title)
    }
}
