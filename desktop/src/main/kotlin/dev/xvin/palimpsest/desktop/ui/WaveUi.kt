package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xvin.palimpsest.core.model.WaveStation
import dev.xvin.palimpsest.desktop.data.Graph
import dev.xvin.palimpsest.desktop.data.WavePrefs
import dev.xvin.palimpsest.desktop.playback.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** «Моя волна» with the tuning from its settings; says what to do when Yandex isn't signed in. */
fun startMyWave(graph: Graph, nav: Nav, station: WaveStation? = null) {
    if (!graph.yandex.isReady) { nav.toast(t("Нужно войти в Яндекс Музыку (Сервисы)", "Sign in to Yandex Music first (Services)")); return }
    graph.scope.launch {
        runCatching {
            if (station != null) graph.player.startRadio(station.seed, station.name)
            else graph.player.startRadio(Player.MY_WAVE, t("Моя волна", "My Wave"), graph.settings.value.wave.seeds())
        }.onFailure { withContext(Dispatchers.Main) { nav.toast(t("Волна не запустилась: ", "My Wave didn’t start: ") + (it.message ?: it.javaClass.simpleName)) } }
    }
}

/** «Настроить волну»: character, mood, language — as on the phone — plus Yandex's ready-made stations. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaveTuningDialog(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val settings by graph.settings.state.collectAsState()
    val w = settings.wave
    val stations by produceState<List<WaveStation>?>(null) {
        value = if (graph.yandex.isReady) withContext(Dispatchers.IO) { runCatching { graph.yandex.waveStations() }.getOrDefault(emptyList()) } else emptyList()
    }
    fun set(n: WavePrefs) = graph.settings.update { it.copy(wave = n) }

    @Composable
    fun Group(title: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
        Spacer(Modifier.height(14.dp))
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = pal.muted)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, label) -> Pill(label, value == v, { onPick(v) }) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 520.dp, max = 680.dp),
        title = { Text(t("Моя волна", "My Wave")) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text(t("Что выберешь, то и будет в волне — с этого запуска и дальше.", "What you pick goes into the wave, from this start on."), color = pal.muted, fontSize = 13.sp)
                Group(t("Характер", "Character"), w.diversity, listOf("" to t("Любой", "Any"), "favorite" to t("Любимое", "Favourite"), "discover" to t("Незнакомое", "Unfamiliar"), "popular" to t("Популярное", "Popular"))) { set(w.copy(diversity = it)) }
                Group(t("Настроение", "Mood"), w.mood, listOf("" to t("Любое", "Any"), "active" to t("Бодрое", "Upbeat"), "fun" to t("Весёлое", "Fun"), "calm" to t("Спокойное", "Calm"), "sad" to t("Грустное", "Sad"))) { set(w.copy(mood = it)) }
                Group(t("Язык", "Language"), w.language, listOf("" to t("Любой", "Any"), "russian" to t("Русский", "Russian"), "not-russian" to t("Иностранный", "Foreign"), "without-words" to t("Без слов", "Instrumental"))) { set(w.copy(language = it)) }
                val list = stations
                if (!list.isNullOrEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text(t("Занятия и настроения".uppercase(), "Activities and moods".uppercase()), style = MaterialTheme.typography.labelSmall, color = pal.muted)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.take(40).forEach { st -> Pill(st.name, false, { startMyWave(graph, nav, st); onDismiss() }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { startMyWave(graph, nav); onDismiss() }) { Text(t("Запустить волну", "Start the wave")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Готово", "Done")) } },
    )
}
