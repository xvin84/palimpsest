package dev.xvin.palimpsest.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.playback.HandoffEvent
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.theme.LocalAccent
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * The music between the phone and the paired computer: send what plays there, or take what plays
 * there. Shown only while paired.
 */
@Composable
fun HandoffButton(tint: Color) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pairing by graph.lanSync.pairing.collectAsStateWithLifecycle()
    val busy by graph.handoff.busy.collectAsStateWithLifecycle()
    val reachable by graph.handoff.reachable.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val p = pairing ?: return
    Box {
        IconButton(onClick = {
            if (reachable) menu = true
            else {
                // Out of reach: the computer shakes its head, and is looked for again right away.
                refusal.value = Refusal(Refusal.Kind.UNREACHABLE, System.nanoTime())
                graph.lanServer.helloNow()
            }
        }, enabled = !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Computer, tr(R.string.handoff_ctx), tint = tint.copy(alpha = if (reachable) tint.alpha else 0.35f))
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Column { Text(tr(R.string.handoff_send)); Text(p.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                onClick = { menu = false; scope.launch { sendToComputer(graph, nav) } },
            )
            DropdownMenuItem(text = { Text(tr(R.string.handoff_pull)) }, onClick = { menu = false; scope.launch { takeFromComputer(graph, nav) } })
        }
    }
}

suspend fun sendToComputer(graph: dev.xvin.palimpsest.AppGraph, nav: dev.xvin.palimpsest.ui.AppNav, fromGesture: Boolean = false) {
    graph.handoff.send()
        .onSuccess {
            // The flight already says it; the gesture leaves nothing else behind.
            if (!fromGesture) nav.toast(tr(R.string.handoff_playing_on, graph.lanSync.pairing.value?.name.orEmpty()), tr(R.string.handoff_take_back)) { graph.appScope.launch { takeFromComputer(graph, nav) } }
        }
        .onFailure { notHandedOver(graph, nav, it, here = true) }
}

suspend fun takeFromComputer(graph: dev.xvin.palimpsest.AppGraph, nav: dev.xvin.palimpsest.ui.AppNav) {
    graph.handoff.pull().onFailure { notHandedOver(graph, nav, it, here = false) }
}

/** Why nothing moved — shown as the computer shaking at the top, not an error sheet, when it's the usual reasons. */
private fun notHandedOver(graph: dev.xvin.palimpsest.AppGraph, nav: dev.xvin.palimpsest.ui.AppNav, e: Throwable, here: Boolean) {
    when {
        e is java.io.IOException -> { refusal.value = Refusal(Refusal.Kind.UNREACHABLE, System.nanoTime()); graph.lanServer.helloNow() }
        e.message?.startsWith("nothing is playing") == true ->
            refusal.value = Refusal(if (here) Refusal.Kind.NOTHING_HERE else Refusal.Kind.NOTHING_THERE, System.nanoTime())
        else -> nav.error(e, tr(R.string.handoff_ctx))
    }
}

/** A handover that couldn't happen, for [HandoffRefusal] to show. */
private data class Refusal(val kind: Kind, val at: Long) {
    enum class Kind { UNREACHABLE, NOTHING_HERE, NOTHING_THERE }
}

private val refusal = androidx.compose.runtime.mutableStateOf<Refusal?>(null)

/**
 * The computer at the top shakes its head: crossed out when it can't be reached, plain when it's
 * there but there's nothing to move. With a tick of the vibration; gone by itself.
 */
