package dev.xvin.palimpsest.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
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
import dev.xvin.palimpsest.core.sync.PullRequest
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.library.TagIO
import dev.xvin.palimpsest.data.remote.RemoteRepository
import dev.xvin.palimpsest.data.sync.LanSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Moments the UI animates: the cover flying off to the computer, or arriving from it. */
sealed interface HandoffEvent {
    val title: String
    /** Something Coil can draw: a cover URI, bytes or URL. */
    val art: Any?
    data class Sent(val to: String, override val title: String, override val art: Any?) : HandoffEvent
    data class Received(val from: String, override val title: String, override val art: Any?) : HandoffEvent
}

/**
 * Moving what plays between this phone and the paired computer ([Handoff]): send it there, take
 * it from there, or play what the computer sends — the volume fading out on one side and in on
 * the other. Songs one side doesn't have stream from the other over Wi-Fi.
 */
class PhoneHandoff(
    private val db: AppDatabase,
    private val remote: RemoteRepository,
    private val lan: () -> LanSyncRepository,
    private val player: PlayerConnection,
    private val radio: RadioController,
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<HandoffEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<HandoffEvent> = _events
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _reachable = MutableStateFlow(false)
    /** Whether the paired computer answered lately: the handover button and gesture work only then. */
    val reachable: StateFlow<Boolean> = _reachable

    fun setReachable(on: Boolean) { _reachable.value = on }

    /** The port this phone's [dev.xvin.palimpsest.data.sync.PhoneLanServer] listens on; 0 while it doesn't. */
    @Volatile var port: Int = 0

    /** What plays here, as a [Handoff]; null when nothing does. */
    suspend fun build(): Handoff? {
        val (ids, position) = player.upcoming(HandoffConst.MAX_QUEUE)
        if (ids.isEmpty()) return null
        val tracks = withContext(Dispatchers.IO) { db.tracks().getMany(ids.mapNotNull { it.toLongOrNull() }).associateBy { it.id } }
        var covers = 0
        val queue = ids.mapNotNull { id ->
            val t = id.toLongOrNull()?.let { tracks[it] }
            when {
                t != null -> describe(t, withCover = covers++ < HandoffConst.COVERS)
                id.startsWith(MediaItems.LAN_PREFIX) -> LanItem.fromMediaId(id)?.let { item ->
                    item.track.copy(cover = LanItem.covers[id]?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                }
                else -> null // a version or a file from another app: nothing the computer could find
            }
        }
        if (queue.isEmpty()) return null
        val st = player.state.value
        return Handoff(lan().device, queue, 0, position, st.isPlaying, radio.snapshot(), port)
    }

    private suspend fun describe(t: TrackEntity, withCover: Boolean): HandoffTrack = HandoffTrack(
        identity = LanSync.identity(t.artist, t.title),
        title = t.title, artist = t.artist, album = t.album, durationMs = t.durationMs,
        service = t.service.takeIf { t.remoteId != null }, remoteId = t.remoteId,
        coverUrl = t.coverUrl?.takeIf { it.startsWith("http") },
        cover = if (withCover && t.path != null) thumbnail(File(t.path)) else null,
        fileKey = t.id.toString().takeIf { t.path != null },
        ext = t.path?.substringAfterLast('.', ""),
    )

    private suspend fun thumbnail(f: File): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = TagIO.coverBytes(f) ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 360) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
            val side = 360f / maxOf(bmp.width, bmp.height)
            val scaled = if (side < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * side).toInt().coerceAtLeast(1), (bmp.height * side).toInt().coerceAtLeast(1), true) else bmp
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

    /** «На компьютер»: the computer takes over from this second; the music here fades out. */
    suspend fun send(): Result<Unit> = runCatching {
        val h = build() ?: error("nothing is playing")
        _busy.value = true
        try {
            reachIfIo {
            val name = lan().withDesktop { p ->
                val r = LanTransport.call(p.host, p.port, LanMessage(token = p.token, handoff = h), 15_000)
                if (!r.ok) error(r.error ?: "refused")
                p.name
            }
            val np = player.state.value.nowPlaying
            _events.tryEmit(HandoffEvent.Sent(name, np?.title.orEmpty(), np?.artworkUri ?: np?.artworkData))
            player.fadeOutAndPause()
            }
        } finally {
            _busy.value = false
        }
    }

    /** «Забрать с компьютера»: asks the computer for what it plays, and plays it here. */
    suspend fun pull(): Result<Unit> = runCatching {
        _busy.value = true
        try {
            reachIfIo {
            val (h, host, token) = lan().withDesktop { p ->
                val r = LanTransport.call(p.host, p.port, LanMessage(token = p.token, pull = PullRequest(lan().device)), 15_000)
                Triple(r.handoff ?: error(r.error ?: "nothing is playing there"), p.host, p.token)
            }
            play(h, host, token)
            }
        } finally {
            _busy.value = false
        }
    }

    /** A network failure means the computer is out of reach (the button greys out); getting through means it isn't. */
    private suspend fun <T> reachIfIo(block: suspend () -> T): T = try {
        block().also { _reachable.value = true }
    } catch (e: java.io.IOException) {
        _reachable.value = false
        throw e
    }

    /** The computer asked for what plays here: hand it over and fade out. */
    suspend fun pulled(): LanReply {
        val h = build() ?: return LanReply(error = "nothing is playing")
        val np = player.state.value.nowPlaying
        _events.tryEmit(HandoffEvent.Sent(lan().pairing.value?.name.orEmpty(), np?.title.orEmpty(), np?.artworkUri ?: np?.artworkData))
        scope.launch { player.fadeOutAndPause() }
        return LanReply(handoff = h, ok = true)
    }

    /** The computer sent what it plays. */
    suspend fun received(h: Handoff, host: String, token: String): LanReply =
        runCatching { play(h, host, token); LanReply(ok = true) }.getOrElse { LanReply(error = it.message ?: it.toString()) }

    /** Finds each song here — the file, else the service track — and streams the rest from the computer. */
    private suspend fun play(h: Handoff, host: String, token: String) {
        val all = withContext(Dispatchers.IO) { db.tracks().all() }
        // Songs this phone can actually play: its files, and service tracks of services it's signed in to.
        val index = withContext(Dispatchers.IO) {
            all.mapNotNull { t ->
                when {
                    t.path != null && File(t.path).isFile -> t to 0
                    t.remoteId != null && remote.providers[t.service]?.isReady == true -> t to 1
                    else -> null
                }
            }.sortedBy { it.second }.groupBy({ LanSync.identity(it.first.artist, it.first.title) }, { it.first }).mapValues { it.value.first() }
        }
        suspend fun service(ht: HandoffTrack): String? = ht.remoteId?.let { rid ->
            ht.service?.let { s -> runCatching { remote.ensureTrack(RemoteTrack(s, rid, ht.title, listOf(ht.artist), album = ht.album, durationMs = ht.durationMs, coverUrl = ht.coverUrl)).id.toString() }.getOrNull() }
        }
        val resolved = h.queue.mapNotNull { ht ->
            // A service this phone can't play (not signed in) loses to the sender's own file.
            val playable = ht.service?.let { remote.providers[it]?.isReady } == true
            val id = index[ht.identity]?.id?.toString()
                ?: (if (playable) service(ht) else null)
                ?: ht.fileKey?.takeIf { h.port > 0 }?.let { LanItem.of(host, h.port, token, ht).mediaId() }
                ?: service(ht)
            id?.let { ht to it }
        }
        if (resolved.isEmpty()) error("none of these songs can play here")
        // The current song may be the one that couldn't be found: then start the next one from its beginning.
        val sameSong = resolved.first().first.identity == h.current?.identity
        player.playHandoff(resolved.map { it.second }, 0, if (sameSong) h.positionMs else 0, h.playing).join()
        h.station?.let { radio.resume(it) }
        val first = resolved.first()
        val art: Any? = first.second.toLongOrNull()?.let { all.firstOrNull { t -> t.id == it } }?.let { t -> dev.xvin.palimpsest.data.library.ArtProvider.uriFor(t.id, t.modifiedAt) }
            ?: LanItem.covers[first.second] ?: first.first.coverUrl
        _events.tryEmit(HandoffEvent.Received(h.from, first.first.title, art))
    }

    /** A file the computer asked for: only library songs that are files on this phone. */
    suspend fun file(key: String, from: Long): LanAnswer {
        val f = key.toLongOrNull()?.let { withContext(Dispatchers.IO) { db.tracks().get(it) } }?.path?.let(::File)?.takeIf { it.isFile }
            ?: return LanAnswer(LanReply(error = "no such file"))
        return LanAnswer(LanReply(file = FileReply(f.length(), f.extension)), f, from.coerceIn(0, f.length()))
    }
}
