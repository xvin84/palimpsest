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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.xvin.palimpsest.ui.components.formatDuration

/** Everything a player screen shows, read once and handed to the style's layout. */
internal class PlayerBits(
    val state: dev.xvin.palimpsest.playback.PlayerUiState,
    val np: dev.xvin.palimpsest.playback.NowPlaying,
    val track: TrackEntity?,
    val positionMs: Long,
    val vizMode: VisualizerMode,
    val art: Any?,
)

@Composable
internal fun rememberPlayerBits(): PlayerBits? {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val settings by graph.settings.flow.collectAsStateWithLifecycle(null)
    val np = state.nowPlaying ?: return null
    val track by remember(np.trackId) { graph.db.tracks().observe(np.trackId ?: -1) }.collectAsStateWithLifecycle(null)
    val position by remember { graph.player.positionFlow() }.collectAsStateWithLifecycle(0L to 0L)
    return PlayerBits(state, np, track, position.first, settings?.visualizer ?: VisualizerMode.BARS, np.artworkUri ?: np.artworkData)
}

/** Swipe the whole player down to put it away (shared by every style). */
@Composable
internal fun Modifier.dismissOnSwipeDown(onClose: () -> Unit): Modifier {
    val dragOffset = remember { androidx.compose.animation.core.Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    var heightPx by remember { mutableFloatStateOf(1f) }
    return this
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
                        } else dragOffset.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f))
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
}

/** The ⋮ menu and the sheets it opens; every style uses the same one. */
@Composable
internal fun PlayerMenu(bits: PlayerBits, tint: Color, onQueue: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    var menu by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, tr(R.string.more), tint = tint) }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(tr(R.string.queue)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) }, onClick = { menu = false; onQueue() })
            DropdownMenuItem(text = { Text(tr(R.string.sleep_timer)) }, leadingIcon = { Icon(Icons.Rounded.Bedtime, null) }, onClick = { menu = false; showSleep = true })
            DropdownMenuItem(text = { Text(tr(R.string.speed_pitch)) }, leadingIcon = { Icon(Icons.Rounded.Speed, null) }, onClick = { menu = false; showSpeed = true })
            DropdownMenuItem(text = { Text(tr(R.string.equalizer)) }, leadingIcon = { Icon(Icons.Rounded.Equalizer, null) }, onClick = { menu = false; nav.openEqualizer() })
            DropdownMenuItem(text = { Text(tr(R.string.share_card_action)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { menu = false; showShare = true })
            bits.track?.let { t -> DropdownMenuItem(text = { Text(tr(R.string.track_actions)) }, onClick = { menu = false; nav.showActions(t) }) }
        }
    }
    if (showSleep) SleepDialog(onDismiss = { showSleep = false })
    if (showSpeed) SpeedDialog(onDismiss = { showSpeed = false })
    if (showShare) dev.xvin.palimpsest.ui.components.ShareCardDialog(bits.np, onDismiss = { showShare = false })
}

@Composable
private fun LikeButton(track: TrackEntity?, tint: Color, likedTint: Color = tint) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val t = track ?: return
    IconButton(onClick = { scope.launch { graph.setLiked(t, !t.liked).onFailure { nav.toast(tr(R.string.like_not_saved_yandex, it.message)) } } }) {
        Icon(if (t.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, tr(R.string.favorite), tint = if (t.liked) likedTint else tint)
    }
}

/** A seek line any style can colour: track, played part and a round thumb. */
@Composable
private fun StyledSeekBar(positionMs: Long, durationMs: Long, played: Brush, rest: Color, thumb: Color?, height: Dp, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) dragFraction else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
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
        val hp = height.toPx()
        drawRoundRect(rest, Offset(0f, y - hp / 2), Size(size.width, hp), CornerRadius(hp / 2))
        drawRoundRect(played, Offset(0f, y - hp / 2), Size(size.width * fraction, hp), CornerRadius(hp / 2))
        if (thumb != null) drawCircle(thumb, hp * 1.6f, Offset(size.width * fraction, y))
    }
}

