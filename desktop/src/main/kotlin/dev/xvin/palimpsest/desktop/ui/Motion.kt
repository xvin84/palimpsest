package dev.xvin.palimpsest.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
 * Where the playing track's cover is: in the player bar and on the big player. Opening or closing
 * the big player flies one cover to the other's place instead of the two just swapping.
 */
class CoverSpots {
    var bar by mutableStateOf<Rect?>(null)
    var big by mutableStateOf<Rect?>(null)
    var bigCorner: Dp = 18.dp
    /** While a cover is in the air, both real ones stay hidden. */
    var flying by mutableStateOf(false)
    internal var from: Rect? = null
    internal var to: Rect? = null
    internal var fromCorner = 0.dp
    internal var toCorner = 0.dp
    internal val progress = Animatable(0f)
}

val LocalCoverSpots = androidx.compose.runtime.staticCompositionLocalOf { CoverSpots() }

/** Reports this cover's place ([big] or the bar's), and hides it while a copy flies. */
fun Modifier.coverSpot(big: Boolean, corner: Dp = 18.dp): Modifier = composed {
    val spots = LocalCoverSpots.current
    DisposableEffect(big) { onDispose { if (big) spots.big = null else spots.bar = null } }
    onGloballyPositioned { c ->
        val r = c.boundsInRoot()
        if (big) { spots.big = r; spots.bigCorner = corner } else spots.bar = r
    }.graphicsLayer { alpha = if (spots.flying) 0f else 1f }
}

/** Flies the cover between the bar and the big player whenever [open] changes. Drawn above everything. */
@Composable
fun CoverFlight(open: Boolean, model: Any?, seed: String, artist: String?) {
    val spots = LocalCoverSpots.current
    val first = remember { mutableStateOf(true) }
    LaunchedEffect(open) {
        if (first.value) { first.value = false; return@LaunchedEffect }
        val from = (if (open) spots.bar else spots.big) ?: return@LaunchedEffect
        val fromCorner = if (open) Radius.sm else spots.bigCorner
        spots.from = from; spots.fromCorner = fromCorner
        // The other end may not be laid out yet (the big player is only now appearing).
        spots.to = from; spots.toCorner = fromCorner
        spots.progress.snapTo(0f)
        spots.flying = true
        try {
            val to = withTimeoutOrNull(300) {
                if (open) snapshotFlow { spots.big }.first { it != null && it.width > 0f } else spots.bar
            } ?: return@LaunchedEffect
            spots.to = to
            spots.toCorner = if (open) spots.bigCorner else Radius.sm
            spots.progress.animateTo(1f, tween(if (open) 520 else 440, easing = Emphasized))
        } finally {
            spots.flying = false
        }
    }
    if (!spots.flying) return
    val from = spots.from ?: return
    val to = spots.to ?: return
    val p = spots.progress.value
    val r = lerp(from, to, p)
    val density = LocalDensity.current
    val corner = lerp(spots.fromCorner, spots.toCorner, p)
    // A shadow that grows mid-flight: the cover lifts off and lands.
    val lift = (1f - kotlin.math.abs(p * 2f - 1f))
    with(density) {
        Box(
            Modifier.offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                .size(r.width.toDp(), r.height.toDp())
                .shadow((24 * lift).dp, RoundedCornerShape(corner)),
        ) { Artwork(model, seed, Modifier.fillMaxSize(), corner, artist = artist) }
    }
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
