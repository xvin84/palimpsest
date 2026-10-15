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

/**
 * The UI's handle on the playback service. Wraps a MediaController and exposes plain state.
 */
class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Last session's queue, put back (paused) when the app starts with an idle player. */
    private val savedQueue: suspend () -> SavedQueue?,
) {
    private var controller: MediaController? = null
    private var connecting: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = _queue

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
            _state.value = _state.value.copy(error = error.cause?.message ?: error.message)
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
            nowPlaying = item?.let { toNowPlaying(it, p.duration) },
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

    private fun toNowPlaying(item: MediaItem, duration: Long): NowPlaying {
        val m: MediaMetadata = item.mediaMetadata
        return NowPlaying(
            mediaId = item.mediaId,
            trackId = item.mediaId.toLongOrNull(),
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

    fun currentPosition(): Long = controller?.currentPosition ?: 0L

    // --- Commands ---------------------------------------------------------------------------------

    private fun bare(id: String) = MediaItem.Builder().setMediaId(id).build()

    fun play(tracks: List<TrackEntity>, startIndex: Int = 0, shuffle: Boolean = false) = scope.launch {
        if (tracks.isEmpty()) return@launch
        val c = ctrl()
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) tracks.indices.random() else startIndex.coerceIn(0, tracks.lastIndex)
        c.setMediaItems(tracks.map { bare(it.id.toString()) }, start, 0)
        c.prepare()
        c.play()
    }

    fun playNext(tracks: List<TrackEntity>) = scope.launch {
        val c = ctrl()
        if (c.mediaItemCount == 0) { play(tracks); return@launch }
        c.addMediaItems(c.currentMediaItemIndex + 1, tracks.map { bare(it.id.toString()) })
    }

    fun enqueue(tracks: List<TrackEntity>) = scope.launch {
        val c = ctrl()
        if (c.mediaItemCount == 0) { play(tracks); return@launch }
        c.addMediaItems(tracks.map { bare(it.id.toString()) })
    }

    /** Plays a file handed to us by another app (file manager, Telegram, …). */
    fun playExternal(uri: Uri, libraryTrack: TrackEntity?) = scope.launch {
        val c = ctrl()
        val item = if (libraryTrack != null) bare(libraryTrack.id.toString()) else bare(MediaItems.EXTERNAL_PREFIX + uri)
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    /** Plays an arbitrary file (e.g. an archived track version) without touching the library. */
    fun playFile(uri: Uri) = playExternal(uri, null)

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
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

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
