package dev.xvin.palimpsest.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import dev.xvin.palimpsest.core.lyrics.Lrc
import dev.xvin.palimpsest.core.lyrics.ParsedLyrics
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.settings.VisualizerMode
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.Artwork
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.SeekBar
import dev.xvin.palimpsest.ui.components.Visualizer
import dev.xvin.palimpsest.ui.components.WaveSeekBar
import dev.xvin.palimpsest.ui.components.formatDuration
import dev.xvin.palimpsest.ui.components.techLine
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import kotlinx.coroutines.launch
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.i18n.label

@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    when (dev.xvin.palimpsest.ui.theme.LocalUiStyle.current) {
        dev.xvin.palimpsest.data.settings.UiStyle.LANE -> { LaneNowPlaying(onClose); return }
        dev.xvin.palimpsest.data.settings.UiStyle.GLASS -> { GlassNowPlaying(onClose); return }
        dev.xvin.palimpsest.data.settings.UiStyle.VINYL -> { VinylNowPlaying(onClose); return }
        dev.xvin.palimpsest.data.settings.UiStyle.MONO -> { MonoNowPlaying(onClose); return }
        dev.xvin.palimpsest.data.settings.UiStyle.NEON -> { NeonNowPlaying(onClose); return }
        dev.xvin.palimpsest.data.settings.UiStyle.PALIMPSEST -> Unit
    }
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val state by graph.player.state.collectAsStateWithLifecycle()
    val settings by graph.settings.flow.collectAsStateWithLifecycle(null)
    val np = state.nowPlaying ?: return
    val track by remember(np.trackId) { graph.db.tracks().observe(np.trackId ?: -1) }.collectAsStateWithLifecycle(null)
    val position by remember { graph.player.positionFlow() }.collectAsStateWithLifecycle(0L to 0L)
    val accent = LocalAccent.current
    val vizMode = settings?.visualizer ?: VisualizerMode.BARS

    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val dragOffset = remember { androidx.compose.animation.core.Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    var heightPx by remember { mutableFloatStateOf(1f) }
    var artistMenu by remember { mutableStateOf(false) }
    val artists by remember(np.trackId) { graph.db.artists().observeForTrack(np.trackId ?: -1) }.collectAsStateWithLifecycle(emptyList())
    val station by graph.radio.station.collectAsStateWithLifecycle()
    val artModel: Any? = np.artworkUri ?: np.artworkData

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
            // Measured before the transform, so the finger's speed is real even while the card moves.
            .pointerInput(Unit) {
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = {
                        val velocity = tracker.calculateVelocity().y
                        dragScope.launch {
                            if (dragOffset.value > heightPx * 0.25f || velocity > 1800f) {
                                dragOffset.animateTo(heightPx, tween(220, easing = androidx.compose.animation.core.FastOutLinearInEasing))
                                onClose()
                            } else {
                                dragOffset.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f))
                            }
                        }
                    },
                    onDragCancel = { dragScope.launch { dragOffset.animateTo(0f) } },
                ) { change, dy ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    dragScope.launch { dragOffset.snapTo((dragOffset.value + dy).coerceAtLeast(0f)) }
                }
            }
            .graphicsLayer {
                // Follow the finger, shrink a touch and round the corners — like a card being put away.
                val p = (dragOffset.value / heightPx).coerceIn(0f, 1f)
                translationY = dragOffset.value
                val scale = 1f - p * 0.07f
                scaleX = scale; scaleY = scale
                shape = RoundedCornerShape((p * 120).coerceAtMost(28f).dp)
                clip = p > 0f
            }
            .background(Ink.Paper),
    ) {
        // Blurred cover as ambient backdrop.
        if (artModel != null) {
            AsyncImage(
                artModel, null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(80.dp).graphicsLayer { alpha = 0.55f },
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Ink.Paper.copy(alpha = 0.35f), 0.55f to Ink.Paper.copy(alpha = 0.75f), 1f to Ink.Paper),
            ),
        )

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, tr(R.string.collapse), Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(station?.title?.uppercase() ?: tr(R.string.now_playing_caps), style = MaterialTheme.typography.labelSmall, color = if (station != null) accent else Ink.Muted, maxLines = 1)
                    Text(
                        track?.let { techLine(it) } ?: tr(R.string.external_file),
                        style = MaterialTheme.typography.labelSmall, color = Ink.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, tr(R.string.more)) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(tr(R.string.sleep_timer)) }, leadingIcon = { Icon(Icons.Rounded.Bedtime, null) }, onClick = { menu = false; showSleep = true })
                        DropdownMenuItem(text = { Text(tr(R.string.speed_pitch)) }, leadingIcon = { Icon(Icons.Rounded.Speed, null) }, onClick = { menu = false; showSpeed = true })
                        DropdownMenuItem(text = { Text(tr(R.string.equalizer)) }, leadingIcon = { Icon(Icons.Rounded.Equalizer, null) }, onClick = { menu = false; nav.openEqualizer() })
                        DropdownMenuItem(text = { Text(tr(R.string.share_card_action)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { menu = false; showShare = true })
                        track?.let { t ->
                            DropdownMenuItem(text = { Text(tr(R.string.track_actions)) }, onClick = { menu = false; nav.showActions(t) })
                        }
                    }
                }
            }

            // Cover / lyrics area takes the remaining space.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedContent(showLyrics, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "art-lyrics") { lyrics ->
                    if (lyrics) {
                        LyricsPane(track, position.first, onSeek = { graph.player.seekTo(it) })
                    } else {
                        val side = minOf(maxWidth, maxHeight - 8.dp)
                        Box(Modifier.size(side), contentAlignment = Alignment.Center) {
                            if (vizMode == VisualizerMode.RING) {
                                Visualizer(graph.effects, vizMode, accent, state.isPlaying, Modifier.fillMaxSize())
                            }
                            val coverScale by animateFloatAsState(if (state.isPlaying) 1f else 0.9f, label = "cover-scale")
                            val inner = if (vizMode == VisualizerMode.RING) 0.7f else 1f
                            var swipe by remember { mutableFloatStateOf(0f) }
                            Box(
                                Modifier
                                    .fillMaxSize(inner)
                                    .graphicsLayer { scaleX = coverScale; scaleY = coverScale; translationX = swipe * 0.4f; alpha = 1f - (kotlin.math.abs(swipe) / 900f).coerceAtMost(0.5f) }
                                    .pointerInput(Unit) {
                                        // Swipe the cover sideways to change track.
                                        detectHorizontalDragGestures(
                                            onDragEnd = {
                                                if (swipe < -160f) graph.player.next() else if (swipe > 160f) graph.player.previous()
                                                swipe = 0f
                                            },
                                            onDragCancel = { swipe = 0f },
                                        ) { _, dx -> swipe += dx }
                                    }
                                    .shadow(24.dp, RoundedCornerShape(if (vizMode == VisualizerMode.RING) 1000.dp else 16.dp))
                                    .clickable { showLyrics = true },
                            ) {
                                // The cover slides out in the direction you moved through the queue.
                                AnimatedContent(
                                    targetState = np.mediaId to state.index,
                                    transitionSpec = {
                                        val forward = targetState.second >= initialState.second
                                        (androidx.compose.animation.slideInHorizontally(tween(320)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(260)) +
                                            androidx.compose.animation.scaleIn(tween(320), initialScale = 0.92f)) togetherWith
                                            (androidx.compose.animation.slideOutHorizontally(tween(320)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(200)))
                                    },
                                    label = "cover",
                                ) { _ ->
                                    Artwork(artModel, np.album ?: np.title, Modifier.fillMaxSize(), corner = if (vizMode == VisualizerMode.RING) 1000.dp else 16.dp)
                                }
                            }
                        }
                    }
                }
            }

            if (vizMode == VisualizerMode.BARS || vizMode == VisualizerMode.MIRROR) {
                Visualizer(graph.effects, vizMode, accent.copy(alpha = 0.85f), state.isPlaying, Modifier.fillMaxWidth().height(44.dp).padding(vertical = 4.dp))
            } else Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(np.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Box {
                        Text(
                            listOfNotNull(np.artist.ifBlank { null }, np.album).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = artists.isNotEmpty()) {
                                if (artists.size == 1) nav.openArtist(artists[0].name, artists[0].remoteId) else artistMenu = true
                            },
                        )
                        // Several artists: let the user pick whose page to open.
                        DropdownMenu(artistMenu, onDismissRequest = { artistMenu = false }) {
                            artists.forEach { a ->
                                DropdownMenuItem(text = { Text(a.name) }, onClick = { artistMenu = false; nav.openArtist(a.name, a.remoteId) })
                            }
                        }
                    }
                }
                track?.let { t ->
                    IconButton(onClick = {
                        scope.launch { graph.setLiked(t, !t.liked).onFailure { nav.toast(tr(R.string.like_not_saved_yandex, it.message)) } }
                    }) {
                        Icon(if (t.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, tr(R.string.favorite), tint = if (t.liked) accent else Ink.Muted)
                    }
                }
            }
            val ab by graph.player.ab.collectAsStateWithLifecycle()
            if (ab != null && ab!!.trackId == np.trackId) AbSwitch(ab!!, np.mediaId) else VersionChip(np.variant, track, np.mediaId)
            Spacer(Modifier.height(8.dp))
            val wave by produceState<FloatArray?>(null, track?.id, track?.path, track?.modifiedAt, np.variant) {
                value = null
                val t = track
                // The waveform belongs to the library file; another version gets the plain bar.
                if (t?.path != null && np.variant == null) value = graph.waveform.get(t.id, java.io.File(t.path))
            }
            val w = wave
            if (w != null) WaveSeekBar(w, position.first, np.durationMs, onSeek = { graph.player.seekTo(it) })
            else SeekBar(position.first, position.second, np.durationMs, onSeek = { graph.player.seekTo(it) })

            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { graph.player.toggleShuffle() }) {
                    Icon(Icons.Rounded.Shuffle, tr(R.string.shuffle), tint = if (state.shuffle) accent else Ink.Muted)
                }
                IconButton(onClick = { graph.player.previous() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, tr(R.string.back), Modifier.size(36.dp)) }
                val playInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val pressed by playInteraction.collectIsPressedAsState()
                val playScale by animateFloatAsState(
                    if (pressed) 0.88f else 1f,
                    androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 600f), label = "play-press",
                )
                Box(
                    Modifier.size(76.dp).graphicsLayer { scaleX = playScale; scaleY = playScale }.clip(CircleShape).background(accent)
                        .clickable(interactionSource = playInteraction, indication = androidx.compose.material3.ripple()) { graph.player.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = if (state.isBuffering) 2 else if (state.isPlaying) 1 else 0,
                        transitionSpec = {
                            (fadeIn(tween(160)) + androidx.compose.animation.scaleIn(tween(200), initialScale = 0.6f)) togetherWith
                                (fadeOut(tween(120)) + androidx.compose.animation.scaleOut(tween(160), targetScale = 0.6f))
                        },
                        label = "play-icon",
                    ) { mode ->
                        when (mode) {
                            2 -> CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                            1 -> Icon(Icons.Rounded.Pause, tr(R.string.pause), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp))
                            else -> Icon(Icons.Rounded.PlayArrow, tr(R.string.play), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp))
                        }
                    }
                }
                IconButton(onClick = { graph.player.next() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, tr(R.string.next), Modifier.size(36.dp)) }
                IconButton(onClick = { graph.player.cycleRepeat() }) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        tr(R.string.repeat), tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) accent else Ink.Muted,
                    )
                }
            }

            val sleep by graph.player.sleep.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                IconButton(onClick = { showLyrics = !showLyrics }) { Icon(Icons.Rounded.Lyrics, tr(R.string.lyrics), tint = if (showLyrics) accent else Ink.Muted) }
                IconButton(onClick = {
                    scope.launch { graph.settings.setVisualizer(VisualizerMode.entries[(vizMode.ordinal + 1) % VisualizerMode.entries.size]) }
                    nav.toast(tr(R.string.visualizer_mode, VisualizerMode.entries[(vizMode.ordinal + 1) % VisualizerMode.entries.size].label))
                }) { Icon(Icons.Rounded.GraphicEq, tr(R.string.visualizer), tint = if (vizMode != VisualizerMode.OFF) accent else Ink.Muted) }
                IconButton(onClick = { showSleep = true }) {
                    Icon(Icons.Rounded.Bedtime, tr(R.string.sleep_timer), tint = if (sleep.endsAt != null || sleep.endOfTrack) accent else Ink.Muted)
                }
                IconButton(onClick = { nav.openEqualizer() }) { Icon(Icons.Rounded.Equalizer, tr(R.string.equalizer), tint = if (settings?.eqEnabled == true) accent else Ink.Muted) }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, tr(R.string.queue), tint = Ink.Muted) }
            }
        }
    }

    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
    if (showSleep) SleepDialog(onDismiss = { showSleep = false })
    if (showSpeed) SpeedDialog(onDismiss = { showSpeed = false })
    if (showShare) dev.xvin.palimpsest.ui.components.ShareCardDialog(np, onDismiss = { showShare = false })
}

