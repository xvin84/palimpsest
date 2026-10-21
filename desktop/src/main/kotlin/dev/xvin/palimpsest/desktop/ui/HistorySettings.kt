package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xvin.palimpsest.core.dsp.EqProfile
import dev.xvin.palimpsest.core.model.Quality
import dev.xvin.palimpsest.desktop.data.DesktopStyle
import dev.xvin.palimpsest.desktop.data.DesktopTheme
import dev.xvin.palimpsest.desktop.data.Rg
import dev.xvin.palimpsest.desktop.playback.AudioEngine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun HistoryScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val accent = LocalAccent.current
    val plays by graph.history.plays.collectAsState()
    var days by remember { mutableStateOf(30) }
    val since = System.currentTimeMillis() - days * 86_400_000L
    val recent = plays.filter { it.at >= since }
    val top = recent.groupBy { it.trackId }.map { (id, p) -> id to p }.sortedByDescending { it.second.size }.take(15)
    val artists = recent.flatMap { p -> dev.xvin.palimpsest.core.library.ArtistNames.split(p.artist).map { it to p.listenedMs } }
        .groupBy({ it.first }, { it.second }).map { it.key to it.value.sum() }.sortedByDescending { it.second }.take(10)
    val fmtDate = remember { SimpleDateFormat("d MMM, HH:mm") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        item { Text(t("История", "History"), style = MaterialTheme.typography.displayMedium) }
        item {
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to t("Неделя", "Week"), 30 to t("Месяц", "Month"), 365 to t("Год", "Year"), 100_000 to t("Всё время", "All time")).forEach { (d, l) -> Pill(l, days == d, { days = d }) }
            }
        }
        item {
            Row {
                Column(Modifier.weight(1f)) { Text(fmt(recent.sumOf { it.listenedMs }), style = MaterialTheme.typography.displayMedium, color = accent); Text(t("музыки", "of music"), color = pal.muted) }
                Column(Modifier.weight(1f)) { Text("${recent.size}", style = MaterialTheme.typography.displayMedium); Text(t("прослушиваний", "plays"), color = pal.muted) }
            }
        }
        if (top.isNotEmpty()) {
            item { SectionHeader(t("Чаще всего", "Most played")) }
            itemsIndexed(top) { i, (id, p) ->
                Row(Modifier.fillMaxWidth().clickable {
                    val tracks = top.mapNotNull { graph.library.get(it.first) }
                    graph.library.get(id)?.let { tr -> graph.player.play(tracks, tracks.indexOf(tr).coerceAtLeast(0)) }
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = if (i < 3) accent else pal.faint, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.width(40.dp))
                    Text("${p.first().title} — ${p.first().artist}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${p.size}×", color = pal.muted)
                }
            }
        }
        if (artists.isNotEmpty()) {
            item { SectionHeader(t("Исполнители", "Artists")) }
            items(artists) { (a, ms) ->
                Row(Modifier.fillMaxWidth().clickable { nav.go(Screen.Artist(a)) }.padding(vertical = 6.dp)) { Text(a, Modifier.weight(1f)); Text(fmt(ms), color = pal.muted) }
            }
        }
        item { SectionHeader(t("Недавно", "Recently")) }
        items(plays.takeLast(100).reversed()) { p ->
            Row(Modifier.fillMaxWidth().clickable { graph.library.get(p.trackId)?.let { graph.player.play(listOf(it)) } }.padding(vertical = 5.dp)) {
                Column(Modifier.weight(1f)) { Text(p.title, maxLines = 1); Text(p.artist, color = pal.muted, fontSize = 12.sp, maxLines = 1) }
                Text(fmtDate.format(Date(p.at)), color = pal.faint, fontSize = 12.sp)
            }
        }
        if (plays.isEmpty()) item { EmptyState(t("Пока пусто", "Nothing yet"), t("Трек попадает в историю после 30 секунд прослушивания.", "A track counts after 30 seconds of listening.")) }
    }
}

