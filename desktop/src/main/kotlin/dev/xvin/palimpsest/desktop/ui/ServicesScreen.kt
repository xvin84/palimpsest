package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.sp
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
    LazyColumn(Modifier.fillMaxSize().padding(start = 28.dp, end = 20.dp, top = 22.dp)) {
        item { Text(t("Сервисы", "Services"), style = MaterialTheme.typography.displayMedium) }
        item { SectionHeader(t("Яндекс Музыка", "Yandex Music")) }
        if (settings.yandexToken == null) item {
            Column {
                Text(t("Вход по коду: пароль в приложение не вводится.", "Sign in with a code: no password goes into the app."), style = MaterialTheme.typography.bodyMedium, color = pal.muted)
                val c = code
                if (c == null) PButton(t("Войти в Яндекс", "Sign in to Yandex"), enabled = !busy, kind = Kind.PRIMARY, modifier = Modifier.padding(top = 12.dp), onClick = {
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
                })
                else Column(Modifier.padding(top = 12.dp).clip(RoundedCornerShape(Radius.xl)).background(pal.paper2).border(1.dp, pal.line, RoundedCornerShape(Radius.xl)).padding(20.dp)) {
                    Text(t("Открой ${c.verification_url} и введи код:", "Open ${c.verification_url} and enter the code:"), style = MaterialTheme.typography.bodyMedium)
                    Text(c.user_code, style = MaterialTheme.typography.displayMedium.copy(fontFamily = Sans, letterSpacing = 4.sp), modifier = Modifier.padding(vertical = 8.dp))
                    Text(t("Жду подтверждения…", "Waiting for confirmation…"), style = Type.meta, color = pal.muted)
                }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(pal.paper3), contentAlignment = Alignment.Center) {
                        Text((settings.yandexLogin ?: "Я").take(1).uppercase(), style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(settings.yandexLogin ?: t("Яндекс", "Yandex"), style = Type.rowTitle)
                        Text(t("Вход выполнен", "Signed in"), style = Type.meta, color = pal.muted)
                    }
                    PButton(t("Выйти", "Sign out"), { graph.settings.update { it.copy(yandexToken = null, yandexRefresh = null, yandexLogin = null) } }, kind = Kind.GHOST)
                }
            }
            item {
                // «Моя волна»: a quiet card; the accent is only on its sign and the play button.
                val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp).clip(RoundedCornerShape(Radius.xl))
                        .background(if (hovered) pal.paper3 else pal.paper2).border(1.dp, pal.line.copy(alpha = 0.7f), RoundedCornerShape(Radius.xl))
                        .hoverable(hover).clickable { startMyWave(graph, nav) }
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Waves, null, tint = accent, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t("Моя волна", "My Wave"), style = MaterialTheme.typography.headlineLarge)
                        Text(t("Бесконечный поток под твой вкус", "An endless stream tuned to you"), style = MaterialTheme.typography.bodyMedium, color = pal.muted)
                    }
                    PButton(t("Настроить", "Tune"), { nav.waveTuning = true }, icon = Icons.Rounded.Tune, kind = Kind.GHOST)
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { startMyWave(graph, nav) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, t("Слушать", "Play"), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton(if (syncing) t("Синхронизирую…", "Syncing…") else t("Синхронизировать лайки", "Sync likes"), enabled = !syncing, icon = Icons.Rounded.Sync, onClick = {
                        syncing = true
                        scope.launch {
                            runCatching { graph.library.syncLikes(Service.YANDEX, graph.yandex.likedTracks()) }
                                .onSuccess { nav.toast(t("Лайки синхронизированы", "Likes synced")) }.onFailure { nav.toast(it.message ?: "") }
                            syncing = false
                        }
                    })
                    PButton(t("Любимые", "Favourites"), { nav.root(Screen.Liked) }, icon = Icons.Rounded.Favorite)
                }
            }
            item {
                val lists by produceState<Load<List<RemotePlaylist>>>(Load.Loading, settings.yandexToken) { value = load { graph.yandex.playlists() } }
                SectionHeader(t("Плейлисты Яндекса", "Yandex playlists"))
                when (val l = lists) {
                    is Load.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = pal.muted)
                    is Load.Failed -> Text(l.error.message.orEmpty(), color = MaterialTheme.colorScheme.error)
                    is Load.Ok -> LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(l.value, key = { it.id }) { p -> AlbumTile(p.coverUrl, p.title, tracksCount(p.trackCount)) { nav.go(Screen.RemotePlaylist(Service.YANDEX, p.id, p.title)) } }
                    }
                }
            }
        }
        item { SectionHeader("YouTube Music · Bandcamp") }
        item {
            Text(
                t("Работают без входа — ищи в «Поиске». Из России нужен прокси (Настройки → Сеть).", "No sign-in needed — use Search. From Russia they need the proxy (Settings → Network)."),
                style = MaterialTheme.typography.bodyMedium, color = pal.muted,
            )
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}
