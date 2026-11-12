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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
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
    dev.xvin.palimpsest.ui.components.NotThisButton(tint)
                dev.xvin.palimpsest.ui.components.ShakeWaveButton(tint)
    dev.xvin.palimpsest.ui.components.HandoffButton(tint)
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
    dev.xvin.palimpsest.ui.components.HeartButton(t.liked, { scope.launch { graph.setLiked(t, !t.liked).onFailure { nav.toast(tr(R.string.like_not_saved_yandex, it.message)) } } }, likedTint = likedTint, idleTint = tint, iconSize = 24.dp)
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

// --- «Обложка» ---------------------------------------------------------------------------------------

/** A key of glass over the cover's colour: a round button with a frosted fill. */
@Composable
private fun GlassKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, fg: Color, size: Dp = 48.dp, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(fg.copy(alpha = if (active) 0.32f else 0.16f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(size * 0.46f), tint = fg) }
}

/**
 * «Обложка»: the screen is the cover's colour — a glow from its tint at the top falling to dark —
 * with the cover big in the middle, a heavy title, a thick white line and a white play button.
 */
@Composable
fun CoverNowPlaying(onClose: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val bits = rememberPlayerBits() ?: return
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val station by graph.radio.station.collectAsStateWithLifecycle()
    val np = bits.np
    val tint = dev.xvin.palimpsest.ui.theme.LocalCoverTint.current
    val fg = if (Ink.isLight) Color(0xFF140C10) else Color.White
    val glow = if (Ink.isLight) lerp(tint, Color.White, 0.55f) else lerp(tint, Color.White, 0.12f)
    Box(
        Modifier.fillMaxSize().dismissOnSwipeDown(onClose)
            .background(Brush.radialGradient(0f to glow, 0.4f to lerp(tint, Ink.Paper, 0.35f), 0.85f to Ink.Paper, 1f to Ink.Paper, center = Offset(300f, 360f), radius = 2200f)),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassKey(Icons.Rounded.KeyboardArrowDown, tr(R.string.collapse), fg, 44.dp, onClick = onClose)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    val why = station?.let { dev.xvin.palimpsest.ui.components.waveReason(graph, np.trackId) }
                    val label = listOfNotNull(station?.title, why).joinToString(" · ").ifEmpty { tr(R.string.now_playing_caps).lowercase().replaceFirstChar { it.uppercase() } }
                    Text(
                        label, Modifier.clip(RoundedCornerShape(50)).background(fg.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                PlayerMenu(bits, fg) { showQueue = true }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight) * 0.9f
                AnimatedContent(showLyrics, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "cover-lyrics") { lyrics ->
                    if (lyrics) LyricsPane(bits.track, bits.positionMs, onSeek = { graph.player.seekTo(it) })
                    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val scale by animateFloatAsState(if (bits.isPlaying()) 1f else 0.9f, label = "cover-scale")
                        Artwork(
                            bits.art, np.album ?: np.title,
                            Modifier.size(side).graphicsLayer { scaleX = scale; scaleY = scale }
                                .shadow(40.dp, RoundedCornerShape(32.dp), ambientColor = Color.Black, spotColor = Color.Black).clickable { showLyrics = true },
                            corner = 32.dp, artist = np.artist,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(np.title, style = MaterialTheme.typography.headlineLarge, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(np.artist, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium), color = fg.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            FlacCandidatePrompt(bits.track, np.mediaId)
            Spacer(Modifier.height(10.dp))
            StyledSeekBar(bits.positionMs, np.durationMs, SolidColor(fg), fg.copy(alpha = 0.22f), null, 6.dp) { graph.player.seekTo(it) }
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration(bits.positionMs), style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.75f))
                Spacer(Modifier.weight(1f))
                Text(formatDuration(np.durationMs), style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.75f))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(fg.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { LikeButton(bits.track, fg) }
                IconButton(onClick = { graph.player.previous() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, tr(R.string.back), Modifier.size(38.dp), tint = fg) }
                Box(
                    Modifier.size(84.dp).shadow(18.dp, CircleShape).clip(CircleShape).background(fg).clickable { graph.player.toggle() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (bits.isPlaying()) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, tr(R.string.play_pause), Modifier.size(40.dp), tint = lerp(tint, Color.Black, 0.55f)) }
                IconButton(
                    onClick = { graph.player.next() }, enabled = !np.mediaId.startsWith(dev.xvin.palimpsest.playback.InternetRadioItems.PREFIX), modifier = Modifier.size(56.dp),
                ) { Icon(Icons.Rounded.SkipNext, tr(R.string.next), Modifier.size(38.dp), tint = fg) }
                GlassKey(Icons.Rounded.Shuffle, tr(R.string.shuffle), fg, active = bits.state.shuffle) { graph.player.toggleShuffle() }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable
                fun Chip(text: String, active: Boolean = false, onClick: () -> Unit) = Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(50))
                        .background(fg.copy(alpha = if (active) 0.28f else 0.10f)).border(1.dp, fg.copy(alpha = 0.22f), RoundedCornerShape(50)).clickable(onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) { Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1) }
                Chip(tr(R.string.lyrics), showLyrics) { showLyrics = !showLyrics }
                Chip(tr(R.string.queue)) { showQueue = true }
                Chip(tr(R.string.equalizer)) { nav.openEqualizer() }
            }
        }
    }
    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
}

