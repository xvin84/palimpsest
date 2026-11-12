package dev.xvin.palimpsest.core.sync

import dev.xvin.palimpsest.core.library.ArtistNames
import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.core.model.Service
import kotlinx.serialization.Serializable

/**
 * Phone ↔ desktop sync over the local network: likes, playlists and listening history.
 *
 * The desktop serves `POST /sync` on [PORT] and announces itself by UDP broadcast on
 * [BEACON_PORT] («PALIMPSEST port name»). The first time, the phone sends the 6-digit code the
 * desktop shows and gets a token back. Each sync the phone sends its state and the state both
 * agreed on last time ([SyncRequest.base]); the desktop merges three ways, keeps the result and
 * sends it back for the phone to apply and keep as the next base.
 *
 * Tracks are matched by name ([identity]) — the same song is a different file, path and id on
 * each device; the service link travels along so a missing service track can be added.
 */
object LanSync {
    const val PORT = 47823
    const val BEACON_PORT = 47824
    const val BEACON_PREFIX = "PALIMPSEST"

    /** «artist|title», normalised: the first credited artist and the title without «(feat…)». */
    fun identity(artist: String, title: String): String =
        TrackMatcher.normalize(ArtistNames.split(artist).firstOrNull() ?: artist) + "|" + TrackMatcher.normalizeTitle(title)

    /**
     * What likes and playlists look like, in one string: a change since the last sync is a different
     * fingerprint, while a sync that only brought the other side's changes leaves it as agreed.
     */
    fun fingerprint(state: SyncState): String =
        state.likes.sorted().joinToString("\n") + "\u0000" +
            state.playlists.sortedBy { it.id ?: it.name.lowercase() }.joinToString("\n") { (it.id ?: "") + "/" + it.name + "/" + it.coverAt + ":" + it.keys.joinToString(",") }

    /**
     * Three-way merge against what both agreed on last time ([base]).
     *
     * Likes: what either side added since is kept, what either removed is removed.
     *
     * Playlists are matched by their shared [SyncPlaylist.id] (by name for those from before ids,
     * which then get one), so a renamed playlist stays the same playlist. Each part goes its own way:
     * - the name and the cover: the side that changed it since [base]; both — the later change;
     * - the songs: one side changed them — that side; both — the later side's order, without what
     *   the other side removed, with what the other side added at the end;
     * - deleted on one side and not touched on the other since — deleted; touched — kept.
     *
     * History: everything from both, once.
     */
    fun merge(base: SyncState?, phone: SyncState, desktop: SyncState): SyncState {
        val b = base ?: SyncState()
        val likes = (phone.likes + desktop.likes).filterTo(LinkedHashSet()) { k ->
            (k in phone.likes && k in desktop.likes) || (k in phone.likes && k !in b.likes) || (k in desktop.likes && k !in b.likes)
        }

        // One key per playlist across the three states: its id, or for an old one without an id the
        // id another state has under the same name, or the name.
        // The first sync after ids came: each side gave its old playlists ids of its own. Same name and
        // neither id agreed on before — the same playlist, under the phone's id.
        val agreed = b.playlists.mapNotNullTo(HashSet()) { it.id }
        val phoneIdByName = phone.playlists.filter { it.id != null && it.id !in agreed }.associate { it.name.lowercase() to it.id!! }
        val alias = desktop.playlists.filter { it.id != null && it.id !in agreed }
            .mapNotNull { d -> phoneIdByName[d.name.lowercase()]?.takeIf { it != d.id }?.let { d.id!! to it } }.toMap()
        // Ids by name (the phone's first), for states and playlists from before ids.
        val idByName = (b.playlists + desktop.playlists + phone.playlists).filter { it.id != null }.associate { it.name.lowercase() to (alias[it.id!!] ?: it.id) }
        fun keyOf(pl: SyncPlaylist) = pl.id?.let { alias[it] ?: it } ?: idByName[pl.name.lowercase()] ?: "name:" + pl.name.lowercase()
        val pLists = phone.playlists.associateBy(::keyOf)
        val dLists = desktop.playlists.associateBy(::keyOf)
        val oLists = b.playlists.associateBy(::keyOf)
        val playlists = (pLists.keys + dLists.keys).distinct().mapNotNull { k ->
            val p = pLists[k]; val d = dLists[k]; val o = oLists[k]
            val merged = when {
                p != null && d != null -> mergeList(o, p, d)
                p != null -> if (o != null && !p.changedSince(o)) null else p // deleted on the desktop
                d != null -> if (o != null && !d.changedSince(o)) null else d // deleted on the phone
                else -> null
            } ?: return@mapNotNull null
            merged.copy(id = k.takeUnless { it.startsWith("name:") } ?: merged.id ?: java.util.UUID.randomUUID().toString())
        }

        val plays = (phone.plays + desktop.plays).distinctBy { it.identity + "@" + it.at / 1000 }.sortedBy { it.at }
        val tracks = (phone.tracks + desktop.tracks).distinctBy { it.identity }
        val used = likes + playlists.flatMap { it.keys }
        return SyncState(likes, playlists, plays, tracks.filter { it.identity in used })
    }
}

