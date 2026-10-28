package dev.xvin.palimpsest.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.xvin.palimpsest.desktop.data.Graph
import dev.xvin.palimpsest.desktop.data.HandoffEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin

/**
 * The phone in the player bar, while a paired phone has Palimpsest open: send what plays to it, or
 * take what plays there. The phone says hello every minute, so it shows up within one.
 */
@Composable
fun HandoffButton() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val phone by graph.handoff.phone.collectAsState()
    val busy by graph.handoff.busy.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val p = phone?.takeIf { it.at > 0 } ?: return
    Box {
        PIconButton(Icons.Rounded.Smartphone, t("Музыка на телефон и обратно", "Music to the phone and back"), { if (!busy) menu = true }, iconSize = 18.dp, active = busy)
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Column { Text(t("Передать на телефон", "Send to the phone")); Text(p.device, style = Type.meta, color = LocalPal.current.muted) } },
                onClick = { menu = false; sendToPhone(graph, nav) },
            )
            DropdownMenuItem(text = { Text(t("Забрать с телефона", "Take from the phone")) }, onClick = { menu = false; takeFromPhone(graph, nav) })
        }
    }
}

fun sendToPhone(graph: Graph, nav: Nav) {
    graph.scope.launch {
        graph.handoff.sendToPhone().onFailure { withContext(Dispatchers.Main) { nav.toast(handoffError(it)) } }
    }
}

fun takeFromPhone(graph: Graph, nav: Nav) {
    graph.scope.launch {
        graph.handoff.pullFromPhone().onFailure { withContext(Dispatchers.Main) { nav.toast(handoffError(it)) } }
    }
}

private fun handoffError(e: Throwable) = when {
    e is java.io.IOException -> t("Телефон не отвечает — открой Palimpsest на телефоне", "The phone doesn’t answer — open Palimpsest on the phone")
    e.message == "nothing" || e.message?.startsWith("nothing is playing") == true -> t("Там ничего не играет", "Nothing is playing there")
    else -> t("Не получилось: ", "Didn’t work: ") + (e.message ?: e.javaClass.simpleName)
}

/**
 * The flight when music changes devices, drawn over the whole window: the cover leaves its place
 * (the bar's, or the big player's) for the phone in the top-right corner with a fading trail — or
 * comes out of the phone, lands on its place and sends a ripple.
 */
