package dev.xvin.palimpsest.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.core.model.WaveStation
import dev.xvin.palimpsest.data.settings.WavePrefs
import dev.xvin.palimpsest.playback.RadioController
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.Pill
import dev.xvin.palimpsest.ui.theme.Ink
import kotlinx.coroutines.launch
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr

/** «Настроить волну»: character, mood, language — plus Yandex's ready-made stations. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WaveTuningSheet(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val s by graph.settings.flow.collectAsStateWithLifecycle(null)
    val w = s?.wave ?: WavePrefs()
    val stations by produceState<List<WaveStation>>(emptyList()) { value = runCatching { graph.remote.waveStations() }.getOrDefault(emptyList()) }

    fun set(n: WavePrefs) = scope.launch { graph.settings.setWave(n) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.Paper2) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            Text(tr(R.string.my_wave), style = MaterialTheme.typography.headlineSmall)
            Text(tr(R.string.wave_settings_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
            // Where the wave comes from; Yandex's tuning only matters when Yandex is part of it.
            Group(tr(R.string.wave_source), w.source, listOf(
                dev.xvin.palimpsest.core.radio.StationSpec.YANDEX to tr(R.string.wave_src_yandex),
                dev.xvin.palimpsest.core.radio.Waves.YOUTUBE to "YouTube Music",
                dev.xvin.palimpsest.core.radio.Waves.BANDCAMP to "Bandcamp",
                dev.xvin.palimpsest.core.radio.Waves.MIX to tr(R.string.wave_src_mix),
            )) { set(w.copy(source = it)) }
            Text(
                when (w.source) {
                    dev.xvin.palimpsest.core.radio.Waves.YOUTUBE -> tr(R.string.wave_src_youtube_hint)
                    dev.xvin.palimpsest.core.radio.Waves.BANDCAMP -> tr(R.string.wave_src_bandcamp_hint)
                    dev.xvin.palimpsest.core.radio.Waves.MIX -> tr(R.string.wave_src_mix_hint)
                    else -> tr(R.string.wave_src_yandex_hint)
                },
                style = MaterialTheme.typography.bodySmall, color = Ink.Muted, modifier = Modifier.padding(top = 6.dp),
            )
            if (w.source == dev.xvin.palimpsest.core.radio.Waves.BANDCAMP || (w.source == dev.xvin.palimpsest.core.radio.Waves.MIX && w.mixBandcamp > 0)) {
                Group(tr(R.string.wave_genre), w.bandcampTag, BANDCAMP_TAGS.map { it to it }) { set(w.copy(bandcampTag = it)) }
            }
            if (w.source == dev.xvin.palimpsest.core.radio.Waves.MIX) {
                Spacer(Modifier.height(16.dp))
                Text(tr(R.string.wave_shares).uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                Share(tr(R.string.wave_share_local), w.mixLocal) { set(w.copy(mixLocal = it)) }
                Share(tr(R.string.wave_src_yandex), w.mixYandex) { set(w.copy(mixYandex = it)) }
                Share("YouTube Music", w.mixYouTube) { set(w.copy(mixYouTube = it)) }
                Share("Bandcamp", w.mixBandcamp) { set(w.copy(mixBandcamp = it)) }
            }
            if (w.source == dev.xvin.palimpsest.core.radio.StationSpec.YANDEX || w.source == dev.xvin.palimpsest.core.radio.Waves.MIX) {
            Group(tr(R.string.wave_character), w.diversity, listOf("" to tr(R.string.wave_any_m), "favorite" to tr(R.string.favorite), "discover" to tr(R.string.wave_discover), "popular" to tr(R.string.artist_popular))) { set(w.copy(diversity = it)) }
            Group(tr(R.string.wave_mood), w.mood, listOf("" to tr(R.string.wave_any_n), "active" to tr(R.string.wave_mood_active), "fun" to tr(R.string.wave_mood_fun), "calm" to tr(R.string.wave_mood_calm), "sad" to tr(R.string.wave_mood_sad))) { set(w.copy(mood = it)) }
            Group(tr(R.string.language), w.language, listOf("" to tr(R.string.wave_any_m), "russian" to tr(R.string.wave_lang_russian), "not-russian" to tr(R.string.wave_lang_foreign), "without-words" to tr(R.string.wave_lang_instrumental))) { set(w.copy(language = it)) }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                // The sheet closes right away, so its own scope would cancel the start mid-request.
                graph.mainScope.launch {
                    graph.startMyWave().onSuccess { nav.openPlayer() }.onFailure { nav.error(it, tr(R.string.my_wave)) }
                }
                onDismiss()
            }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.wave_start)) }
            if (stations.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(tr(R.string.wave_stations), style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stations.take(40).forEach { st ->
                        Pill(st.name, false, {
                            graph.mainScope.launch {
                                graph.radio.start(st.seed, st.name).onSuccess { nav.openPlayer() }.onFailure { nav.error(it, st.name) }
                            }
                            onDismiss()
                        })
                    }
                }
            }
        }
    }
}

private val BANDCAMP_TAGS = listOf("ambient", "electronic", "experimental", "jazz", "rock", "metal", "hip-hop-rap", "lo-fi", "folk", "indie", "synthwave", "classical")

/** One part of the mixed wave: how big its share is, 0 (off) to 3. */
@Composable
private fun Share(label: String, value: Int, onChange: (Int) -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        (0..3).forEach { n ->
            val on = n == value
            androidx.compose.foundation.layout.Box(
                Modifier.padding(start = 6.dp).size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (on) Ink.Text else Ink.Paper3).clickable { onChange(n) },
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { Text(if (n == 0) "—" else "$n", color = if (on) Ink.Paper else Ink.Text, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> Pill(label, value == v, { onPick(v) }) }
    }
}
