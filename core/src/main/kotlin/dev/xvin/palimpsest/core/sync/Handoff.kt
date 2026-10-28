package dev.xvin.palimpsest.core.sync

import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.radio.SavedStation
import kotlinx.serialization.Serializable

/**
 * Moving what plays from one device to the other: the queue from the current track on, where in
 * it the music is, and the radio feeding it, if any. The receiver finds each song itself — the
 * same file by name, else the service track — and what it can't find it streams from the sender
 * by [HandoffTrack.fileKey] over the LAN ([LanTransport.openFile]).
 */
@Serializable
data class Handoff(
    val from: String,
    val queue: List<HandoffTrack>,
    val index: Int = 0,
    val positionMs: Long = 0,
    val playing: Boolean = true,
    val station: SavedStation? = null,
    /** Where the sender serves its files; 0 when it can't (the receiver then skips its file-only songs). */
    val port: Int = 0,
) {
    val current: HandoffTrack? get() = queue.getOrNull(index)
}

@Serializable
data class HandoffTrack(
    val identity: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0,
    val service: Service? = null,
    val remoteId: String? = null,
    val coverUrl: String? = null,
    /** A small JPEG of the cover, base64, when there's no URL for it (a file's own picture); only near the start of the queue. */
    val cover: String? = null,
    /** The sender has this song as a file: ask for it by this key. */
    val fileKey: String? = null,
    val ext: String? = null,
)

/** A phone telling the desktop where it listens, so the desktop can send music to it. */
@Serializable
data class Hello(val device: String, val port: Int)

/** «Give me what you're playing»: the answer is a [Handoff], and the asked device fades out. */
@Serializable
data class PullRequest(val device: String)

@Serializable
data class FileRequest(val key: String, val from: Long = 0)

@Serializable
data class FileReply(val length: Long, val ext: String)

object HandoffConst {
    /** The phone listens here while the app runs (the desktop is on [LanSync.PORT]). */
    const val PHONE_PORT = 47825
    /** How many tracks of the queue travel; a radio brings more on the other side anyway. */
    const val MAX_QUEUE = 200
    /** Covers inline for the first few tracks only, to keep the message small. */
    const val COVERS = 12
    const val FADE_OUT_MS = 700L
    const val FADE_IN_MS = 1_800L
}
