package dev.xvin.palimpsest.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** The «emphasized» curve: quick to leave, long to settle — for things that travel across the window. */
val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Where the playing track's cover is: in the player bar, and on the big player in each of its two
 * layouts (with the lyrics and without). Opening or closing the big player, or switching its lyrics,
 * flies the cover from one place to the other instead of the picture jumping.
 */
class CoverSpots {
    var bar by mutableStateOf<Rect?>(null)
    /** The big player's cover by layout (lyrics on → true): its place and corner. */
    val big = androidx.compose.runtime.mutableStateMapOf<Boolean, Pair<Rect, Dp>>()
    /** While a cover is in the air, the real ones stay hidden. */
    var flying by mutableStateOf(false)
    internal var from: Rect? = null
    internal var to: Rect? = null
    internal var fromCorner = 0.dp
    internal var toCorner = 0.dp
    internal val progress = Animatable(0f)
}

val LocalCoverSpots = androidx.compose.runtime.staticCompositionLocalOf { CoverSpots() }

/** Reports this cover's place — the bar's, or the big player's in the [lyrics] layout — and hides it while a copy flies. */
fun Modifier.coverSpot(big: Boolean, corner: Dp = 18.dp, lyrics: Boolean = true): Modifier = composed {
    val spots = LocalCoverSpots.current
    DisposableEffect(big, lyrics) { onDispose { if (big) spots.big.remove(lyrics) else spots.bar = null } }
    onGloballyPositioned { c ->
        val r = c.boundsInRoot()
        if (big) spots.big[lyrics] = r to corner else spots.bar = r
    }.graphicsLayer { alpha = if (spots.flying) 0f else 1f }
}

/** Flies the cover when the big player opens or closes ([open]) or its [lyrics] are switched. Drawn above everything. */
@Composable
fun CoverFlight(open: Boolean, lyrics: Boolean, model: Any?, seed: String, artist: String?) {
    val spots = LocalCoverSpots.current
    val was = remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    LaunchedEffect(open, lyrics) {
        val before = was.value
        was.value = open to lyrics
        if (before == null) return@LaunchedEffect
        val (wasOpen, wasLyrics) = before
        // Where it leaves from, and how to find where it lands (that layout may only now be appearing).
        val start: Pair<Rect, Dp>
        val target: () -> Pair<Rect, Dp>?
        when {
            open && !wasOpen -> { start = (spots.bar ?: return@LaunchedEffect) to Radius.sm; target = { spots.big[lyrics] } }
            !open && wasOpen -> { start = spots.big[wasLyrics] ?: return@LaunchedEffect; target = { spots.bar?.let { it to Radius.sm } } }
            open && lyrics != wasLyrics -> { start = spots.big[wasLyrics] ?: return@LaunchedEffect; target = { spots.big[lyrics] } }
            else -> return@LaunchedEffect
        }
        spots.from = start.first; spots.fromCorner = start.second
        spots.to = start.first; spots.toCorner = start.second
        spots.progress.snapTo(0f)
        spots.flying = true
        try {
            val end = withTimeoutOrNull(400) { snapshotFlow { target() }.first { it != null && it.first.width > 0f } } ?: return@LaunchedEffect
            spots.to = end.first
            spots.toCorner = end.second
            spots.progress.animateTo(1f, tween(if (open && wasOpen) 480 else if (open) 520 else 440, easing = Emphasized))
        } finally {
            spots.flying = false
        }
    }
    // The copy is always there, unseen at rest, and always laid out large so its picture is loaded
    // sharp; the layer moves and scales it onto the cover's place. Its place, corners and visibility
    // are read while drawing — the same moment the real covers hide — so no frame is without a cover.
    Box(
        Modifier.layout { m, _ ->
            val side = FlightSide.roundToPx()
            val placeable = m.measure(Constraints.fixed(side, side))
            layout(0, 0) {
                placeable.placeWithLayer(0, 0) {
                    val r = spots.rectNow() ?: spots.bar ?: Rect(0f, 0f, 1f, 1f)
                    val p = spots.progress.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    translationX = r.left
                    translationY = r.top
                    scaleX = r.width / side
                    scaleY = r.height / side
                    alpha = if (spots.flying) 1f else 0f
                    // A shadow that grows mid-flight: the cover lifts off and lands.
                    shadowElevation = 24.dp.toPx() * (1f - kotlin.math.abs(p * 2f - 1f)) / scaleX.coerceAtLeast(0.05f)
                    shape = RoundedCornerShape(lerp(spots.fromCorner, spots.toCorner, p).toPx() / scaleX.coerceAtLeast(0.05f))
                    clip = true
                }
            }
        },
    ) { Artwork(model, seed, Modifier.fillMaxSize(), 0.dp, artist = artist) }
}

/** The flying copy's own size before it's scaled onto a cover: big enough for the big player's. */
private val FlightSide = 640.dp

/** Where the flying cover is right now, or null when nothing flies. */
private fun CoverSpots.rectNow(): Rect? {
    if (!flying) return null
    val f = from ?: return null
    val t = to ?: return null
    return lerp(f, t, progress.value)
}

/**
 * The analyser's bands for what's playing, worked out once a frame however many things show them
 * (the big player's spectrum, the bars by the playing track). Silent → all zeros.
 */
@Composable
fun rememberLevels(): State<FloatArray> {
    val graph = LocalGraph.current
    val playing by remember(graph) { graph.player.state.map { it.playing }.distinctUntilChanged() }.collectAsState(false)
    val levels = remember { mutableStateOf(SpectrumFeed.levels) }
    LaunchedEffect(playing) {
        if (!playing) { levels.value = FloatArray(SpectrumFeed.levels.size); return@LaunchedEffect }
        while (true) withFrameNanos { n ->
            if (SpectrumFeed.frame != n) {
                val raw = graph.player.engine.spectrum.compute()
                SpectrumFeed.levels = FloatArray(raw.size) { i -> SpectrumFeed.levels.getOrElse(i) { 0f } * 0.6f + raw[i] * 0.4f }
                SpectrumFeed.frame = n
            }
            levels.value = SpectrumFeed.levels
        }
    }
    return levels
}

private object SpectrumFeed {
    var frame = -1L
    var levels = FloatArray(48)
}