private fun PlayerBits.isPlaying() = state.isPlaying

// --- Glass ------------------------------------------------------------------------------------------

/** Glass: the cover blurred over the whole screen; a frosted panel holds the controls. */
@Composable
fun GlassNowPlaying(onClose: () -> Unit) {
    val graph = LocalGraph.current
    val bits = rememberPlayerBits() ?: return
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val np = bits.np
    val fg = Ink.Text
    Box(Modifier.fillMaxSize().dismissOnSwipeDown(onClose).background(Ink.Paper)) {
        if (bits.art != null) AsyncImage(bits.art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }.blur(70.dp))
        Box(Modifier.fillMaxSize().background(Ink.Paper.copy(alpha = if (Ink.isLight) 0.35f else 0.45f)))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, tr(R.string.collapse), Modifier.size(30.dp), tint = fg) }
                Spacer(Modifier.weight(1f))
                Text(tr(R.string.now_playing_caps), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                PlayerMenu(bits, fg) { showQueue = true }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (showLyrics) LyricsPane(bits.track, bits.positionMs, onSeek = { graph.player.seekTo(it) })
                else {
                    val side = minOf(maxWidth, maxHeight) * 0.92f
                    val scale by animateFloatAsState(if (bits.isPlaying()) 1f else 0.88f, label = "glass-cover")
                    Artwork(
                        bits.art, np.album ?: np.title,
                        Modifier.size(side).graphicsLayer { scaleX = scale; scaleY = scale }.shadow(30.dp, RoundedCornerShape(24.dp)).clickable { showLyrics = true },
                        corner = 24.dp,
                    )
                }
            }
            // The frosted panel.
            Column(
                Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(30.dp))
                    .background(fg.copy(alpha = 0.10f)).border(1.dp, fg.copy(alpha = 0.16f), RoundedCornerShape(30.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(np.title, style = MaterialTheme.typography.titleLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(np.artist, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    LikeButton(bits.track, fg)
                }
                FlacCandidatePrompt(bits.track, np.mediaId)
                StyledSeekBar(bits.positionMs, np.durationMs, SolidColor(fg), fg.copy(alpha = 0.25f), null, 6.dp) { graph.player.seekTo(it) }
                Row(Modifier.fillMaxWidth()) {
                    Text(formatDuration(bits.positionMs), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.6f))
                    Spacer(Modifier.weight(1f))
                    Text("-" + formatDuration((np.durationMs - bits.positionMs).coerceAtLeast(0)), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.6f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { graph.player.toggleShuffle() }) { Icon(Icons.Rounded.Shuffle, null, tint = if (bits.state.shuffle) fg else fg.copy(alpha = 0.45f)) }
                    IconButton(onClick = { graph.player.previous() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, null, Modifier.size(38.dp), tint = fg) }
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).background(fg.copy(alpha = 0.18f)).border(1.dp, fg.copy(alpha = 0.3f), CircleShape).clickable { graph.player.toggle() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (bits.isPlaying()) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, tr(R.string.play_pause), Modifier.size(40.dp), tint = fg) }
                    IconButton(onClick = { graph.player.next() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, null, Modifier.size(38.dp), tint = fg) }
                    IconButton(onClick = { showLyrics = !showLyrics }) { Icon(Icons.Rounded.Lyrics, null, tint = if (showLyrics) fg else fg.copy(alpha = 0.45f)) }
                }
            }
        }
    }
    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
}

// --- Vinyl ------------------------------------------------------------------------------------------