// --- «Пульт» ----------------------------------------------------------------------------------------

/** A square key of the instrument panel. */
@Composable
private fun PanelKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, width: Dp = 48.dp, height: Dp = 48.dp, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.size(width, height).clip(RoundedCornerShape(10.dp))
            .background(if (active) accent.copy(alpha = 0.12f) else Ink.Paper2)
            .border(1.dp, if (active) accent.copy(alpha = 0.4f) else Ink.Line, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(minOf(width, height) * 0.48f), tint = if (!enabled) Ink.Faint else if (active) accent else Ink.Text) }
}

/** Minutes, seconds and tenths, as an instrument reads them. */
private fun preciseTime(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 100
    return "%d:%02d.%d".format(t / 600, t / 10 % 60, t % 10)
}

/**
 * «Пульт»: everything a listener who cares wants in sight — format and bitrate, ReplayGain, the
 * track's waveform, why this song plays, what comes next — on a dark panel with one lime light.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ConsoleNowPlaying(onClose: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val bits = rememberPlayerBits() ?: return
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val station by graph.radio.station.collectAsStateWithLifecycle()
    val queue by graph.player.queue.collectAsStateWithLifecycle()
    val np = bits.np
    val t = bits.track
    val accent = LocalAccent.current
    val cyan = if (Ink.isLight) Color(0xFF00788A) else Color(0xFF59D6E6)
    val mono = MaterialTheme.typography.labelSmall
    Box(Modifier.fillMaxSize().dismissOnSwipeDown(onClose).background(Ink.Paper)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PanelKey(Icons.Rounded.KeyboardArrowDown, tr(R.string.collapse), 44.dp, 44.dp, onClick = onClose)
                Text(
                    listOfNotNull(station?.title?.uppercase() ?: tr(R.string.now_playing_caps), "${bits.state.index + 1}/${bits.state.queueSize}".takeIf { bits.state.queueSize > 1 })
                        .joinToString(" · "),
                    Modifier.weight(1f).padding(horizontal = 8.dp), style = mono, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                PlayerMenu(bits, Ink.Text) { showQueue = true }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)) {
                if (showLyrics) LyricsPane(t, bits.positionMs, onSeek = { graph.player.seekTo(it) })
                else Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(bits.art, np.album ?: np.title, Modifier.size(128.dp).clickable { showLyrics = true }, corner = 10.dp, artist = np.artist)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(np.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(np.artist, np.album, t?.year?.toString()).joinToString(" · "),
                                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            // The facts of the file, as chips: lossless in cyan.
                            val lossless = t?.codec?.lowercase()?.let { c -> listOf("flac", "alac", "wav", "aiff", "ape", "wv").any { c.contains(it) } } == true
                            val chips = listOfNotNull(
                                t?.codec?.uppercase()?.let { it to lossless },
                                t?.let { tr -> listOfNotNull(tr.bitsPerSample?.takeIf { it > 0 }?.toString(), tr.sampleRate?.let { "%.1f".format(it / 1000f).removeSuffix(".0") }).joinToString("/").ifEmpty { null } }?.let { it to false },
                                t?.bitrateKbps?.let { tr(R.string.tech_kbps, it) to false },
                                t?.replayGainTrackDb?.let { "RG %+.1f dB".format(it) to false },
                                (if (t != null && t.path == null) t.service.label else null)?.let { it to false },
                            )
                            if (chips.isNotEmpty()) FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                chips.forEach { (text, hi) ->
                                    Text(
                                        text, Modifier.border(1.dp, if (hi) cyan.copy(alpha = 0.5f) else Ink.Line, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
                                        style = mono, color = if (hi) cyan else Ink.Muted,
                                    )
                                }
                            }
                        }
                    }
                    val why = station?.let { dev.xvin.palimpsest.ui.components.waveReason(graph, np.trackId) }
                    if (why != null) {
                        Column(
                            Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.07f))
                                .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(tr(R.string.console_why).uppercase(), style = mono, color = accent)
                            Text(why.replaceFirstChar { it.uppercase() }, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    FlacCandidatePrompt(t, np.mediaId)
                    // What's left of the panel is a live spectrum, an instrument's meter.
                    Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.BottomCenter) {
                        if (bits.vizMode != VisualizerMode.OFF) Visualizer(
                            graph.effects, if (bits.vizMode == VisualizerMode.RING) VisualizerMode.BARS else bits.vizMode, accent.copy(alpha = 0.55f), bits.isPlaying(),
                            Modifier.fillMaxWidth().heightIn(max = 140.dp).fillMaxHeight(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val wave by produceState<FloatArray?>(null, t?.id, t?.path, np.variant) {
                value = null
                if (t?.path != null && np.variant == null) value = graph.waveform.get(t.id, java.io.File(t.path))
            }
            val w = wave
            if (w != null) WaveSeekBar(w, bits.positionMs, np.durationMs, onSeek = { graph.player.seekTo(it) }, color = accent)
            else StyledSeekBar(bits.positionMs, np.durationMs, SolidColor(accent), Ink.Line, null, 4.dp) { graph.player.seekTo(it) }
            // The waveform writes its own times; the plain line gets them to the tenth.
            if (w == null) Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(preciseTime(bits.positionMs), style = MaterialTheme.typography.labelMedium, color = accent)
                Spacer(Modifier.weight(1f))
                Text("−" + preciseTime(np.durationMs - bits.positionMs), style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PanelKey(Icons.Rounded.Shuffle, tr(R.string.shuffle), active = bits.state.shuffle) { graph.player.toggleShuffle() }
                PanelKey(Icons.Rounded.SkipPrevious, tr(R.string.back), 56.dp, 56.dp) { graph.player.previous() }
                Box(
                    Modifier.size(84.dp, 64.dp).clip(RoundedCornerShape(14.dp)).background(accent).clickable { graph.player.toggle() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (bits.isPlaying()) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, tr(R.string.play_pause), Modifier.size(32.dp), tint = Ink.Paper) }
                PanelKey(Icons.Rounded.SkipNext, tr(R.string.next), 56.dp, 56.dp, enabled = !np.mediaId.startsWith(dev.xvin.palimpsest.playback.InternetRadioItems.PREFIX)) { graph.player.next() }
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Ink.Paper2).border(1.dp, Ink.Line, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) { LikeButton(t, Ink.Text, likedTint = accent) }
            }
            // The panel below: three keys, then what plays next.
            Column(Modifier.padding(bottom = 10.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Ink.Line, RoundedCornerShape(10.dp))) {
                Row(Modifier.height(40.dp)) {
                    @Composable
                    fun Tab(text: String, active: Boolean = false, onClick: () -> Unit) = Box(
                        Modifier.weight(1f).fillMaxSize().background(if (active) Ink.Paper2 else Color.Transparent).clickable(onClick = onClick),
                        contentAlignment = Alignment.Center,
                    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (active) accent else Ink.Muted, maxLines = 1) }
                    Tab(tr(R.string.lyrics), showLyrics) { showLyrics = !showLyrics }
                    Box(Modifier.width(1.dp).fillMaxSize().background(Ink.Line))
                    Tab(tr(R.string.queue)) { showQueue = true }
                    Box(Modifier.width(1.dp).fillMaxSize().background(Ink.Line))
                    Tab(tr(R.string.equalizer)) { nav.openEqualizer() }
                }
                val next = queue.withIndex().drop(bits.state.index + 1).take(2)
                next.forEach { (i, item) ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Line))
                    Row(Modifier.fillMaxWidth().clickable { graph.player.skipTo(i) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i - bits.state.index}", Modifier.width(18.dp), style = mono, color = Ink.Faint)
                        Column(Modifier.weight(1f)) {
                            Text(item.mediaMetadata.title?.toString() ?: "—", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.mediaMetadata.artist?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(formatDuration(item.mediaMetadata.durationMs ?: 0), style = mono, color = Ink.Faint)
                    }
                }
            }
        }
    }
    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
}
