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

@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
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
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть", Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(station?.title?.uppercase() ?: "СЕЙЧАС ИГРАЕТ", style = MaterialTheme.typography.labelSmall, color = if (station != null) accent else Ink.Muted, maxLines = 1)
                    Text(
                        track?.let { techLine(it) } ?: "Внешний файл",
                        style = MaterialTheme.typography.labelSmall, color = Ink.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Таймер сна") }, leadingIcon = { Icon(Icons.Rounded.Bedtime, null) }, onClick = { menu = false; showSleep = true })
                        DropdownMenuItem(text = { Text("Скорость и тон") }, leadingIcon = { Icon(Icons.Rounded.Speed, null) }, onClick = { menu = false; showSpeed = true })
                        DropdownMenuItem(text = { Text("Эквалайзер") }, leadingIcon = { Icon(Icons.Rounded.Equalizer, null) }, onClick = { menu = false; nav.openEqualizer() })
                        DropdownMenuItem(text = { Text("Поделиться карточкой") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { menu = false; showShare = true })
                        track?.let { t ->
                            DropdownMenuItem(text = { Text("Действия с треком") }, onClick = { menu = false; nav.showActions(t) })
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
                        scope.launch { graph.setLiked(t, !t.liked).onFailure { nav.toast("В Яндексе не сохранилось: ${it.message}") } }
                    }) {
                        Icon(if (t.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Любимое", tint = if (t.liked) accent else Ink.Muted)
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
                    Icon(Icons.Rounded.Shuffle, "Перемешать", tint = if (state.shuffle) accent else Ink.Muted)
                }
                IconButton(onClick = { graph.player.previous() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Назад", Modifier.size(36.dp)) }
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
                            1 -> Icon(Icons.Rounded.Pause, "Пауза", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp))
                            else -> Icon(Icons.Rounded.PlayArrow, "Играть", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp))
                        }
                    }
                }
                IconButton(onClick = { graph.player.next() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Вперёд", Modifier.size(36.dp)) }
                IconButton(onClick = { graph.player.cycleRepeat() }) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Повтор", tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) accent else Ink.Muted,
                    )
                }
            }

            val sleep by graph.player.sleep.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                IconButton(onClick = { showLyrics = !showLyrics }) { Icon(Icons.Rounded.Lyrics, "Текст", tint = if (showLyrics) accent else Ink.Muted) }
                IconButton(onClick = {
                    scope.launch { graph.settings.setVisualizer(VisualizerMode.entries[(vizMode.ordinal + 1) % VisualizerMode.entries.size]) }
                    nav.toast("Визуализация: ${VisualizerMode.entries[(vizMode.ordinal + 1) % VisualizerMode.entries.size].label}")
                }) { Icon(Icons.Rounded.GraphicEq, "Визуализация", tint = if (vizMode != VisualizerMode.OFF) accent else Ink.Muted) }
                IconButton(onClick = { showSleep = true }) {
                    Icon(Icons.Rounded.Bedtime, "Таймер сна", tint = if (sleep.endsAt != null || sleep.endOfTrack) accent else Ink.Muted)
                }
                IconButton(onClick = { nav.openEqualizer() }) { Icon(Icons.Rounded.Equalizer, "Эквалайзер", tint = if (settings?.eqEnabled == true) accent else Ink.Muted) }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Очередь", tint = Ink.Muted) }
            }
        }
    }

    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
    if (showSleep) SleepDialog(onDismiss = { showSleep = false })
    if (showSpeed) SpeedDialog(onDismiss = { showSpeed = false })
    if (showShare) dev.xvin.palimpsest.ui.components.ShareCardDialog(np, onDismiss = { showShare = false })
}