@Composable
fun HandoffFlight() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val spots = LocalCoverSpots.current
    var event by remember { mutableStateOf<HandoffEvent?>(null) }
    var model by remember { mutableStateOf<Any?>(null) }
    var home by remember { mutableStateOf<Rect?>(null) }
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        graph.handoff.events.collect { e ->
            model = graph.covers.model(e.track)
            home = (if (nav.nowPlaying) spots.big[nav.bigLyrics]?.first else null) ?: spots.bar
            event = e
            p.snapTo(0f)
            p.animateTo(1f, tween(if (e is HandoffEvent.Sent) 1150 else 1300, easing = LinearEasing))
            event = null
        }
    }
    val e = event ?: return
    val from = home ?: return
    val sent = e is HandoffEvent.Sent
    val name = when (e) { is HandoffEvent.Sent -> e.to; is HandoffEvent.Received -> e.from }
    val pal = LocalPal.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current
        val w = with(d) { maxWidth.toPx() }
        val t = p.value
        // The phone, top right: it fades in, brightens as the cover reaches it (or leaves it), fades out.
        val show = (t / 0.15f).coerceIn(0f, 1f) * (1f - ((t - 0.85f) / 0.15f).coerceIn(0f, 1f))
        val glow = if (sent) ((t - 0.55f) / 0.25f).coerceIn(0f, 1f) else (1f - t / 0.35f).coerceIn(0f, 1f)
        val phoneW = with(d) { 54.dp.toPx() }; val phoneH = with(d) { 96.dp.toPx() }
        val phoneX = w - with(d) { 40.dp.toPx() } - phoneW; val phoneY = with(d) { 40.dp.toPx() }
        Box(
            Modifier.offset { androidx.compose.ui.unit.IntOffset(phoneX.toInt(), phoneY.toInt()) }.size(54.dp, 96.dp)
                .graphicsLayer { alpha = show; scaleX = 1f + glow * 0.12f; scaleY = scaleX }
                .shadow((8 + 16 * glow).dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp)).background(pal.paper2)
                .border(1.5.dp, androidx.compose.ui.graphics.lerp(pal.line, LocalAccent.current, glow), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Smartphone, null, tint = androidx.compose.ui.graphics.lerp(pal.muted, LocalAccent.current, glow), modifier = Modifier.size(22.dp)) }
        Text(
            if (sent) t("Играет на «$name»", "Playing on “$name”") else t("С «$name»", "From “$name”"),
            style = Type.meta, color = pal.text,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 144.dp, end = 24.dp).graphicsLayer { alpha = show },
        )
        // The cover: from its place to the phone's screen (sent), or back (received).
        val target = Rect(phoneX + phoneW * 0.15f, phoneY + phoneH * 0.3f, phoneX + phoneW * 0.85f, phoneY + phoneH * 0.3f + phoneW * 0.7f)
        fun at(k: Float): Rect {
            val r = Rect(
                from.left + (target.left - from.left) * k, from.top + (target.top - from.top) * k,
                from.right + (target.right - from.right) * k, from.bottom + (target.bottom - from.bottom) * k,
            )
            // The bar's cover is small: it swells on the way, so the flight reads from across the room.
            val grow = 1f + 1.6f * sin(k * Math.PI).toFloat() * (with(d) { 120.dp.toPx() } / maxOf(from.width, 1f)).coerceAtMost(1.5f).coerceAtLeast(0f)
            val c = r.center
            return Rect(c.x - r.width * grow / 2, c.y - r.height * grow / 2, c.x + r.width * grow / 2, c.y + r.height * grow / 2)
        }
        @Composable
        fun copy(k: Float, alpha: Float) {
            val r = at(k)
            val arc = sin(k * Math.PI).toFloat()
            Box(
                Modifier.graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = r.left
                    // An arc, not a straight line: it rises a little on the way.
                    translationY = r.top - arc * 60.dp.toPx()
                    rotationZ = (if (sent) 8f else -8f) * arc
                    this.alpha = alpha
                }.size(with(d) { r.width.toDp() }, with(d) { r.height.toDp() }).shadow((20 * arc).dp, RoundedCornerShape(Radius.sm)).clip(RoundedCornerShape(Radius.sm)),
            ) { Artwork(model, e.track?.album ?: e.track?.title ?: "?", Modifier.fillMaxSize(), 0.dp, artist = e.track?.artist) }
        }
        val ease = Emphasized
        if (sent) {
            fun k(x: Float) = ease.transform(((x - 0.08f) / 0.7f).coerceIn(0f, 1f))
            for (lag in listOf(0.1f, 0.05f)) if (t > lag + 0.08f) copy(k(t - lag), 0.22f * (1f - k(t - lag)))
            copy(k(t), 1f - ((t - 0.72f) / 0.12f).coerceIn(0f, 1f))
        } else {
            val k = 1f - ease.transform((t / 0.7f).coerceIn(0f, 1f))
            val ring = ((t - 0.66f) / 0.34f).coerceIn(0f, 1f)
            if (ring > 0f) {
                val grow = 1f + ring * 0.6f
                Box(
                    Modifier.graphicsLayer {
                        translationX = from.center.x - from.width * grow / 2; translationY = from.center.y - from.height * grow / 2
                        transformOrigin = TransformOrigin(0f, 0f); scaleX = grow; scaleY = grow; alpha = 0.8f * (1f - ring)
                    }.size(with(d) { from.width.toDp() }, with(d) { from.height.toDp() }).border(2.dp, LocalAccent.current, RoundedCornerShape(Radius.sm)),
                )
            }
            copy(k, ((t / 0.12f).coerceIn(0f, 1f)) * (1f - ((t - 0.85f) / 0.15f).coerceIn(0f, 1f)))
        }
    }
}
