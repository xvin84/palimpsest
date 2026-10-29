package dev.xvin.palimpsest.desktop.data

import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.sync.FileReply
import dev.xvin.palimpsest.core.sync.Handoff
import dev.xvin.palimpsest.core.sync.HandoffConst
import dev.xvin.palimpsest.core.sync.HandoffTrack
import dev.xvin.palimpsest.core.sync.LanAnswer
import dev.xvin.palimpsest.core.sync.LanMessage
import dev.xvin.palimpsest.core.sync.LanReply
import dev.xvin.palimpsest.core.sync.LanSync
import dev.xvin.palimpsest.core.sync.LanTransport
import dev.xvin.palimpsest.desktop.playback.LanSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

/** A paired phone that said where it listens, and when it last answered. */
data class PhoneSeen(val device: String, val host: String, val port: Int, val token: String, val at: Long)

/** Moments the UI animates: the cover flying off to the phone, or arriving from it. */
sealed interface HandoffEvent {
    val track: Track?
    data class Sent(val to: String, override val track: Track?) : HandoffEvent
    data class Received(val from: String, override val track: Track?) : HandoffEvent
}

/**
 * Moving what plays between this computer and the phone ([Handoff]): send to the phone, give it
 * up when the phone pulls it, take it when the phone sends it — each with the volume fading out
 * on one side and in on the other.
 */
class HandoffDesk(private val graph: Graph) {
    private val _phone = MutableStateFlow<PhoneSeen?>(null)
    val phone: StateFlow<PhoneSeen?> = _phone
    private val _events = MutableSharedFlow<HandoffEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<HandoffEvent> = _events
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _online = MutableStateFlow(false)
    /** Whether the phone answers right now: the handover button works only then. */
    val online: StateFlow<Boolean> = _online

