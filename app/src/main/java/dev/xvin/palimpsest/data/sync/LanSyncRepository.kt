package dev.xvin.palimpsest.data.sync

import android.content.Context
import android.net.wifi.WifiManager
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.core.sync.LanMessage
import dev.xvin.palimpsest.core.sync.LanPeer
import dev.xvin.palimpsest.core.sync.LanSync
import dev.xvin.palimpsest.core.sync.LanTransport
import dev.xvin.palimpsest.core.sync.PairRequest
import dev.xvin.palimpsest.core.sync.SyncPlay
import dev.xvin.palimpsest.core.sync.SyncPlaylist
import dev.xvin.palimpsest.core.sync.SyncReport
import dev.xvin.palimpsest.core.sync.SyncRequest
import dev.xvin.palimpsest.core.sync.SyncState
import dev.xvin.palimpsest.core.sync.SyncTrack
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.db.PlayEventEntity
import dev.xvin.palimpsest.data.db.PlaylistEntity
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.remote.RemoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** The computer this phone is paired with, and what they agreed on last time. */
@Serializable
data class LanPairing(
    val host: String,
    val port: Int,
    val name: String,
    val token: String,
    val base: SyncState? = null,
    val lastSync: Long = 0,
    val lastReport: SyncReport? = null,
)

/**
 * The phone end of [LanSync]: finds the desktop by its beacon, pairs with its code and syncs
 * likes, own playlists and history. Kept in a private file, not the settings: the base can be big.
 */
class LanSyncRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val remote: RemoteRepository,
) {
    private val file = File(context.filesDir, "lan-sync.json")
    private val _pairing = MutableStateFlow(load())
    val pairing: StateFlow<LanPairing?> = _pairing
    private val mutex = Mutex()
    val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()

    private fun load(): LanPairing? = runCatching { Http.json.decodeFromString(LanPairing.serializer(), file.readText()) }.getOrNull()

    private fun save(p: LanPairing?) {
        _pairing.value = p
        if (p == null) { file.delete(); return }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Http.json.encodeToString(LanPairing.serializer(), p))
        tmp.renameTo(file)
    }

    /** Desktops announcing themselves on this network right now. */
    suspend fun discover(): List<LanPeer> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("palimpsest-sync")?.apply { setReferenceCounted(false); acquire() }
        try { LanTransport.discover(3_000) } finally { lock?.release() }
    }

    suspend fun pair(host: String, port: Int, code: String): Result<LanPairing> = withContext(Dispatchers.IO) {
        runCatching {
            val r = LanTransport.call(host, port, LanMessage(pair = PairRequest(device, code.trim())), 15_000)
            val p = r.pair ?: error(r.error ?: "no answer")
            LanPairing(host, port, p.name, p.token).also(::save)
        }
    }

    fun unpair() = save(null)

    /** A call to the paired computer; if it moved to another address, finds it again by name. */
    suspend fun <T> withDesktop(block: (LanPairing) -> T): T = withContext(Dispatchers.IO) {
        val p = _pairing.value ?: throw IllegalStateException("not paired")
        runCatching { block(p) }.recoverCatching { e ->
            if (e !is java.io.IOException) throw e
            val moved = discover().firstOrNull { it.name == p.name && it.host != p.host } ?: throw e
            block(p.copy(host = moved.host, port = moved.port).also(::save))
        }.getOrThrow()
    }

    /** One sync; if the computer moved to another address, finds it again by name. */
    suspend fun sync(): Result<SyncReport> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val p = _pairing.value ?: return@withContext Result.failure(IllegalStateException("not paired"))
            runCatching { syncWith(p) }.recoverCatching { e ->
                val moved = discover().firstOrNull { it.name == p.name && it.host != p.host } ?: throw e
                syncWith(p.copy(host = moved.host, port = moved.port).also(::save))
            }
        }
    }

    private suspend fun syncWith(p: LanPairing): SyncReport {
        val reply = LanTransport.call(p.host, p.port, LanMessage(token = p.token, sync = SyncRequest(device, state(), p.base)))
        val resp = reply.sync ?: error(reply.error ?: "no answer")
        val mine = apply(resp.state)
        // What changed here and there, together.
        val d = resp.desktopReport
        val report = SyncReport(mine.likesAdded + d.likesAdded, mine.likesRemoved + d.likesRemoved, mine.playlistsChanged + d.playlistsChanged, mine.playsAdded + d.playsAdded)
        save(p.copy(base = resp.state, lastSync = System.currentTimeMillis(), lastReport = report))
        return report
    }

    private fun identity(t: TrackEntity) = LanSync.identity(t.artist, t.title)

    private fun syncTrack(t: TrackEntity) = SyncTrack(identity(t), t.title, t.artist, t.durationMs, t.service.takeIf { t.remoteId != null }, t.remoteId)

    private suspend fun state(): SyncState {
        val all = db.tracks().all()
        val byId = all.associateBy { it.id }
        val liked = all.filter { it.liked }
        val lists = db.playlists().own().map { pl -> pl to db.playlists().trackIds(pl.id).mapNotNull { byId[it] } }
        val plays = db.plays().all().map { SyncPlay(LanSync.identity(it.artist, it.title), it.title, it.artist, it.startedAt, it.listenedMs) }
        return SyncState(
            likes = liked.mapTo(LinkedHashSet(), ::identity),
            playlists = lists.map { (pl, ts) -> SyncPlaylist(pl.name, ts.map(::identity)) },
            plays = plays,
            tracks = (liked + lists.flatMap { it.second }).distinctBy { it.id }.map(::syncTrack),
        )
    }

    private suspend fun apply(merged: SyncState): SyncReport {
        val dao = db.tracks()
        val all = dao.all()
        // A song may be a file and a service track at once: prefer the file.
        val index = all.sortedBy { if (it.path != null) 0 else 1 }.groupBy(::identity).mapValues { it.value.first() }
        val described = merged.tracks.associateBy { it.identity }
        suspend fun resolve(key: String): TrackEntity? = index[key] ?: described[key]?.let { d ->
            val service = d.service ?: return@let null
            val remoteId = d.remoteId ?: return@let null
            runCatching { remote.ensureTrack(RemoteTrack(service = service, id = remoteId, title = d.title, artists = listOf(d.artist), durationMs = d.durationMs)) }.getOrNull()
        }

        var removed = 0
        all.filter { it.liked && identity(it) !in merged.likes }.forEach { dao.setLiked(it.id, false); removed++ }
        var added = 0
        merged.likes.forEach { k -> val t = resolve(k) ?: return@forEach; if (!(dao.get(t.id)?.liked ?: false)) { dao.setLiked(t.id, true); added++ } }

        var changed = 0
        val lists = db.playlists()
        val own = lists.own()
        val wanted = merged.playlists.associateBy { it.name.lowercase() }
        own.filter { it.name.lowercase() !in wanted }.forEach { lists.delete(it.id); changed++ }
        merged.playlists.forEach { mp ->
            val ids = mp.keys.mapNotNull { resolve(it)?.id }
            val existing = own.firstOrNull { it.name.equals(mp.name, ignoreCase = true) }
            when {
                existing == null -> { lists.replace(lists.insert(PlaylistEntity(name = mp.name)), ids); changed++ }
                lists.trackIds(existing.id) != ids -> { lists.replace(existing.id, ids); changed++ }
            }
        }

        val have = db.plays().all().mapTo(HashSet()) { LanSync.identity(it.artist, it.title) + "@" + it.startedAt / 1000 }
        val newPlays = merged.plays.filter { it.identity + "@" + it.at / 1000 !in have }.map { pl ->
            // Scrobbled already where they were heard.
            PlayEventEntity(trackId = index[pl.identity]?.id, title = pl.title, artist = pl.artist, album = null, startedAt = pl.at, listenedMs = pl.listenedMs, completed = true, scrobbled = true)
        }
        if (newPlays.isNotEmpty()) db.plays().insertAll(newPlays)
        return SyncReport(added, removed, changed, newPlays.size)
    }
}
