package dev.xvin.palimpsest.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.xvin.palimpsest.core.lyrics.Lrc
import dev.xvin.palimpsest.core.lyrics.ParsedLyrics
import dev.xvin.palimpsest.desktop.data.DesktopStyle
import dev.xvin.palimpsest.desktop.data.Track
import kotlinx.coroutines.launch

@Composable
fun RightPanel(panel: String, modifier: Modifier) {
    val pal = LocalPal.current
    val nav = LocalNav.current
    Column(modifier.background(pal.paper2).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(t("Текст", "Lyrics"), panel == "lyrics", { nav.rightPanel = "lyrics" })
            Spacer(Modifier.width(8.dp))
            Pill(t("Очередь", "Queue"), panel == "queue", { nav.rightPanel = "queue" })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { nav.rightPanel = null }) { Icon(Icons.Rounded.Close, null, tint = pal.muted) }
        }
        Spacer(Modifier.height(8.dp))
        if (panel == "queue") QueueList() else LyricsView(Modifier.fillMaxSize(), big = false)
    }
}

@Composable
private fun QueueList() {
    val graph = LocalGraph.current
    val pal = LocalPal.current
    val accent = LocalAccent.current
    val st by graph.player.state.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(st.index) { if (st.index > 2) list.animateScrollToItem(st.index - 2) }
    LazyColumn(state = list) {
        itemsIndexed(st.queue, key = { i, t -> "$i:${t.id}" }) { i, t ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (i == st.index) accent.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { graph.player.skipTo(i) }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackArt(t, Modifier.size(36.dp), 4.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = if (i == st.index) accent else pal.text)
                    Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = pal.muted)
                }
                if (i != st.index) Icon(Icons.Rounded.Close, null, tint = pal.faint, modifier = Modifier.size(16.dp).clickable { graph.player.removeAt(i) })
            }
        }
    }
}

/** Synced lyrics that follow the music (click a line to jump there); plain text just scrolls. */
@Composable
fun LyricsView(modifier: Modifier, big: Boolean) {
    val graph = LocalGraph.current
    val pal = LocalPal.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    val st by graph.player.state.collectAsState()
    val track = st.current
    var loading by remember { mutableStateOf(false) }
    val parsed by produceState<ParsedLyrics?>(null, track?.id) {
        value = null
        val t = track ?: return@produceState
        loading = true
        val l = runCatching { graph.lyrics.best(t) }.getOrNull()
        loading = false
        value = l?.let { Lrc.parse(it.synced ?: it.plain.orEmpty()) } ?: ParsedLyrics(emptyList(), false)
    }
    var translated by remember(track?.id) { mutableStateOf<List<String>?>(null) }
    var translating by remember(track?.id) { mutableStateOf(false) }
    val p = parsed
    when {
        track == null -> EmptyState(t("Ничего не играет", "Nothing playing"), "")
        p == null || loading -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        p.lines.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) { Text(t("Текста не нашлось", "No lyrics found"), color = pal.muted) }
        else -> Column(modifier) {
            Row {
                TextButton(enabled = !translating, onClick = {
                    if (translated != null) { translated = null; return@TextButton }
                    translating = true
                    scope.launch {
                        translated = runCatching { graph.translator.translate(p.lines.map { it.text }, if (ru) "ru" else "en").lines }.getOrNull()
                        translating = false
                    }
                }) { Text(if (translated != null) t("Оригинал", "Original") else if (translating) t("Перевожу…", "Translating…") else t("Перевести", "Translate"), color = pal.muted) }
            }
            val active = if (p.isSynced) p.indexAt(st.positionMs) else -1
            val list = rememberLazyListState()
            LaunchedEffect(active) { if (active >= 0) list.animateScrollToItem((active - 3).coerceAtLeast(0)) }
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 200.dp)) {
                itemsIndexed(p.lines) { i, line ->
                    val color by animateColorAsState(if (!p.isSynced || i == active) pal.text else if (i < active) pal.faint else pal.muted)
                    val scale by animateFloatAsState(if (i == active) 1f else 0.94f)
                    Column(
                        Modifier.fillMaxWidth().clickable(enabled = p.isSynced) { graph.player.seek(line.timeMs) }
                            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                            .padding(vertical = if (big) 8.dp else 5.dp),
                    ) {
                        Text(line.text.ifBlank { "♪" }, color = color, fontSize = if (big) 30.sp else 18.sp, fontWeight = if (i == active) FontWeight.Bold else FontWeight.SemiBold, lineHeight = if (big) 36.sp else 24.sp)
                        translated?.getOrNull(i)?.takeIf { it.isNotBlank() && it != line.text }?.let {
                            Text(it, color = accent.copy(alpha = 0.8f), fontSize = if (big) 18.sp else 13.sp)
                        }
                    }
                }
            }
        }
    }
}

