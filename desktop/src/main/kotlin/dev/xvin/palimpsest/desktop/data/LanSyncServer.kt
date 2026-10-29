package dev.xvin.palimpsest.desktop.data

import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.sync.LanAnswer
import dev.xvin.palimpsest.core.sync.LanMessage
import dev.xvin.palimpsest.core.sync.LanReply
import dev.xvin.palimpsest.core.sync.LanSync
import dev.xvin.palimpsest.core.sync.LanTransport
import dev.xvin.palimpsest.core.sync.PairResponse
import dev.xvin.palimpsest.core.sync.SyncPlay
import dev.xvin.palimpsest.core.sync.SyncPlaylist
import dev.xvin.palimpsest.core.sync.SyncReport
import dev.xvin.palimpsest.core.sync.SyncResponse
import dev.xvin.palimpsest.core.sync.SyncState
import dev.xvin.palimpsest.core.sync.SyncTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom

data class LanStatus(val running: Boolean = false, val code: String = "", val last: String? = null, val error: String? = null)

/**
 * The desktop end of phone sync ([LanSync]): a socket server plus a UDP beacon so the phone finds
 * it. Runs while «Sync with the phone» is on. A new phone pairs with the code shown in Settings;
 * five wrong codes and the code changes.
 */
class LanSyncServer(private val graph: Graph) {
    private val random = SecureRandom()
    private val _status = MutableStateFlow(LanStatus())
    val status: StateFlow<LanStatus> = _status
    private var job: Job? = null
    private var server: ServerSocket? = null
    private var wrongCodes = 0
    val name: String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Palimpsest")

    private fun newCode() = "%06d".format(random.nextInt(1_000_000))

