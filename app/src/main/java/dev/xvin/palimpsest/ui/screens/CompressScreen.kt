package dev.xvin.palimpsest.ui.screens

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.data.library.CompressPlan
import dev.xvin.palimpsest.data.library.CompressTarget
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.Pill
import dev.xvin.palimpsest.ui.theme.Ink

/**
 * «Сжать медиатеку»: why lossless can't get much smaller, three ways to save space with what each
 * would free in this library, what to do with the originals, and the progress.
 */
@Composable
fun CompressScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val state by graph.compressor.state.collectAsStateWithLifecycle()
    val plans by produceState<Map<CompressTarget, CompressPlan>?>(null, state.running) {
        value = CompressTarget.entries.associateWith { graph.compressor.plan(it) }
    }
    var target by remember { mutableStateOf(CompressTarget.FLAC16) }
    var keepOld by remember { mutableStateOf(false) }
    fun size(b: Long) = Formatter.formatShortFileSize(context, b)

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) }
                Text(tr(R.string.compress_title), style = MaterialTheme.typography.headlineMedium)
            }
        }
        item { Text(tr(R.string.compress_explain), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Ink.Muted) }
        if (state.running) item {
            Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.Paper2).padding(16.dp)) {
                Text(tr(R.string.compress_progress, state.done, state.total, size(state.savedBytes)), style = MaterialTheme.typography.titleSmall)
                state.current?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1) }
                LinearProgressIndicator(progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f }, Modifier.fillMaxWidth().padding(top = 8.dp))
                TextButton(onClick = { graph.compressor.cancel() }) { Text(tr(R.string.cancel)) }
            }
        } else {
            if (state.total > 0) item {
                Text(
                    tr(R.string.compress_done, state.total - state.failed, size(state.savedBytes)) + if (state.failed > 0) " · " + tr(R.string.compress_failed, state.failed) else "",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium,
                )
                if (state.converted.size in 1..5) Text(
                    state.converted.joinToString("\n") { "• $it" },
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
                )
            }
            items(CompressTarget.entries) { t ->
                val p = plans?.get(t)
                val selected = t == target
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Ink.Paper2)
                        .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(16.dp))
                        .clickable { target = t }.padding(16.dp),
                ) {
                    Text(tr(t.labelRes), style = MaterialTheme.typography.titleMedium)
                    Text(tr(t.descRes), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            p == null -> tr(R.string.compress_counting)
                            p.tracks.isEmpty() -> tr(R.string.compress_nothing)
                            else -> tr(R.string.compress_estimate, p.tracks.size, size(p.bytesBefore), size(p.bytesAfter), size(p.bytesBefore - p.bytesAfter))
                        },
                        style = MaterialTheme.typography.labelLarge, color = if (p?.tracks?.isNotEmpty() == true) MaterialTheme.colorScheme.primary else Ink.Muted,
                    )
                }
            }
            item {
                Text(tr(R.string.compress_originals), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(tr(R.string.compress_delete_originals), !keepOld, { keepOld = false })
                    Pill(tr(R.string.compress_keep_originals), keepOld, { keepOld = true })
                }
                Text(
                    if (keepOld) tr(R.string.compress_keep_hint) else tr(R.string.compress_delete_hint),
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
                )
            }
            item {
                val n = plans?.get(target)?.tracks?.size ?: 0
                // A trial first: three files, originals kept as versions, to listen to before the rest.
                if (n > 3) androidx.compose.material3.OutlinedButton(
                    onClick = { graph.compressor.start(target, keepOld = true, limit = 3) },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth(),
                ) { Text(tr(R.string.compress_trial)) }
                if (n > 3) Text(tr(R.string.compress_trial_hint), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                Button(onClick = { graph.compressor.start(target, keepOld) }, enabled = n > 0, modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(if (n > 0) tr(R.string.compress_start, n) else tr(R.string.compress_nothing))
                }
            }
            item { BudgetCard() }
        }
    }
}

/**
 * «Бюджет места»: the library is kept within N GB — favourites and often played tracks stay as
 * they are, the least played become AAC 256 (now, or by itself once a week while charging).
 */
@Composable
private fun BudgetCard() {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val s = settings ?: return
    var gb by remember(s.budgetGb) { mutableIntStateOf(s.budgetGb) }
    val libraryBytes by produceState(0L) { value = graph.compressor.budgetPlan(Long.MAX_VALUE).libraryBytes }
    val maxGb = (libraryBytes / 1_000_000_000L + 1).toInt().coerceAtLeast(2)
    val plan by produceState<dev.xvin.palimpsest.data.library.BudgetPlan?>(null, s.budgetGb) {
        value = if (s.budgetGb > 0) graph.compressor.budgetPlan(s.budgetGb * 1_000_000_000L) else null
    }
    fun size(b: Long) = Formatter.formatShortFileSize(context, b)
    fun save(newGb: Int, auto: Boolean) {
        scope.launch { graph.settings.setBudget(newGb, auto) }
        dev.xvin.palimpsest.data.library.BudgetWorker.schedule(context, newGb > 0 && auto)
    }

    Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.Paper2).padding(16.dp)) {
        Text(tr(R.string.budget_title), style = MaterialTheme.typography.titleMedium)
        Text(tr(R.string.budget_explain), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
        Spacer(Modifier.height(8.dp))
        Text(
            if (gb > 0) tr(R.string.budget_value, gb, size(libraryBytes)) else tr(R.string.budget_off, size(libraryBytes)),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        )
        Slider(
            value = gb.toFloat(), onValueChange = { gb = it.toInt() }, valueRange = 0f..maxGb.toFloat(), steps = (maxGb - 1).coerceIn(0, 200),
            onValueChangeFinished = { save(gb, s.budgetAuto) },
        )
        val p = plan
        if (s.budgetGb > 0 && p != null) {
            Text(
                when {
                    p.tracks.isEmpty() -> tr(R.string.budget_fits)
                    else -> tr(R.string.budget_plan, p.tracks.size, size(p.libraryBytes - p.bytesAfter), p.protectedCount) +
                        if (!p.fits) "\n" + tr(R.string.budget_not_enough, size(p.bytesAfter)) else ""
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.budget_auto), style = MaterialTheme.typography.bodyMedium)
                    Text(tr(R.string.budget_auto_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                }
                Switch(checked = s.budgetAuto, onCheckedChange = { save(s.budgetGb, it) })
            }
            if (p.tracks.isNotEmpty()) Button(onClick = { graph.compressor.startBudget() }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
                Text(tr(R.string.budget_now, p.tracks.size))
            }
        }
    }
}