@Composable
fun HandoffRefusal() {
    val r = refusal.value ?: return
    val graph = LocalGraph.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val t = remember(r.at) { Animatable(0f) }
    LaunchedEffect(r.at) {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        t.animateTo(1f, tween(1900, easing = androidx.compose.animation.core.LinearEasing))
        if (refusal.value?.at == r.at) refusal.value = null
    }
    val p = t.value
    val show = (p / 0.12f).coerceIn(0f, 1f) * (1f - ((p - 0.82f) / 0.18f).coerceIn(0f, 1f))
    // A head-shake: quick at first, settling.
    val shake = sin(p * 46f) * (1f - (p / 0.55f).coerceIn(0f, 1f))
    val crossed = r.kind == Refusal.Kind.UNREACHABLE
    val slash = ((p - 0.18f) / 0.25f).coerceIn(0f, 1f)
    val red = Color(0xFFE5484D)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(top = 28.dp).graphicsLayer { alpha = show },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).graphicsLayer { translationX = shake * 14.dp.toPx() }
                .clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                .border(1.5.dp, (if (crossed) red else Color.White).copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Computer, null, tint = Color.White.copy(alpha = if (crossed) 0.6f else 1f), modifier = Modifier.size(30.dp))
            if (crossed) androidx.compose.foundation.Canvas(Modifier.size(40.dp)) {
                val a = androidx.compose.ui.geometry.Offset(size.width * 0.12f, size.height * 0.12f)
                val b = androidx.compose.ui.geometry.Offset(size.width * 0.88f, size.height * 0.88f)
                drawLine(red, a, a + (b - a) * slash, strokeWidth = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
        Text(
            when (r.kind) {
                Refusal.Kind.UNREACHABLE -> tr(R.string.handoff_offline, graph.lanSync.pairing.value?.name.orEmpty())
                Refusal.Kind.NOTHING_HERE -> tr(R.string.handoff_nothing_here)
                Refusal.Kind.NOTHING_THERE -> tr(R.string.handoff_nothing_there)
            },
            color = Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

private val Lift = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/**
 * The flight drawn over everything when music changes devices: the cover lifts off and flies up
 * to the computer, leaving a trail — or comes down from it and lands with a ripple.
 */
@Composable
fun HandoffOverlay() {
    val graph = LocalGraph.current
    var event by remember { mutableStateOf<HandoffEvent?>(null) }
    val p = remember { Animatable(0f) }
    val shade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        graph.handoff.events.collect { e ->
            event = e
            p.snapTo(0f)
            shade.snapTo(0f)
            launch { shade.animateTo(1f, tween(220)) }
            p.animateTo(1f, tween(if (e is HandoffEvent.Sent) 1250 else 1400, easing = androidx.compose.animation.core.LinearEasing))
            shade.animateTo(0f, tween(380))
            event = null
        }
    }
    val e = event ?: return
    val sent = e is HandoffEvent.Sent
    val name = when (e) { is HandoffEvent.Sent -> e.to; is HandoffEvent.Received -> e.from }
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = shade.value }.background(Color.Black.copy(alpha = 0.45f))) {
        val h = with(LocalDensity.current) { maxHeight.toPx() }
        val t = p.value
        // The computer at the top: it brightens when the cover reaches it (sent) or as it lets go (received).
        val glow = if (sent) ((t - 0.55f) / 0.3f).coerceIn(0f, 1f) * (1f - ((t - 0.85f) / 0.15f).coerceIn(0f, 1f)) else (1f - (t / 0.45f)).coerceIn(0f, 1f)
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
            // Rings going out from the computer while it sends or takes the music.
            for (n in 0 until 2) {
                val ring = ((t * 2.2f + n * 0.5f) % 1f)
                Box(Modifier.size(64.dp).graphicsLayer { val sc = 1f + ring * 0.9f; scaleX = sc; scaleY = sc; alpha = (1f - ring) * 0.5f * (0.3f + glow) }.border(1.5.dp, Color.White, CircleShape))
            }
            Box(
                Modifier.size(64.dp).graphicsLayer { scaleX = 1f + glow * 0.18f; scaleY = scaleX }
                    .clip(CircleShape).background(Color.White.copy(alpha = 0.12f + glow * 0.25f))
                    .border(1.dp, Color.White.copy(alpha = 0.3f + glow * 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Computer, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            }
            Text(
                if (sent) tr(R.string.handoff_playing_on, name) else tr(R.string.handoff_from, name),
                color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp),
            )
        }
        // Effects under the cover: a dotted beam to the computer, sparks trailing the flight, a burst where it lands.
        val accent = LocalAccent.current
        val pathK: (Float) -> Float = if (sent) { x -> Lift.transform(((x - 0.12f) / 0.7f).coerceIn(0f, 1f)) } else { x -> 1f - Lift.transform((x / 0.62f).coerceIn(0f, 1f)) }
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val c = center
            val sideTilt = if (sent) 1f else -1f
            fun at(k: Float) = androidx.compose.ui.geometry.Offset(c.x + sin(k * Math.PI).toFloat() * 18.dp.toPx() * sideTilt, c.y - h * 0.36f * k)
            val top = at(1f)
            // The beam: dots from the cover up to the computer, travelling the way the music goes.
            val beam = (if (sent) (t / 0.3f) else 1f - ((t - 0.5f) / 0.3f)).coerceIn(0f, 1f) * (1f - ((t - 0.85f) / 0.15f).coerceIn(0f, 1f))
            if (beam > 0f) for (i in 0 until 14) {
                val f = ((i / 14f) + t * (if (sent) 1.6f else -1.6f)).mod(1f)
                val p = androidx.compose.ui.geometry.lerp(c, top, f)
                drawCircle(Color.White.copy(alpha = 0.35f * beam * (1f - kotlin.math.abs(f * 2f - 1f))), 2.5.dp.toPx(), p)
            }
            // Sparks: each leaves the cover at its moment and drifts off, fading.
            val rnd = java.util.Random(42)
            for (i in 0 until 36) {
                val born = 0.1f + 0.7f * i / 36f
                val vx = (rnd.nextFloat() - 0.5f) * 90.dp.toPx()
                val vy = (rnd.nextFloat() - 0.2f) * 70.dp.toPx() * (if (sent) 1f else -1f)
                val r = (1.5f + rnd.nextFloat() * 2.5f).dp.toPx()
                val age = (t - born) / 0.28f
                if (age !in 0f..1f) continue
                val from = at(pathK(born))
                val p = androidx.compose.ui.geometry.Offset(from.x + vx * age + (rnd.nextFloat() - 0.5f) * 116.dp.toPx(), from.y + vy * age + (rnd.nextFloat() - 0.5f) * 116.dp.toPx())
                val col = if (i % 3 == 0) accent else Color.White
                // A soft halo, then the spark itself.
                drawCircle(col.copy(alpha = (1f - age) * 0.2f), r * 3f, p)
                drawCircle(col.copy(alpha = (1f - age) * 0.9f), r * (1f - age * 0.6f), p)
            }
            // Where it lands (received): a burst of sparks out of the cover's edges.
            if (!sent) {
                val burst = ((t - 0.6f) / 0.35f).coerceIn(0f, 1f)
                if (burst > 0f) for (i in 0 until 22) {
                    val a = i / 22f * 2 * Math.PI
                    val d = 120.dp.toPx() + burst * 90.dp.toPx() * (0.7f + (i % 4) * 0.12f)
                    drawCircle((if (i % 2 == 0) accent else Color.White).copy(alpha = (1f - burst) * 0.9f), 3.dp.toPx() * (1f - burst * 0.5f),
                        androidx.compose.ui.geometry.Offset(c.x + (kotlin.math.cos(a) * d).toFloat(), c.y + (kotlin.math.sin(a) * d).toFloat()))
                }
            }
        }
        val cover = @Composable { k: Float, a: Float ->
            // k: 0 — resting in the middle, 1 — at the computer.
            val wobble = sin(k * Math.PI).toFloat()
            Box(
                Modifier.align(Alignment.Center).size(232.dp).graphicsLayer {
                    translationY = -h * 0.36f * k
                    translationX = wobble * 18.dp.toPx() * (if (sent) 1f else -1f)
                    val s = 1f - 0.72f * k
                    scaleX = s; scaleY = s
                    rotationZ = (if (sent) -10f else 10f) * wobble
                    alpha = a
                }.shadow((24 * (1f - k)).dp, RoundedCornerShape(22.dp), ambientColor = accent, spotColor = accent).clip(RoundedCornerShape(22.dp)).background(Color(0xFF2A2A30)),
                contentAlignment = Alignment.Center,
            ) {
                if (e.art != null) AsyncImage(e.art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(64.dp))
            }
        }
        if (sent) {
            // A short lift in place, then off to the computer; a fading trail behind it.
            fun k(x: Float) = Lift.transform(((x - 0.12f) / 0.7f).coerceIn(0f, 1f))
            for (lag in listOf(0.12f, 0.06f)) cover(k(t - lag), 0.25f * (1f - k(t - lag)) * (if (t > lag) 1f else 0f))
            cover(k(t), 1f - ((t - 0.75f) / 0.15f).coerceIn(0f, 1f))
        } else {
            // Down from the computer, a little past the middle, back; then a ripple from where it landed.
            val down = Lift.transform((t / 0.62f).coerceIn(0f, 1f))
            val settle = ((t - 0.62f) / 0.2f).coerceIn(0f, 1f)
            val k = (1f - down) - 0.05f * sin(settle * Math.PI).toFloat()
            // Two ripples, the second a beat later and softer.
            for ((lag, strength) in listOf(0f to 0.7f, 0.12f to 0.4f)) {
                val r = ((t - 0.58f - lag) / 0.42f).coerceIn(0f, 1f)
                if (r > 0f) Box(
                    Modifier.align(Alignment.Center).size(232.dp).graphicsLayer { val sc = 1f + r * 0.55f; scaleX = sc; scaleY = sc; alpha = strength * (1f - r) }
                        .border(2.dp, Color.White, RoundedCornerShape(26.dp)),
                )
            }
            cover(k.coerceAtLeast(-0.05f), (down * 3f).coerceAtMost(1f))
        }
    }
}

