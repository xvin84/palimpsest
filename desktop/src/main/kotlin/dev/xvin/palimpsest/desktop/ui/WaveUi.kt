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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.Autorenew
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
    val w = graph.settings.value.wave
    val needsYandex = station != null || w.source == dev.xvin.palimpsest.core.radio.StationSpec.YANDEX
    if (needsYandex && !graph.yandex.isReady) { nav.toast(t("Нужно войти в Яндекс Музыку (Сервисы)", "Sign in to Yandex Music first (Services)")); return }
    graph.scope.launch {
        runCatching {
            if (station != null) graph.player.startRadio(station.seed, station.name)
            else {
                // YouTube starts from a few of your own songs: liked ones, or the most played.
                val favourites = if (w.source == dev.xvin.palimpsest.core.radio.Waves.YOUTUBE || w.source == dev.xvin.palimpsest.core.radio.Waves.MIX) {
                    val lib = graph.library
                    val liked = lib.liked.value.mapNotNull(lib::get)
                    val pool = liked.ifEmpty { graph.history.plays.value.groupingBy { it.trackId }.eachCount().entries.sortedByDescending { it.value }.take(50).mapNotNull { lib.get(it.key) } }
                    pool.shuffled().take(6).map { tr -> if (tr.service == dev.xvin.palimpsest.core.model.Service.YOUTUBE && tr.remoteId != null) "yt:${tr.remoteId}" else "q:${tr.artist} ${tr.title}" }
                } else emptyList()
                graph.player.startRadio(dev.xvin.palimpsest.core.radio.Waves.spec(w.source, t("Моя волна", "My Wave"), w.seeds(), favourites, w.bandcampTag, w.shares))
            }
        }.onFailure { withContext(Dispatchers.Main) { nav.toast(t("Волна не запустилась: ", "My Wave didn’t start: ") + (it.message ?: it.javaClass.simpleName)) } }
    }
}

