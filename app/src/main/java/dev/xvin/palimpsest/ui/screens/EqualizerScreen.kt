package dev.xvin.palimpsest.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.core.dsp.AutoEqParser
import dev.xvin.palimpsest.core.dsp.EqProfile
import dev.xvin.palimpsest.core.dsp.FilterType
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.Pill
import dev.xvin.palimpsest.ui.components.SectionHeader
import dev.xvin.palimpsest.ui.components.VerticalFader
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.pow

@Composable
fun EqualizerScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val s by graph.settings.flow.collectAsStateWithLifecycle(null)
    val settings = s ?: return
    val profile = settings.eqProfile
    var importing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val isGraphic = profile.filters.size == EqProfile.GRAPHIC_BANDS.size &&
        profile.filters.all { it.type == FilterType.PEAK } &&
        profile.filters.map { it.freqHz }.toDoubleArray().contentEquals(EqProfile.GRAPHIC_BANDS)

    fun set(p: EqProfile) = scope.launch { graph.settings.setEqProfile(p); if (!settings.eqEnabled) graph.settings.setEqEnabled(true) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
            Text("Эквалайзер", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Switch(settings.eqEnabled, { scope.launch { graph.settings.setEqEnabled(it) } }, Modifier.padding(end = 16.dp))
        }
        Text(
            profile.name, style = MaterialTheme.typography.titleMedium, color = if (settings.eqEnabled) LocalAccent.current else Ink.Muted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        EqCurve(profile, settings.eqEnabled, Modifier.fillMaxWidth().height(150.dp).padding(16.dp))

        if (isGraphic) {
            Row(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                profile.filters.forEachIndexed { i, f ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%+.0f".format(f.gainDb), style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                        VerticalFader(
                            value = f.gainDb.toFloat(),
                            range = -12f..12f,
                            onChange = { v ->
                                val filters = profile.filters.toMutableList()
                                filters[i] = f.copy(gainDb = Math.round(v * 2) / 2.0)
                                val p = profile.copy(name = "Свой", filters = filters)
                                // Auto-preamp: cut by the biggest boost so the EQ itself never clips.
                                set(p.copy(preampDb = -p.peakBoostDb().coerceAtLeast(0.0)))
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp),
                        )
                        Text(bandLabel(f.freqHz), style = MaterialTheme.typography.labelSmall, color = Ink.Faint, textAlign = TextAlign.Center)
                    }
                }
            }
        } else {
            Text(
                "Параметрический профиль: ${profile.filters.size} фильтров. Ползунки доступны для 10-полосных пресетов.",
                Modifier.padding(16.dp), color = Ink.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }

        Text("Предусиление: %+.1f дБ".format(profile.preampDb), Modifier.padding(horizontal = 16.dp), color = Ink.Muted)
        Slider(
            profile.preampDb.toFloat(), { v -> set(profile.copy(preampDb = (v * 2).toInt() / 2.0)) },
            valueRange = -15f..6f, modifier = Modifier.padding(horizontal = 16.dp),
        )

        SectionHeader("Пресеты")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EqProfile.PRESETS.forEach { p -> Pill(p.name, profile.name == p.name, { set(p) }) }
        }
        if (settings.customEqProfiles.isNotEmpty()) {
            SectionHeader("Мои профили")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                settings.customEqProfiles.forEach { p -> Pill(p.name, profile.name == p.name, { set(p) }) }
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { saving = true }) { Text("Сохранить как…") }
            OutlinedButton(onClick = { importing = true }) { Text("Импорт AutoEQ") }
        }
        Text(
            "AutoEQ — готовые корректирующие кривые для тысяч наушников: github.com/jaakkopasanen/AutoEq → results → твоя модель → ParametricEQ.txt. " +
                "Скопируй содержимое и вставь сюда.",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Faint,
        )
    }

    if (importing) {
        var text by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { importing = false },
            containerColor = Ink.Paper2,
            title = { Text("Импорт AutoEQ") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Наушники") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(text, { text = it }, label = { Text("ParametricEQ.txt") }, minLines = 5, maxLines = 10)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { AutoEqParser.parse(text, name.ifBlank { "AutoEQ" }) }
                        .onSuccess { p ->
                            scope.launch {
                                graph.settings.setCustomEqProfiles(settings.customEqProfiles.filter { it.name != p.name } + p)
                                graph.settings.setEqProfile(p)
                                graph.settings.setEqEnabled(true)
                            }
                            importing = false
                        }
                        .onFailure { error = it.message }
                }) { Text("Импорт") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Отмена") } },
        )
    }
    if (saving) {
        NameDialog("Название профиля", profile.name, onDismiss = { saving = false }) { name ->
            val p = profile.copy(name = name)
            scope.launch {
                graph.settings.setCustomEqProfiles(settings.customEqProfiles.filter { it.name != name } + p)
                graph.settings.setEqProfile(p)
            }
            saving = false
        }
    }
}

private fun bandLabel(f: Double) = if (f >= 1000) "${(f / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}k" else f.toInt().toString()

/** Frequency response on a log axis from 20 Hz to 20 kHz, ±12 dB. */
@Composable
private fun EqCurve(profile: EqProfile, enabled: Boolean, modifier: Modifier) {
    val accent = if (enabled) LocalAccent.current else Ink.Faint
    val points = remember(profile) {
        (0..120).map { i ->
            val f = 20.0 * 1000.0.pow(i / 120.0)
            f to profile.responseDb(f) - profile.preampDb // shape only; preamp has its own slider
        }
    }
    Canvas(modifier) {
        val range = 15.0
        fun y(db: Double) = (size.height / 2 - (db / range) * size.height / 2).toFloat()
        fun x(f: Double) = (log10(f / 20.0) / 3.0 * size.width).toFloat()
        listOf(-12.0, -6.0, 0.0, 6.0, 12.0).forEach { db ->
            drawLine(if (db == 0.0) Ink.Faint else Ink.Line, Offset(0f, y(db)), Offset(size.width, y(db)), strokeWidth = 1f)
        }
        listOf(100.0, 1000.0, 10000.0).forEach { f -> drawLine(Ink.Line, Offset(x(f), 0f), Offset(x(f), size.height), strokeWidth = 1f) }
        val path = Path()
        points.forEachIndexed { i, (f, db) -> if (i == 0) path.moveTo(x(f), y(db)) else path.lineTo(x(f), y(db)) }
        drawPath(path, accent, style = Stroke(width = 5f))
    }
}
