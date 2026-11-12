package dev.xvin.palimpsest.ui.screens

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.yandex.YDeviceCode
import dev.xvin.palimpsest.data.remote.DownloadState
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.SectionHeader
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import kotlinx.coroutines.launch
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr

@Composable
fun SourcesScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.flow.collectAsStateWithLifecycle(null)
    val status by graph.remote.status.collectAsStateWithLifecycle()
    val downloads by graph.downloads.items.collectAsStateWithLifecycle()
    val updates by graph.db.tracks().observeUpdates().collectAsStateWithLifecycle(emptyList())
    var login by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text(tr(R.string.tab_services), style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp)) }
        item { OpenLinksCard() }
        // One status line for sync, update checks and imports.
        if (status.running || status.message != null || status.error != null) item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                (status.error ?: status.message)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (status.error != null) MaterialTheme.colorScheme.error else Ink.Muted)
                }
                if (status.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }

        item {
            Card(tr(R.string.service_yandex)) {
                val loginName = settings?.yandexLogin
                if (settings?.yandexToken == null) {
                    Text(tr(R.string.yandex_hint), color = Ink.Muted)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { login = true }) { Text(tr(R.string.sign_in)) }
                } else {
                    Text(tr(R.string.sources_signed_in, loginName ?: tr(R.string.sources_account)), color = Ink.Muted)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { graph.appScope.launch { graph.syncYandex() } }, enabled = !status.running) { Text(tr(R.string.sync)) }
                        OutlinedButton(onClick = { graph.appScope.launch { graph.remote.checkForUpdates() } }, enabled = !status.running) { Text(tr(R.string.check_new_versions)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                val liked = graph.db.tracks().linkedTo(Service.YANDEX).filter { it.liked && it.path == null }
                                graph.downloads.enqueue(liked.map { it.id })
                                nav.toast(if (liked.isEmpty()) tr(R.string.likes_all_downloaded) else tr(R.string.downloading_n, liked.size))
                            }
                        }) { Text(tr(R.string.download_all_likes)) }
                        TextButton(onClick = { scope.launch { graph.remote.logoutYandex() } }) { Text(tr(R.string.sign_out), color = Ink.Muted) }
                    }
                }
                // FLAC for files stored lossy: found, downloaded as candidates, replaced after «same song?».
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = nav::openFlacReview) { Text(tr(R.string.flac_title) + " →", color = LocalAccent.current) }
                if (updates.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { nav.openList(TrackListKind.UPDATES, "updates") }) {
                        Text(tr(R.string.reuploaded_count, updates.size), color = LocalAccent.current)
                    }
                }
            }
        }

        item {
            Card("YouTube Music") {
                Text(
                    tr(R.string.youtube_hint_1) +
                        tr(R.string.youtube_hint_2),
                    color = Ink.Muted,
                )
            }
        }

        item {
            Card("SoundCloud · Bandcamp") { Text(tr(R.string.soundcloud_hint), color = Ink.Muted) }
        }

        item {
            Card("Spotify") {
                var link by remember { mutableStateOf("") }
                Text(
                    tr(R.string.spotify_hint_1) +
                        tr(R.string.spotify_hint_2),
                    color = Ink.Muted,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("https://open.spotify.com/playlist/…") })
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = !status.running && dev.xvin.palimpsest.core.spotify.SpotifyImporter.parse(link) != null,
                    onClick = { graph.appScope.launch { graph.remote.importSpotify(link, graph.spotify) } },
                ) { Text(tr(R.string.import_action)) }
            }
        }

        if (downloads.isNotEmpty()) {
            item {
                SectionHeader(tr(R.string.downloads)) {
                    Row {
                        TextButton(onClick = graph.downloads::clearFinished) { Text(tr(R.string.clear)) }
                        TextButton(onClick = graph.downloads::cancelAll) { Text(tr(R.string.cancel_action)) }
                    }
                }
            }
            items(downloads.values.sortedBy { it.state.ordinal }, key = { it.trackId }) { d ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(d.artist, style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1)
                        }
                        Text(
                            when (d.state) {
                                DownloadState.QUEUED -> tr(R.string.dl_state_queued)
                                DownloadState.RUNNING -> "${(d.progress * 100).toInt()}%"
                                DownloadState.DONE -> tr(R.string.dl_state_done)
                                DownloadState.SKIPPED -> d.message ?: tr(R.string.dl_state_skipped)
                                DownloadState.FAILED -> tr(R.string.dl_state_failed)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (d.state == DownloadState.FAILED) MaterialTheme.colorScheme.error else Ink.Muted,
                        )
                    }
                    if (d.state == DownloadState.RUNNING) LinearProgressIndicator(progress = { d.progress }, Modifier.fillMaxWidth().padding(top = 4.dp))
                    if (d.state == DownloadState.FAILED) d.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Faint, maxLines = 2) }
                }
            }
        }
    }

    if (login) YandexLoginDialog(onDismiss = { login = false })
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(Ink.Paper2).padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/**
 * Login via OAuth device code: show a short code, the user confirms it on ya.ru/device
 * (on this phone or any other device). A raw token can be pasted as a fallback.
 */
