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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import dev.xvin.palimpsest.ui.components.formatDuration
import dev.xvin.palimpsest.ui.theme.LocalCoverTint

/**
 * The player in the Lane style: the cover fills the top of the screen and melts into a backdrop of
 * its own colour; a big bold title, a thin seek line, a white pill for play/pause.
 */
@Composable
fun LaneNowPlaying(onClose: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val state by graph.player.state.collectAsStateWithLifecycle()
    val settings by graph.settings.flow.collectAsStateWithLifecycle(null)
    val np = state.nowPlaying ?: return
    val track by remember(np.trackId) { graph.db.tracks().observe(np.trackId ?: -1) }.collectAsStateWithLifecycle(null)
    val position by remember { graph.player.positionFlow() }.collectAsStateWithLifecycle(0L to 0L)
    val artists by remember(np.trackId) { graph.db.artists().observeForTrack(np.trackId ?: -1) }.collectAsStateWithLifecycle(emptyList())
    val sleep by graph.player.sleep.collectAsStateWithLifecycle()
    val vizMode = settings?.visualizer ?: VisualizerMode.BARS
    val tint = LocalCoverTint.current
    val light = Ink.isLight
    val fg = Ink.Text
    val muted = Ink.Muted
    // The backdrop: the cover's colour, deep enough for white text (or pale for the light theme).
    val top = if (light) lerp(Color(0xFFF7F7F8), tint, 0.24f) else lerp(Color(0xFF0B0B0D), tint, 0.42f)
    val bottom = Ink.Paper
    val artModel: Any? = np.artworkUri ?: np.artworkData

    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var artistMenu by remember { mutableStateOf(false) }

    // Swipe down to put the player away, like the Palimpsest one.
    val dragOffset = remember { androidx.compose.animation.core.Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    var heightPx by remember { mutableFloatStateOf(1f) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
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
                val p = (dragOffset.value / heightPx).coerceIn(0f, 1f)
                translationY = dragOffset.value
                val scale = 1f - p * 0.07f
                scaleX = scale; scaleY = scale
                shape = RoundedCornerShape((p * 120).coerceAtMost(28f).dp)
                clip = p > 0f
            }
            .background(Brush.verticalGradient(0f to top, 0.62f to top, 1f to bottom)),
    ) {
        // The cover, edge to edge, fading into the backdrop at its lower edge.
        var swipe by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.66f)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipe < -160f) graph.player.next() else if (swipe > 160f) graph.player.previous()
                            swipe = 0f
                        },
                        onDragCancel = { swipe = 0f },
                    ) { _, dx -> swipe += dx }
                }
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { showLyrics = !showLyrics },
        ) {
            AnimatedContent(
                targetState = np.mediaId to state.index,
                transitionSpec = {
                    val forward = targetState.second >= initialState.second
                    (androidx.compose.animation.slideInHorizontally(tween(340)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(300))) togetherWith
                        (androidx.compose.animation.slideOutHorizontally(tween(340)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(240)))
                },
                label = "lane-cover",
            ) { _ ->
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = swipe * 0.35f }) {
                    if (artModel != null) {
                        AsyncImage(artModel, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Artwork(null, np.album ?: np.title, Modifier.fillMaxSize(), corner = 0.dp)
                    }
                }
            }
            // A light scrim behind the status bar, and the melt into the backdrop below.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.18f to Color.Transparent, 0.55f to Color.Transparent, 1f to top)))
            // With lyrics on, the cover only dims; the lyrics themselves sit between the top bar and the title.
            androidx.compose.animation.AnimatedVisibility(showLyrics, enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
                Box(Modifier.fillMaxSize().background(top.copy(alpha = 0.82f)))
            }
        }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, tr(R.string.collapse), Modifier.size(32.dp), tint = Color.White) }
                Spacer(Modifier.width(8.dp))
                // ✦ › : a radio from this track (Yandex), the frosted pill from the Lane look.
                val t = track
                if (t != null && t.service == dev.xvin.palimpsest.core.model.Service.YANDEX && t.remoteId != null && graph.yandex.isReady) {
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.28f))
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                            .clickable {
                                scope.launch { graph.radio.start("track:${t.remoteId}", tr(R.string.radio_title, t.title)).onFailure { nav.error(it, tr(R.string.action_track_radio)) } }
                                nav.toast(tr(R.string.lane_radio_started))
                            }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, tr(R.string.action_track_radio), tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, tr(R.string.more), tint = Color.White) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(tr(R.string.speed_pitch)) }, leadingIcon = { Icon(Icons.Rounded.Speed, null) }, onClick = { menu = false; showSpeed = true })
                        DropdownMenuItem(text = { Text(tr(R.string.equalizer)) }, leadingIcon = { Icon(Icons.Rounded.Equalizer, null) }, onClick = { menu = false; nav.openEqualizer() })
                        DropdownMenuItem(
                            text = { Text(tr(R.string.visualizer_mode, VisualizerMode.entries[(vizMode.ordinal + 1) % VisualizerMode.entries.size].label)) },
                            leadingIcon = { Icon(Icons.Rounded.GraphicEq, null) },
                            onClick = { menu = false; scope.launch { graph.settings.setVisualizer(VisualizerMode.entries[(vizMode.ordinal + 1) % VisualizerMode.entries.size]) } },
                        )
                        DropdownMenuItem(text = { Text(tr(R.string.share_card_action)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { menu = false; showShare = true })
                        track?.let { t -> DropdownMenuItem(text = { Text(tr(R.string.track_actions)) }, onClick = { menu = false; nav.showActions(t) }) }
                    }
                }
            }

            // The lyrics get exactly the space above the title, so they can never run into it.
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                if (showLyrics) LyricsPane(track, position.first, onSeek = { graph.player.seekTo(it) })
            }

            if (vizMode != VisualizerMode.OFF && !showLyrics) {
                Visualizer(graph.effects, if (vizMode == VisualizerMode.RING) VisualizerMode.BARS else vizMode, fg.copy(alpha = 0.45f), state.isPlaying, Modifier.fillMaxWidth().height(36.dp))
                Spacer(Modifier.height(6.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    androidx.compose.animation.Crossfade(np.title, label = "lane-title") { title ->
                        Text(title, style = MaterialTheme.typography.headlineLarge, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        Text(
                            np.artist.ifBlank { np.album.orEmpty() },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal), color = fg.copy(alpha = 0.78f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp).clickable(enabled = artists.isNotEmpty()) {
                                if (artists.size == 1) nav.openArtist(artists[0].name, artists[0].remoteId) else artistMenu = true
                            },
                        )
                        DropdownMenu(artistMenu, onDismissRequest = { artistMenu = false }) {
                            artists.forEach { a -> DropdownMenuItem(text = { Text(a.name) }, onClick = { artistMenu = false; nav.openArtist(a.name, a.remoteId) }) }
                        }
                    }
                }
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(Icons.AutoMirrored.Rounded.Subject, tr(R.string.lyrics), tint = if (showLyrics) fg else muted, modifier = Modifier.size(28.dp))
                }
            }
            val ab by graph.player.ab.collectAsStateWithLifecycle()
            if (ab != null && ab!!.trackId == np.trackId) AbSwitch(ab!!, np.mediaId) else VersionChip(np.variant, track, np.mediaId)

            Spacer(Modifier.height(14.dp))
            LaneSeekBar(position.first, np.durationMs, fg, onSeek = { graph.player.seekTo(it) })
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(formatDuration(position.first), style = MaterialTheme.typography.labelMedium, color = muted)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(np.durationMs), style = MaterialTheme.typography.labelMedium, color = muted)
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { graph.player.toggleShuffle() }) {
                    Icon(Icons.Rounded.Shuffle, tr(R.string.shuffle), tint = if (state.shuffle) fg else muted, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = { graph.player.previous() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, tr(R.string.back), Modifier.size(38.dp), tint = fg) }
                val playInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val pressed by playInteraction.collectIsPressedAsState()
                val playScale by animateFloatAsState(if (pressed) 0.92f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 600f), label = "lane-play")
                Box(
                    Modifier.width(118.dp).height(62.dp).graphicsLayer { scaleX = playScale; scaleY = playScale }
                        .clip(RoundedCornerShape(18.dp)).background(fg)
                        .clickable(interactionSource = playInteraction, indication = androidx.compose.material3.ripple()) { graph.player.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    val iconColor = if (light) Color.White else Color.Black
                    AnimatedContent(
                        targetState = if (state.isBuffering) 2 else if (state.isPlaying) 1 else 0,
                        transitionSpec = { (fadeIn(tween(160)) + androidx.compose.animation.scaleIn(tween(200), initialScale = 0.6f)) togetherWith fadeOut(tween(120)) },
                        label = "lane-play-icon",
                    ) { mode ->
                        when (mode) {
                            2 -> CircularProgressIndicator(color = iconColor, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                            1 -> Icon(Icons.Rounded.Pause, tr(R.string.pause), tint = iconColor, modifier = Modifier.size(38.dp))
                            else -> Icon(Icons.Rounded.PlayArrow, tr(R.string.play), tint = iconColor, modifier = Modifier.size(38.dp))
                        }
                    }
                }
                IconButton(onClick = { graph.player.next() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, tr(R.string.next), Modifier.size(38.dp), tint = fg) }
                IconButton(onClick = { graph.player.cycleRepeat() }) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        tr(R.string.repeat), tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) fg else muted, modifier = Modifier.size(26.dp),
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                track?.let { t ->
                    IconButton(onClick = { scope.launch { graph.setLiked(t, !t.liked).onFailure { nav.toast(tr(R.string.like_not_saved_yandex, it.message)) } } }) {
                        Icon(if (t.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, tr(R.string.favorite), tint = fg, modifier = Modifier.size(28.dp))
                    }
                }
                IconButton(onClick = { showSleep = true }) {
                    Icon(Icons.Rounded.Bedtime, tr(R.string.sleep_timer), tint = if (sleep.endsAt != null || sleep.endOfTrack) fg else muted, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.weight(1f))
                val t = track
                if (t != null && t.path == null && t.remoteId != null) {
                    IconButton(onClick = {
                        graph.appScope.launch { graph.downloads.enqueue(listOf(t.id)) }
                        nav.toast(tr(R.string.downloading_one, t.title))
                    }) { Icon(Icons.Rounded.Download, tr(R.string.download), tint = fg, modifier = Modifier.size(28.dp)) }
                } else {
                    IconButton(onClick = { showShare = true }) { Icon(Icons.Rounded.Share, tr(R.string.share_card_action), tint = muted, modifier = Modifier.size(26.dp)) }
                }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, tr(R.string.queue), tint = fg, modifier = Modifier.size(28.dp)) }
            }
        }
    }

    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
    if (showSleep) SleepDialog(onDismiss = { showSleep = false })
    if (showSpeed) SpeedDialog(onDismiss = { showSpeed = false })
    if (showShare) dev.xvin.palimpsest.ui.components.ShareCardDialog(np, onDismiss = { showShare = false })
}

/** Lane's seek line: hairline track, a dot for the thumb; it thickens while dragged. */
@Composable
private fun LaneSeekBar(positionMs: Long, durationMs: Long, color: Color, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) dragFraction else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val thumb by animateFloatAsState(if (dragging) 9f else 6.5f, label = "lane-thumb")
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(24.dp)
            .pointerInput(durationMs) { detectTapGestures { o -> if (durationMs > 0) onSeek((o.x / size.width * durationMs).toLong()) } }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging = false; onSeek((dragFraction * durationMs).toLong()) },
                    onDragCancel = { dragging = false },
                ) { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        val y = size.height / 2
        val stroke = if (dragging) 4.dp.toPx() else 2.dp.toPx()
        drawLine(color.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, Offset(0f, y), Offset(size.width * fraction, y), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(color, thumb.dp.toPx(), Offset(size.width * fraction, y))
    }
}