/** Vinyl: the cover is the label of a spinning record; the tonearm swings on when it plays. */
@Composable
fun VinylNowPlaying(onClose: () -> Unit) {
    val graph = LocalGraph.current
    val bits = rememberPlayerBits() ?: return
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val np = bits.np
    val accent = LocalAccent.current
    val fg = Ink.Text
    // The record turns at 33⅓ rpm, keeping its angle when paused.
    val angle = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(bits.isPlaying()) {
        if (bits.isPlaying()) while (true) angle.animateTo(angle.value + 360f, tween(1800, easing = androidx.compose.animation.core.LinearEasing))
    }
    // Resting beside the record; swung onto the outer grooves while playing.
    val arm by animateFloatAsState(if (bits.isPlaying()) 20f else 0f, tween(700), label = "tonearm")
    Box(
        Modifier.fillMaxSize().dismissOnSwipeDown(onClose)
            .background(Brush.radialGradient(listOf(Ink.Paper3, Ink.Paper), radius = 1400f)),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, tr(R.string.collapse), Modifier.size(30.dp), tint = fg) }
                Spacer(Modifier.weight(1f))
                Text("33⅓", style = MaterialTheme.typography.titleMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = accent)
                Spacer(Modifier.weight(1f))
                PlayerMenu(bits, fg) { showQueue = true }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (showLyrics) LyricsPane(bits.track, bits.positionMs, onSeek = { graph.player.seekTo(it) })
                else {
                    val side = minOf(maxWidth, maxHeight) * 0.9f
                    Box(Modifier.size(side).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { showLyrics = true }) {
                        // Record and label turn together.
                        Box(Modifier.fillMaxSize(0.92f).align(Alignment.CenterStart).graphicsLayer { rotationZ = angle.value }) {
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                val r = size.minDimension / 2
                                drawCircle(Color(0xFF0A0807), r)
                                var g = r * 0.95f
                                while (g > r * 0.42f) { drawCircle(Color(0xFF221D19), g, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f)); g -= r * 0.035f }
                                // A highlight sweeping across the grooves.
                                drawArc(Color.White.copy(alpha = 0.07f), -60f, 40f, true)
                                drawArc(Color.White.copy(alpha = 0.05f), 120f, 40f, true)
                            }
                            Artwork(bits.art, np.album ?: np.title, Modifier.fillMaxSize(0.4f).align(Alignment.Center), corner = 1000.dp)
                            Box(Modifier.size(8.dp).align(Alignment.Center).clip(CircleShape).background(Ink.Paper))
                        }
                        // Tonearm.
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val pivot = Offset(size.width * 0.93f, size.height * 0.08f)
                            rotate(arm, pivot) {
                                drawCircle(Color(0xFF8C7B66), size.width * 0.045f, pivot)
                                val elbow = Offset(size.width * 0.95f, size.height * 0.5f)
                                val head = Offset(size.width * 0.97f, size.height * 0.64f)
                                drawLine(Color(0xFFD9C7A8), pivot, elbow, strokeWidth = size.width * 0.018f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                drawLine(Color(0xFFD9C7A8), elbow, head, strokeWidth = size.width * 0.018f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                drawRoundRect(Color(0xFF3B2B20), head - Offset(size.width * 0.03f, size.width * 0.02f), Size(size.width * 0.06f, size.width * 0.08f), CornerRadius(4f))
                            }
                        }
                    }
                }
            }
            Text(np.title, style = MaterialTheme.typography.headlineMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(np.artist, np.album).joinToString(" — "), style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlacCandidatePrompt(bits.track, np.mediaId)
            Spacer(Modifier.height(10.dp))
            StyledSeekBar(bits.positionMs, np.durationMs, SolidColor(accent), Ink.Line, accent, 3.dp) { graph.player.seekTo(it) }
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration(bits.positionMs), style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(np.durationMs), style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                LikeButton(bits.track, Ink.Muted, accent)
                IconButton(onClick = { graph.player.previous() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, null, Modifier.size(36.dp), tint = fg) }
                Box(
                    Modifier.size(74.dp).shadow(10.dp, CircleShape).clip(CircleShape).background(Brush.verticalGradient(listOf(accent, lerp(accent, Color.Black, 0.3f)))).clickable { graph.player.toggle() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (bits.isPlaying()) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, tr(R.string.play_pause), Modifier.size(40.dp), tint = Ink.Paper) }
                IconButton(onClick = { graph.player.next() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, null, Modifier.size(36.dp), tint = fg) }
                IconButton(onClick = { showLyrics = !showLyrics }) { Icon(Icons.Rounded.Lyrics, null, tint = if (showLyrics) accent else Ink.Muted) }
            }
        }
    }
    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
}

