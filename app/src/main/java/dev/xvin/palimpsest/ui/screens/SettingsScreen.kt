package dev.xvin.palimpsest.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.core.model.Quality
import dev.xvin.palimpsest.data.settings.ReplayGainMode
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.Pill
import dev.xvin.palimpsest.ui.components.SectionHeader
import dev.xvin.palimpsest.ui.theme.Ink
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onFullAccess: () -> Unit, onReadOnly: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Text("Palimpsest", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Плеер, в котором ничего не стирается: старые версии треков остаются под новыми, как текст на палимпсесте.",
            style = MaterialTheme.typography.bodyLarge, color = Ink.Muted,
        )
        Spacer(Modifier.height(32.dp))
        Text("Нужен доступ к музыке", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Полный доступ к файлам позволяет редактировать теги и обложки, читать .lrc-тексты и хранить версии треков. " +
                "Без него приложение сможет только слушать.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.Muted,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onFullAccess, modifier = Modifier.fillMaxWidth()) { Text("Дать полный доступ") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onReadOnly, modifier = Modifier.fillMaxWidth()) { Text("Только чтение музыки") }
    }
}

@Composable
fun SettingsScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val s by graph.settings.flow.collectAsStateWithLifecycle(null)
    val settings = s ?: return
    var newRoot by remember { mutableStateOf("") }
    var proxy by remember(settings.proxy) { mutableStateOf(settings.proxy.orEmpty()) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
            Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        }

        SectionHeader("Папки с музыкой")
        settings.musicRoots.forEach { root ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(root, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (settings.musicRoots.size > 1) IconButton(onClick = {
                    scope.launch { graph.settings.setMusicRoots(settings.musicRoots - root); graph.scanner.scan() }
                }) { Icon(Icons.Rounded.Close, "Убрать", tint = Ink.Muted) }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newRoot, { newRoot = it }, Modifier.weight(1f), singleLine = true, label = { Text("/storage/emulated/0/…") })
            TextButton(onClick = {
                val r = newRoot.trim().trimEnd('/')
                if (r.isNotEmpty()) scope.launch { graph.settings.setMusicRoots((settings.musicRoots + r).distinct()); graph.scanner.scan() }
                newRoot = ""
            }) { Text("Добавить") }
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = nav::openDuplicates) { Text("Найти дубликаты") }
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { scope.launch { graph.scanner.scan() } }) { Text("Обновить медиатеку") }
            TextButton(onClick = { scope.launch { graph.scanner.scan(force = true) } }) { Text("Перечитать все теги") }
        }

        SectionHeader("Скачивание")
        var downloadDir by remember(settings.downloadDir) { mutableStateOf(settings.downloadDir) }
        Text(
            "Куда сохранять: внутри будут папки «Яндекс» и «YouTube». Можно указать, например, …/Music/Murglar, " +
                "чтобы новые треки лежали рядом со старыми.",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
        )
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(downloadDir, { downloadDir = it }, Modifier.weight(1f), singleLine = true, label = { Text("Папка загрузок") })
            TextButton(
                enabled = downloadDir.isNotBlank() && downloadDir != settings.downloadDir,
                onClick = { scope.launch { graph.settings.setDownloadDir(downloadDir.trim().trimEnd('/')) } },
            ) { Text("Сохранить") }
        }
        Spacer(Modifier.height(10.dp))
        Text("Качество", Modifier.padding(horizontal = 16.dp))
        Row(Modifier.padding(16.dp, 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            mapOf(Quality.LOSSLESS to "Lossless (FLAC)", Quality.HIGH to "Высокое", Quality.NORMAL to "Экономия").forEach { (q, label) ->
                Pill(label, settings.quality == q, { scope.launch { graph.settings.setQuality(q) } })
            }
        }
        ToggleRow(
            "Хранить старые версии",
            "При перезаливке трека старый файл переезжает в историю версий, а не удаляется",
            settings.keepVersions,
        ) { scope.launch { graph.settings.setKeepVersions(it) } }

        ToggleRow(
            "Фоновая синхронизация",
            "Каждые 3 часа: лайки и плейлисты Яндекса, проверка перезалитых треков",
            settings.autoSync,
        ) { scope.launch { graph.settings.setAutoSync(it) } }
        ToggleRow(
            "Скачивать избранное сразу",
            "♥ в приложении — трек сразу скачивается; лайки из официального приложения — при синхронизации",
            settings.autoDownloadLikes,
        ) { scope.launch { graph.settings.setAutoDownloadLikes(it) } }

        SectionHeader("Громкость")
        Text("Выравнивание громкости (ReplayGain)", Modifier.padding(horizontal = 16.dp))
        Row(Modifier.padding(16.dp, 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            ReplayGainMode.entries.forEach { m -> Pill(m.label, settings.replayGain == m, { scope.launch { graph.settings.setReplayGain(m) } }) }
        }
        Text("Предусиление: %+.1f дБ".format(settings.replayGainPreampDb), Modifier.padding(horizontal = 16.dp), color = Ink.Muted)
        Slider(
            settings.replayGainPreampDb, { v -> scope.launch { graph.settings.setReplayGainPreamp((v * 2).toInt() / 2f) } },
            valueRange = -6f..12f, modifier = Modifier.padding(horizontal = 16.dp),
        )

        SectionHeader("Внешний вид")
        ToggleRow("Цвет из обложки", "Акцентный цвет подстраивается под текущий трек", settings.dynamicColor) {
            scope.launch { graph.settings.setDynamicColor(it) }
        }

        SectionHeader("Сеть")
        Text(
            "Прокси только для YouTube и Spotify (например, 127.0.0.1:10808 или socks://host:port). Яндекс и остальное ходят напрямую.",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
        )
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(proxy, { proxy = it }, Modifier.weight(1f), singleLine = true, label = { Text("host:port") })
            TextButton(onClick = { scope.launch { graph.settings.setProxy(proxy) } }) { Text("Сохранить") }
        }

        SectionHeader("Скробблинг")
        var lbToken by remember { mutableStateOf("") }
        var lbError by remember { mutableStateOf<String?>(null) }
        if (settings.listenBrainzToken != null) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ListenBrainz: ${settings.listenBrainzUser ?: "подключено"}", Modifier.weight(1f))
                TextButton(onClick = { scope.launch { graph.settings.setListenBrainz(null, null) } }) { Text("Отключить") }
            }
        } else {
            Text(
                "ListenBrainz — открытая замена Last.fm. Токен: listenbrainz.org/settings. Прослушивания копятся офлайн и уходят при появлении сети.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
            )
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(lbToken, { lbToken = it }, Modifier.weight(1f), singleLine = true, label = { Text("Токен ListenBrainz") })
                TextButton(enabled = lbToken.isNotBlank(), onClick = {
                    scope.launch {
                        val user = runCatching { graph.listenBrainz.validate(lbToken.trim()) }.getOrNull()
                        if (user == null) lbError = "Токен не подошёл" else {
                            lbError = null
                            graph.settings.setListenBrainz(lbToken.trim(), user)
                        }
                    }
                }) { Text("Подключить") }
            }
            lbError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        }

        SectionHeader("Помощь")
        val errorCount by graph.errors.log.collectAsStateWithLifecycle()
        Row(Modifier.fillMaxWidth().clickable { nav.openErrors() }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Журнал ошибок")
                Text(
                    if (errorCount.isEmpty()) "Пусто" else "Записей: ${errorCount.size} — с объяснениями и подробностями для копирования",
                    style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
                )
            }
        }

        SectionHeader("О приложении")
        Text(
            "Palimpsest 0.1 · Kotlin, Jetpack Compose, Media3.\nЯндекс.Музыка — через неофициальный API, YouTube — через NewPipe Extractor, тексты — LRCLIB.",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
        )
    }
}

@Composable
fun ToggleRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Muted) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}
