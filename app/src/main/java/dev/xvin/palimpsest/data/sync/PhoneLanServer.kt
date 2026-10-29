package dev.xvin.palimpsest.data.sync

import dev.xvin.palimpsest.core.sync.HandoffConst
import dev.xvin.palimpsest.core.sync.Hello
import dev.xvin.palimpsest.core.sync.LanAnswer
import dev.xvin.palimpsest.core.sync.LanMessage
import dev.xvin.palimpsest.core.sync.LanReply
import dev.xvin.palimpsest.core.sync.LanTransport
import dev.xvin.palimpsest.playback.PhoneHandoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * The phone's ear for the paired computer while the app runs: it can send music here, take what
 * plays here, and fetch the files of songs it doesn't have. Every minute the phone tells the
 * computer where it listens ([Hello]) — the computer can't find a phone on its own.
 * Only the paired computer's token gets an answer.
 */
class PhoneLanServer(
    private val lan: () -> LanSyncRepository,
    private val handoff: PhoneHandoff,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var server: ServerSocket? = null

    /** Follows the pairing: listens while paired, stops when unpaired. */
    fun follow() {
        scope.launch { lan().pairing.collect { p -> if (p != null) start() else stop() } }
    }

    /** Says hello now — e.g. when the app comes to the front, or the button was tapped while out of reach. */
    fun helloNow() {
        if (job == null) return
        scope.launch(Dispatchers.IO) { hello() }
    }

    @Synchronized
    private fun start() {
        if (job != null) return
        val socket = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(HandoffConst.PHONE_PORT)) } }
            .recoverCatching { ServerSocket(0) } // the usual port is taken: any will do, the hello carries it
            .getOrNull() ?: return
        server = socket
        handoff.port = socket.localPort
        job = scope.launch(Dispatchers.IO) {
            // Often enough that a computer switched on, or a phone that walked out of the Wi-Fi, shows within half a minute.
            launch { while (isActive) { hello(); delay(20_000) } }
            while (isActive) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { LanTransport.serve(client, ::answer) }
            }
        }
    }

    @Synchronized
    private fun stop() {
        job?.cancel(); job = null
        runCatching { server?.close() }; server = null
        handoff.port = 0
        handoff.setReachable(false)
    }

    private suspend fun hello() {
        val port = handoff.port.takeIf { it > 0 } ?: return
        val ok = runCatching { lan().withDesktop { p -> LanTransport.call(p.host, p.port, LanMessage(token = p.token, hello = Hello(lan().device, port)), 5_000) }.ok }.getOrDefault(false)
        handoff.setReachable(ok)
    }

    private fun answer(msg: LanMessage, host: String): LanAnswer = runBlocking {
        val p = lan().pairing.value
        if (p == null || msg.token != p.token) return@runBlocking LanAnswer(LanReply(error = "not paired"))
        when {
            msg.ping -> LanAnswer(LanReply(ok = true))
            msg.file != null -> handoff.file(msg.file!!.key, msg.file!!.from)
            // Through a port forward (adb, an emulator) the computer seems to call from this very phone: use its paired address.
            msg.handoff != null -> LanAnswer(handoff.received(msg.handoff!!, if (host.startsWith("127.") || host == "::1") p.host else host, p.token))
            msg.pull != null -> LanAnswer(handoff.pulled())
            else -> LanAnswer(LanReply(error = "empty request"))
        }
    }
}