/** A song as both sides can find it: by name, or by its service link. */
@Serializable
data class SyncTrack(
    val identity: String,
    val title: String,
    val artist: String,
    val durationMs: Long = 0,
    val service: Service? = null,
    val remoteId: String? = null,
)

@Serializable
data class SyncPlay(val identity: String, val title: String, val artist: String, val at: Long, val listenedMs: Long)

@Serializable
data class SyncPlaylist(
    val name: String,
    val keys: List<String>,
    /** The same on both devices; a rename keeps it. Missing on states from before ids. */
    val id: String? = null,
    /** When the name, the songs and the cover were last changed — the later change wins a conflict. */
    val nameAt: Long = 0,
    val tracksAt: Long = 0,
    val coverAt: Long = 0,
    /** The playlist's own picture as a small base64 JPEG; null — a collage of its songs. */
    val cover: String? = null,
)

private fun SyncPlaylist.changedSince(o: SyncPlaylist) = name != o.name || keys != o.keys || coverAt != o.coverAt

private fun mergeList(o: SyncPlaylist?, p: SyncPlaylist, d: SyncPlaylist): SyncPlaylist {
    fun <T> pick(pv: T, dv: T, ov: T?, pAt: Long, dAt: Long): T = when {
        pv == dv -> pv
        ov != null && pv == ov -> dv
        ov != null && dv == ov -> pv
        else -> if (dAt > pAt) dv else pv
    }
    val name = pick(p.name, d.name, o?.name, p.nameAt, d.nameAt)
    val nameAt = if (name == p.name && name != d.name) p.nameAt else if (name == d.name && name != p.name) d.nameAt else maxOf(p.nameAt, d.nameAt)
    val keys = when {
        p.keys == d.keys -> p.keys
        o != null && p.keys == o.keys -> d.keys
        o != null && d.keys == o.keys -> p.keys
        else -> {
            // Both changed: the later side's order, minus what the other removed, plus what it added.
            val (later, other) = if (d.tracksAt > p.tracksAt) d to p else p to d
            val before = o?.keys.orEmpty().toSet()
            val removed = before - other.keys.toSet()
            val added = other.keys.filter { it !in before }
            val out = later.keys.filter { it !in removed }.toMutableList()
            added.forEach { if (it !in out) out += it }
            out
        }
    }
    // The cover travels with its own time: the later picture wins.
    val coverSide = if (d.coverAt > p.coverAt) d else p
    return SyncPlaylist(
        name = name, keys = keys, id = p.id ?: d.id ?: o?.id,
        nameAt = nameAt, tracksAt = maxOf(p.tracksAt, d.tracksAt),
        coverAt = coverSide.coverAt, cover = coverSide.cover,
    )
}

/** Likes and playlist items are [SyncTrack.identity] keys; [tracks] describes the ones in use. */
@Serializable
data class SyncState(
    val likes: Set<String> = emptySet(),
    val playlists: List<SyncPlaylist> = emptyList(),
    val plays: List<SyncPlay> = emptyList(),
    val tracks: List<SyncTrack> = emptyList(),
)

@Serializable
data class SyncRequest(val device: String, val state: SyncState, val base: SyncState? = null)

@Serializable
data class PairRequest(val device: String, val code: String)

@Serializable
data class PairResponse(val token: String, val name: String)

/** What a sync changed, for the «Synced: …» line on both sides. */
@Serializable
/**
 * [notHere]: liked or playlist songs this side has neither as a file nor as a service track, so they
 * can't show here; [notThere]: the same on the other side (filled in by the phone).
 */
data class SyncReport(val likesAdded: Int = 0, val likesRemoved: Int = 0, val playlistsChanged: Int = 0, val playsAdded: Int = 0, val notHere: Int = 0, val notThere: Int = 0)

@Serializable
data class SyncResponse(val state: SyncState, val desktopReport: SyncReport)
