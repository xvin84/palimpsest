package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.xvin.palimpsest.core.lyrics.Lrc
import dev.xvin.palimpsest.core.lyrics.ParsedLyrics
import dev.xvin.palimpsest.core.model.Lyrics
import dev.xvin.palimpsest.desktop.data.LyricsService
import dev.xvin.palimpsest.desktop.data.Track
import dev.xvin.palimpsest.desktop.data.DesktopStyle
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
fun LyricsView(modifier: Modifier, big: Boolean, scale: Float = 1f) {
    val graph = LocalGraph.current
    val pal = LocalPal.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    val st by graph.player.state.collectAsState()
    val track = st.current
    var loading by remember { mutableStateOf(false) }
    // A variant picked with «Source» replaces what was found.
    var picked by remember(track?.id) { mutableStateOf<Lyrics?>(null) }
    val found by produceState<Lyrics?>(null, track?.id) {
        value = null
        val t = track ?: return@produceState
        loading = true
        value = runCatching { graph.lyrics.best(t) }.getOrNull()
        loading = false
    }
    val shown = picked ?: found
    val parsed = remember(shown, loading, track?.id) {
        if (track == null || loading) null else shown?.let { Lrc.parse(it.synced ?: it.plain.orEmpty()) } ?: ParsedLyrics(emptyList(), false)
    }
    var translated by remember(track?.id, shown) { mutableStateOf<List<String>?>(null) }
    var translating by remember(track?.id) { mutableStateOf(false) }
    val p = parsed
    when {
        track == null -> EmptyState(t("Ничего не играет", "Nothing playing"), "")
        p == null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        p.lines.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t("Текста не нашлось", "No lyrics found"), color = pal.muted)
                LyricsSourceButton(track, shown) { picked = it }
            }
        }
        else -> Column(modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LyricsSourceButton(track, shown) { picked = it }
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
            // The sung line rests a little below the top, however tall the lines are (a translation doubles them).
            LaunchedEffect(active, translated != null) {
                if (active < 0) return@LaunchedEffect
                val info = list.layoutInfo
                val anchor = ((info.viewportEndOffset - info.viewportStartOffset) * 0.18f).toInt()
                val visible = info.visibleItemsInfo.firstOrNull { it.index == active }
                if (visible != null) list.animateScrollBy((visible.offset - anchor).toFloat())
                else { list.scrollToItem(active); list.scrollBy(-anchor.toFloat()) }
            }
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 200.dp)) {
                itemsIndexed(p.lines) { i, line ->
                    val color by animateColorAsState(if (!p.isSynced || i == active) pal.text else if (i < active) pal.faint else pal.muted)
                    val scale by animateFloatAsState(if (i == active) 1f else 0.94f)
                    Column(
                        Modifier.fillMaxWidth().clickable(enabled = p.isSynced) { graph.player.seek(line.timeMs) }
                            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                            .padding(vertical = if (big) 8.dp else 5.dp),
                    ) {
                        Text(line.text.ifBlank { "♪" }, color = color, fontSize = if (big) (30 * scale).sp else 18.sp, fontWeight = if (i == active) FontWeight.Bold else FontWeight.SemiBold, lineHeight = if (big) (36 * scale).sp else 24.sp)
                        translated?.getOrNull(i)?.takeIf { it.isNotBlank() && it != line.text }?.let {
                            Text(it, color = accent.copy(alpha = 0.8f), fontSize = if (big) (18 * scale).sp else 13.sp)
                        }
                    }
                }
            }
        }
    }
}

/** «tag» → «из файла»: a lyrics variant's source as people say it. */
fun lyricsSourceName(source: String): String = when (source) {
    LyricsService.SAVED -> t("сохранённый", "saved")
    LyricsService.TAG -> t("из тега файла", "from the file’s tag")
    LyricsService.LRC -> t("файл .lrc", ".lrc file")
    "Yandex Music" -> t("Яндекс Музыка", "Yandex Music")
    else -> source
}

/**
 * «Source: LRCLIB ▾» — every variant found for the track (saved, in the file, LRCLIB, NetEase,
 * Genius, Yandex), timed or not, with its first line; the picked one is shown and saved.
 */