private val BANDCAMP_TAGS = listOf("ambient", "electronic", "experimental", "jazz", "rock", "metal", "hip-hop-rap", "lo-fi", "folk", "indie", "synthwave", "classical")

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
        Spacer(Modifier.height(16.dp))
        Caption(title)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, label) -> Pill(label, value == v, { onPick(v) }) }
        }
    }

    PDialog(
        t("Моя волна", "My Wave"), onDismiss, width = 600.dp,
        subtitle = t("Что выберешь, то и будет в волне — с этого запуска и дальше.", "What you pick goes into the wave, from this start on."),
        buttons = {
            PButton(t("Готово", "Done"), onDismiss, kind = Kind.GHOST)
            PButton(t("Запустить волну", "Start the wave"), { startMyWave(graph, nav); onDismiss() }, icon = Icons.Rounded.PlayArrow, kind = Kind.PRIMARY)
        },
    ) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Group(t("Источник", "Source"), w.source, listOf(
                dev.xvin.palimpsest.core.radio.StationSpec.YANDEX to t("Яндекс", "Yandex"),
                dev.xvin.palimpsest.core.radio.Waves.YOUTUBE to "YouTube Music",
                dev.xvin.palimpsest.core.radio.Waves.BANDCAMP to "Bandcamp",
                dev.xvin.palimpsest.core.radio.Waves.MIX to t("Смешанная", "Mixed"),
            )) { set(w.copy(source = it)) }
            Spacer(Modifier.height(6.dp))
            Text(
                when (w.source) {
                    dev.xvin.palimpsest.core.radio.Waves.YOUTUBE -> t("Бесконечный микс YouTube Music от нескольких твоих любимых песен. Из России нужен прокси (Настройки → Сеть).", "YouTube Music’s endless mix, started from a few of your favourite songs. From Russia it needs the proxy (Settings → Network).")
                    dev.xvin.palimpsest.core.radio.Waves.BANDCAMP -> t("Независимые артисты: по песне из лучших альбомов жанра на Bandcamp. Из России нужен прокси.", "Independent artists: a song from each of the genre’s top albums on Bandcamp. From Russia it needs the proxy.")
                    dev.xvin.palimpsest.core.radio.Waves.MIX -> t("Несколько волн в одной, в долях, которые выставишь.", "Several waves as one, in the shares you set.")
                    else -> t("Волна Яндекс Музыки, подстроенная под то, что ты там слушаешь и лайкаешь.", "Yandex Music’s own wave, tuned by what you like there.")
                },
                style = MaterialTheme.typography.bodySmall, color = LocalPal.current.muted,
            )
            if (w.source == dev.xvin.palimpsest.core.radio.Waves.BANDCAMP || (w.source == dev.xvin.palimpsest.core.radio.Waves.MIX && w.mixBandcamp > 0)) {
                Group(t("Жанр", "Genre"), w.bandcampTag, BANDCAMP_TAGS.map { it to it }) { set(w.copy(bandcampTag = it)) }
            }
            if (w.source == dev.xvin.palimpsest.core.radio.Waves.MIX) {
                val zero = listOf("0" to "—", "1" to "1", "2" to "2", "3" to "3")
                Group(t("Мои файлы", "My files"), "${w.mixLocal}", zero) { set(w.copy(mixLocal = it.toInt())) }
                Group(t("Яндекс", "Yandex"), "${w.mixYandex}", zero) { set(w.copy(mixYandex = it.toInt())) }
                Group("YouTube Music", "${w.mixYouTube}", zero) { set(w.copy(mixYouTube = it.toInt())) }
                Group("Bandcamp", "${w.mixBandcamp}", zero) { set(w.copy(mixBandcamp = it.toInt())) }
            }
            if (w.source == dev.xvin.palimpsest.core.radio.StationSpec.YANDEX || w.source == dev.xvin.palimpsest.core.radio.Waves.MIX) {
            Group(t("Характер", "Character"), w.diversity, listOf("" to t("Любой", "Any"), "favorite" to t("Любимое", "Favourite"), "discover" to t("Незнакомое", "Unfamiliar"), "popular" to t("Популярное", "Popular"))) { set(w.copy(diversity = it)) }
            Group(t("Настроение", "Mood"), w.mood, listOf("" to t("Любое", "Any"), "active" to t("Бодрое", "Upbeat"), "fun" to t("Весёлое", "Fun"), "calm" to t("Спокойное", "Calm"), "sad" to t("Грустное", "Sad"))) { set(w.copy(mood = it)) }
            Group(t("Язык", "Language"), w.language, listOf("" to t("Любой", "Any"), "russian" to t("Русский", "Russian"), "not-russian" to t("Иностранный", "Foreign"), "without-words" to t("Без слов", "Instrumental"))) { set(w.copy(language = it)) }
            }
            val list = stations
            if (!list.isNullOrEmpty()) {
                Spacer(Modifier.height(20.dp))
                Caption(t("Занятия и настроения — запускаются сразу", "Activities and moods — start right away"))
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.take(40).forEach { st -> Pill(st.name, false, { startMyWave(graph, nav, st); onDismiss() }) }
                }
            }
        }
    }
}

/**
 * «Встряхнуть волну»: a new session that avoids everything already heard, from its first track
 * now — for when the station keeps circling the same few songs. Spins while it works.
 */
@Composable
fun ShakeWaveButton(size: androidx.compose.ui.unit.Dp = 22.dp, iconSize: androidx.compose.ui.unit.Dp = 14.dp, tint: androidx.compose.ui.graphics.Color? = null) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val shaking by graph.player.shaking.collectAsState()
    val turn = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(shaking) {
        if (shaking) turn.animateTo(turn.value + 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.LinearEasing)))
        else turn.animateTo(((turn.value / 360f).toInt() + 1) * 360f, androidx.compose.animation.core.tween(300))
    }
    PIconButton(
        androidx.compose.material.icons.Icons.Rounded.Autorenew, t("Встряхнуть волну", "Shake up the wave"),
        {
            if (!shaking) graph.scope.launch {
                runCatching { graph.player.shakeRadio() }
                    .onSuccess { withContext(Dispatchers.Main) { nav.toast(t("Встряхнул — дальше свежие треки", "Shook it up — fresh tracks ahead")) } }
                    .onFailure { withContext(Dispatchers.Main) { nav.toast(t("Не вышло встряхнуть: ", "Couldn’t shake it up: ") + (it.message ?: it.javaClass.simpleName)) } }
            }
        },
        size = size, iconSize = iconSize, tint = tint,
        iconModifier = Modifier.graphicsLayer { rotationZ = turn.value },
    )
}
