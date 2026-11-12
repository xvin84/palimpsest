package dev.xvin.palimpsest.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.flow.first
import com.google.common.util.concurrent.ListenableFuture
import dev.xvin.palimpsest.MainActivity
import dev.xvin.palimpsest.PalimpsestApp
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.data.db.PlayEventEntity
import dev.xvin.palimpsest.data.settings.ReplayGainMode
import dev.xvin.palimpsest.data.settings.SavedQueue
import dev.xvin.palimpsest.widget.PlayerWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.xvin.palimpsest.i18n.tr

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private companion object {
        const val ROOT = "root"
    }

    private val graph by lazy { (application as PalimpsestApp).graph }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val history = HistoryTracker()
    private var skippedInARow = 0
    private var cameFromAutoTransition = false

    override fun onCreate() {
        super.onCreate()
        val fx = graph.effects
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(EffectsAudioProcessor(fx)))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                    .build()
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val local = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(graph.http))
        val dataSource = PalimpsestDataSource.Factory(local, graph.streamResolver, graph.streamCache)

        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(PlayerListener())
        // Errors the player gets over by itself never reach onPlayerError, but they're exactly what's
        // behind a song that plays in silence — keep them in the error log.
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioSinkError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioSinkError: Exception) {
                graph.errors.report(audioSinkError, player.currentMediaItem?.mediaMetadata?.title?.toString(), show = false)
            }
            override fun onAudioCodecError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioCodecError: Exception) {
                graph.errors.report(audioCodecError, player.currentMediaItem?.mediaMetadata?.title?.toString(), show = false)
            }
        })
        // A radio station's song changed: the item gets it as its title (the notification, the app), the stream plays on.
        scope.launch {
            InternetRadioItems.onAir.collect { pair ->
                val (url, song) = pair ?: return@collect
                val item = player.currentMediaItem ?: return@collect
                if (InternetRadioItems.streamOf(item.localConfiguration?.uri) != url || item.mediaMetadata.title?.toString() == song) return@collect
                val station = item.mediaMetadata.station ?: item.mediaMetadata.artist
                player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setTitle(song).setArtist(station).build()).build())
            }
        }

        val activity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setSessionActivity(activity)
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )

        // Keep the audio chain in sync with settings.
        scope.launch {
            // The EQ follows both the settings and the current output (each headphone can have its own profile).
            combine(graph.settings.flow, graph.outputs.current) { s, device -> s to device }.collectLatest { (s, device) ->
                fx.equalizer.setProfile(s.eqFor(device.key))
                fx.equalizer.enabled = s.eqEnabled
                applyReplayGain(player.currentMediaItem)
            }
        }
        // Smooth transitions: fade out the last seconds of a track and fade the next one in.
        scope.launch {
            while (isActive) {
                delay(80)
                val fadeMs = (graph.settings.state.value?.fadeSec ?: 0) * 1000L
                if (fadeMs <= 0 || !player.isPlaying) { if (player.volume != 1f && fadeMs <= 0) player.volume = 1f; continue }
                val dur = player.duration
                val pos = player.currentPosition
                if (dur <= 0 || dur < fadeMs * 3) { player.volume = 1f; continue }
                val remaining = dur - pos
                val fadingOut = remaining < fadeMs && player.hasNextMediaItem() && player.repeatMode != Player.REPEAT_MODE_ONE
                val fadingIn = pos < fadeMs && cameFromAutoTransition
                val v = when {
                    fadingOut -> (remaining.toFloat() / fadeMs)
                    fadingIn -> (pos.toFloat() / fadeMs)
                    else -> 1f
                }.coerceIn(0f, 1f)
                // Equal-power curve sounds smoother than a straight line.
                player.volume = kotlin.math.sin(v * Math.PI / 2).toFloat()
            }
        }
        watchSilence(fx)
        // Persist the queue now and then so a crash or reboot loses at most a few seconds.
        scope.launch {
            while (isActive) {
                delay(10_000)
                if (player.isPlaying) {
                    saveQueue()
                    history.checkpoint()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while paused stops the service; while playing, music keeps going.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            saveQueue()
            stopSelf()
        }
    }

    override fun onDestroy() {
        history.flush()
        saveQueue()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The bar moves but nothing is heard: seen on the wave, both online and off, and the song then
     * seemed to start half a minute in. Once a second this checks that sound really flows through the
     * chain while the player says it plays, that the volume and the handoff fade aren't stuck at zero,
     * and that the EQ didn't break — notes what it found in the error log (Settings → About → Errors)
     * and puts the sound back, from where it went quiet.
     */
    private fun watchSilence(fx: AudioEffects) = scope.launch {
        var lastFrames = fx.framesIn
        var stuck = 0
        var quietFrom = 0L
        var lowVolume = 0
        var fadedOut = 0
        var blowUps = fx.equalizer.blowUps
        while (isActive) {
            delay(1000)
            val playing = player.isPlaying
            val frames = fx.framesIn
            if (playing && fx.inChain && frames == lastFrames) {
                if (stuck++ == 0) quietFrom = (player.currentPosition - 1000).coerceAtLeast(0)
            } else stuck = 0
            lastFrames = frames
            if (stuck >= 3) {
                noteGlitch(R.string.glitch_silent_title, R.string.glitch_silent_text, fx, "quiet since ${quietFrom / 1000}s")
                stuck = 0
                player.seekTo(quietFrom)
            }
            // The fade between tracks sets the volume every 80 ms while it's on, so a near-zero one
            // outside the first and last seconds of a song means it's stuck.
            val fadeMs = (graph.settings.state.value?.fadeSec ?: 0) * 1000L
            val pos = player.currentPosition
            val edge = fadeMs > 0 && (pos < fadeMs + 1000 || player.duration - pos < fadeMs + 1000)
            lowVolume = if (playing && player.volume < 0.05f && !edge && graph.player.sleep.value.endsAt == null) lowVolume + 1 else 0
            if (lowVolume >= 3) {
                noteGlitch(R.string.glitch_quiet_title, R.string.glitch_quiet_text, fx, "player.volume=${player.volume}")
                player.volume = 1f
                lowVolume = 0
            }
            // The handoff fade-out pauses within a couple of seconds; playing on at zero is a leftover.
            fadedOut = if (playing && fx.fadeTarget < 0.5f) fadedOut + 1 else 0
            if (fadedOut >= 10) {
                noteGlitch(R.string.glitch_quiet_title, R.string.glitch_quiet_text, fx, "handoff fade=${fx.fadeTarget}")
                fx.fadeSet(1f)
                fadedOut = 0
            }
            if (fx.equalizer.blowUps != blowUps) {
                blowUps = fx.equalizer.blowUps
                noteGlitch(R.string.glitch_eq_title, R.string.glitch_eq_text, fx, "eq resets=$blowUps")
            }
        }
    }

    private fun noteGlitch(title: Int, text: Int, fx: AudioEffects, what: String) {
        val item = player.currentMediaItem
        val details = buildString {
            appendLine(what)
            appendLine("track: ${item?.mediaMetadata?.artist} — ${item?.mediaMetadata?.title} (${item?.mediaId})")
            appendLine("source: ${item?.localConfiguration?.uri?.scheme}")
            appendLine("at ${player.currentPosition / 1000}s of ${player.duration / 1000}s, item ${player.currentMediaItemIndex + 1}/${player.mediaItemCount}")
            appendLine("volume=${player.volume} fade=${fx.fadeTarget} eq=${fx.equalizer.enabled} frames=${fx.framesIn}")
            appendLine("format: ${player.audioFormat?.sampleMimeType} ${player.audioFormat?.sampleRate} Hz, pcm=${player.audioFormat?.pcmEncoding}")
            append("output: ${graph.outputs.current.value.key}")
        }
        android.util.Log.w("Palimpsest", "playback glitch: $details")
        graph.errors.note(dev.xvin.palimpsest.i18n.tr(title), dev.xvin.palimpsest.i18n.tr(text), details, dev.xvin.palimpsest.i18n.tr(R.string.my_wave).takeIf { graph.radio.station.value != null })
    }

    private fun applyReplayGain(item: MediaItem?) {
        val s = graph.settings.state.value ?: return
        val extras = item?.mediaMetadata?.extras
        val track = extras?.takeIf { it.containsKey(MediaItems.EXTRA_RG_TRACK) }?.getFloat(MediaItems.EXTRA_RG_TRACK)
        val album = extras?.takeIf { it.containsKey(MediaItems.EXTRA_RG_ALBUM) }?.getFloat(MediaItems.EXTRA_RG_ALBUM)
        val gain = when (s.replayGain) {
            ReplayGainMode.OFF -> null
            ReplayGainMode.TRACK -> track ?: album
            ReplayGainMode.ALBUM -> album ?: track
        }
        graph.effects.equalizer.setGainDb(if (gain == null) 0.0 else (gain + s.replayGainPreampDb).toDouble())
    }

    private fun updateWidget(item: MediaItem?) {
        val m = item?.mediaMetadata
        val playing = player.isPlaying
        graph.appScope.launch(Dispatchers.IO) {
            PlayerWidget.update(this@PlaybackService, m?.title?.toString(), m?.artist?.toString(), playing, m?.artworkUri, m?.artworkData)
        }
    }

    private fun saveQueue() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val ids = (0 until player.mediaItemCount).mapNotNull { MediaItems.trackId(player.getMediaItemAt(it)) }
        if (ids.size != player.mediaItemCount) return // queue contains external files; don't persist a broken copy
        val q = SavedQueue(ids, player.currentMediaItemIndex, player.currentPosition)
        graph.appScope.launch { graph.settings.saveQueue(q) }
    }

    /**
     * With no network a cloud track that isn't cached can't load; instead of stumbling on each one
     * (an error, a pause, a skip), jump straight to the next item that can play — in shuffle order
     * too. True if it moved on.
     */
    private fun skipUnplayableOffline(): Boolean {
        val item = player.currentMediaItem ?: return false
        if (playableNow(item) || graph.isOnline()) return false
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return false
        val repeat = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else player.repeatMode
        var idx = player.currentMediaItemIndex
        repeat(player.mediaItemCount) {
            idx = timeline.getNextWindowIndex(idx, repeat, player.shuffleModeEnabled)
            if (idx == C.INDEX_UNSET || idx == player.currentMediaItemIndex) return false
            if (playableNow(player.getMediaItemAt(idx))) {
                player.seekTo(idx, 0)
                return true
            }
        }
        return false
    }

    /** Local files, content:// and fully cached streams play without the network. */
    private fun playableNow(item: MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return true
        return uri.scheme != StreamResolver.SCHEME || graph.isFullyCached(uri.toString())
    }

    private inner class PlayerListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (skipUnplayableOffline()) return
            cameFromAutoTransition = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            updateWidget(mediaItem)
            history.flush()
            history.start(mediaItem, player.isPlaying)
            applyReplayGain(mediaItem)
            saveQueue()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // A cloud track that can't load (no network, region lock, expired link) shouldn't stop
            // the music: move on to the next item, which may well be a local file.
            val item = player.currentMediaItem ?: return
            // Said here, not through the controller: the skip below replaces the error before a
            // controller sees it, and the songs went by one after another without a word. The first
            // of a run shows; the rest only go to the log.
            val title = item.mediaMetadata.title?.toString()
            graph.errors.report(error, listOfNotNull(dev.xvin.palimpsest.i18n.tr(R.string.ctx_playback), title).joinToString(" · "), show = skippedInARow == 0)
            val isRemote = item.localConfiguration?.uri?.scheme == StreamResolver.SCHEME
            if (isRemote && player.hasNextMediaItem() && skippedInARow < 5) {
                skippedInARow++
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) skippedInARow = 0
            PlayerWidget.setPlaying(this@PlaybackService, isPlaying)
            history.setPlaying(isPlaying)
            if (!isPlaying) saveQueue()
        }
    }

    /**
     * Counts real listening time per item. A play is logged as soon as it becomes meaningful
     * (30 s or half the track) and then kept up to date, so a killed process loses nothing.
     */
    private inner class HistoryTracker {
        private var item: MediaItem? = null
        private var startedAt = 0L
        private var listened = 0L
        private var segmentStart = 0L
        @Volatile private var recordedId: Long? = null
        private var recording = false

        fun start(mediaItem: MediaItem?, playing: Boolean) {
            item = mediaItem
            startedAt = System.currentTimeMillis()
            listened = 0
            recordedId = null
            recording = false
            segmentStart = if (playing) System.currentTimeMillis() else 0
        }

        fun setPlaying(playing: Boolean) {
            if (item == null) start(player.currentMediaItem, playing)
            val now = System.currentTimeMillis()
            if (playing && segmentStart == 0L) segmentStart = now
            if (!playing && segmentStart != 0L) { listened += now - segmentStart; segmentStart = 0 }
            if (!playing) checkpoint()
        }

        private fun total(): Long = listened + if (segmentStart != 0L) System.currentTimeMillis() - segmentStart else 0

        private fun duration(it: MediaItem): Long = it.mediaMetadata.durationMs ?: player.duration.takeIf { d -> d > 0 } ?: 0L

        /** Inserts the play once it crosses the threshold, or refreshes its listened time. */
        fun checkpoint() {
            val it = item ?: return
            val ms = total()
            val duration = duration(it)
            val completed = duration > 0 && ms >= duration * 9 / 10
            val id = recordedId
            if (id != null) {
                graph.appScope.launch(Dispatchers.IO) { graph.db.plays().updateProgress(id, ms, completed) }
                return
            }
            val threshold = if (duration > 0) minOf(30_000L, duration / 2) else 30_000L
            if (ms < threshold || recording) return
            recording = true
            val meta = it.mediaMetadata
            val event = PlayEventEntity(
                trackId = MediaItems.trackId(it),
                title = meta.title?.toString() ?: "?",
                artist = meta.artist?.toString() ?: "?",
                album = meta.albumTitle?.toString(),
                startedAt = startedAt,
                listenedMs = ms,
                completed = completed,
            )
            graph.appScope.launch(Dispatchers.IO) {
                recordedId = graph.db.plays().insert(event)
                graph.scrobbler.flush()
            }
        }

        fun flush() {
            checkpoint()
            item = null
            listened = 0
            segmentStart = 0
        }
    }

    /**
     * Browsing for Android Auto and other media browsers: Любимые, Плейлисты, Часто слушаю,
     * Недавно добавленные — plus voice search over the library.
     */
    private inner class SessionCallback : MediaLibrarySession.Callback {
        private fun folder(id: String, title: String, playable: Boolean = false) = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setTitle(title)
                    .setIsBrowsable(true).setIsPlayable(playable)
                    .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build(),
            ).build()

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Palimpsest"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val db = graph.db
            val items: List<MediaItem> = when {
                parentId == ROOT -> listOf(
                    folder("liked", tr(R.string.favorites)), folder("playlists", tr(R.string.playlists)),
                    folder("most", tr(R.string.smart_most_played)), folder("recent", tr(R.string.smart_recently_added)),
                )
                parentId == "liked" -> db.tracks().observeLiked().first().map(MediaItems::from)
                parentId == "most" -> db.tracks().observeMostPlayed().first().map(MediaItems::from)
                parentId == "recent" -> db.tracks().observeRecentlyAdded().first().map(MediaItems::from)
                parentId == "playlists" -> db.playlists().observeAll().first().map { folder("pl:${it.id}", it.name) }
                parentId.startsWith("pl:") -> db.playlists().observeTracks(parentId.removePrefix("pl:").toLong()).first().map(MediaItems::from)
                else -> emptyList()
            }
            val from = (page * pageSize).coerceAtMost(items.size)
            LibraryResult.ofItemList(items.subList(from, (from + pageSize).coerceAtMost(items.size)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
            val t = mediaId.toLongOrNull()?.let { graph.db.tracks().get(it) }
            if (t != null) LibraryResult.ofItem(MediaItems.from(t), null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future(Dispatchers.IO) {
            session.notifySearchResultChanged(browser, query, graph.db.tracks().search(query).size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val all = graph.db.tracks().search(query).map(MediaItems::from)
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(all.subList(from, (from + pageSize).coerceAtMost(all.size)), params)
        }

        /** Controllers send bare ids; turn them into playable items. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future(Dispatchers.IO) {
            val ids = mediaItems.mapNotNull { it.mediaId.toLongOrNull() }
            val tracks = graph.db.tracks().getMany(ids).associateBy { it.id }
            mediaItems.mapNotNull { item ->
                val id = item.mediaId.toLongOrNull()
                when {
                    id != null -> tracks[id]?.let(MediaItems::from)
                    item.mediaId.startsWith(MediaItems.VERSION_PREFIX) -> {
                        val v = graph.db.versions().byId(item.mediaId.removePrefix(MediaItems.VERSION_PREFIX).toLong())
                        val t = v?.let { graph.db.tracks().get(it.trackId) }
                        if (v != null && t != null) MediaItems.fromVersion(t, v, tr(R.string.version_from_prefix) + java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(v.createdAt))) else null
                    }
                    item.mediaId.startsWith(MediaItems.NEW_PREFIX) ->
                        graph.db.tracks().get(item.mediaId.removePrefix(MediaItems.NEW_PREFIX).toLong())?.let(MediaItems::fromRemoteNew)
                    item.mediaId.startsWith(InternetRadioItems.PREFIX) -> InternetRadioItems.station(item.mediaId)?.let { runCatching { InternetRadioItems.item(it) }.getOrNull() }
                    item.mediaId.startsWith(MediaItems.LAN_PREFIX) -> LanItem.fromMediaId(item.mediaId)?.let(MediaItems::fromLan)
                    item.mediaId.startsWith(MediaItems.EXTERNAL_PREFIX) ->
                        MediaItems.fromExternal(this@PlaybackService, Uri.parse(item.mediaId.removePrefix(MediaItems.EXTERNAL_PREFIX)))
                    else -> null
                }
            }.toMutableList()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            val q = graph.settings.loadQueue()
            val tracks = q?.trackIds?.let { graph.db.tracks().getMany(it).associateBy { t -> t.id } }.orEmpty()
            val items = q?.trackIds?.mapNotNull { tracks[it]?.let(MediaItems::from) }.orEmpty()
            MediaSession.MediaItemsWithStartPosition(
                items,
                (q?.index ?: 0).coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                q?.positionMs ?: 0L,
            )
        }
    }
}
