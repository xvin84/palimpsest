package dev.xvin.palimpsest.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.core.sync.LanPeer
import dev.xvin.palimpsest.core.sync.LanSync
import dev.xvin.palimpsest.core.sync.SyncReport
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.theme.Ink
import kotlinx.coroutines.launch

/** «Синхронизация с компьютером»: find the desktop app on the home network, pair, sync. */
@Composable
fun LanSyncScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val pairing by graph.lanSync.pairing.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var peers by remember { mutableStateOf<List<LanPeer>?>(null) }
    var pairWith by remember { mutableStateOf<LanPeer?>(null) }
    var manualHost by remember { mutableStateOf("") }

    fun report(r: SyncReport) = tr(R.string.lan_report, r.likesAdded, r.likesRemoved, r.playlistsChanged, r.playsAdded)

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) }
                Text(tr(R.string.lan_title), style = MaterialTheme.typography.headlineMedium)
            }
        }
        item { Text(tr(R.string.lan_explain), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Ink.Muted) }
        val p = pairing
        if (p != null) {
            item {
                Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.Paper2).padding(16.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text("${p.host}:${p.port}", style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                    Text(
                        if (p.lastSync > 0) tr(R.string.lan_last, DateUtils.getRelativeTimeSpanString(p.lastSync).toString()) + (p.lastReport?.let { " · " + report(it) } ?: "")
                        else tr(R.string.lan_never),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                    )
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                graph.lanSync.sync()
                                    .onSuccess { nav.toast(tr(R.string.lan_done) + ": " + report(it)) }
                                    .onFailure { nav.error(it, tr(R.string.lan_title)) }
                                busy = false
                            }
                        },
                        enabled = !busy, modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                    ) { Text(if (busy) tr(R.string.lan_syncing) else tr(R.string.lan_sync_now)) }
                    TextButton(onClick = { graph.lanSync.unpair() }) { Text(tr(R.string.lan_unpair)) }
                }
            }
        } else {
            item {
                Button(
                    onClick = { busy = true; scope.launch { peers = graph.lanSync.discover(); busy = false } },
                    enabled = !busy, modifier = Modifier.padding(16.dp).fillMaxWidth(),
                ) { Text(if (busy) tr(R.string.lan_searching) else tr(R.string.lan_find)) }
            }
            peers?.let { list ->
                if (list.isEmpty()) item { Text(tr(R.string.lan_none), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted) }
                items(list) { peer ->
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Paper2)
                            .clickable { pairWith = peer }.padding(14.dp),
                    ) {
                        Text(peer.name.ifBlank { peer.host }, style = MaterialTheme.typography.titleSmall)
                        Text(peer.host, style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                    }
                }
            }
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(manualHost, { manualHost = it }, Modifier.weight(1f), singleLine = true, label = { Text(tr(R.string.lan_manual)) }, placeholder = { Text("192.168.1.10") })
                    TextButton(enabled = manualHost.isNotBlank(), onClick = { pairWith = LanPeer(manualHost.trim().substringBefore(':'), manualHost.substringAfter(':', "").toIntOrNull() ?: LanSync.PORT, "") }) {
                        Text(tr(R.string.lan_connect))
                    }
                }
            }
        }
    }

    pairWith?.let { peer ->
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pairWith = null },
            title = { Text(tr(R.string.lan_code_title)) },
            text = {
                Column {
                    Text(tr(R.string.lan_code_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = code.length == 6, onClick = {
                    pairWith = null
                    scope.launch {
                        graph.lanSync.pair(peer.host, peer.port, code)
                            .onSuccess { nav.toast(tr(R.string.lan_paired, it.name)) }
                            .onFailure { nav.error(it, tr(R.string.lan_title)) }
                    }
                }) { Text(tr(R.string.lan_connect)) }
            },
            dismissButton = { TextButton(onClick = { pairWith = null }) { Text(tr(R.string.cancel)) } },
        )
    }
}