/** The full-window player: Palimpsest — blurred cover, cover and lyrics side by side; Lane — the cover fills the left half. */
@Composable
fun NowPlayingView() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val st by graph.player.state.collectAsState()
    val t = st.current ?: return
    val model by produceState<Any?>(t.coverUrl, t.id) { value = graph.covers.model(t) }
    val lane = LocalStyle.current == DesktopStyle.LANE
    val tint = LocalTint.current
    Box(Modifier.fillMaxSize().background(pal.paper)) {
        if (lane) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.lerp(Color.Black, tint, 0.5f), pal.paper))))
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)) {
                    if (model != null) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.55f to Color.Transparent, 1f to androidx.compose.ui.graphics.lerp(Color.Black, tint, 0.5f))))
                    Column(Modifier.align(Alignment.BottomStart).padding(32.dp)) {
                        Text(t.title, style = MaterialTheme.typography.displayMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal), color = Color.White.copy(alpha = 0.8f))
                    }
                }
                LyricsView(Modifier.weight(1f).fillMaxHeight().padding(40.dp), big = true)
            }
        } else {
            if (model != null) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(90.dp).graphicsLayer { alpha = 0.5f })
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(pal.paper.copy(alpha = 0.4f), pal.paper))))
            Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Artwork(model, t.album ?: t.title, Modifier.fillMaxWidth().aspectRatio(1f), 18.dp)
                    Spacer(Modifier.height(20.dp))
                    Text(t.title, style = MaterialTheme.typography.displayMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(t.artist, t.album).joinToString(" · "), color = pal.muted, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Spectrum(Modifier.fillMaxWidth().height(60.dp))
                }
                LyricsView(Modifier.weight(1.2f).fillMaxHeight(), big = true)
            }
        }
        IconButton(onClick = { nav.nowPlaying = false }, modifier = Modifier.padding(12.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(32.dp), tint = Color.White) }
    }
}

/** The spectrum of what's playing, straight from the shared analyser. */
@Composable
fun Spectrum(modifier: Modifier) {
    val graph = LocalGraph.current
    val accent = LocalAccent.current
    val st by graph.player.state.collectAsState()
    var levels by remember { mutableStateOf(FloatArray(48)) }
    LaunchedEffect(st.playing) {
        while (st.playing) {
            androidx.compose.runtime.withFrameNanos { }
            val raw = graph.player.engine.spectrum.compute()
            levels = FloatArray(raw.size) { i -> levels.getOrElse(i) { 0f } * 0.6f + raw[i] * 0.4f }
        }
    }
    androidx.compose.foundation.Canvas(modifier) {
        val n = levels.size
        val slot = size.width / n
        levels.forEachIndexed { i, v ->
            val h = (v.coerceIn(0f, 1f) * size.height).coerceAtLeast(2f)
            drawRoundRect(accent, androidx.compose.ui.geometry.Offset(i * slot + slot * 0.15f, size.height - h), androidx.compose.ui.geometry.Size(slot * 0.7f, h), androidx.compose.ui.geometry.CornerRadius(slot * 0.35f))
        }
    }
}
