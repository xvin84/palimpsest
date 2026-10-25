package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xvin.palimpsest.core.model.RemotePlaylist
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.yandex.YDeviceCode
import dev.xvin.palimpsest.desktop.playback.Player
import kotlinx.coroutines.launch

/** Yandex sign-in (device code), My Wave, likes and playlists; YouTube Music and Bandcamp need no account. */
@Composable
fun ServicesScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.state.collectAsState()
    var code by remember { mutableStateOf<YDeviceCode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        item { Text(t("Сервисы", "Services"), style = MaterialTheme.typography.displayMedium) }
        item { SectionHeader(t("Яндекс Музыка", "Yandex Music")) }
        if (settings.yandexToken == null) item {
            Column {
                Text(t("Вход по коду: пароль в приложение не вводится.", "Sign in with a code: no password goes into the app."), color = pal.muted)
                val c = code
                if (c == null) Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { graph.yandexAuth.requestDeviceCode("Palimpsest (Desktop)") }.onSuccess { dc ->
                            code = dc
                            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(dc.verification_url)) }
                            val token = runCatching { graph.yandexAuth.awaitToken(dc) }.getOrNull()
                            if (token != null) {
                                graph.settings.update { it.copy(yandexToken = token.access_token, yandexRefresh = token.refresh_token) }
                                runCatching { graph.yandex.account() }.onSuccess { a -> graph.settings.update { it.copy(yandexLogin = a.login ?: a.name) } }
                                nav.toast(t("Яндекс подключён", "Yandex connected"))
                            }
                            code = null
                        }.onFailure { nav.toast(it.message ?: "") }
                        busy = false
                    }
                }, modifier = Modifier.padding(top = 8.dp)) { Text(t("Войти в Яндекс", "Sign in to Yandex")) }
                else Column(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(pal.paper2).padding(20.dp)) {
                    Text(t("Открой ${c.verification_url} и введи код:", "Open ${c.verification_url} and enter the code:"))
                    Text(c.user_code, style = MaterialTheme.typography.displayMedium, color = accent)
                    Text(t("Жду подтверждения…", "Waiting for confirmation…"), color = pal.muted)
                }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Вход выполнен: ", "Signed in: ") + (settings.yandexLogin ?: "✓"), Modifier.weight(1f))
                    TextButton(onClick = { graph.settings.update { it.copy(yandexToken = null, yandexRefresh = null, yandexLogin = null) } }) { Text(t("Выйти", "Sign out")) }
                }
            }
            item {
                // «Моя волна»
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(RoundedCornerShape(20.dp))
                        .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.12f))))
                        .clickable { startMyWave(graph, nav) }
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t("Моя волна", "My Wave"), style = MaterialTheme.typography.headlineLarge)
                        Text(t("Бесконечный поток под твой вкус", "An endless stream tuned to you"), color = pal.text.copy(alpha = 0.8f))
                    }
                    TextButton(onClick = { nav.waveTuning = true }) { Text(t("Настроить", "Tune"), color = pal.text) }
                    Text("▶", style = MaterialTheme.typography.displayMedium)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !syncing, onClick = {
                        syncing = true
                        scope.launch {
                            runCatching { graph.library.syncLikes(Service.YANDEX, graph.yandex.likedTracks()) }
                                .onSuccess { nav.toast(t("Лайки синхронизированы", "Likes synced")) }.onFailure { nav.toast(it.message ?: "") }
                            syncing = false
                        }
                    }) { Text(if (syncing) t("Синхронизирую…", "Syncing…") else t("Синхронизировать лайки", "Sync likes")) }
                    OutlinedButton(onClick = { nav.root(Screen.Liked) }) { Text(t("Любимые", "Favourites")) }
                }
            }
            item {
                val lists by produceState<Load<List<RemotePlaylist>>>(Load.Loading, settings.yandexToken) { value = load { graph.yandex.playlists() } }
                SectionHeader(t("Плейлисты Яндекса", "Yandex playlists"))
                when (val l = lists) {
                    is Load.Loading -> CircularProgressIndicator()
                    is Load.Failed -> Text(l.error.message.orEmpty(), color = MaterialTheme.colorScheme.error)
                    is Load.Ok -> LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(l.value, key = { it.id }) { p -> AlbumTile(p.coverUrl, p.title, t("${p.trackCount} тр.", "${p.trackCount} tracks")) { nav.go(Screen.RemotePlaylist(Service.YANDEX, p.id, p.title)) } }
                    }
                }
            }
        }
        item { SectionHeader("YouTube Music · Bandcamp") }
        item {
            Text(
                t("Работают без входа — ищи в «Поиске». Из России нужен прокси (Настройки → Сеть).", "No sign-in needed — use Search. From Russia they need the proxy (Settings → Network)."),
                color = pal.muted,
            )
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}