@Composable
private fun LyricsPane(track: TrackEntity?, positionMs: Long, onSeek: (Long) -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    val lyrics by produceState<ParsedLyrics?>(null, track?.id, track?.modifiedAt, reload) {
        value = null
        val l = track?.let { graph.lyrics.local(it) }
        value = l?.let { Lrc.parse(it.synced ?: it.plain.orEmpty()) } ?: ParsedLyrics.EMPTY
    }
    val parsed = lyrics
    when {
        parsed == null -> CircularProgressIndicator()
        parsed.lines.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Текста пока нет", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            if (track != null) {
                OutlinedButton(enabled = !searching, onClick = {
                    searching = true
                    scope.launch {
                        val found = graph.lyrics.fetchOnline(track)
                        searching = false
                        if (found != null) reload++ else nav.toast("Текст не нашёлся ни в LRCLIB, ни в Яндексе")
                    }
                }) { Text(if (searching) "Ищу…" else "Найти онлайн (LRCLIB / Яндекс)") }
            }
        }
        !parsed.isSynced -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp)) {
            itemsIndexed(parsed.lines) { _, l ->
                Text(l.text, style = MaterialTheme.typography.titleMedium, color = Ink.Text, modifier = Modifier.padding(vertical = 3.dp))
            }
        }
        else -> {
            val current = parsed.indexAt(positionMs)
            val listState = rememberLazyListState()
            var translateOn by remember(track?.id) { mutableStateOf(false) }
            var translation by remember(track?.id) { mutableStateOf<List<String>?>(null) }
            var translating by remember { mutableStateOf(false) }
            LaunchedEffect(translateOn, track?.id) {
                if (!translateOn || translation != null || track == null) return@LaunchedEffect
                translating = true
                runCatching { graph.lyrics.translate(track.id, parsed.lines.map { it.text }) }
                    .onSuccess { if (it == null) { nav.toast("Текст уже на русском"); translateOn = false } else translation = it }
                    .onFailure { nav.error(it, "Перевод текста"); translateOn = false }
                translating = false
            }
            LaunchedEffect(current) {
                if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
            }
            Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 32.dp)) {
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
                    Text(
                        l.text.ifBlank { "♪" },
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold),
                        color = color,
                        modifier = Modifier.fillMaxWidth().clickable { onSeek(l.timeMs) }.padding(top = 6.dp, bottom = if (translateOn) 0.dp else 6.dp)
                            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) },
                    )
                    val tr = translation?.getOrNull(i)
                    if (translateOn && !tr.isNullOrBlank() && l.text.isNotBlank()) {
                        Text(tr, style = MaterialTheme.typography.bodyMedium, color = if (i == current) accent.copy(alpha = 0.8f) else Ink.Muted, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
            TextButton(
                onClick = { translateOn = !translateOn },
                modifier = Modifier.align(Alignment.TopEnd),
            ) { Text(if (translating) "Перевожу…" else if (translateOn) "Оригинал" else "Перевод", color = accent) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val queue by graph.player.queue.collectAsStateWithLifecycle()
    val state by graph.player.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.index - 1).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.Paper2) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Очередь", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
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
                    if (!current) IconButton(onClick = { graph.player.remove(i) }) { Icon(Icons.Rounded.Close, "Убрать", tint = Ink.Faint) }
                    else Spacer(Modifier.width(48.dp))
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun SleepDialog(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val sleep by graph.player.sleep.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Paper2,
        title = { Text("Таймер сна") },
        text = {
            Column {
                if (sleep.endsAt != null) {
                    val left = ((sleep.endsAt!! - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                    Text("Остановится через $left мин (с плавным затуханием)", color = Ink.Muted)
                    Spacer(Modifier.height(8.dp))
                }
                if (sleep.endOfTrack) { Text("Остановится в конце трека", color = Ink.Muted); Spacer(Modifier.height(8.dp)) }
                listOf(10, 15, 30, 45, 60, 90).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { m ->
                            OutlinedButton(
                                onClick = { graph.player.sleepIn(m); onDismiss() },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            ) { Text("$m мин", maxLines = 1, softWrap = false) }
                        }
                    }
                }
                TextButton(onClick = { graph.player.sleepAtEndOfTrack(); onDismiss() }) { Text("В конце этого трека") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        dismissButton = {
            if (sleep.endsAt != null || sleep.endOfTrack) TextButton(onClick = { graph.player.cancelSleep(); onDismiss() }) { Text("Отключить") }
        },
    )
}

@Composable
private fun SpeedDialog(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    var speed by remember { mutableFloatStateOf(state.speed) }
    var keepPitch by remember { mutableStateOf(state.pitch == 1f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Paper2,
        title = { Text("Скорость: ${"%.2f".format(speed)}×") },
        text = {
            Column {
                Slider(speed, { speed = (it * 20).toInt() / 20f; graph.player.setSpeed(speed, if (keepPitch) 1f else speed) }, valueRange = 0.5f..2f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Сохранять тон")
                        Text("Выключи для эффекта «nightcore» / «slowed»", style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
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
    Column(
        Modifier.fillMaxWidth().background(Ink.Paper2).clickable(onClick = onOpen)
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
        Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.Line)) {
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
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Играть/пауза", Modifier.size(30.dp))
                }
            }
            IconButton(onClick = { graph.player.next() }) { Icon(Icons.Rounded.SkipNext, "Следующий", tint = Ink.Muted) }
        }
    }
}

/**
 * Says which audio is playing when a track has several versions, and offers the other one:
 * "saved version — newer on Yandex: listen / download", or "this is the new/archived version".
 */
@Composable
private fun VersionChip(variant: String?, track: TrackEntity?, mediaId: String) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    val t = track ?: return
    val text: String
    val actions = mutableListOf<Pair<String, () -> Unit>>()
    when {
        variant != null -> {
            text = if (mediaId.startsWith(dev.xvin.palimpsest.playback.MediaItems.NEW_PREFIX)) "Играет новая версия с сервиса (не скачана)" else "Играет: ${variant.lowercase()}"
            if (mediaId.startsWith(dev.xvin.palimpsest.playback.MediaItems.NEW_PREFIX)) {
                actions += "Скачать" to { scope.launch { graph.downloads.enqueue(listOf(t.id)) }; nav.toast("Скачиваю — старая версия сохранится") }
            }
            actions += "Все версии" to { nav.openTrack(t.id, 2) }
        }
        t.updateAvailable && t.path != null -> {
            text = "Играет сохранённая версия · на ${t.service.displayName} новая: " +
                "${formatDuration(t.remoteDurationMs ?: 0)} вместо ${formatDuration(t.durationMs)}"
            actions += "Послушать новую" to { graph.player.playNewVersion(t.id) }
            actions += "Скачать" to { scope.launch { graph.downloads.enqueue(listOf(t.id)) }; nav.toast("Скачиваю — старая версия сохранится") }
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
private fun AbSwitch(ab: dev.xvin.palimpsest.playback.AbCompare, mediaId: String) {
    val graph = LocalGraph.current
    val accent = LocalAccent.current
    val isB = mediaId.startsWith(dev.xvin.palimpsest.playback.MediaItems.VERSION_PREFIX)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Paper3)) {
            listOf(false to "A · текущая", true to "B · ${ab.versionLabel.lowercase()}").forEach { (b, label) ->
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
            Text("Закончить сравнение", color = Ink.Muted, style = MaterialTheme.typography.labelMedium)
        }
    }
}
