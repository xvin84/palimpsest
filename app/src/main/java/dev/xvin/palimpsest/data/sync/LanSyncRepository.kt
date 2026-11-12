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

    /** Likes and playlists as they were right after the last sync (null: not synced in this run). */
    @Volatile private var agreed: String? = null

    /**
     * Syncs when likes or playlists changed here since the last sync — called a few seconds after a
     * change and whenever the app comes to the front, so a like reaches the computer in moments,
     * not at the next three-hourly round. Quiet when the computer isn't around.
     */
    suspend fun syncIfChanged(): Result<SyncReport>? {
        if (_pairing.value == null) return null
        val now = withContext(Dispatchers.IO) { LanSync.fingerprint(state()) }
        if (now == agreed) return null
        return sync()
    }

    private suspend fun syncWith(p: LanPairing): SyncReport {
        val reply = LanTransport.call(p.host, p.port, LanMessage(token = p.token, sync = SyncRequest(device, state(), p.base)))
        val resp = reply.sync ?: error(reply.error ?: "no answer")
        val mine = apply(resp.state)
        // What changed here and there, together.
        val d = resp.desktopReport
        val report = SyncReport(mine.likesAdded + d.likesAdded, mine.likesRemoved + d.likesRemoved, mine.playlistsChanged + d.playlistsChanged, mine.playsAdded + d.playsAdded, notHere = mine.notHere, notThere = d.notHere)
        save(p.copy(base = resp.state, lastSync = System.currentTimeMillis(), lastReport = report))
        agreed = LanSync.fingerprint(state())
        return report
    }

    private fun identity(t: TrackEntity) = LanSync.identity(t.artist, t.title)

    private fun syncTrack(t: TrackEntity) = SyncTrack(identity(t), t.title, t.artist, t.durationMs, t.service.takeIf { t.remoteId != null }, t.remoteId)

    private suspend fun state(): SyncState {
        val all = db.tracks().all()
        val byId = all.associateBy { it.id }
        val liked = all.filter { it.liked }
        // Every own playlist gets its shared id the first time it's synced.
        val lists = db.playlists().own().map { pl ->
            val sid = pl.syncId ?: java.util.UUID.randomUUID().toString().also { db.playlists().setSyncId(pl.id, it) }
            pl.copy(syncId = sid) to db.playlists().trackIds(pl.id).mapNotNull { byId[it] }
        }
        val plays = db.plays().all().map { SyncPlay(LanSync.identity(it.artist, it.title), it.title, it.artist, it.startedAt, it.listenedMs) }
        return SyncState(
            likes = liked.mapTo(LinkedHashSet(), ::identity),
            playlists = lists.map { (pl, ts) -> SyncPlaylist(pl.name, ts.map(::identity), pl.syncId, pl.nameAt, pl.tracksAt, pl.coverAt, coverOf(pl)) },
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
        // Matched by the shared id; a playlist from before ids, by its name.
        // (A computer on an older version sends no ids at all: then by name too.)
        // An id the merge didn't keep (the other side's id won for the same playlist): by name.
        val mergedIds = merged.playlists.mapNotNullTo(HashSet()) { it.id }
        fun matches(pl: PlaylistEntity, mp: SyncPlaylist) =
            if (pl.syncId != null && mp.id != null && pl.syncId in mergedIds) pl.syncId == mp.id else pl.name.equals(mp.name, ignoreCase = true)
        own.filter { pl -> merged.playlists.none { matches(pl, it) } }.forEach { lists.delete(it.id); lists.clear(it.id); changed++ }
        merged.playlists.forEach { mp ->
            val ids = mp.keys.mapNotNull { resolve(it)?.id }
            val existing = own.firstOrNull { matches(it, mp) }
            val id = existing?.id ?: lists.insert(PlaylistEntity(name = mp.name, syncId = mp.id)).also { changed++ }
            if (existing == null || lists.trackIds(id) != ids) { lists.replace(id, ids); if (existing != null) changed++ }
            // The picture comes along only when the other side's is newer.
            val cover = if (existing == null || mp.coverAt > existing.coverAt) mp.cover?.let { writeCover(id, it) } else existing.coverUrl
            if (existing != null && (existing.name != mp.name || cover != existing.coverUrl)) changed++
            lists.applySynced(id, mp.name, mp.id, mp.nameAt, mp.tracksAt, mp.coverAt, cover)
        }

        val have = db.plays().all().mapTo(HashSet()) { LanSync.identity(it.artist, it.title) + "@" + it.startedAt / 1000 }
        val newPlays = merged.plays.filter { it.identity + "@" + it.at / 1000 !in have }.map { pl ->
            // Scrobbled already where they were heard.
            PlayEventEntity(trackId = index[pl.identity]?.id, title = pl.title, artist = pl.artist, album = null, startedAt = pl.at, listenedMs = pl.listenedMs, completed = true, scrobbled = true)
        }
        if (newPlays.isNotEmpty()) db.plays().insertAll(newPlays)
        val missing = (merged.likes + merged.playlists.flatMap { it.keys }).distinct().count { resolve(it) == null }
        return SyncReport(added, removed, changed, newPlays.size, notHere = missing)
    }

    /** A playlist's own picture as a small JPEG for the computer (encoded once per change). */
    private val coverCache = HashMap<String, String?>()
    private fun coverOf(pl: PlaylistEntity): String? {
        val url = pl.coverUrl?.takeIf { it.startsWith("file:") } ?: return null
        return coverCache.getOrPut(url) {
            runCatching {
                val src = android.graphics.BitmapFactory.decodeFile(android.net.Uri.parse(url).path) ?: return@runCatching null
                val side = 400
                val scaled = if (src.width > side) android.graphics.Bitmap.createScaledBitmap(src, side, side * src.height / src.width, true) else src
                val out = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
                android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            }.getOrNull()
        }
    }

    /** The other side's picture, kept the way covers chosen here are (a new name each time). */
    private fun writeCover(id: Long, base64: String): String? = runCatching {
        val dir = File(context.filesDir, "playlist_covers").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("${id}_") }?.forEach { it.delete() }
        val f = File(dir, "${id}_${System.currentTimeMillis()}.jpg")
        f.writeBytes(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
        android.net.Uri.fromFile(f).toString()
    }.getOrNull()
}