/** How far a two-finger swipe has gone: −1 … 1 (negative — up, to the computer), 0 when there's none. */
private val swipeProgress = androidx.compose.runtime.mutableFloatStateOf(0f)

/**
 * Two fingers up — send the music to the computer; two fingers down — take it from there. Anywhere
 * in the app. Only a gesture with two fingers is taken (one-finger swipes, scrolling and the player's
 * swipe-down stay as they are); a one-finger drag that a second finger joins is cancelled.
 */
@Composable
fun Modifier.handoffSwipe(): Modifier {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val distance = with(density) { 110.dp.toPx() }
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            var start: Float? = null
            var fired = false
            while (true) {
                val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val down = ev.changes.filter { it.pressed }
                // Once a second finger has come, the whole touch is the gesture's — to the last finger
                // lifted — so nothing under it is pressed, scrolled or dragged.
                if (start != null) ev.changes.forEach { it.consume() }
                if (down.isEmpty()) break
                if (start == null && (down.size < 2 || graph.lanSync.pairing.value == null)) continue
                ev.changes.forEach { it.consume() }
                if (down.size < 2) continue
                val y = down.take(2).map { it.position.y }.average().toFloat()
                val from = start ?: y.also { start = it }
                if (fired) continue
                val p = ((y - from) / distance).coerceIn(-1f, 1f)
                swipeProgress.floatValue = p
                if (kotlin.math.abs(p) >= 1f) {
                    fired = true
                    swipeProgress.floatValue = 0f
                    val up = p < 0
                    scope.launch {
                        when {
                            !graph.handoff.reachable.value -> {
                                refusal.value = Refusal(Refusal.Kind.UNREACHABLE, System.nanoTime())
                                graph.lanServer.helloNow()
                            }
                            up -> sendToComputer(graph, nav, fromGesture = true)
                            else -> takeFromComputer(graph, nav)
                        }
                    }
                }
            }
            swipeProgress.floatValue = 0f
        }
    }
}

/** While two fingers swipe: an arrow and where the music will go, growing as the swipe nears the goal. */
@Composable
fun HandoffSwipeHint() {
    val p = swipeProgress.floatValue
    if (p == 0f) return
    val up = p < 0
    val k = kotlin.math.abs(p)
    Box(Modifier.fillMaxSize(), contentAlignment = if (up) Alignment.TopCenter else Alignment.BottomCenter) {
        androidx.compose.foundation.layout.Row(
            Modifier.statusBarsPadding().padding(vertical = 64.dp)
                .graphicsLayer { alpha = 0.35f + 0.65f * k; translationY = (if (up) -1 else 1) * k * 24.dp.toPx(); scaleX = 0.9f + 0.1f * k; scaleY = scaleX }
                .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f))
                .border(1.dp, Color.White.copy(alpha = 0.2f + 0.6f * k), RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Computer, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Text(
                (if (up) "↑  " else "↓  ") + tr(if (up) R.string.handoff_swipe_up else R.string.handoff_swipe_down),
                color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
