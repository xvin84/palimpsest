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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.playback.HandoffEvent
import dev.xvin.palimpsest.ui.LocalNav
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
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val p = pairing ?: return
    Box {
        IconButton(onClick = { menu = true }, enabled = !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Computer, tr(R.string.handoff_ctx), tint = tint)
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

suspend fun sendToComputer(graph: dev.xvin.palimpsest.AppGraph, nav: dev.xvin.palimpsest.ui.AppNav) {
    graph.handoff.send()
        .onSuccess { nav.toast(tr(R.string.handoff_playing_on, graph.lanSync.pairing.value?.name.orEmpty()), tr(R.string.handoff_take_back)) { graph.appScope.launch { takeFromComputer(graph, nav) } } }
        .onFailure { nav.error(it, tr(R.string.handoff_ctx)) }
}

suspend fun takeFromComputer(graph: dev.xvin.palimpsest.AppGraph, nav: dev.xvin.palimpsest.ui.AppNav) {
    graph.handoff.pull().onFailure { nav.error(it, tr(R.string.handoff_ctx)) }
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
            Box(
                Modifier.size(64.dp).graphicsLayer { scaleX = 1f + glow * 0.18f; scaleY = scaleX }
                    .clip(CircleShape).background(Color.White.copy(alpha = 0.12f + glow * 0.25f))
                    .border(1.dp, Color.White.copy(alpha = 0.3f + glow * 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Computer, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            Text(
                if (sent) tr(R.string.handoff_playing_on, name) else tr(R.string.handoff_from, name),
                color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp),
            )
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
                }.shadow((24 * (1f - k)).dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Color(0xFF2A2A30)),
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
            val ring = ((t - 0.58f) / 0.42f).coerceIn(0f, 1f)
            if (ring > 0f) Box(
                Modifier.align(Alignment.Center).size(232.dp).graphicsLayer { val s = 1f + ring * 0.55f; scaleX = s; scaleY = s; alpha = 0.7f * (1f - ring) }
                    .border(2.dp, Color.White, RoundedCornerShape(26.dp)),
            )
            cover(k.coerceAtLeast(-0.05f), (down * 3f).coerceAtMost(1f))
        }
    }
}