@Composable
private fun LyricsSourceButton(track: Track, shown: Lyrics?, onPick: (Lyrics) -> Unit) {
    val graph = LocalGraph.current
    val pal = LocalPal.current
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(t("Источник", "Source") + (shown?.let { ": " + lyricsSourceName(it.source) } ?: "") + " ▾", color = pal.muted)
        }
        if (open) {
            val variants by produceState<List<Lyrics>?>(null, track.id) { value = runCatching { graph.lyrics.variants(track) }.getOrDefault(emptyList()) }
            androidx.compose.material3.DropdownMenu(true, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 300.dp, max = 420.dp)) {
                val list = variants
                when {
                    list == null -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text(t("Ищу во всех источниках…", "Looking everywhere…"), color = pal.muted)
                    }
                    list.isEmpty() -> Text(t("Нигде нет", "Nowhere to be found"), Modifier.padding(16.dp), color = pal.muted)
                    else -> list.forEach { l ->
                        val text = l.synced ?: l.plain.orEmpty()
                        val first = Lrc.parse(text).lines.firstOrNull { it.text.isNotBlank() }?.text.orEmpty()
                        val same = shown != null && (shown.synced ?: shown.plain).orEmpty().trim() == text.trim()
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Column {
                                    Text((if (same) "✓ " else "") + lyricsSourceName(l.source) + " · " + (if (l.synced != null) t("по времени", "timed") else t("без времени", "untimed")), fontSize = 14.sp)
                                    Text(first, color = pal.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            onClick = { open = false; graph.lyrics.save(track, l); onPick(l) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The full-window player, laid out for the window it's in: wide — cover and lyrics side by side,
 * narrow or tall — one above the other; with lyrics off, just a big cover. Text scales with width.
 * Palimpsest: blurred cover behind; Lane: the cover itself, edge to edge, melting into its colour.
 */
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
    val deep = androidx.compose.ui.graphics.lerp(Color.Black, tint, 0.5f)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(pal.paper)) {
        val w = maxWidth
        val h = maxHeight
        val wide = w > h * 1.15f && w >= 820.dp
        val lyrics = nav.bigLyrics
        val scale = (w.value / 1400f).coerceIn(0.7f, 1.3f)
        val titleStyle = MaterialTheme.typography.displayMedium.copy(fontSize = (34 * scale).coerceAtLeast(24f).sp, lineHeight = (40 * scale).coerceAtLeast(28f).sp)
        // Backdrop.
        if (lane) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(deep, pal.paper))))
        } else {
            if (model != null) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(64.dp).graphicsLayer { alpha = 0.5f })
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(pal.paper.copy(alpha = 0.4f), pal.paper))))
        }
        @Composable
        fun Titles(color: Color = pal.text) {
            Text(t.title, style = titleStyle, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(t.artist, t.album).joinToString(" · "), color = color.copy(alpha = 0.75f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            // Wide, Lane: the cover fills the left part edge to edge.
            wide && lane && lyrics -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxHeight().widthIn(max = w * 0.55f).aspectRatio(1f, matchHeightConstraintsFirst = true)) {
                    if (model != null) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.55f to Color.Transparent, 1f to deep)))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
                    Column(Modifier.align(Alignment.BottomStart).padding(32.dp)) { Titles(Color.White) }
                }
                LyricsView(Modifier.weight(1f).fillMaxHeight().padding(40.dp), big = true, scale = scale)
            }
            wide && lyrics -> Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                CoverWithInfo(Modifier.weight(1f).fillMaxHeight(), w, centered = false, cover = { Artwork(model, t.album ?: t.title, Modifier.fillMaxSize(), 18.dp) }) {
                    Titles()
                    Spacer(Modifier.height(12.dp))
                    Spectrum(Modifier.fillMaxWidth().height(60.dp))
                }
                LyricsView(Modifier.weight(1.2f).fillMaxHeight(), big = true, scale = scale)
            }
            // Narrow or tall with lyrics: a compact header, the lyrics below it.
            lyrics -> Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
                Row(Modifier.padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(model, t.album ?: t.title, Modifier.size(minOf(w * 0.22f, 120.dp)), 12.dp)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f, fill = false)) {
                        Titles()
                        if (!lane) { Spacer(Modifier.height(10.dp)); Spectrum(Modifier.widthIn(max = 360.dp).fillMaxWidth().height(32.dp)) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                LyricsView(Modifier.weight(1f).fillMaxWidth(), big = true, scale = scale)
            }
            // Lyrics off: the cover as big as the window allows.
            else -> CoverWithInfo(Modifier.fillMaxSize().padding(32.dp), minOf(w * 0.8f, h * 0.68f), centered = true, cover = { Artwork(model, t.album ?: t.title, Modifier.fillMaxSize(), if (lane) 6.dp else 18.dp) }) {
                Titles()
                if (!lane) { Spacer(Modifier.height(12.dp)); Spectrum(Modifier.fillMaxWidth().height(48.dp)) }
            }
        }
        IconButton(onClick = { nav.openMenu(graph.library.get(t.id) ?: t) }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(Icons.Rounded.MoreHoriz, t("Действия", "Actions"), Modifier.size(28.dp), tint = if (lane || !pal.light) Color.White else pal.text)
        }
        IconButton(onClick = { nav.nowPlaying = false }, modifier = Modifier.padding(8.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(32.dp), tint = if (lane || !pal.light) Color.White else pal.text) }
    }
}

/**
 * A square cover with the titles and spectrum under it, all as wide as the cover. The cover gives way
 * (down from [maxSide]) so the part below always fits — otherwise a tall cover squeezed the spectrum flat.
 */
@Composable
private fun CoverWithInfo(modifier: Modifier, maxSide: Dp, centered: Boolean, cover: @Composable () -> Unit, info: @Composable ColumnScope.() -> Unit) {
    SubcomposeLayout(modifier) { c ->
        val gap = 20.dp.roundToPx()
        var side = minOf(c.maxWidth, maxSide.roundToPx())
        var below = subcompose(0) { Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start, content = info) }
            .single().measure(Constraints.fixedWidth(side))
        // A narrower column may wrap the title onto another line, so measure again at the new width.
        for (pass in 1..2) {
            val fits = (c.maxHeight - gap - below.height).coerceAtLeast(0)
            if (side <= fits) break
            side = fits
            below = subcompose(pass) { Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start, content = info) }
                .single().measure(Constraints.fixedWidth(side))
        }
        side = minOf(side, (c.maxHeight - gap - below.height).coerceAtLeast(0))
        val art = subcompose("cover", cover).single().measure(Constraints.fixed(side, side))
        val width = c.maxWidth
        val x = if (centered) (width - maxOf(side, below.width)) / 2 else 0
        val y = (c.maxHeight - side - gap - below.height).coerceAtLeast(0) / 2
        layout(width, c.maxHeight) {
            art.place(x, y)
            below.place(x, y + side + gap)
        }
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
