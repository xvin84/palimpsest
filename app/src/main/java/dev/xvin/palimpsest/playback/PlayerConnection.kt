package dev.xvin.palimpsest.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.settings.SavedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NowPlaying(
    val mediaId: String,
    val trackId: Long?,
    /** Set when playing a specific version («Версия от …», «Новая версия · Яндекс»). */
    val variant: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUri: Uri?,
    val artworkData: ByteArray?,
    val durationMs: Long,
)

data class PlayerUiState(
    val nowPlaying: NowPlaying? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val index: Int = 0,
    val queueSize: Int = 0,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val error: String? = null,
)

data class SleepTimer(val endsAt: Long?, val endOfTrack: Boolean = false)

/** A/B listening: the library file (A) against one archived version (B) of the same track. */
data class AbCompare(val trackId: Long, val versionId: Long, val versionLabel: String)

/**
 * The UI's handle on the playback service. Wraps a MediaController and exposes plain state.
 */
class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Last session's queue, put back (paused) when the app starts with an idle player. */
    private val savedQueue: suspend () -> SavedQueue?,
    /** Whether a track can play with no network (a local file or a fully cached stream). */
    private val offlinePlayable: (TrackEntity) -> Boolean = { true },
) {
    private var controller: MediaController? = null
    private var connecting: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = _queue

    /** Called when the user replaces the queue (plays something new) — e.g. to end a radio station. */
    var onQueueReplaced: (() -> Unit)? = null

    /** False while a radio feeds the queue: it never loops, it brings new tracks instead. */
    var repeatAllAllowed: () -> Boolean = { true }

    /** Playback failures, for the error journal and the explained error sheet. */
    var onError: ((Throwable) -> Unit)? = null

    private val _ab = MutableStateFlow<AbCompare?>(null)
    val ab: StateFlow<AbCompare?> = _ab

    private val _sleep = MutableStateFlow(SleepTimer(null))
    val sleep: StateFlow<SleepTimer> = _sleep
    private var sleepJob: Job? = null

    fun connect() {
        if (controller != null || connecting?.isActive == true) return
        connecting = scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val c = MediaController.Builder(context, token).buildAsync().await()
            controller = c
            c.addListener(listener)
            refresh(c)
            refreshQueue(c)
            if (c.mediaItemCount == 0) {
                savedQueue()?.takeIf { it.trackIds.isNotEmpty() }?.let { q ->
                    c.setMediaItems(q.trackIds.map { bare(it.toString()) }, q.index.coerceIn(0, q.trackIds.lastIndex), q.positionMs)
                    c.prepare()
                }
            }
        }
    }

    private suspend fun ctrl(): MediaController {
        controller?.let { return it }
        connect()
        connecting?.join()
        return controller!!
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player)
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) refreshQueue(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            onError?.invoke(error)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (_sleep.value.endOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                cancelSleep()
            }
        }
    }

    private fun refresh(p: Player) {
        val item = p.currentMediaItem
        _state.value = PlayerUiState(
            nowPlaying = item?.let { toNowPlaying(it, p.duration, p.mediaMetadata) },
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            repeatMode = p.repeatMode,
            shuffle = p.shuffleModeEnabled,
            index = p.currentMediaItemIndex,
            queueSize = p.mediaItemCount,
            speed = p.playbackParameters.speed,
            pitch = p.playbackParameters.pitch,
            error = if (p.playerError != null) _state.value.error else null,
        )
    }

    private fun refreshQueue(p: Player) {
        _queue.value = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
    }

    private fun toNowPlaying(item: MediaItem, duration: Long, live: MediaMetadata? = null): NowPlaying {
        // A radio station's item says only the station; what's on air comes in the stream (ICY), merged into the player's.
        val m: MediaMetadata = if (item.mediaId.startsWith(InternetRadioItems.PREFIX) && live != null) live else item.mediaMetadata
        return NowPlaying(
            mediaId = item.mediaId,
            trackId = MediaItems.trackId(item),
            variant = m.extras?.getString(MediaItems.EXTRA_VARIANT),
            title = m.title?.toString() ?: "—",
            artist = m.artist?.toString() ?: "",
            album = m.albumTitle?.toString(),
            artworkUri = m.artworkUri,
            artworkData = m.artworkData,
            durationMs = if (duration > 0) duration else m.durationMs ?: 0,
        )
    }

    /** Current position, ticking while someone collects it. */
    fun positionFlow(intervalMs: Long = 250): Flow<Pair<Long, Long>> = flow {
        while (currentCoroutineContext().isActive) {
            val c = controller
            if (c != null) emit(c.currentPosition to c.bufferedPosition)
            delay(intervalMs)
        }
    }

    private fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    fun currentPosition(): Long = controller?.currentPosition ?: 0L

    // --- Commands ---------------------------------------------------------------------------------

    private fun bare(id: String) = MediaItem.Builder().setMediaId(id).build()

    fun play(tracks: List<TrackEntity>, startIndex: Int = 0, shuffle: Boolean = false): Job {
        onQueueReplaced?.invoke()
        return playInternal(tracks, startIndex, shuffle)
    }

    private fun playInternal(all: List<TrackEntity>, startIndex: Int, shuffle: Boolean) = scope.launch {
        if (all.isEmpty()) return@launch
        val c = ctrl()
        // With no network, cloud tracks that aren't cached can't load: leave them out of the queue
        // (the one the user tapped stays — they asked for it). If nothing would be left, keep them all.
        val picked = all.getOrNull(startIndex).takeIf { !shuffle }
        val tracks = if (isOnline()) all else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            all.filter { it === picked || offlinePlayable(it) }.ifEmpty { all }
        }
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) tracks.indices.random() else tracks.indexOf(picked).takeIf { it >= 0 } ?: startIndex.coerceIn(0, tracks.lastIndex)
        c.setMediaItems(tracks.map { bare(it.id.toString()) }, start, 0)
        c.prepare()
        c.play()
    }

    fun playNext(tracks: List<TrackEntity>) = scope.launch {
        val c = ctrl()
        if (c.mediaItemCount == 0) { playInternal(tracks, 0, false); return@launch }
        c.addMediaItems(c.currentMediaItemIndex + 1, tracks.map { bare(it.id.toString()) })
    }

    fun enqueue(tracks: List<TrackEntity>) = scope.launch {
        val c = ctrl()
        if (c.mediaItemCount == 0) { playInternal(tracks, 0, false); return@launch }
        val first = c.mediaItemCount
        c.addMediaItems(tracks.map { bare(it.id.toString()) })
        // The queue ran out (or its last track failed) before these arrived: carry on with them.
        if (c.playbackState == Player.STATE_ENDED || (c.playerError != null && c.currentMediaItemIndex == first - 1)) {
            c.seekTo(first, 0)
            c.prepare()
            c.play()
        }
    }

    /** Swaps everything after the current track for [tracks] and, with [playNow], jumps to them. */
    fun replaceUpcoming(tracks: List<TrackEntity>, playNow: Boolean) = scope.launch {
        val c = ctrl()
        if (c.mediaItemCount == 0) { playInternal(tracks, 0, false); return@launch }
        val at = c.currentMediaItemIndex + 1
        if (at < c.mediaItemCount) c.removeMediaItems(at, c.mediaItemCount)
        c.addMediaItems(tracks.map { bare(it.id.toString()) })
        if (playNow) {
            c.seekTo(at, 0)
            c.prepare()
            c.play()
        }
    }

    private var fadeJob: Job? = null

    /**
     * Plays what the computer handed over ([ids] are media ids: library tracks or songs streamed
     * from the computer) from the same second, the volume rising from silence once it sounds.
     */
    fun playHandoff(ids: List<String>, index: Int, positionMs: Long, playing: Boolean): Job {
        onQueueReplaced?.invoke()
        return scope.launch {
            val c = ctrl()
            fadeJob?.cancel()
            c.shuffleModeEnabled = false
            c.volume = if (playing) 0f else 1f
            c.setMediaItems(ids.map(::bare), index.coerceIn(0, ids.lastIndex), positionMs)
            c.prepare()
            if (!playing) return@launch
            c.play()
            fadeJob = scope.launch {
                // A stream takes a moment to start: rise from when it actually sounds.
                kotlinx.coroutines.withTimeoutOrNull(20_000) { while (!c.isPlaying) delay(40) }
                val steps = 36
                for (i in 1..steps) {
                    // Ease in: loudness is logarithmic, a straight ramp sounds like a jump at the start.
                    val x = i.toFloat() / steps
                    c.volume = x * x
                    delay(dev.xvin.palimpsest.core.sync.HandoffConst.FADE_IN_MS / steps)
                }
                c.volume = 1f
            }
        }
    }

    /** The computer took over: the music here fades out and pauses where it was. */
    suspend fun fadeOutAndPause() {
        val c = ctrl()
        fadeJob?.cancel()
        if (c.isPlaying) {
            val from = c.volume
            val steps = 20
            for (i in 1..steps) {
                c.volume = from * (1f - i.toFloat() / steps)
                delay(dev.xvin.palimpsest.core.sync.HandoffConst.FADE_OUT_MS / steps)
            }
            c.pause()
        }
        c.volume = 1f
    }

    /** Media ids of the queue from the current item on, and where in the current one it is. */
    suspend fun upcoming(max: Int): Pair<List<String>, Long> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
        // The computer asks from a socket thread; the controller answers only on the main one.
        val c = ctrl()
        val from = c.currentMediaItemIndex.coerceAtLeast(0)
        val ids = (from until minOf(c.mediaItemCount, from + max)).map { c.getMediaItemAt(it).mediaId }
        ids to c.currentPosition
    }

    /** Plays an internet radio station (it replaces the queue, like playing anything else). */
    fun playStation(station: dev.xvin.palimpsest.core.radio.RadioStation): Job {
        onQueueReplaced?.invoke()
        return scope.launch {
            val c = ctrl()
            fadeJob?.cancel()
            c.volume = 1f
            c.shuffleModeEnabled = false
            c.setMediaItem(bare(InternetRadioItems.mediaId(station)))
            c.prepare()
            c.play()
        }
    }

    /** Plays a file handed to us by another app (file manager, Telegram, …). */
    fun playExternal(uri: Uri, libraryTrack: TrackEntity?): Job {
        onQueueReplaced?.invoke()
        return playExternalInternal(uri, libraryTrack)
    }

    private fun playExternalInternal(uri: Uri, libraryTrack: TrackEntity?) = scope.launch {
        val c = ctrl()
        val item = if (libraryTrack != null) bare(libraryTrack.id.toString()) else bare(MediaItems.EXTERNAL_PREFIX + uri)
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    /** Plays an arbitrary file without touching the library. */
    fun playFile(uri: Uri) = playExternal(uri, null)

    /** Plays a specific version right now, keeping the rest of the queue. */
    fun playVersion(versionId: Long) = insertAndPlay(bare(MediaItems.VERSION_PREFIX + versionId))

    /** Streams the service's newer audio of [trackId] to compare, keeping the queue. */
    fun playNewVersion(trackId: Long) = insertAndPlay(bare(MediaItems.NEW_PREFIX + trackId))

    /** Starts A/B: plays the current file (A); [toggleAb] swaps to the version (B) at the same moment. */
    fun startAb(trackId: Long, versionId: Long, label: String) {
        _ab.value = AbCompare(trackId, versionId, label)
        insertAndPlay(bare(trackId.toString()))
    }

    /** True when B (the archived version) is the one playing. */
    fun abPlayingB(): Boolean = controller?.currentMediaItem?.mediaId?.startsWith(MediaItems.VERSION_PREFIX) == true

    fun toggleAb() = scope.launch {
        val ab = _ab.value ?: return@launch
        val c = ctrl()
        val idx = c.currentMediaItemIndex
        val pos = c.currentPosition
        val toB = !abPlayingB()
        val item = bare(if (toB) MediaItems.VERSION_PREFIX + ab.versionId else ab.trackId.toString())
        c.replaceMediaItem(idx, item)
        c.seekTo(idx, pos)
        c.play()
    }

    fun stopAb() { _ab.value = null }

    /** Back from a version to the track's library file, at the same second (after «same song?»). */
    fun backToTrack(trackId: Long) = scope.launch {
        _ab.value = null
        val c = ctrl()
        val idx = c.currentMediaItemIndex
        if (c.currentMediaItem?.mediaId == trackId.toString()) return@launch
        val pos = c.currentPosition
        c.replaceMediaItem(idx, bare(trackId.toString()))
        c.seekTo(idx, pos)
        c.play()
    }

    private fun insertAndPlay(item: MediaItem) = scope.launch {
        val c = ctrl()
        val at = if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1
        c.addMediaItem(at, item)
        c.seekTo(at, 0)
        c.prepare()
        c.play()
    }

    fun toggle() = scope.launch {
        val c = ctrl()
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = scope.launch { ctrl().seekToNextMediaItem() }
    fun previous() = scope.launch { ctrl().seekToPrevious() }
    fun seekTo(ms: Long) = scope.launch { ctrl().seekTo(ms) }
    fun skipTo(index: Int) = scope.launch { ctrl().seekTo(index, 0) }
    fun remove(index: Int) = scope.launch { ctrl().removeMediaItem(index) }
    fun move(from: Int, to: Int) = scope.launch { ctrl().moveMediaItem(from, to) }
    fun clearQueue() = scope.launch { ctrl().clearMediaItems() }

    fun cycleRepeat() = scope.launch {
        val c = ctrl()
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> if (repeatAllAllowed()) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setRepeat(mode: Int) = scope.launch { ctrl().repeatMode = mode }

    fun toggleShuffle() = scope.launch { ctrl().let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }

    fun setSpeed(speed: Float, pitch: Float = _state.value.pitch) = scope.launch {
        ctrl().playbackParameters = PlaybackParameters(speed, pitch)
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    // --- Sleep timer ------------------------------------------------------------------------------

    fun sleepIn(minutes: Int) {
        sleepJob?.cancel()
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _sleep.value = SleepTimer(endsAt)
        sleepJob = scope.launch {
            delay((endsAt - System.currentTimeMillis() - FADE_MS).coerceAtLeast(0))
            val c = ctrl()
            // Fade out instead of cutting off mid-phrase.
            val startVol = c.volume
            val steps = 40
            repeat(steps) { i ->
                c.volume = startVol * (1f - (i + 1f) / steps)
                delay(FADE_MS / steps)
            }
            c.pause()
            c.volume = startVol
            _sleep.value = SleepTimer(null)
        }
    }

    fun sleepAtEndOfTrack() {
        sleepJob?.cancel()
        _sleep.value = SleepTimer(null, endOfTrack = true)
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        _sleep.value = SleepTimer(null)
    }

    companion object {
        private const val FADE_MS = 10_000L
    }
}
