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
            Text("Моя волна", style = MaterialTheme.typography.headlineSmall)
            Text("Настройки применяются при следующем запуске волны", style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
            Group("Характер", w.diversity, listOf("" to "Любой", "favorite" to "Любимое", "discover" to "Незнакомое", "popular" to "Популярное")) { set(w.copy(diversity = it)) }
            Group("Настроение", w.mood, listOf("" to "Любое", "active" to "Бодрое", "fun" to "Весёлое", "calm" to "Спокойное", "sad" to "Грустное")) { set(w.copy(mood = it)) }
            Group("Язык", w.language, listOf("" to "Любой", "russian" to "Русский", "not-russian" to "Иностранный", "without-words" to "Без слов")) { set(w.copy(language = it)) }
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                // The sheet closes right away, so its own scope would cancel the start mid-request.
                graph.mainScope.launch {
                    graph.radio.start(RadioController.MY_WAVE, "Моя волна", w.seeds())
                        .onSuccess { nav.openPlayer() }.onFailure { nav.error(it, "Моя волна") }
                }
                onDismiss()
            }, modifier = Modifier.fillMaxWidth()) { Text("Запустить волну") }
            if (stations.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("ЗАНЯТИЯ И НАСТРОЕНИЯ", style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
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
