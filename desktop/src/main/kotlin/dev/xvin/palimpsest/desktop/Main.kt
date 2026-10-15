package dev.xvin.palimpsest.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.xvin.palimpsest.core.dsp.EqProfile
import dev.xvin.palimpsest.core.lyrics.Lrc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val Paper = Color(0xFF0F0E0C)
private val Paper2 = Color(0xFF171513)
private val Paper3 = Color(0xFF211E1B)
private val TextC = Color(0xFFEDE6DA)
private val Muted = Color(0xFF9C9488)
private val Faint = Color(0xFF6A635A)
private val Accent = Color(0xFFE4572E)

private val Serif = FontFamily(
    Font(resource = "font/playfair_display.ttf", weight = FontWeight.Bold),
)

fun main(args: Array<String>) = application {
    val root = File(args.firstOrNull() ?: (System.getProperty("user.home") + "/Music"))
    Window(
        onCloseRequest = ::exitApplication,
        title = "Palimpsest",
        state = rememberWindowState(size = DpSize(1180.dp, 760.dp)),
    ) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Paper, surface = Paper, onSurface = TextC)) {
            App(root)
        }
    }
}

@Composable
private fun App(root: File) {
    val engine = remember { AudioEngine() }
    val tracks by produceState<List<DesktopTrack>?>(null, root) { value = Library.scan(root) }
    var query by remember { mutableStateOf("") }
    var currentIndex by remember { mutableStateOf(-1) }
    val state by engine.state.collectAsState()
    val list = tracks.orEmpty().filter {
        query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) || (it.album?.contains(query, true) == true)
    }

    fun playAt(i: Int) {
        val t = list.getOrNull(i) ?: return
        currentIndex = i
        engine.play(t.file)
    }

    // Auto-advance at the end of a track.
    LaunchedEffect(state.ended) { if (state.ended && currentIndex >= 0) playAt(currentIndex + 1) }

    Row(Modifier.fillMaxSize().background(Paper)) {
        Column(Modifier.width(440.dp).fillMaxHeight().background(Paper2).padding(16.dp)) {
            Text("Медиатека", style = TextStyle(fontFamily = Serif, fontSize = 34.sp, color = TextC))
            Text(
                tracks?.let { "${it.size} треков · ${root.path}" } ?: "Сканирую ${root.path}…",
                color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted) },
                placeholder = { Text("Поиск", color = Faint) },
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(list, key = { _, t -> t.file.path }) { i, t ->
                    val playing = state.file == t.file
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (playing) Accent.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable { playAt(i) }.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = if (playing) Accent else TextC, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                            Text(listOfNotNull(t.artist, t.album).joinToString(" · "), color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        }
                        Text(fmt(t.durationMs), color = Faint, fontSize = 11.sp)
                    }
                }
            }
        }
        NowPlaying(
            engine = engine,
            track = list.getOrNull(currentIndex)?.takeIf { it.file == state.file } ?: tracks?.firstOrNull { it.file == state.file },
            onPrev = { playAt((currentIndex - 1).coerceAtLeast(0)) },
            onNext = { playAt(currentIndex + 1) },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun NowPlaying(engine: AudioEngine, track: DesktopTrack?, onPrev: () -> Unit, onNext: () -> Unit, modifier: Modifier) {
    val state by engine.state.collectAsState()
    var eqName by remember { mutableStateOf(EqProfile.FLAT.name) }
    val cover by produceState<ImageBitmap?>(null, track?.file) {
        value = track?.file?.let { f ->
            withContext(Dispatchers.IO) {
                Library.cover(f)?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
            }
        }
    }
    val lyrics = remember(track?.file) { track?.lyrics?.let(Lrc::parse) }

    Column(modifier.padding(28.dp)) {
        if (track == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Выбери трек слева", style = TextStyle(fontFamily = Serif, fontSize = 28.sp, color = Muted))
            }
            return@Column
        }
        Row(Modifier.weight(1f)) {
            Box(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(16.dp)).background(Paper3)) {
                cover?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Spacer(Modifier.width(24.dp))
            LyricsColumn(lyrics, state.positionMs, Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(16.dp))
        Spectrum(engine, state.playing, Modifier.fillMaxWidth().height(56.dp))
        Spacer(Modifier.height(8.dp))
        Text(track.title, style = TextStyle(fontFamily = Serif, fontSize = 30.sp, color = TextC), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(track.artist, track.album).joinToString(" · "), color = Muted, fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        val frac = if (track.durationMs > 0) (state.positionMs.toFloat() / track.durationMs).coerceIn(0f, 1f) else 0f
        Box(
            Modifier.fillMaxWidth().height(18.dp).pointerInput(track.file) {
                detectTapGestures { o -> engine.seek((o.x / size.width * track.durationMs).toLong()) }
            },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Paper3))
            Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(CircleShape).background(Accent))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(fmt(state.positionMs), color = Muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(fmt(track.durationMs), color = Muted, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = onPrev) { Icon(Icons.Rounded.SkipPrevious, "Назад", tint = TextC, modifier = Modifier.size(34.dp)) }
            Box(Modifier.size(60.dp).clip(CircleShape).background(Accent).clickable { engine.toggle() }, contentAlignment = Alignment.Center) {
                Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "Вперёд", tint = TextC, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.width(24.dp))
            Text("EQ:", color = Muted)
            EqProfile.PRESETS.take(6).forEach { p ->
                Text(
                    p.name,
                    color = if (eqName == p.name) Accent else Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                        eqName = p.name
                        engine.equalizer.setProfile(p)
                    }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun LyricsColumn(lyrics: dev.xvin.palimpsest.core.lyrics.ParsedLyrics?, positionMs: Long, modifier: Modifier) {
    if (lyrics == null || lyrics.lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.CenterStart) { Text("Текста нет", color = Faint, fontSize = 18.sp) }
        return
    }
    val current = lyrics.indexAt(positionMs)
    val listState = rememberLazyListState()
    LaunchedEffect(current) { if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    LazyColumn(modifier, state = listState) {
        itemsIndexed(lyrics.lines) { i, l ->
            Text(
                l.text.ifBlank { "♪" },
                style = TextStyle(
                    fontFamily = Serif,
                    fontSize = if (i == current) 24.sp else 20.sp,
                    color = when { i == current -> Accent; i < current -> Faint; else -> TextC.copy(alpha = 0.75f) },
                ),
                modifier = Modifier.padding(vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun Spectrum(engine: AudioEngine, playing: Boolean, modifier: Modifier) {
    val levels = remember { FloatArray(engine.spectrum.bands) }
    var frame by remember { mutableLongStateOf(0L) }
    val peak = remember { floatArrayOf(0.3f) }
    LaunchedEffect(playing) {
        while (true) {
            withFrameNanos { t ->
                val target = if (playing) engine.spectrum.compute() else null
                if (target != null) peak[0] = maxOf(peak[0] * 0.997f, target.max(), 0.15f)
                for (i in levels.indices) {
                    val v = target?.get(i)?.let { (it / peak[0]).coerceIn(0f, 1f).let { x -> x * x } } ?: 0f
                    levels[i] = if (v > levels[i]) levels[i] + (v - levels[i]) * 0.6f else levels[i] * 0.9f + v * 0.1f
                }
                frame = t
            }
            if (!playing && levels.all { it < 0.01f }) break
        }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame
        val n = levels.size
        val gap = size.width / n * 0.3f
        val w = size.width / n - gap
        for (i in 0 until n) {
            val h = (levels[i] * size.height).coerceAtLeast(w * 0.5f)
            drawRoundRect(Accent.copy(alpha = 0.5f + levels[i] * 0.5f), Offset(i * (w + gap) + gap / 2, size.height - h), Size(w, h), CornerRadius(w / 2, w / 2))
        }
    }
}

private fun fmt(ms: Long): String = if (ms <= 0) "–:––" else "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)
