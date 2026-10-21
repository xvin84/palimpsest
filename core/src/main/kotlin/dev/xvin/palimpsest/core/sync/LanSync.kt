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
     * Three-way merge. Likes: what either side added since [base] is kept, what either removed
     * is removed. Playlists (by name): one side changed → that side wins; both → the phone's
     * order plus what only the desktop added; deleted on one side and untouched on the other →
     * deleted. History: everything from both, once.
     */
    fun merge(base: SyncState?, phone: SyncState, desktop: SyncState): SyncState {
        val b = base ?: SyncState()
        val likes = (phone.likes + desktop.likes).filterTo(LinkedHashSet()) { k ->
            (k in phone.likes && k in desktop.likes) || (k in phone.likes && k !in b.likes) || (k in desktop.likes && k !in b.likes)
        }

        val baseLists = b.playlists.associateBy { it.name.lowercase() }
        val pLists = phone.playlists.associateBy { it.name.lowercase() }
        val dLists = desktop.playlists.associateBy { it.name.lowercase() }
        val names = (pLists.keys + dLists.keys).distinct()
        val playlists = names.mapNotNull { n ->
            val p = pLists[n]; val d = dLists[n]; val o = baseLists[n]
            when {
                p != null && d != null -> when {
                    p.keys == d.keys -> p
                    o != null && p.keys == o.keys -> d
                    o != null && d.keys == o.keys -> p
                    else -> p.copy(keys = (p.keys + d.keys.filterNot { it in p.keys }))
                }
                p != null -> if (o != null && o.keys == p.keys) null else p // the desktop deleted it
                d != null -> if (o != null && o.keys == d.keys) null else d // the phone deleted it
                else -> null
            }
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
data class SyncPlaylist(val name: String, val keys: List<String>)

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
data class SyncReport(val likesAdded: Int = 0, val likesRemoved: Int = 0, val playlistsChanged: Int = 0, val playsAdded: Int = 0)

@Serializable
data class SyncResponse(val state: SyncState, val desktopReport: SyncReport)
