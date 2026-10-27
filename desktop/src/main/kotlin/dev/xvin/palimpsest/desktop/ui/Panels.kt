package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VerticalAlignCenter
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun RightPanel(panel: String, modifier: Modifier) {
    val pal = LocalPal.current
    val nav = LocalNav.current
    Column(modifier.background(pal.paper2).drawBehind { drawLine(pal.line, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tabs(listOf("lyrics" to t("Текст", "Lyrics"), "queue" to t("Очередь", "Queue")), panel, { nav.rightPanel = it })
            Spacer(Modifier.weight(1f))
            PIconButton(Icons.Rounded.Close, t("Закрыть", "Close"), { nav.rightPanel = null }, iconSize = 18.dp)
        }
        Spacer(Modifier.height(12.dp))
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
            val now = i == st.index
            val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(Radius.md))
                    .background(if (hovered) pal.text.copy(alpha = 0.05f) else Color.Transparent)
                    .hoverable(hover).clickable { graph.player.skipTo(i) }.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    TrackArt(t, Modifier.size(36.dp), Radius.xs)
                    if (now) Box(Modifier.size(36.dp).clip(RoundedCornerShape(Radius.xs)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        PlayingBars(st.playing, color = Color.White)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = Type.rowTitle.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (now) accent else if (i < st.index) pal.muted else pal.text)
                    Text(t.artist, style = Type.rowSub.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = pal.muted)
                }
                if (!now && hovered) PIconButton(Icons.Rounded.Close, t("Убрать из очереди", "Remove from the queue"), { graph.player.removeAt(i) }, size = 26.dp, iconSize = 15.dp)
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
        else -> LyricsBody(p, track, shown, big, scale, modifier) { picked = it }
    }
}

/**
 * The lyrics themselves. Synced ones follow the music: the sung line is bright and fills as it's
 * sung (word by word where the lyrics know the words), the others fade with distance, the list glides
 * to keep the line a third of the way down and fades out at the edges. Scroll it yourself and it stays
 * put until «Back to the line»; a click on a line jumps there; pauses in the singing show as dots.
 */