// --- Mono -------------------------------------------------------------------------------------------

/** Mono: black and white, monospace, square boxes; the progress is a row of blocks. */
@Composable
fun MonoNowPlaying(onClose: () -> Unit) {
    val graph = LocalGraph.current
    val bits = rememberPlayerBits() ?: return
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val np = bits.np
    val fg = Ink.Text
    val bg = Ink.Paper
    val gray = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) })
    @Composable
    fun box(label: String, onClick: () -> Unit, filled: Boolean = false, modifier: Modifier = Modifier) {
        Box(
            modifier.border(2.dp, fg).background(if (filled) fg else bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) { Text(label, style = MaterialTheme.typography.titleMedium, color = if (filled) bg else fg) }
    }
    Box(Modifier.fillMaxSize().dismissOnSwipeDown(onClose).background(bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("[ ↓ ]", style = MaterialTheme.typography.titleMedium, color = fg, modifier = Modifier.clickable(onClick = onClose).padding(vertical = 12.dp))
                Spacer(Modifier.weight(1f))
                Text("%02d/%02d".format(bits.state.index + 1, bits.state.queueSize), style = MaterialTheme.typography.labelLarge, color = Ink.Muted)
                Spacer(Modifier.weight(1f))
                PlayerMenu(bits, fg) { showQueue = true }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(fg))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                if (showLyrics) LyricsPane(bits.track, bits.positionMs, onSeek = { graph.player.seekTo(it) })
                else {
                    val side = minOf(maxWidth, maxHeight)
                    Box(Modifier.size(side).border(2.dp, fg).padding(6.dp).clickable { showLyrics = true }) {
                        if (bits.art != null) AsyncImage(bits.art, null, contentScale = ContentScale.Crop, colorFilter = gray, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            Text(np.title.uppercase(), style = MaterialTheme.typography.headlineSmall, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("> " + np.artist, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlacCandidatePrompt(bits.track, np.mediaId)
            Spacer(Modifier.height(12.dp))
            // [█████░░░░░░░] 01:12 / 03:45 — tap anywhere on the blocks to jump there.
            val cells = 18
            val frac = if (np.durationMs > 0) (bits.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f
            val filled = (frac * cells).toInt()
            Text(
                "[" + "█".repeat(filled) + "░".repeat(cells - filled) + "]",
                style = MaterialTheme.typography.titleMedium, color = fg, maxLines = 1,
                modifier = Modifier.fillMaxWidth().pointerInput(np.durationMs) {
                    detectTapGestures { o -> if (np.durationMs > 0) graph.player.seekTo((o.x / size.width * np.durationMs).toLong()) }
                },
            )
            Text("${formatDuration(bits.positionMs)} / ${formatDuration(np.durationMs)}", style = MaterialTheme.typography.labelLarge, color = Ink.Muted)
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                box("|◀", { graph.player.previous() }, modifier = Modifier.weight(1f))
                box(if (bits.isPlaying()) "❚❚" else "▶", { graph.player.toggle() }, filled = true, modifier = Modifier.weight(1.4f))
                box("▶|", { graph.player.next() }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (bits.track?.liked == true) "[♥]" else "[♡]", color = fg, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable {
                    bits.track?.let { t -> graph.appScope.launch { graph.setLiked(t, !t.liked) } }
                })
                Text(if (bits.state.shuffle) "[SHUF*]" else "[SHUF]", color = fg, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable { graph.player.toggleShuffle() })
                Text(if (showLyrics) "[LYR*]" else "[LYR]", color = fg, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable { showLyrics = !showLyrics })
                Text("[Q]", color = fg, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable { showQueue = true })
            }
        }
    }
    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
}