    init {
        // The phone says hello every 20 s while Palimpsest runs there; when it falls silent, ask it directly.
        graph.scope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                checkNow()
            }
        }
    }

    fun hello(device: String, host: String, port: Int, token: String) {
        _phone.value = PhoneSeen(device, host, port, token, System.currentTimeMillis())
        _online.value = true
    }

    /** Is the phone there? A recent hello says yes; otherwise a ping decides. */
    suspend fun checkNow() {
        val p = _phone.value ?: run { _online.value = false; return }
        if (System.currentTimeMillis() - p.at < 30_000) { _online.value = true; return }
        val ok = withContext(Dispatchers.IO) { runCatching { LanTransport.call(p.host, p.port, LanMessage(token = p.token, ping = true), 3_000).ok }.getOrDefault(false) }
        if (ok) _phone.value = p.copy(at = System.currentTimeMillis())
        _online.value = ok
    }

    /** What plays here, as a [Handoff]; null when nothing does. */
    fun build(): Handoff? {
        val st = graph.player.state.value
        if (st.current == null) return null
        val queue = st.queue.drop(st.index).take(HandoffConst.MAX_QUEUE)
        return Handoff(
            from = graph.lanSync.name,
            queue = queue.mapIndexed { i, t -> describe(t, withCover = i < HandoffConst.COVERS) },
            index = 0,
            positionMs = st.positionMs,
            playing = st.playing,
            station = graph.player.stationSnapshot(),
            port = LanSync.PORT,
        )
    }

    private fun describe(t: Track, withCover: Boolean): HandoffTrack {
        val lan = graph.player.lanSourceOf(t)
        return HandoffTrack(
            identity = LanSync.identity(t.artist, t.title),
            title = t.title, artist = t.artist, album = t.album, durationMs = t.durationMs,
            service = t.service.takeIf { t.remoteId != null }, remoteId = t.remoteId,
            coverUrl = t.coverUrl?.takeIf { it.startsWith("http") },
            cover = if (withCover && t.coverUrl?.startsWith("http") != true) lan?.cover ?: t.path?.let { thumbnail(t) } else null,
            // A song that came from the phone goes back to it as its own file.
            fileKey = lan?.key ?: t.id.takeIf { t.path != null },
            ext = lan?.ext ?: t.path?.substringAfterLast('.', ""),
        )
    }

    private fun thumbnail(t: Track): String? = runCatching {
        val src = kotlinx.coroutines.runBlocking { graph.covers.model(t) } as? File ?: return null
        val img = javax.imageio.ImageIO.read(src) ?: return null
        val side = 360
        val scale = minOf(1.0, side.toDouble() / maxOf(img.width, img.height))
        val w = (img.width * scale).toInt().coerceAtLeast(1); val h = (img.height * scale).toInt().coerceAtLeast(1)
        val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, w, h, null); dispose()
        }
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(out, "jpg", it) }.toByteArray()
        Base64.getEncoder().encodeToString(bytes)
    }.getOrNull()

    /** «На телефон»: the phone takes over from this second; the music here fades out. */
    suspend fun sendToPhone(): Result<Unit> = runCatching {
        val p = _phone.value ?: error("phone")
        val h = build() ?: error("nothing")
        _busy.value = true
        try {
            val reply = withContext(Dispatchers.IO) { LanTransport.call(p.host, p.port, LanMessage(token = p.token, handoff = h), 15_000) }
            if (!reply.ok) error(reply.error ?: "refused")
            val t = graph.player.state.value.current
            _events.tryEmit(HandoffEvent.Sent(p.device, t))
            graph.player.fadeOutAndPause()
        } catch (e: java.io.IOException) {
            // The phone went away (app closed, other network): the button greys out until it answers again.
            _phone.value = p.copy(at = 0)
            _online.value = false
            throw e
        } finally {
            _busy.value = false
        }
    }

    /** «Забрать с телефона»: asks the phone for what it plays, and plays it here. */
    suspend fun pullFromPhone(): Result<Unit> = runCatching {
        val p = _phone.value ?: error("phone")
        _busy.value = true
        try {
            val reply = withContext(Dispatchers.IO) { LanTransport.call(p.host, p.port, LanMessage(token = p.token, pull = dev.xvin.palimpsest.core.sync.PullRequest(graph.lanSync.name)), 15_000) }
            val h = reply.handoff ?: error(reply.error ?: "nothing is playing there")
            val r = received(h, p.host, p.token)
            if (!r.ok) error(r.error ?: "refused")
        } catch (e: java.io.IOException) {
            _phone.value = p.copy(at = 0)
            _online.value = false
            throw e
        } finally {
            _busy.value = false
        }
    }

    /** The phone asked for what plays here: hand it over and fade out. */
    fun pulled(device: String): LanReply {
        val h = build() ?: return LanReply(error = "nothing is playing")
        _events.tryEmit(HandoffEvent.Sent(device, graph.player.state.value.current))
        graph.scope.launch { graph.player.fadeOutAndPause() }
        return LanReply(handoff = h, ok = true)
    }

    /** The phone sent what it plays: find each song here, stream the rest from the phone. */
    fun received(h: Handoff, host: String, token: String): LanReply {
        val lib = graph.library
        // Songs this computer can actually play: its files, and service tracks of services it's signed in to.
        val index = lib.tracks.value.mapNotNull { t ->
            when {
                t.path != null && File(t.path).isFile -> t to 0
                t.remoteId != null && graph.provider(t.service)?.isReady == true -> t to 1
                else -> null
            }
        }.sortedBy { it.second }.groupBy({ LanSync.identity(it.first.artist, it.first.title) }, { it.first }).mapValues { it.value.first() }
        fun service(ht: HandoffTrack) = ht.remoteId?.let { rid ->
            ht.service?.let { s -> lib.ensureRemote(RemoteTrack(s, rid, ht.title, listOf(ht.artist), album = ht.album, durationMs = ht.durationMs, coverUrl = ht.coverUrl)) }
        }
        val tracks = h.queue.mapNotNull { ht ->
            // A service this computer can't play (not signed in) loses to the phone's own file.
            val playable = ht.service?.let { graph.provider(it)?.isReady } == true
            index[ht.identity]
                ?: (if (playable) service(ht) else null)
                ?: ht.fileKey?.takeIf { h.port > 0 }?.let { key -> graph.player.lanTrack(ht, LanSource(host, h.port, token, key, ht.ext ?: "audio", ht.cover)) }
                ?: service(ht)
        }
        if (tracks.isEmpty()) return LanReply(error = "none of these songs can play here")
        // The current song may be the one that couldn't be found: then start from the next one that could, from its beginning.
        val sameSong = h.current?.identity == LanSync.identity(tracks[0].artist, tracks[0].title)
        val station = h.station?.let { s -> graph.player.feedFor(s) }
        graph.scope.launch {
            graph.player.playHandoff(tracks, 0, if (sameSong) h.positionMs else 0, station, h.playing)
            _events.tryEmit(HandoffEvent.Received(h.from, tracks[0]))
        }
        return LanReply(ok = true)
    }

    /** A file the phone asked for: only songs that are in the library (or came from the phone). */
    fun file(key: String, from: Long): LanAnswer {
        val f = graph.library.get(key)?.path?.let(::File)?.takeIf { it.isFile }
            ?: graph.player.cachedLanFile(key)
            ?: return LanAnswer(LanReply(error = "no such file"))
        return LanAnswer(LanReply(file = FileReply(f.length(), f.extension)), f, from.coerceIn(0, f.length()))
    }
}