@Composable
private fun LyricsBody(p: ParsedLyrics, track: Track, shown: Lyrics?, big: Boolean, scale: Float, modifier: Modifier, onPick: (Lyrics) -> Unit) {
    val graph = LocalGraph.current
    val pal = LocalPal.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var translated by remember(track.id, shown) { mutableStateOf<List<String>?>(null) }
    var translating by remember(track.id) { mutableStateOf(false) }
    val pos by rememberSmoothPosition()
    val active = if (p.isSynced) p.indexAt(pos) else -1
    val karaoke = graph.settings.state.collectAsState().value.lyricsKaraoke
    val list = rememberLazyListState()
    var follow by remember(track.id) { mutableStateOf(true) }
    var gliding by remember { mutableStateOf(false) }
    // A scroll that isn't ours means the reader wants to look around: stop following.
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress && !gliding) follow = false }
    LaunchedEffect(active, follow, translated != null) {
        if (!follow || active < 0) return@LaunchedEffect
        gliding = true
        try {
            // The first time, the list may not be laid out yet: wait until it has a height.
            androidx.compose.runtime.snapshotFlow { list.layoutInfo.viewportSize.height }.first { it > 0 }
            val info = list.layoutInfo
            val anchor = ((info.viewportEndOffset - info.viewportStartOffset) * 0.3f).toInt()
            val visible = info.visibleItemsInfo.firstOrNull { it.index == active }
            if (visible != null) list.animateScrollBy((visible.offset - anchor).toFloat(), androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 120f))
            else { list.scrollToItem(active); list.scrollBy(-anchor.toFloat()) }
        } finally { gliding = false }
    }
    val lineSize = if (big) (32 * scale).sp else 18.sp
    val lineHeight = if (big) (40 * scale).sp else 25.sp
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.offset(x = (-8).dp)) {
                LyricsSourceButton(track, shown, onPick)
                PButton(
                    if (translated != null) t("Оригинал", "Original") else if (translating) t("Перевожу…", "Translating…") else t("Перевести", "Translate"),
                    {
                        if (translated != null) translated = null
                        else {
                            translating = true
                            scope.launch {
                                translated = runCatching { graph.translator.translate(p.lines.map { it.text }, if (ru) "ru" else "en").lines }.getOrNull()
                                translating = false
                            }
                        }
                    },
                    icon = Icons.Rounded.Translate, kind = Kind.GHOST, enabled = !translating,
                )
            }
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(top = 24.dp, bottom = 260.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
                    // The lines fade in at the top and out at the bottom instead of being cut off.
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.07f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    },
            ) {
                itemsIndexed(p.lines) { i, line ->
                    val isActive = i == active
                    val distance = if (active < 0) 3 else kotlin.math.abs(i - active)
                    val alpha by animateFloatAsState(
                        when { !p.isSynced -> 0.92f; isActive -> 1f; distance == 1 -> 0.42f; distance == 2 -> 0.3f; else -> 0.22f },
                        androidx.compose.animation.core.tween(500),
                    )
                    val grow by animateFloatAsState(if (isActive || !p.isSynced) 1f else 0.965f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 200f))
                    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val hovered by hover.collectIsHoveredAsState()
                    // Lines further from the sung one go a little out of focus (on the big player).
                    val soft by animateFloatAsState(if (!big || !p.isSynced || distance < 2) 0f else if (distance == 2) 1f else 2f, androidx.compose.animation.core.tween(500))
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg))
                            .background(if (hovered && p.isSynced) pal.text.copy(alpha = 0.05f) else Color.Transparent)
                            .hoverable(hover)
                            .clickable(enabled = p.isSynced) { follow = true; graph.player.seek(line.timeMs) }
                            .graphicsLayer { this.alpha = alpha; scaleX = grow; scaleY = grow; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                            .then(if (soft > 0.05f) Modifier.blur(soft.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded) else Modifier)
                            .padding(horizontal = 10.dp, vertical = if (big) 9.dp else 6.dp),
                    ) {
                        val style = Type.lyric.copy(fontSize = lineSize, lineHeight = lineHeight)
                        val end = p.lines.getOrNull(i + 1)?.timeMs ?: (line.timeMs + 5000)
                        when {
                            line.text.isBlank() -> PauseDots(isActive, if (big) 10.dp else 7.dp, pal.text)
                            isActive && karaoke -> SungLine(line, end, pos, style, pal.text)
                            else -> Text(line.text, style = style, color = pal.text)
                        }
                        translated?.getOrNull(i)?.takeIf { it.isNotBlank() && it != line.text }?.let {
                            Text(it, color = pal.text.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyLarge.copy(fontSize = if (big) (19 * scale).sp else 13.sp, lineHeight = if (big) (25 * scale).sp else 18.sp), modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
        if (p.isSynced && !follow) {
            androidx.compose.material3.Surface(
                onClick = { follow = true }, shape = RoundedCornerShape(50), color = pal.paper3, shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            ) {
                Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.VerticalAlignCenter, null, tint = pal.muted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("К текущей строке", "Back to the line"), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * The line being sung, lit up to where the singing is: word by word with word timings, otherwise
 * evenly over the time a line like this takes (and never slower than until the next line).
 */
@Composable
private fun SungLine(line: dev.xvin.palimpsest.core.lyrics.LrcLine, endMs: Long, posMs: Long, style: androidx.compose.ui.text.TextStyle, color: Color) {
    val text = line.text
    val sung = if (line.words != null) {
        val w = line.words!!
        val prog = line.wordProgress(posMs, endMs)
        var chars = 0f
        w.forEachIndexed { k, word -> chars += word.text.length * prog[k] }
        (chars / text.length.coerceAtLeast(1)).coerceIn(0f, 1f)
    } else {
        val span = minOf(endMs - line.timeMs, text.length * 70L + 600).coerceAtLeast(300)
        ((posMs - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
    }
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    Box {
        Text(text, style = style, color = color.copy(alpha = 0.45f), onTextLayout = { layout = it })
        Text(
            text, style = style, color = color,
            modifier = Modifier.drawWithContent {
                val l = layout ?: return@drawWithContent drawContent()
                val upTo = (sung * text.length).toInt().coerceIn(0, text.length)
                val path = androidx.compose.ui.graphics.Path()
                for (n in 0 until l.lineCount) {
                    val startChar = l.getLineStart(n)
                    val endChar = l.getLineEnd(n)
                    val top = l.getLineTop(n)
                    val bottom = l.getLineBottom(n)
                    when {
                        upTo >= endChar -> path.addRect(androidx.compose.ui.geometry.Rect(0f, top, this.size.width, bottom))
                        upTo > startChar -> path.addRect(androidx.compose.ui.geometry.Rect(0f, top, l.getHorizontalPosition(upTo, true), bottom))
                    }
                }
                clipPath(path) { this@drawWithContent.drawContent() }
            },
        )
    }
}

/** A pause in the singing: three dots that breathe while it lasts. */
@Composable
private fun PauseDots(active: Boolean, dot: androidx.compose.ui.unit.Dp, color: Color) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition()
    Row(horizontalArrangement = Arrangement.spacedBy(dot * 0.8f), modifier = Modifier.padding(vertical = dot)) {
        repeat(3) { k ->
            val a by pulse.animateFloat(
                0.3f, 1f,
                androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700, delayMillis = k * 180), androidx.compose.animation.core.RepeatMode.Reverse),
            )
            Box(Modifier.size(dot).graphicsLayer { alpha = if (active) a else 0.5f; val sc = if (active) 0.8f + 0.2f * a else 0.8f; scaleX = sc; scaleY = sc }.clip(CircleShape).background(color))
        }
    }
}

/**
 * Where the music is, every frame: the player reports it five times a second, and between reports
 * the clock carries it on, so the lyrics move smoothly.
 */
@Composable
fun rememberSmoothPosition(): androidx.compose.runtime.State<Long> {
    val graph = LocalGraph.current
    val st by graph.player.state.collectAsState()
    val pos = remember { androidx.compose.runtime.mutableLongStateOf(st.positionMs) }
    LaunchedEffect(st.positionMs, st.playing) {
        // Small differences are the report lagging behind the clock: keep going rather than step back.
        val base = if (kotlin.math.abs(st.positionMs - pos.longValue) < 250) maxOf(pos.longValue, st.positionMs) else st.positionMs
        pos.longValue = base
        if (!st.playing) return@LaunchedEffect
        val start = System.nanoTime()
        while (true) androidx.compose.runtime.withFrameNanos { pos.longValue = base + (it - start).coerceAtLeast(0) / 1_000_000 }
    }
    return pos
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
        PButton(
            shown?.let { lyricsSourceName(it.source).replaceFirstChar(Char::uppercase) } ?: t("Источник", "Source"), { open = true },
            icon = Icons.AutoMirrored.Rounded.Notes, kind = Kind.GHOST, trailingIcon = Icons.Rounded.ExpandMore,
        )
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
                                    Text(lyricsSourceName(l.source).replaceFirstChar(Char::uppercase) + " · " + (if (l.synced != null) t("по времени", "timed") else t("без времени", "untimed")), style = Type.rowTitle)
                                    Text(first, color = pal.muted, style = Type.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            onClick = { open = false; graph.lyrics.save(track, l); onPick(l) },
                            trailingIcon = if (same) { { Icon(Icons.Rounded.Check, null, tint = pal.text, modifier = Modifier.size(18.dp)) } } else null,
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
        val titleStyle = MaterialTheme.typography.displayLarge.copy(fontSize = (40 * scale).coerceAtLeast(26f).sp, lineHeight = (46 * scale).coerceAtLeast(31f).sp)
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
            Text(
                listOfNotNull(t.artist, t.album?.takeIf { it != t.title }).joinToString(" · "), color = color.copy(alpha = 0.7f),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
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
            wide && lyrics -> Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 48.dp, top = 64.dp, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                CoverWithInfo(Modifier.weight(1f).fillMaxHeight(), w, centered = false, cover = { androidx.compose.animation.Crossfade(t.id to model, animationSpec = androidx.compose.animation.core.tween(450)) { (_, m) -> Artwork(m, t.album ?: t.title, Modifier.fillMaxSize(), 18.dp, artist = t.albumArtist ?: t.artist) } }) {
                    Titles()
                    Spacer(Modifier.height(16.dp))
                    Spectrum(Modifier.fillMaxWidth().height(44.dp), pal.text.copy(alpha = 0.32f))
                }
                LyricsView(Modifier.weight(1.2f).fillMaxHeight(), big = true, scale = scale)
            }
            // Narrow or tall with lyrics: a compact header, the lyrics below it.
            lyrics -> Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
                Row(Modifier.padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(model, t.album ?: t.title, Modifier.size(minOf(w * 0.22f, 120.dp)), 12.dp, artist = t.albumArtist ?: t.artist)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f, fill = false)) {
                        Titles()
                        if (!lane) { Spacer(Modifier.height(10.dp)); Spectrum(Modifier.widthIn(max = 360.dp).fillMaxWidth().height(28.dp), pal.text.copy(alpha = 0.32f)) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                LyricsView(Modifier.weight(1f).fillMaxWidth(), big = true, scale = scale)
            }
            // Lyrics off: the cover as big as the window allows.
            else -> CoverWithInfo(Modifier.fillMaxSize().padding(32.dp), minOf(w * 0.8f, h * 0.68f), centered = true, cover = { androidx.compose.animation.Crossfade(t.id to model, animationSpec = androidx.compose.animation.core.tween(450)) { (_, m) -> Artwork(m, t.album ?: t.title, Modifier.fillMaxSize(), if (lane) 6.dp else 18.dp, artist = t.albumArtist ?: t.artist) } }) {
                Titles()
                if (!lane) { Spacer(Modifier.height(12.dp)); Spectrum(Modifier.fillMaxWidth().height(40.dp), pal.text.copy(alpha = 0.32f)) }
            }
        }
        // On a soft disc, so they read over any cover.
        val onArt = if (lane || !pal.light) Color.White else pal.text
        @Composable
        fun Corner(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, align: Alignment, onClick: () -> Unit) {
            Box(Modifier.align(align).padding(12.dp).clip(CircleShape).background(if (lane || !pal.light) Color.Black.copy(alpha = 0.28f) else pal.paper.copy(alpha = 0.6f))) {
                PIconButton(icon, description, onClick, size = 36.dp, iconSize = 22.dp, tint = onArt)
            }
        }
        Corner(Icons.Rounded.MoreHoriz, t("Действия", "Actions"), Alignment.TopEnd) { nav.openMenu(graph.library.get(t.id) ?: t) }
        Corner(Icons.Rounded.KeyboardArrowDown, t("Свернуть", "Collapse"), Alignment.TopStart) { nav.nowPlaying = false }
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
fun Spectrum(modifier: Modifier, color: Color = LocalAccent.current) {
    val graph = LocalGraph.current
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
            drawRoundRect(color, androidx.compose.ui.geometry.Offset(i * slot + slot * 0.15f, size.height - h), androidx.compose.ui.geometry.Size(slot * 0.7f, h), androidx.compose.ui.geometry.CornerRadius(slot * 0.35f))
        }
    }
}