@Composable
private fun Setting(title: String, hint: String?, content: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().widthIn(max = 900.dp).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        hint?.let { Text(it, color = LocalPal.current.muted, fontSize = 13.sp) }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Toggle(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().widthIn(max = 900.dp).clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(hint, color = LocalPal.current.muted, fontSize = 13.sp) }
        Switch(checked, onChange)
    }
}

@Composable
fun SettingsScreen() {
    val graph = LocalGraph.current
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    val s by graph.settings.state.collectAsState()
    val ffmpegOk = remember { AudioEngine.ffmpegAvailable() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        item { Text(t("Настройки", "Settings"), style = MaterialTheme.typography.displayMedium) }
        if (!ffmpegOk) item {
            Text(
                t("Не найден ffmpeg — без него музыка не играет. Установи ffmpeg (Linux: пакет ffmpeg) или возьми сборку с ним со страницы релизов.",
                    "ffmpeg wasn’t found — music can’t play without it. Install ffmpeg (Linux: the ffmpeg package) or use a release build that bundles it."),
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        item { SectionHeader(t("Оформление", "Appearance")) }
        item {
            Setting(t("Стиль", "Style"), t("Palimpsest — чернила и бумага, засечки. Lane — фон в цвете обложки, жирный гротеск, белые кнопки.", "Palimpsest — ink and paper, a serif. Lane — backgrounds in the cover’s colour, a bold sans, white buttons.")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DesktopStyle.entries.forEach { st -> Pill(if (st == DesktopStyle.LANE) "Lane" else "Palimpsest", s.style == st, { graph.settings.update { it.copy(style = st) } }) } }
            }
        }
        item {
            Setting(t("Тема", "Theme"), null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(DesktopTheme.DARK to t("Тёмная", "Dark"), DesktopTheme.AMOLED to t("Чёрная", "Black"), DesktopTheme.LIGHT to t("Светлая", "Light")).forEach { (th, l) -> Pill(l, s.theme == th, { graph.settings.update { it.copy(theme = th) } }) }
                }
            }
        }
        item { Toggle(t("Цвет из обложки", "Colour from the cover"), t("Акцент подстраивается под текущий трек", "The accent follows the current track"), s.dynamicColor) { v -> graph.settings.update { it.copy(dynamicColor = v) } } }

        item { SectionHeader(t("Медиатека", "Library")) }
        item {
            var newRoot by remember { mutableStateOf("") }
            Setting(t("Папки с музыкой", "Music folders"), t("Подпапки учитываются, папки с точкой в начале — нет.", "Subfolders are included; folders starting with a dot are skipped.")) {
                s.musicRoots.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r, Modifier.weight(1f))
                        if (s.musicRoots.size > 1) TextButton(onClick = { graph.settings.update { it.copy(musicRoots = it.musicRoots - r) }; scope.launch { graph.library.scan() } }) { Text(t("Убрать", "Remove")) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newRoot, { newRoot = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(t("Путь к папке", "Folder path")) })
                    TextButton(onClick = {
                        val chooser = javax.swing.JFileChooser().apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY }
                        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) newRoot = chooser.selectedFile.absolutePath
                    }) { Text(t("Выбрать…", "Browse…")) }
                    TextButton(enabled = newRoot.isNotBlank(), onClick = {
                        graph.settings.update { it.copy(musicRoots = (it.musicRoots + newRoot.trim()).distinct()) }; newRoot = ""; scope.launch { graph.library.scan() }
                    }) { Text(t("Добавить", "Add")) }
                }
                Row {
                    TextButton(onClick = { scope.launch { graph.library.scan() } }) { Text(t("Обновить медиатеку", "Rescan library")) }
                    TextButton(onClick = { scope.launch { graph.library.scan(force = true) } }) { Text(t("Перечитать все теги", "Re-read all tags")) }
                }
            }
        }
        item { Toggle(t("Сохранять тексты в файлы", "Save lyrics into files"), t("Найденный текст сразу пишется в тег файла", "Found lyrics go straight into the file’s tag"), s.embedLyrics) { v -> graph.settings.update { it.copy(embedLyrics = v) } } }

        item { SectionHeader(t("Загрузки", "Downloads")) }
        item {
            var dir by remember(s.downloadDir) { mutableStateOf(s.downloadDir) }
            Setting(t("Папка загрузок", "Download folder"), t("Внутри будут папки «Яндекс», «YouTube», «Bandcamp».", "Inside go «Яндекс», «YouTube» and «Bandcamp» folders.")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(dir, { dir = it }, Modifier.weight(1f), singleLine = true)
                    TextButton(enabled = dir != s.downloadDir, onClick = { graph.settings.update { it.copy(downloadDir = dir.trim()) } }) { Text(t("Сохранить", "Save")) }
                }
            }
        }
        item {
            Setting(t("Качество", "Quality"), t("Для стриминга и загрузок; lossless — FLAC, если он есть у сервиса.", "For streaming and downloads; lossless is FLAC where available.")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Quality.LOSSLESS to "Lossless", Quality.HIGH to t("Высокое", "High"), Quality.NORMAL to t("Экономия", "Saver")).forEach { (q, l) -> Pill(l, s.quality == q, { graph.settings.update { it.copy(quality = q) } }) }
                }
            }
        }

        item { SectionHeader(t("Звук", "Sound")) }
        item {
            Setting(t("Выравнивание громкости (ReplayGain)", "Volume levelling (ReplayGain)"), t("По меткам ReplayGain в файлах", "From the ReplayGain tags in files")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Rg.OFF to t("Выкл.", "Off"), Rg.TRACK to t("По треку", "Track"), Rg.ALBUM to t("По альбому", "Album")).forEach { (r, l) -> Pill(l, s.replayGain == r, { graph.settings.update { it.copy(replayGain = r) } }) }
                }
                Text(t("Предусиление: ", "Preamp: ") + "%+.1f dB".format(s.preampDb), color = pal.muted, fontSize = 13.sp)
                Slider(s.preampDb, { v -> graph.settings.update { it.copy(preampDb = (v * 2).toInt() / 2f) } }, valueRange = -6f..12f, modifier = Modifier.widthIn(max = 500.dp))
            }
        }
        item { Toggle(t("Эквалайзер", "Equalizer"), t("Тот же 10-полосный эквалайзер, что и на телефоне", "The same 10-band equalizer as on the phone"), s.eqEnabled) { v -> graph.settings.update { it.copy(eqEnabled = v) } } }
        if (s.eqEnabled) item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EqProfile.PRESETS.forEach { p -> Pill(p.name, s.eqProfile == p.name, { graph.settings.update { it.copy(eqProfile = p.name) } }) }
            }
        }

        item { SectionHeader(t("Сеть", "Network")) }
        item {
            var proxy by remember(s.proxy) { mutableStateOf(s.proxy.orEmpty()) }
            Setting(t("Прокси", "Proxy"), t("Только для YouTube, Spotify, Bandcamp и баз обложек (например 127.0.0.1:10808 или socks://host:port). Яндекс ходит напрямую.", "Only for YouTube, Spotify, Bandcamp and cover databases (e.g. 127.0.0.1:10808 or socks://host:port). Yandex goes direct.")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(proxy, { proxy = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("host:port") })
                    TextButton(onClick = { graph.settings.update { it.copy(proxy = proxy.trim().ifBlank { null }) } }) { Text(t("Сохранить", "Save")) }
                }
            }
        }
        item { SectionHeader(t("О программе", "About")) }
        item {
            Text(
                t("Palimpsest для компьютера. Свободная программа под GPL-3.0. Горячие клавиши: пробел — пауза, ←/→ — перемотка на 5 с, Ctrl+←/→ — трек назад/вперёд, Ctrl+F — поиск, Esc — назад. Двойной клик по треку — играть, правый клик — меню.",
                    "Palimpsest for the desktop. Free software under GPL-3.0. Keys: Space — pause, ←/→ — seek 5 s, Ctrl+←/→ — previous/next track, Ctrl+F — search, Esc — back. Double-click a track to play it, right-click for its menu."),
                color = pal.muted, modifier = Modifier.widthIn(max = 900.dp).padding(bottom = 40.dp),
            )
        }
    }
}
