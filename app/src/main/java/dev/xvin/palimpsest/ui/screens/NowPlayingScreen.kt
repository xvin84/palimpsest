package dev.xvin.palimpsest.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    var menu by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val artModel: Any? = np.artworkUri ?: np.artworkData

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = dragOffset.coerceAtLeast(0f) }
            .background(Ink.Paper)
            .pointerInput(Unit) {
                // Pull down to close.
                detectVerticalDragGestures(
                    onDragEnd = { if (dragOffset > 220f) onClose(); dragOffset = 0f },
                    onDragCancel = { dragOffset = 0f },
                ) { _, dy -> dragOffset += dy }
            },
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
                    Text("СЕЙЧАС ИГРАЕТ", style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
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
                            Box(
                                Modifier
                                    .fillMaxSize(inner)
                                    .graphicsLayer { scaleX = coverScale; scaleY = coverScale }
                                    .shadow(24.dp, RoundedCornerShape(if (vizMode == VisualizerMode.RING) 1000.dp else 16.dp))
                                    .clickable { showLyrics = true },
                            ) {
                                Artwork(artModel, np.album ?: np.title, Modifier.fillMaxSize(), corner = if (vizMode == VisualizerMode.RING) 1000.dp else 16.dp)
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
                    Text(
                        listOfNotNull(np.artist.ifBlank { null }, np.album).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = track != null) { track?.let { nav.openList(TrackListKind.ARTIST, it.artist) } },
                    )
                }
                track?.let { t ->
                    IconButton(onClick = { scope.launch { graph.db.tracks().setLiked(t.id, !t.liked) } }) {
                        Icon(if (t.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Любимое", tint = if (t.liked) accent else Ink.Muted)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SeekBar(position.first, position.second, np.durationMs, onSeek = { graph.player.seekTo(it) })

            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { graph.player.toggleShuffle() }) {
                    Icon(Icons.Rounded.Shuffle, "Перемешать", tint = if (state.shuffle) accent else Ink.Muted)
                }
                IconButton(onClick = { graph.player.previous() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Назад", Modifier.size(36.dp)) }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(accent).clickable { graph.player.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isBuffering) CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                    else Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.isPlaying) "Пауза" else "Играть",
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp),
                    )
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
}

@Composable
private fun LyricsPane(track: TrackEntity?, positionMs: Long, onSeek: (Long) -> Unit) {
    val graph = LocalGraph.current
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
                        if (found != null) reload++
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
            LaunchedEffect(current) {
                if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 32.dp)) {
                itemsIndexed(parsed.lines) { i, l ->
                    val active = i == current
                    Text(
                        l.text.ifBlank { "♪" },
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = if (active) 26.sp else 22.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                        ),
                        color = when {
                            active -> accent
                            i < current -> Ink.Faint
                            else -> Ink.Text.copy(alpha = 0.7f)
                        },
                        modifier = Modifier.fillMaxWidth().clickable { onSeek(l.timeMs) }.padding(vertical = 6.dp),
                    )
                }
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
                            OutlinedButton(onClick = { graph.player.sleepIn(m); onDismiss() }, modifier = Modifier.weight(1f)) { Text("$m мин") }
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
    Column(Modifier.fillMaxWidth().background(Ink.Paper2).clickable(onClick = onOpen)) {
        val frac = if (np.durationMs > 0) (position.first.toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.Line)) {
            Box(Modifier.fillMaxWidth(frac).height(2.dp).background(accent))
        }
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(np.artworkUri ?: np.artworkData, np.album ?: np.title, Modifier.size(42.dp).aspectRatio(1f), corner = 6.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(np.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(np.artist, style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { graph.player.toggle() }) {
                Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Играть/пауза", Modifier.size(30.dp))
            }
            IconButton(onClick = { graph.player.next() }) { Icon(Icons.Rounded.SkipNext, "Следующий", tint = Ink.Muted) }
        }
    }
}