    @Synchronized
    fun setEnabled(on: Boolean) {
        if (on == (job != null)) return
        if (!on) {
            job?.cancel(); job = null
            runCatching { server?.close() }; server = null
            _status.value = LanStatus()
            return
        }
        val socket = runCatching { ServerSocket(LanSync.PORT) }.getOrElse {
            _status.value = LanStatus(error = it.message); return
        }
        server = socket
        _status.value = LanStatus(running = true, code = newCode())
        job = graph.scope.launch(Dispatchers.IO) {
            launch { while (isActive) { runCatching { LanTransport.announce(LanSync.PORT, name) }; delay(2_000) } }
            while (isActive) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { LanTransport.serve(client, ::answer) }
            }
        }
    }

    /** Playback handover and files go around the lock: a file can take a while, and must not hold up a sync. */
    private fun answer(msg: LanMessage, host: String): LanAnswer {
        if (msg.pair == null && msg.sync == null) {
            val token = msg.token ?: return LanAnswer(LanReply(error = "not paired"))
            val device = graph.settings.value.lanTokens[token] ?: return LanAnswer(LanReply(error = "not paired"))
            msg.file?.let { return graph.handoff.file(it.key, it.from) }
            msg.hello?.let { graph.handoff.hello(it.device, host, it.port, token); return LanAnswer(LanReply(ok = true)) }
            if (msg.ping) return LanAnswer(LanReply(ok = true))
            msg.handoff?.let { return LanAnswer(graph.handoff.received(it, host, token)) }
            msg.pull?.let { return LanAnswer(graph.handoff.pulled(it.device.ifBlank { device })) }
            return LanAnswer(LanReply(error = "empty request"))
        }
        return LanAnswer(handle(msg))
    }

    @Synchronized
    private fun handle(msg: LanMessage): LanReply {
        msg.pair?.let { p ->
            if (p.code != _status.value.code) {
                if (++wrongCodes >= 5) { wrongCodes = 0; _status.value = _status.value.copy(code = newCode()) }
                return LanReply(error = "wrong code")
            }
            val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            graph.settings.update { it.copy(lanTokens = it.lanTokens + (token to p.device)) }
            _status.value = _status.value.copy(code = newCode(), last = "Paired: ${p.device}")
            return LanReply(pair = PairResponse(token, name))
        }
        val device = msg.token?.let { graph.settings.value.lanTokens[it] } ?: return LanReply(error = "not paired")
        val req = msg.sync ?: return LanReply(error = "empty request")
        val merged = LanSync.merge(req.base, req.state, state())
        val report = apply(merged)
        _status.value = _status.value.copy(last = "${device}: +${report.likesAdded}/−${report.likesRemoved} ♥, ${report.playlistsChanged} playlists, +${report.playsAdded} plays")
        return LanReply(sync = SyncResponse(merged, report))
    }

    private fun identity(t: Track) = LanSync.identity(t.artist, t.title)

    private fun syncTrack(t: Track) = SyncTrack(identity(t), t.title, t.artist, t.durationMs, t.service.takeIf { t.remoteId != null }, t.remoteId)

    /** What this desktop has, in sync terms. */
    fun state(): SyncState {
        val byId = graph.library.tracks.value.associateBy { it.id }
        val liked = graph.library.liked.value.mapNotNull { byId[it] }
        val lists = graph.playlists.lists.value.map { p -> p to p.trackIds.mapNotNull { byId[it] } }
        val plays = graph.history.plays.value.map { SyncPlay(LanSync.identity(it.artist, it.title), it.title, it.artist, it.at, it.listenedMs) }
        return SyncState(
            likes = liked.mapTo(LinkedHashSet(), ::identity),
            playlists = lists.map { (p, ts) -> SyncPlaylist(p.name, ts.map(::identity)) },
            plays = plays,
            tracks = (liked + lists.flatMap { it.second }).distinctBy { it.id }.map(::syncTrack),
        )
    }

    /** Makes this desktop match [merged]; returns what changed. */
    private fun apply(merged: SyncState): SyncReport {
        val lib = graph.library
        // A song may be a file and a service track at once: prefer the file.
        val index = lib.tracks.value.sortedBy { if (it.isLocal) 0 else 1 }.groupBy(::identity).mapValues { it.value.first() }
        val described = merged.tracks.associateBy { it.identity }
        fun resolve(key: String): Track? = index[key] ?: described[key]?.let { d ->
            val service = d.service ?: return@let null
            val remoteId = d.remoteId ?: return@let null
            lib.ensureRemote(RemoteTrack(service = service, id = remoteId, title = d.title, artists = listOf(d.artist), durationMs = d.durationMs))
        }

        val likedNow = lib.liked.value
        val byId = lib.tracks.value.associateBy { it.id }
        var removed = 0
        likedNow.forEach { id -> val t = byId[id] ?: return@forEach; if (identity(t) !in merged.likes) { lib.setLiked(id, false); removed++ } }
        var added = 0
        merged.likes.forEach { k -> val t = resolve(k) ?: return@forEach; if (t.id !in lib.liked.value) { lib.setLiked(t.id, true); added++ } }

        var changed = 0
        val lists = graph.playlists
        val wanted = merged.playlists.associateBy { it.name.lowercase() }
        lists.lists.value.forEach { p -> if (p.name.lowercase() !in wanted) { lists.delete(p.id); changed++ } }
        merged.playlists.forEach { mp ->
            val ids = mp.keys.mapNotNull { resolve(it)?.id }
            val existing = lists.lists.value.firstOrNull { it.name.equals(mp.name, ignoreCase = true) }
            when {
                existing == null -> { lists.setTracks(lists.create(mp.name).id, ids); changed++ }
                existing.trackIds != ids -> { lists.setTracks(existing.id, ids); changed++ }
            }
        }

        val have = graph.history.plays.value.mapTo(HashSet()) { LanSync.identity(it.artist, it.title) + "@" + it.at / 1000 }
        val newPlays = merged.plays.filter { it.identity + "@" + it.at / 1000 !in have }.map { p ->
            Play(resolve(p.identity)?.id ?: p.identity, p.title, p.artist, p.at, p.listenedMs)
        }
        graph.history.addAll(newPlays)
        graph.fillInServiceTracks()
        return SyncReport(added, removed, changed, newPlays.size)
    }
}