@Composable
internal fun LyricsPane(track: TrackEntity?, positionMs: Long, onSeek: (Long) -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var loaded by remember(track?.id) { mutableStateOf<dev.xvin.palimpsest.core.model.Lyrics?>(null) }
    val lyrics by produceState<ParsedLyrics?>(null, track?.id, track?.modifiedAt, reload) {
        value = null
        val l = track?.let { graph.lyrics.local(it) }
        loaded = l
        value = l?.let { Lrc.parse(it.synced ?: it.plain.orEmpty()) } ?: ParsedLyrics.EMPTY
    }
    val parsed = lyrics
    // Timed lyrics are the point: with none, or only plain text, look online once per track —
    // quietly, unless the user picked this text by hand.
    var autoSearched by remember(track?.id) { mutableStateOf(false) }
    LaunchedEffect(parsed, track?.id) {
        val t = track ?: return@LaunchedEffect
        if (parsed == null || autoSearched || parsed.isSynced || graph.lyrics.isPinned(t.id)) return@LaunchedEffect
        autoSearched = true
        searching = true
        val found = if (parsed.lines.isEmpty()) graph.lyrics.fetchOnline(t) else graph.lyrics.findSynced(t)
        searching = false
        if (found != null) reload++
    }
    var chooseSource by remember { mutableStateOf(false) }
    if (chooseSource && track != null) {
        LyricsSourcesSheet(track, loaded, onDismiss = { chooseSource = false }, onPicked = { chooseSource = false; reload++ })
    }
    // Overlay controls (translation, source) fade away after a few seconds; any touch brings them back.
    var touches by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    val sourceChip: @Composable () -> Unit = {
        loaded?.let { l ->
            Text(
                tr(R.string.lyrics_source_chip, l.source) + " ▾",
                style = MaterialTheme.typography.labelMedium,
                color = Ink.Muted,
                modifier = Modifier.padding(top = 6.dp).shadow(6.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50))
                    .background(Ink.Paper2.copy(alpha = 0.96f)).clickable { touches++; chooseSource = true }.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
    val touchCatcher = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            touches++
        }
    }
    when {
        parsed == null -> CircularProgressIndicator()
        parsed.lines.isEmpty() && searching -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(tr(R.string.lyrics_auto_searching), color = Ink.Muted)
        }
        parsed.lines.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr(R.string.lyrics_none_yet), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            if (track != null) {
                OutlinedButton(enabled = !searching, onClick = {
                    searching = true
                    scope.launch {
                        val found = graph.lyrics.fetchOnline(track)
                        searching = false
                        if (found != null) reload++ else nav.toast(tr(R.string.lyrics_not_found))
                    }
                }) { Text(if (searching) tr(R.string.searching) else tr(R.string.lyrics_find_online_sources)) }
            }
        }
        !parsed.isSynced -> Box(Modifier.fillMaxSize()) {
            LaunchedEffect(touches) { controlsVisible = true; kotlinx.coroutines.delay(3000); controlsVisible = false }
            LazyColumn(Modifier.fillMaxSize().then(touchCatcher), contentPadding = PaddingValues(top = 48.dp, bottom = 24.dp)) {
                itemsIndexed(parsed.lines) { _, l ->
                    Text(l.text, style = MaterialTheme.typography.titleMedium, color = Ink.Text, modifier = Modifier.padding(vertical = 3.dp))
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = controlsVisible || searching,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = androidx.compose.animation.fadeIn(tween(180)),
                exit = androidx.compose.animation.fadeOut(tween(400)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    sourceChip()
                    if (searching) Text(tr(R.string.lyrics_auto_searching), style = MaterialTheme.typography.labelMedium, color = Ink.Muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        else -> {
            val current = parsed.indexAt(positionMs)
            val listState = rememberLazyListState()
            // The mode is a setting, so it carries over from track to track until changed.
            val appSettings by graph.settings.flow.collectAsStateWithLifecycle(null)
            val mode = appSettings?.lyricsTranslation ?: dev.xvin.palimpsest.data.settings.LyricsTranslation.OFF
            val lyricsKey = track?.id
            var translation by remember(lyricsKey) { mutableStateOf<List<String>?>(null) }
            // Why there's no translation for this track, shown under the switch instead of a pop-up.
            var status by remember(lyricsKey) { mutableStateOf<TranslationStatus?>(null) }
            LaunchedEffect(mode, lyricsKey) {
                if (mode == dev.xvin.palimpsest.data.settings.LyricsTranslation.OFF || translation != null || status is TranslationStatus.Final) return@LaunchedEffect
                status = TranslationStatus.Working
                runCatching { graph.lyrics.translate(track?.id ?: 0L, parsed.lines.map { it.text }) }
                    .onSuccess { status = if (it == null) TranslationStatus.SameLanguage else null; translation = it }
                    .onFailure { e -> status = graph.errors.report(e, tr(R.string.ctx_lyrics_translate), show = false)?.let(TranslationStatus::Failed) }
            }
            val translateOn = mode != dev.xvin.palimpsest.data.settings.LyricsTranslation.OFF && translation != null
            val only = translateOn && mode == dev.xvin.palimpsest.data.settings.LyricsTranslation.ONLY
            LaunchedEffect(current) {
                if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
            }
            LaunchedEffect(touches, status) {
                controlsVisible = true
                if (status != TranslationStatus.Working) { kotlinx.coroutines.delay(3000); controlsVisible = false }
            }
            Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().then(touchCatcher),
                state = listState, contentPadding = PaddingValues(top = 56.dp, bottom = 24.dp),
            ) {
                itemsIndexed(parsed.lines) { i, l ->
                    val active = i == current
                    // Smooth karaoke: the active line grows and lights up instead of jumping.
                    val color by androidx.compose.animation.animateColorAsState(
                        when {
                            active -> accent
                            i < current -> Ink.Faint
                            else -> Ink.Text.copy(alpha = 0.7f)
                        },
                        tween(350), label = "lyric-color",
                    )
                    val scale by animateFloatAsState(if (active) 1.08f else 1f, tween(350), label = "lyric-scale")
                    val shown = if (only) translation?.getOrNull(i)?.takeIf { it.isNotBlank() && l.text.isNotBlank() } ?: l.text else l.text
                    Text(
                        shown.ifBlank { "♪" },
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold),
                        color = color,
                        modifier = Modifier.fillMaxWidth(0.92f).clickable { onSeek(l.timeMs) }.padding(top = 6.dp, bottom = if (translateOn && !only) 0.dp else 6.dp)
                            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) },
                    )
                    val tr = translation?.getOrNull(i)
                    if (translateOn && !only && !tr.isNullOrBlank() && l.text.isNotBlank()) {
                        Text(tr, style = MaterialTheme.typography.bodyMedium, color = if (i == current) accent.copy(alpha = 0.8f) else Ink.Muted, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                enter = androidx.compose.animation.fadeIn(tween(180)) + androidx.compose.animation.slideInVertically(tween(180)) { -it / 3 },
                exit = androidx.compose.animation.fadeOut(tween(400)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TranslationSwitch(mode, status, accent, onMode = { m -> touches++; graph.appScope.launch { graph.settings.setLyricsTranslation(m) } }, onDetails = { info -> nav.showError(info) })
                    sourceChip()
                }
            }
            }
        }
    }
}

/** «Источник текста»: every lyrics variant found locally and online; the picked one sticks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsSourcesSheet(track: TrackEntity, current: dev.xvin.palimpsest.core.model.Lyrics?, onDismiss: () -> Unit, onPicked: () -> Unit) {
    val graph = LocalGraph.current
    val accent = LocalAccent.current
    val options by produceState<List<dev.xvin.palimpsest.core.model.Lyrics>?>(null, track.id) { value = graph.lyrics.candidates(track) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.Paper2) {
        Text(tr(R.string.lyrics_sources_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
        val list = options
        when {
            list == null -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(tr(R.string.lyrics_sources_loading), color = Ink.Muted)
            }
            list.isEmpty() -> Text(tr(R.string.lyrics_not_found), color = Ink.Muted, modifier = Modifier.padding(20.dp))
            else -> LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(list) { _, l ->
                    val text = (l.synced ?: l.plain).orEmpty()
                    val isCurrent = current != null && (current.synced ?: current.plain).orEmpty().trim() == text.trim()
                    val preview = Lrc.parse(text).lines.map { it.text }.filter { it.isNotBlank() }.take(2).joinToString(" / ")
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            graph.appScope.launch { graph.lyrics.choose(track, l); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onPicked() } }
                        }.padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(l.source, style = MaterialTheme.typography.titleMedium, color = if (isCurrent) accent else Ink.Text)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                tr(if (l.synced != null) R.string.lyrics_synced_badge else R.string.lyrics_plain_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (l.synced != null) accent else Ink.Faint,
                            )
                            if (isCurrent) { Spacer(Modifier.weight(1f)); Text("✓", color = accent) }
                        }
                        Text(preview, style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Where a translation request stands; [Final] ones aren't retried for the same track. */
private sealed interface TranslationStatus {
    sealed interface Final : TranslationStatus
    data object Working : TranslationStatus
    data object SameLanguage : Final
    data class Failed(val info: dev.xvin.palimpsest.errors.ErrorInfo) : Final
}

/** One compact segmented control: Original · Both · Translation, with a status line under it. */
@Composable
private fun TranslationSwitch(
    mode: dev.xvin.palimpsest.data.settings.LyricsTranslation,
    status: TranslationStatus?,
    accent: androidx.compose.ui.graphics.Color,
    onMode: (dev.xvin.palimpsest.data.settings.LyricsTranslation) -> Unit,
    onDetails: (dev.xvin.palimpsest.errors.ErrorInfo) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.shadow(10.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(Ink.Paper2.copy(alpha = 0.96f)).padding(3.dp),
        ) {
            listOf(
                dev.xvin.palimpsest.data.settings.LyricsTranslation.OFF to R.string.lyrics_mode_original,
                dev.xvin.palimpsest.data.settings.LyricsTranslation.BELOW to R.string.lyrics_mode_both,
                dev.xvin.palimpsest.data.settings.LyricsTranslation.ONLY to R.string.translation,
            ).forEach { (m, label) ->
                val selected = mode == m
                val bg by androidx.compose.animation.animateColorAsState(if (selected) accent else androidx.compose.ui.graphics.Color.Transparent, tween(200), label = "seg")
                Text(
                    tr(label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else Ink.Muted,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).clickable { onMode(m) }.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        if (mode != dev.xvin.palimpsest.data.settings.LyricsTranslation.OFF) {
            val text = when (status) {
                TranslationStatus.Working -> tr(R.string.translating)
                TranslationStatus.SameLanguage -> tr(R.string.lyrics_already_native)
                is TranslationStatus.Failed -> tr(R.string.translation_failed)
                null -> null
            }
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status is TranslationStatus.Failed) MaterialTheme.colorScheme.error else Ink.Muted,
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).background(Ink.Paper2.copy(alpha = 0.9f))
                        .then(if (status is TranslationStatus.Failed) Modifier.clickable { onDetails(status.info) } else Modifier)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val queue by graph.player.queue.collectAsStateWithLifecycle()
    val state by graph.player.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.index - 1).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.Paper2) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.queue), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Text("${state.index + 1} / ${queue.size}", color = Ink.Muted, style = MaterialTheme.typography.labelMedium)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(480.dp)) {
            itemsIndexed(queue, key = { i, it -> "${it.mediaId}#$i" }) { i, item ->
                val current = i == state.index
                Row(
                    Modifier.fillMaxWidth().clickable { graph.player.skipTo(i) }.padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(item.mediaMetadata.artworkUri ?: item.mediaMetadata.artworkData, item.mediaMetadata.title?.toString() ?: "", Modifier.size(42.dp), corner = 6.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.mediaMetadata.title?.toString() ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (current) LocalAccent.current else Ink.Text,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(item.mediaMetadata.artist?.toString().orEmpty(), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                    }
                    Text(formatDuration(item.mediaMetadata.durationMs ?: 0), style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
                    if (!current) IconButton(onClick = { graph.player.remove(i) }) { Icon(Icons.Rounded.Close, tr(R.string.remove), tint = Ink.Faint) }
                    else Spacer(Modifier.width(48.dp))
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
internal fun SleepDialog(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val sleep by graph.player.sleep.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Paper2,
        title = { Text(tr(R.string.sleep_timer)) },
        text = {
            Column {
                if (sleep.endsAt != null) {
                    val left = ((sleep.endsAt!! - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                    Text(tr(R.string.sleep_in_minutes, left), color = Ink.Muted)
                    Spacer(Modifier.height(8.dp))
                }
                if (sleep.endOfTrack) { Text(tr(R.string.sleep_end_of_track_set), color = Ink.Muted); Spacer(Modifier.height(8.dp)) }
                listOf(10, 15, 30, 45, 60, 90).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { m ->
                            OutlinedButton(
                                onClick = { graph.player.sleepIn(m); onDismiss() },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            ) { Text(tr(R.string.duration_minutes, m), maxLines = 1, softWrap = false) }
                        }
                    }
                }
                TextButton(onClick = { graph.player.sleepAtEndOfTrack(); onDismiss() }) { Text(tr(R.string.sleep_end_of_track)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.close)) } },
        dismissButton = {
            if (sleep.endsAt != null || sleep.endOfTrack) TextButton(onClick = { graph.player.cancelSleep(); onDismiss() }) { Text(tr(R.string.turn_off)) }
        },
    )
}

@Composable
internal fun SpeedDialog(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    var speed by remember { mutableFloatStateOf(state.speed) }
    var keepPitch by remember { mutableStateOf(state.pitch == 1f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Paper2,
        title = { Text(tr(R.string.speed_value, "%.2f".format(speed))) },
        text = {
            Column {
                Slider(speed, { speed = (it * 20).toInt() / 20f; graph.player.setSpeed(speed, if (keepPitch) 1f else speed) }, valueRange = 0.5f..2f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.keep_pitch))
                        Text(tr(R.string.keep_pitch_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                    }
                    Switch(keepPitch, { keepPitch = it; graph.player.setSpeed(speed, if (it) 1f else speed) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.8f, 1f, 1.25f, 1.5f).forEach { s ->
                        TextButton(onClick = { speed = s; graph.player.setSpeed(s, if (keepPitch) 1f else s) }) { Text("$s×") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.done)) } },
    )
}

/** Compact player above the bottom navigation. */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val np = state.nowPlaying ?: return
    val position by remember { graph.player.positionFlow(500) }.collectAsStateWithLifecycle(0L to 0L)
    val accent = LocalAccent.current
    var swipe by remember { mutableFloatStateOf(0f) }
    val lane = dev.xvin.palimpsest.ui.theme.LocalUiStyle.current == dev.xvin.palimpsest.data.settings.UiStyle.LANE
    // Lane: a floating card in the cover's colour instead of a strip.
    val tint = dev.xvin.palimpsest.ui.theme.LocalCoverTint.current
    val cardModifier = if (lane) Modifier.padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
        .background(androidx.compose.ui.graphics.lerp(Ink.Paper2, tint, if (Ink.isLight) 0.18f else 0.38f)) else Modifier.background(Ink.Paper2)
    Column(
        Modifier.fillMaxWidth().then(cardModifier).clickable(onClick = onOpen)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (swipe < -120f) graph.player.next() else if (swipe > 120f) graph.player.previous()
                        swipe = 0f
                    },
                    onDragCancel = { swipe = 0f },
                ) { _, dx -> swipe += dx }
            }
            .graphicsLayer { translationX = swipe * 0.3f },
    ) {
        val frac = if (np.durationMs > 0) (position.first.toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f
        if (!lane) Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.Line)) {
            Box(Modifier.fillMaxWidth(frac).height(2.dp).background(accent))
        }
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(np.artworkUri ?: np.artworkData, np.album ?: np.title, Modifier.size(42.dp).aspectRatio(1f), corner = 6.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                androidx.compose.animation.Crossfade(np.title, label = "mini-title") { title ->
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(np.artist, style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { graph.player.toggle() }) {
                AnimatedContent(state.isPlaying, transitionSpec = { (fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.6f)) togetherWith fadeOut() }, label = "mini-play") { playing ->
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, tr(R.string.play_pause), Modifier.size(30.dp))
                }
            }
            IconButton(onClick = { graph.player.next() }) { Icon(Icons.Rounded.SkipNext, tr(R.string.next_track), tint = Ink.Muted) }
        }
        if (lane) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(2.dp).clip(CircleShape).background(Ink.Text.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(frac).height(2.dp).background(Ink.Text))
        }
        if (lane) Spacer(Modifier.height(6.dp))
    }
}