@Composable
private fun YandexLoginDialog(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<YDeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            val c = graph.remote.startYandexLogin()
            code = c
            val name = graph.remote.finishYandexLogin(c)
            if (name != null) {
                // The dialog (and this effect) goes away; the sync must outlive it.
                graph.appScope.launch { graph.syncYandex() }
                onDismiss()
            } else error = tr(R.string.login_code_expired)
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Paper2,
        title = { Text(tr(R.string.login_yandex_title)) },
        text = {
            Column {
                if (!manual) {
                    val c = code
                    if (c == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (c != null) {
                        Text(tr(R.string.login_open_device), color = Ink.Muted)
                        Spacer(Modifier.height(12.dp))
                        Text(c.user_code, fontFamily = FontFamily.Monospace, fontSize = 34.sp, color = LocalAccent.current)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch { clipboard.setClipEntry(ClipData.newPlainText("code", c.user_code).toClipEntry()) }
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.verification_url)))
                            }) { Text(tr(R.string.open)) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(tr(R.string.login_waiting), style = MaterialTheme.typography.bodySmall, color = Ink.Faint)
                    }
                } else {
                    Text(tr(R.string.login_paste_token), color = Ink.Muted)
                    OutlinedTextField(token, { token = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { graph.remote.loginWithToken(token) }
                                .onSuccess { graph.appScope.launch { graph.syncYandex() }; onDismiss() }
                                .onFailure { error = it.message }
                        }
                    }) { Text(tr(R.string.sign_in)) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
        dismissButton = { TextButton(onClick = { manual = !manual }) { Text(if (manual) tr(R.string.login_by_code) else tr(R.string.login_enter_token)) } },
    )
}

/**
 * Android 12+ opens links to other companies' sites in the browser (or their own app) unless the
 * user allows an app in its «Open by default» page — so music links never reached Palimpsest and it
 * looked as if it couldn't open them. Shown until they're allowed; the button goes straight there.
 */
@Composable
private fun OpenLinksCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var allowed by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        allowed = runCatching {
            val dvm = context.getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
            val st = dvm.getDomainVerificationUserState(context.packageName)
            st?.hostToStateMap?.filterKeys { it.startsWith("music.yandex") }?.values
                ?.any { it != android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE } ?: true
        }.getOrDefault(true)
        onPauseOrDispose { }
    }
    if (allowed) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(dev.xvin.palimpsest.ui.theme.LocalAccent.current.copy(alpha = 0.12f)).padding(16.dp),
    ) {
        Text(tr(R.string.links_card_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(tr(R.string.links_card_text), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.Button(onClick = {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }) { Text(tr(R.string.links_card_action)) }
    }
}
