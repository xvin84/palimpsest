package dev.xvin.palimpsest.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.ui.LocalNav
import kotlinx.coroutines.launch

/**
 * «Встряхнуть волну»: shown only while a radio plays. Starts a new session that avoids everything
 * already heard and jumps to it — for when the station gets stuck on the same few songs.
 */
@Composable
fun ShakeWaveButton(tint: Color, modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val station by graph.radio.station.collectAsStateWithLifecycle()
    val shaking by graph.radio.shaking.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val turn = remember { Animatable(0f) }
    LaunchedEffect(shaking) {
        if (shaking) turn.animateTo(turn.value + 360f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Restart))
        else turn.animateTo(((turn.value / 360f).toInt() + 1) * 360f, tween(300))
    }
    if (station == null) return
    IconButton(
        onClick = {
            scope.launch {
                graph.radio.shake()
                    .onSuccess { nav.toast(tr(R.string.wave_shaken)) }
                    .onFailure { nav.error(it, tr(R.string.wave_shake)) }
            }
        },
        enabled = !shaking,
        modifier = modifier,
    ) {
        Icon(Icons.Rounded.Autorenew, tr(R.string.wave_shake), tint = tint, modifier = Modifier.graphicsLayer { rotationZ = turn.value })
    }
}