/**
 * Says which audio is playing when a track has several versions, and offers the other one:
 * "saved version — newer on Yandex: listen / download", or "this is the new/archived version".
 */
@Composable
internal fun VersionChip(variant: String?, track: TrackEntity?, mediaId: String) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    val t = track ?: return
    val text: String
    val actions = mutableListOf<Pair<String, () -> Unit>>()
    when {
        variant != null -> {
            text = if (mediaId.startsWith(dev.xvin.palimpsest.playback.MediaItems.NEW_PREFIX)) tr(R.string.variant_playing_new) else tr(R.string.variant_playing, variant.lowercase())
            if (mediaId.startsWith(dev.xvin.palimpsest.playback.MediaItems.NEW_PREFIX)) {
                actions += tr(R.string.download) to { scope.launch { graph.downloads.enqueue(listOf(t.id)) }; nav.toast(tr(R.string.downloading_keep_old)) }
            }
            actions += tr(R.string.all_versions) to { nav.openTrack(t.id, 2) }
        }
        t.updateAvailable && t.path != null -> {
            text = tr(R.string.variant_saved_new_on, t.service.label) +
                tr(R.string.duration_instead, formatDuration(t.remoteDurationMs ?: 0), formatDuration(t.durationMs))
            actions += tr(R.string.listen_new) to { graph.player.playNewVersion(t.id) }
            actions += tr(R.string.download) to { scope.launch { graph.downloads.enqueue(listOf(t.id)) }; nav.toast(tr(R.string.downloading_keep_old)) }
        }
        else -> return
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = Ink.Text)
        Row {
            actions.forEach { (label, action) ->
                TextButton(onClick = action, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(label, color = accent) }
            }
        }
    }
}

/** Big A | B switch: same moment of the song, other version. */
@Composable
internal fun AbSwitch(ab: dev.xvin.palimpsest.playback.AbCompare, mediaId: String) {
    val graph = LocalGraph.current
    val accent = LocalAccent.current
    val isB = mediaId.startsWith(dev.xvin.palimpsest.playback.MediaItems.VERSION_PREFIX)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Paper3)) {
            listOf(false to tr(R.string.ab_current), true to "B · ${ab.versionLabel.lowercase()}").forEach { (b, label) ->
                val selected = b == isB
                val bg by androidx.compose.animation.animateColorAsState(if (selected) accent else Ink.Paper3, tween(200), label = "ab")
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(bg)
                        .clickable(enabled = !selected) { graph.player.toggleAb() }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (selected) MaterialTheme.colorScheme.onPrimary else Ink.Text, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        TextButton(onClick = { graph.player.stopAb() }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(tr(R.string.ab_finish), color = Ink.Muted, style = MaterialTheme.typography.labelMedium)
        }
    }
}
