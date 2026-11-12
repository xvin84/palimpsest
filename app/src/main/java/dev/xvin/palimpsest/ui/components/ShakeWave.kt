package dev.xvin.palimpsest.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
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

/**
 * «Not this» while a wave plays: the song is out of every wave for good and the next one starts;
 * the note under it offers to leave out the whole artist. Settings → My Wave lists them to bring back.
 */
@Composable
fun NotThisButton(tint: Color, modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val station by graph.radio.station.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (station == null) return
    IconButton(
        onClick = {
            val np = graph.player.state.value.nowPlaying ?: return@IconButton
            val id = np.trackId ?: return@IconButton
            scope.launch {
                graph.db.wave().ban(dev.xvin.palimpsest.data.db.WaveBanEntity("t:$id", "${np.artist} — ${np.title}"))
                graph.player.next()
                val artist = dev.xvin.palimpsest.core.library.ArtistNames.split(np.artist).firstOrNull() ?: np.artist
                nav.toast(tr(R.string.wave_not_this_done, np.title), tr(R.string.wave_not_artist_too, artist)) {
                    scope.launch { graph.db.wave().ban(dev.xvin.palimpsest.data.db.WaveBanEntity("a:${artist.lowercase()}", artist)) }
                }
            }
        },
        modifier = modifier,
    ) { Icon(Icons.Rounded.ThumbDownOffAlt, tr(R.string.wave_not_this), tint = tint) }
}

/** Why the own-files wave picked [trackId], in words — null for other waves and songs. */
fun waveReason(graph: dev.xvin.palimpsest.AppGraph, trackId: Long?): String? {
    val r = trackId?.let { graph.localWave?.reasonFor(it.toString()) } ?: return null
    return tr(
        when (r) {
            dev.xvin.palimpsest.core.radio.WaveReason.FAVOURITE -> R.string.why_favourite
            dev.xvin.palimpsest.core.radio.WaveReason.ARTIST_YOU_LOVE -> R.string.why_artist
            dev.xvin.palimpsest.core.radio.WaveReason.NEW_TO_YOU -> R.string.why_new
            dev.xvin.palimpsest.core.radio.WaveReason.LONG_AGO -> R.string.why_long_ago
            dev.xvin.palimpsest.core.radio.WaveReason.OFTEN -> R.string.why_often
            dev.xvin.palimpsest.core.radio.WaveReason.FLOWS_ON -> R.string.why_flows
        },
    )
}
