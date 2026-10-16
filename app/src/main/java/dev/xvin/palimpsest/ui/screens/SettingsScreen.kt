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
import androidx.compose.foundation.layout.Arrangement
import dev.xvin.palimpsest.ui.components.Pill
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
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr

@Composable
fun OnboardingScreen(onFullAccess: () -> Unit, onReadOnly: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Text("Palimpsest", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            tr(R.string.app_tagline),
            style = MaterialTheme.typography.bodyLarge, color = Ink.Muted,
        )
        Spacer(Modifier.height(32.dp))
        Text(tr(R.string.perm_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            tr(R.string.perm_body_1) +
                tr(R.string.perm_body_2),
            style = MaterialTheme.typography.bodyMedium, color = Ink.Muted,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onFullAccess, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.perm_grant_full)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onReadOnly, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.perm_read_only)) }
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
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) }
            Text(tr(R.string.settings), style = MaterialTheme.typography.headlineMedium)
        }

        SectionHeader(tr(R.string.settings_music_folders))
        settings.musicRoots.forEach { root ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(root, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (settings.musicRoots.size > 1) IconButton(onClick = {
                    scope.launch { graph.settings.setMusicRoots(settings.musicRoots - root); graph.scanner.scan() }
                }) { Icon(Icons.Rounded.Close, tr(R.string.remove), tint = Ink.Muted) }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newRoot, { newRoot = it }, Modifier.weight(1f), singleLine = true, label = { Text("/storage/emulated/0/…") })
            TextButton(onClick = {
                val r = newRoot.trim().trimEnd('/')
                if (r.isNotEmpty()) scope.launch { graph.settings.setMusicRoots((settings.musicRoots + r).distinct()); graph.scanner.scan() }
                newRoot = ""
            }) { Text(tr(R.string.add)) }
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = nav::openDuplicates) { Text(tr(R.string.settings_find_duplicates)) }
            val cov by graph.covers.progress.collectAsStateWithLifecycle()
            TextButton(enabled = !cov.running, onClick = { graph.appScope.launch { graph.covers.run() } }) {
                Text(if (cov.running) tr(R.string.covers_progress, cov.done, cov.total, cov.found) else tr(R.string.settings_find_covers))
            }
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { scope.launch { graph.scanner.scan() } }) { Text(tr(R.string.settings_refresh_library)) }
            TextButton(onClick = { scope.launch { graph.scanner.scan(force = true) } }) { Text(tr(R.string.settings_reread_tags)) }
        }

        SectionHeader(tr(R.string.settings_downloading))
        var downloadDir by remember(settings.downloadDir) { mutableStateOf(settings.downloadDir) }
        Text(
            tr(R.string.settings_download_dir_hint_1) +
                tr(R.string.settings_download_dir_hint_2),
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
        )
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(downloadDir, { downloadDir = it }, Modifier.weight(1f), singleLine = true, label = { Text(tr(R.string.settings_download_dir)) })
            TextButton(
                enabled = downloadDir.isNotBlank() && downloadDir != settings.downloadDir,
                onClick = { scope.launch { graph.settings.setDownloadDir(downloadDir.trim().trimEnd('/')) } },
            ) { Text(tr(R.string.save)) }
        }
        Spacer(Modifier.height(10.dp))
        Text(tr(R.string.settings_quality), Modifier.padding(horizontal = 16.dp))
        Row(Modifier.padding(16.dp, 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            mapOf(Quality.LOSSLESS to "Lossless (FLAC)", Quality.HIGH to tr(R.string.quality_high), Quality.NORMAL to tr(R.string.quality_saver)).forEach { (q, label) ->
                Pill(label, settings.quality == q, { scope.launch { graph.settings.setQuality(q) } })
            }
        }
        ToggleRow(
            tr(R.string.settings_keep_versions),
            tr(R.string.settings_keep_versions_hint),
            settings.keepVersions,
        ) { scope.launch { graph.settings.setKeepVersions(it) } }

        ToggleRow(
            tr(R.string.settings_background_sync),
            tr(R.string.settings_background_sync_hint),
            settings.autoSync,
        ) { scope.launch { graph.settings.setAutoSync(it) } }
        ToggleRow(
            tr(R.string.settings_auto_download_likes),
            tr(R.string.settings_auto_download_likes_hint),
            settings.autoDownloadLikes,
        ) { scope.launch { graph.settings.setAutoDownloadLikes(it) } }

        SectionHeader(tr(R.string.settings_volume))
        Text(tr(R.string.settings_replaygain), Modifier.padding(horizontal = 16.dp))
        Row(Modifier.padding(16.dp, 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            ReplayGainMode.entries.forEach { m -> Pill(m.label, settings.replayGain == m, { scope.launch { graph.settings.setReplayGain(m) } }) }
        }
        Text(tr(R.string.preamp_db).format(settings.replayGainPreampDb), Modifier.padding(horizontal = 16.dp), color = Ink.Muted)
        Slider(
            settings.replayGainPreampDb, { v -> scope.launch { graph.settings.setReplayGainPreamp((v * 2).toInt() / 2f) } },
            valueRange = -6f..12f, modifier = Modifier.padding(horizontal = 16.dp),
        )

        Text(tr(R.string.settings_fade, if (settings.fadeSec == 0) tr(R.string.settings_fade_off) else tr(R.string.settings_fade_sec, settings.fadeSec)), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Text(tr(R.string.settings_fade_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
        Slider(
            settings.fadeSec.toFloat(), { v -> scope.launch { graph.settings.setFadeSec(v.toInt()) } },
            valueRange = 0f..12f, steps = 11, modifier = Modifier.padding(horizontal = 16.dp),
        )

        SectionHeader(tr(R.string.settings_appearance))
        Text(tr(R.string.settings_theme), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            dev.xvin.palimpsest.data.settings.ThemeMode.entries.forEach { m ->
                Pill(tr(m.labelRes), settings.theme == m, { scope.launch { graph.settings.setTheme(m) } })
            }
        }
        Text(tr(R.string.language), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val language = remember { dev.xvin.palimpsest.i18n.Language.current() }
        androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            dev.xvin.palimpsest.i18n.Language.options.forEach { (tag, name) ->
                // The system applies it and the app restarts in the new language.
                Pill(name ?: tr(R.string.language_system), language.substringBefore('-') == tag, { dev.xvin.palimpsest.i18n.Language.set(tag) })
            }
        }
        ToggleRow(tr(R.string.settings_cover_color), tr(R.string.settings_cover_color_hint), settings.dynamicColor) {
            scope.launch { graph.settings.setDynamicColor(it) }
        }

        SectionHeader(tr(R.string.settings_network))
        Text(
            tr(R.string.settings_proxy_hint),
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
        )
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(proxy, { proxy = it }, Modifier.weight(1f), singleLine = true, label = { Text("host:port") })
            TextButton(onClick = { scope.launch { graph.settings.setProxy(proxy) } }) { Text(tr(R.string.save)) }
        }

        SectionHeader(tr(R.string.settings_scrobbling))
        var lbToken by remember { mutableStateOf("") }
        var lbError by remember { mutableStateOf<String?>(null) }
        if (settings.listenBrainzToken != null) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ListenBrainz: ${settings.listenBrainzUser ?: "подключено"}", Modifier.weight(1f))
                TextButton(onClick = { scope.launch { graph.settings.setListenBrainz(null, null) } }) { Text(tr(R.string.turn_off)) }
            }
        } else {
            Text(
                tr(R.string.settings_listenbrainz_hint),
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
            )
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(lbToken, { lbToken = it }, Modifier.weight(1f), singleLine = true, label = { Text(tr(R.string.settings_listenbrainz_token)) })
                TextButton(enabled = lbToken.isNotBlank(), onClick = {
                    scope.launch {
                        val user = runCatching { graph.listenBrainz.validate(lbToken.trim()) }.getOrNull()
                        if (user == null) lbError = tr(R.string.settings_token_rejected) else {
                            lbError = null
                            graph.settings.setListenBrainz(lbToken.trim(), user)
                        }
                    }
                }) { Text(tr(R.string.connect)) }
            }
            lbError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        }

        SectionHeader(tr(R.string.backup))
        var backups by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
        var confirmRestore by remember { mutableStateOf<java.io.File?>(null) }
        androidx.compose.runtime.LaunchedEffect(Unit) { backups = graph.backups.list() }
        Text(
            tr(R.string.backup_hint),
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
        )
        TextButton(onClick = {
            scope.launch {
                runCatching { graph.backups.create() }.onSuccess { nav.toast(tr(R.string.backup_saved, it.name)) }.onFailure { nav.error(it, tr(R.string.backup)) }
                backups = graph.backups.list()
            }
        }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr(R.string.backup_now)) }
        backups.take(5).forEach { f ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f.name.removePrefix("palimpsest-").removeSuffix(".zip").replace('_', ' '), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { confirmRestore = f }) { Text(tr(R.string.restore)) }
            }
        }
        confirmRestore?.let { f ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmRestore = null },
                containerColor = Ink.Paper2,
                title = { Text(tr(R.string.backup_restore_title)) },
                text = { Text(tr(R.string.backup_restore_body, f.name)) },
                confirmButton = { TextButton(onClick = { graph.appScope.launch { runCatching { graph.backups.restore(f) } } }) { Text(tr(R.string.restore)) } },
                dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text(tr(R.string.cancel)) } },
            )
        }

        SectionHeader(tr(R.string.settings_help))
        val errorCount by graph.errors.log.collectAsStateWithLifecycle()
        Row(Modifier.fillMaxWidth().clickable { nav.openErrors() }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr(R.string.error_log))
                Text(
                    if (errorCount.isEmpty()) tr(R.string.empty) else tr(R.string.error_log_count, errorCount.size),
                    style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
                )
            }
        }

        SectionHeader(tr(R.string.settings_about))
        Text(
            tr(R.string.about_body, dev.xvin.palimpsest.BuildConfig.VERSION_NAME),
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
