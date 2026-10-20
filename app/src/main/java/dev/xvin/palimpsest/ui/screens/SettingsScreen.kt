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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
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
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import dev.xvin.palimpsest.data.settings.AppSettings
import dev.xvin.palimpsest.data.settings.ThemeMode
import dev.xvin.palimpsest.data.settings.UiStyle
import dev.xvin.palimpsest.data.settings.VisualizerMode
import dev.xvin.palimpsest.ui.theme.LocalAccent
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

/** Settings are grouped; each group has an icon and a one-line summary of what's inside. */
private enum class SettingsCategory(val icon: ImageVector, @StringRes val titleRes: Int, @StringRes val summaryRes: Int) {
    APPEARANCE(Icons.Rounded.Palette, R.string.settings_appearance, R.string.settings_cat_appearance_summary),
    PLAYBACK(Icons.Rounded.PlayCircle, R.string.settings_cat_playback, R.string.settings_cat_playback_summary),
    LIBRARY(Icons.Rounded.LibraryMusic, R.string.settings_cat_library, R.string.settings_cat_library_summary),
    DOWNLOADS(Icons.Rounded.Download, R.string.settings_cat_downloads, R.string.settings_cat_downloads_summary),
    LYRICS(Icons.Rounded.Lyrics, R.string.lyrics, R.string.settings_cat_lyrics_summary),
    STORAGE(Icons.Rounded.Storage, R.string.settings_cat_storage, R.string.settings_cat_storage_summary),
    NETWORK(Icons.Rounded.Hub, R.string.settings_cat_network, R.string.settings_cat_network_summary),
    BACKUP(Icons.Rounded.Backup, R.string.backup, R.string.settings_cat_backup_summary),
    UPDATES(Icons.Rounded.SystemUpdate, R.string.settings_updates, R.string.settings_cat_updates_summary),
    ABOUT(Icons.Rounded.Info, R.string.settings_cat_about, R.string.settings_cat_about_summary),
}

/**
 * One setting: where it lives, what it's called and what it does (both searchable, plus a few
 * extra words people might type), and how it's drawn.
 */
private class SettingItem(
    val category: SettingsCategory,
    val title: String,
    val hint: String,
    val keywords: String = "",
    val content: @Composable () -> Unit,
) {
    fun matches(words: List<String>, categoryTitle: String): Boolean {
        val hay = listOf(title, hint, keywords, categoryTitle).joinToString(" ").lowercase().replace('ё', 'е')
        return words.all { it in hay }
    }
}

/** «Настройки»: categories, a search over every option, and the style switch up top. */
@Composable
fun SettingsScreen() {
    val nav = LocalNav.current
    val graph = LocalGraph.current
    val s by graph.settings.state.collectAsStateWithLifecycle()
    val settings = s ?: return
    var category by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val items = settingItems(settings)
    val words = query.lowercase().replace('ё', 'е').split(' ').filter { it.isNotBlank() }
    androidx.activity.compose.BackHandler(enabled = category != null || query.isNotEmpty()) {
        if (query.isNotEmpty()) query = "" else category = null
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (category != null) category = null else nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) }
            Text(category?.let { tr(it.titleRes) } ?: tr(R.string.settings), style = MaterialTheme.typography.headlineMedium)
        }
        if (category == null) {
            OutlinedTextField(
                query, { query = it },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, tr(R.string.clear)) } },
                placeholder = { Text(tr(R.string.settings_search_hint)) },
                shape = RoundedCornerShape(14.dp),
            )
        }
        when {
            category == null && words.isNotEmpty() -> {
                val found = items.filter { it.matches(words, tr(it.category.titleRes)) }
                if (found.isEmpty()) Text(tr(R.string.settings_search_nothing), Modifier.padding(16.dp), color = Ink.Muted)
                found.groupBy { it.category }.forEach { (cat, list) ->
                    SectionHeader(tr(cat.titleRes))
                    list.forEach { it.content() }
                }
            }
            category == null -> {
                SectionHeader(tr(R.string.settings_style))
                StylePicker(settings.uiStyle)
                Spacer(Modifier.height(8.dp))
                SettingsCategory.entries.forEach { c -> CategoryRow(c) { category = c } }
            }
            else -> items.filter { it.category == category }.forEach { it.content() }
        }
    }
}

@Composable
private fun CategoryRow(c: SettingsCategory, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(c.icon, null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(tr(c.titleRes), style = MaterialTheme.typography.titleMedium)
            Text(tr(c.summaryRes), style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ink.Faint)
    }
}

/** A title, what it does, and its choices as pills underneath. */
@Composable
private fun ChoiceRow(title: String, hint: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Muted) }
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** A row that does something or opens a screen. */
@Composable
private fun ActionRow(title: String, hint: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Ink.Text else Ink.Faint)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Muted) }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ink.Faint)
    }
}

/** A text value saved with a button (a path, a proxy, a token). */
@Composable
private fun FieldRow(title: String, hint: String?, initial: String, label: String, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Muted) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value, { value = it }, Modifier.weight(1f), singleLine = true, label = { Text(label) })
            TextButton(enabled = value != initial, onClick = { onSave(value) }) { Text(tr(R.string.save)) }
        }
    }
}

/** The style switch: a small live preview of each style; one tap applies it to the whole app. */
@Composable
private fun StylePicker(current: UiStyle) {
    val graph = LocalGraph.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        UiStyle.entries.forEach { style ->
            val selected = style == current
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Ink.Paper2)
                    .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(16.dp))
                    .clickable { graph.appScope.launch { graph.settings.setUiStyle(style) } }
                    .padding(10.dp),
            ) {
                StylePreview(style, Modifier.fillMaxWidth().aspectRatio(0.78f))
                Spacer(Modifier.height(8.dp))
                Text(tr(style.labelRes), style = MaterialTheme.typography.titleSmall)
                Text(tr(style.descRes), style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A tiny drawing of the player in each style, so the choice is visual. */
@Composable
private fun StylePreview(style: UiStyle, modifier: Modifier) {
    val accent = LocalAccent.current
    val tint = dev.xvin.palimpsest.ui.theme.LocalCoverTint.current
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(10.dp))) {
        val w = size.width
        val h = size.height
        when (style) {
            UiStyle.PALIMPSEST -> {
                drawRect(Color(0xFF0F0E0C))
                val side = w * 0.7f
                drawRoundRect(accent.copy(alpha = 0.85f), Offset((w - side) / 2, h * 0.12f), Size(side, side), CornerRadius(w * 0.05f))
                drawRect(Color(0xFFEDE6DA), Offset(w * 0.15f, h * 0.62f), Size(w * 0.5f, h * 0.035f))
                drawRect(Color(0xFF9C9488), Offset(w * 0.15f, h * 0.68f), Size(w * 0.35f, h * 0.025f))
                for (i in 0 until 24) {
                    val bh = h * 0.02f + h * 0.05f * kotlin.math.abs(kotlin.math.sin(i * 0.9f))
                    drawRect(accent.copy(alpha = if (i < 9) 1f else 0.35f), Offset(w * 0.15f + i * w * 0.0292f, h * 0.78f - bh / 2), Size(w * 0.018f, bh))
                }
                drawCircle(accent, w * 0.08f, Offset(w / 2, h * 0.9f))
            }
            UiStyle.LANE -> {
                drawRect(Brush.verticalGradient(listOf(lerp(Color(0xFF0B0B0D), tint, 0.5f), Color(0xFF0B0B0D))))
                drawRect(Brush.verticalGradient(listOf(tint, lerp(tint, Color.Black, 0.35f), Color.Transparent), endY = h * 0.62f), size = Size(w, h * 0.62f))
                drawRect(Color.White, Offset(w * 0.08f, h * 0.66f), Size(w * 0.6f, h * 0.045f))
                drawRect(Color(0xFFA7A7AF), Offset(w * 0.08f, h * 0.73f), Size(w * 0.4f, h * 0.025f))
                drawRect(Color.White.copy(alpha = 0.5f), Offset(w * 0.08f, h * 0.8f), Size(w * 0.84f, h * 0.006f))
                drawCircle(Color.White, w * 0.022f, Offset(w * 0.2f, h * 0.803f))
                drawRoundRect(Color.White, Offset(w * 0.32f, h * 0.85f), Size(w * 0.36f, h * 0.075f), CornerRadius(w * 0.05f))
            }
        }
    }
}

/** Every option, by category. Built per composition so it reads the current values. */
@Composable
private fun settingItems(settings: AppSettings): List<SettingItem> {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    fun launch(block: suspend () -> Unit) { scope.launch { block() } }
    val list = mutableListOf<SettingItem>()
    fun add(c: SettingsCategory, title: String, hint: String, keywords: String = "", content: @Composable () -> Unit) {
        list += SettingItem(c, title, hint, keywords, content)
    }
    fun toggle(c: SettingsCategory, title: String, hint: String, checked: Boolean, keywords: String = "", onChange: (Boolean) -> Unit) =
        add(c, title, hint, keywords) { ToggleRow(title, hint, checked, onChange) }

    // --- Appearance ---
    val styleTitle = tr(R.string.settings_style)
    add(SettingsCategory.APPEARANCE, styleTitle, tr(R.string.settings_style_hint), "style стиль lane palimpsest дизайн design look") {
        Text(styleTitle, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        Text(tr(R.string.settings_style_hint), Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
        StylePicker(settings.uiStyle)
    }
    val themeTitle = tr(R.string.settings_theme)
    add(SettingsCategory.APPEARANCE, themeTitle, tr(R.string.settings_theme_hint), "theme dark light amoled тема тёмная светлая") {
        ChoiceRow(themeTitle, tr(R.string.settings_theme_hint)) {
            ThemeMode.entries.forEach { m -> Pill(tr(m.labelRes), settings.theme == m, { launch { graph.settings.setTheme(m) } }) }
        }
    }
    toggle(SettingsCategory.APPEARANCE, tr(R.string.settings_cover_color), tr(R.string.settings_cover_color_hint), settings.dynamicColor, "color accent цвет акцент") {
        launch { graph.settings.setDynamicColor(it) }
    }
    val langTitle = tr(R.string.language)
    add(SettingsCategory.APPEARANCE, langTitle, tr(R.string.settings_language_hint), "language язык english русский") {
        val language = remember { dev.xvin.palimpsest.i18n.Language.current() }
        ChoiceRow(langTitle, tr(R.string.settings_language_hint)) {
            dev.xvin.palimpsest.i18n.Language.options.forEach { (tag, name) ->
                // The system applies it and the app restarts in the new language.
                Pill(name ?: tr(R.string.language_system), language.substringBefore('-') == tag, { dev.xvin.palimpsest.i18n.Language.set(tag) })
            }
        }
    }
    val vizTitle = tr(R.string.visualizer)
    add(SettingsCategory.APPEARANCE, vizTitle, tr(R.string.settings_visualizer_hint), "visualizer spectrum визуализатор спектр") {
        ChoiceRow(vizTitle, tr(R.string.settings_visualizer_hint)) {
            VisualizerMode.entries.forEach { m -> Pill(m.label, settings.visualizer == m, { launch { graph.settings.setVisualizer(m) } }) }
        }
    }

    // --- Playback ---
    val rgTitle = tr(R.string.settings_replaygain)
    add(SettingsCategory.PLAYBACK, rgTitle, tr(R.string.settings_replaygain_hint), "replaygain volume loudness громкость") {
        ChoiceRow(rgTitle, tr(R.string.settings_replaygain_hint)) {
            ReplayGainMode.entries.forEach { m -> Pill(m.label, settings.replayGain == m, { launch { graph.settings.setReplayGain(m) } }) }
        }
    }
    val preampTitle = tr(R.string.preamp_db).format(settings.replayGainPreampDb)
    add(SettingsCategory.PLAYBACK, preampTitle, tr(R.string.settings_preamp_hint), "preamp gain предусиление") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(preampTitle)
            Text(tr(R.string.settings_preamp_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
            Slider(settings.replayGainPreampDb, { v -> launch { graph.settings.setReplayGainPreamp((v * 2).toInt() / 2f) } }, valueRange = -6f..12f)
        }
    }
    val fadeTitle = tr(R.string.settings_fade, if (settings.fadeSec == 0) tr(R.string.settings_fade_off) else tr(R.string.settings_fade_sec, settings.fadeSec))
    add(SettingsCategory.PLAYBACK, fadeTitle, tr(R.string.settings_fade_hint), "crossfade fade кроссфейд переход") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(fadeTitle)
            Text(tr(R.string.settings_fade_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
            Slider(settings.fadeSec.toFloat(), { v -> launch { graph.settings.setFadeSec(v.toInt()) } }, valueRange = 0f..12f, steps = 11)
        }
    }
    add(SettingsCategory.PLAYBACK, tr(R.string.equalizer), tr(R.string.settings_eq_hint), "eq equalizer autoeq эквалайзер наушники") {
        ActionRow(tr(R.string.equalizer), tr(R.string.settings_eq_hint)) { nav.openEqualizer() }
    }

    // --- Library ---
    val foldersTitle = tr(R.string.settings_music_folders)
    add(SettingsCategory.LIBRARY, foldersTitle, tr(R.string.settings_music_folders_hint), "folders папки путь") {
        var newRoot by remember { mutableStateOf("") }
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(foldersTitle, Modifier.padding(horizontal = 16.dp))
            Text(tr(R.string.settings_music_folders_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
            settings.musicRoots.forEach { root ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(root, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (settings.musicRoots.size > 1) IconButton(onClick = {
                        launch { graph.settings.setMusicRoots(settings.musicRoots - root); graph.scanner.scan() }
                    }) { Icon(Icons.Rounded.Close, tr(R.string.remove), tint = Ink.Muted) }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newRoot, { newRoot = it }, Modifier.weight(1f), singleLine = true, label = { Text("/storage/emulated/0/…") })
                TextButton(onClick = {
                    val r = newRoot.trim().trimEnd('/')
                    if (r.isNotEmpty()) launch { graph.settings.setMusicRoots((settings.musicRoots + r).distinct()); graph.scanner.scan() }
                    newRoot = ""
                }) { Text(tr(R.string.add)) }
            }
        }
    }
    add(SettingsCategory.LIBRARY, tr(R.string.settings_refresh_library), tr(R.string.settings_refresh_hint), "scan rescan сканировать") {
        ActionRow(tr(R.string.settings_refresh_library), tr(R.string.settings_refresh_hint)) { launch { graph.scanner.scan() } }
    }
    add(SettingsCategory.LIBRARY, tr(R.string.settings_reread_tags), tr(R.string.settings_reread_hint), "tags теги") {
        ActionRow(tr(R.string.settings_reread_tags), tr(R.string.settings_reread_hint)) { launch { graph.scanner.scan(force = true) } }
    }
    add(SettingsCategory.LIBRARY, tr(R.string.settings_find_duplicates), tr(R.string.settings_duplicates_hint), "duplicates дубли копии") {
        ActionRow(tr(R.string.settings_find_duplicates), tr(R.string.settings_duplicates_hint)) { nav.openDuplicates() }
    }
    add(SettingsCategory.LIBRARY, tr(R.string.settings_find_covers), tr(R.string.settings_covers_hint), "covers art обложки") {
        val cov by graph.covers.progress.collectAsStateWithLifecycle()
        ActionRow(
            if (cov.running) tr(R.string.covers_progress, cov.done, cov.total, cov.found) else tr(R.string.settings_find_covers),
            tr(R.string.settings_covers_hint), enabled = !cov.running,
        ) { graph.appScope.launch { graph.covers.run() } }
    }

    // --- Downloads & sync ---
    add(SettingsCategory.DOWNLOADS, tr(R.string.settings_download_dir), tr(R.string.settings_download_dir_hint_1) + tr(R.string.settings_download_dir_hint_2), "download folder загрузки папка") {
        FieldRow(tr(R.string.settings_download_dir), tr(R.string.settings_download_dir_hint_1) + tr(R.string.settings_download_dir_hint_2), settings.downloadDir, tr(R.string.settings_download_dir)) {
            launch { graph.settings.setDownloadDir(it.trim().trimEnd('/')) }
        }
    }
    val qualityTitle = tr(R.string.settings_quality)
    add(SettingsCategory.DOWNLOADS, qualityTitle, tr(R.string.settings_quality_hint), "quality flac lossless качество") {
        ChoiceRow(qualityTitle, tr(R.string.settings_quality_hint)) {
            mapOf(Quality.LOSSLESS to "Lossless (FLAC)", Quality.HIGH to tr(R.string.quality_high), Quality.NORMAL to tr(R.string.quality_saver)).forEach { (q, label) ->
                Pill(label, settings.quality == q, { launch { graph.settings.setQuality(q) } })
            }
        }
    }
    toggle(SettingsCategory.DOWNLOADS, tr(R.string.settings_keep_versions), tr(R.string.settings_keep_versions_hint), settings.keepVersions, "versions версии история") {
        launch { graph.settings.setKeepVersions(it) }
    }
    toggle(SettingsCategory.DOWNLOADS, tr(R.string.settings_background_sync), tr(R.string.settings_background_sync_hint), settings.autoSync, "sync синхронизация яндекс") {
        launch { graph.settings.setAutoSync(it) }
    }
    toggle(SettingsCategory.DOWNLOADS, tr(R.string.settings_auto_download_likes), tr(R.string.settings_auto_download_likes_hint), settings.autoDownloadLikes, "likes favorites лайки избранное") {
        launch { graph.settings.setAutoDownloadLikes(it) }
    }

    // --- Lyrics ---
    toggle(SettingsCategory.LYRICS, tr(R.string.settings_embed_lyrics), tr(R.string.settings_embed_lyrics_hint), settings.embedLyrics, "lyrics tags lrc текст теги") {
        launch { graph.settings.setEmbedLyrics(it) }
    }
    val trTitle = tr(R.string.translation)
    add(SettingsCategory.LYRICS, trTitle, tr(R.string.settings_translation_hint), "translate перевод") {
        ChoiceRow(trTitle, tr(R.string.settings_translation_hint)) {
            listOf(
                dev.xvin.palimpsest.data.settings.LyricsTranslation.OFF to R.string.lyrics_mode_original,
                dev.xvin.palimpsest.data.settings.LyricsTranslation.BELOW to R.string.lyrics_mode_both,
                dev.xvin.palimpsest.data.settings.LyricsTranslation.ONLY to R.string.translation,
            ).forEach { (m, label) -> Pill(tr(label), settings.lyricsTranslation == m, { launch { graph.settings.setLyricsTranslation(m) } }) }
        }
    }

    // --- Storage & trash ---
    add(SettingsCategory.STORAGE, tr(R.string.storage), tr(R.string.settings_storage_hint), "storage space cache память место кэш") {
        ActionRow(tr(R.string.storage), tr(R.string.settings_storage_hint)) { nav.openStorage() }
    }
    add(SettingsCategory.STORAGE, tr(R.string.trash), tr(R.string.trash_enabled_hint), "trash delete корзина удаление") {
        ActionRow(tr(R.string.trash), tr(R.string.storage_trash_hint, graph.trash.entries.collectAsStateWithLifecycle().value.size)) { nav.openTrash() }
        TrashSettings()
    }
    val cacheTitle = tr(R.string.storage_stream_limit)
    add(SettingsCategory.STORAGE, cacheTitle, tr(R.string.storage_stream_cache_hint), "cache кэш стриминг") {
        ChoiceRow(cacheTitle, tr(R.string.storage_stream_cache_hint)) {
            listOf(256, 512, 1024, 2048, 4096).forEach { mb ->
                val label = android.text.format.Formatter.formatShortFileSize(context, if (mb < 1024) mb * 1_000_000L else mb / 1024 * 1_000_000_000L)
                Pill(label, settings.streamCacheMb == mb, { launch { graph.settings.setStreamCacheMb(mb) } })
            }
        }
    }

    // --- Network & services ---
    add(SettingsCategory.NETWORK, tr(R.string.settings_proxy), tr(R.string.settings_proxy_hint), "proxy vpn youtube прокси") {
        FieldRow(tr(R.string.settings_proxy), tr(R.string.settings_proxy_hint), settings.proxy.orEmpty(), "host:port") { launch { graph.settings.setProxy(it) } }
    }
    add(SettingsCategory.NETWORK, "ListenBrainz", tr(R.string.settings_listenbrainz_hint), "scrobble last.fm скробблинг") {
        var lbToken by remember { mutableStateOf("") }
        var lbError by remember { mutableStateOf<String?>(null) }
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(tr(R.string.settings_scrobbling) + " · ListenBrainz", Modifier.padding(horizontal = 16.dp))
            if (settings.listenBrainzToken != null) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(settings.listenBrainzUser ?: tr(R.string.connected), Modifier.weight(1f), color = Ink.Muted)
                    TextButton(onClick = { launch { graph.settings.setListenBrainz(null, null) } }) { Text(tr(R.string.turn_off)) }
                }
            } else {
                Text(tr(R.string.settings_listenbrainz_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(lbToken, { lbToken = it }, Modifier.weight(1f), singleLine = true, label = { Text(tr(R.string.settings_listenbrainz_token)) })
                    TextButton(enabled = lbToken.isNotBlank(), onClick = {
                        launch {
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
        }
    }

    // --- Backup ---
    add(SettingsCategory.BACKUP, tr(R.string.backup), tr(R.string.backup_hint), "backup restore копия восстановить") {
        var backups by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
        var confirmRestore by remember { mutableStateOf<java.io.File?>(null) }
        LaunchedEffect(Unit) { backups = graph.backups.list() }
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(tr(R.string.backup_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
            TextButton(onClick = {
                launch {
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
    }

    // --- Updates ---
    toggle(SettingsCategory.UPDATES, tr(R.string.update_auto), tr(R.string.update_auto_hint), settings.checkUpdates, "update github обновление") {
        launch { graph.settings.setCheckUpdates(it) }
    }
    add(SettingsCategory.UPDATES, tr(R.string.update_check), tr(R.string.settings_update_check_hint, dev.xvin.palimpsest.BuildConfig.VERSION_NAME), "version версия") {
        UpdateBlock()
    }

    // --- Help & about ---
    add(SettingsCategory.ABOUT, tr(R.string.error_log), tr(R.string.settings_error_log_hint), "errors log ошибки журнал bug") {
        val errorCount by graph.errors.log.collectAsStateWithLifecycle()
        ActionRow(tr(R.string.error_log), if (errorCount.isEmpty()) tr(R.string.empty) else tr(R.string.error_log_count, errorCount.size)) { nav.openErrors() }
    }
    add(SettingsCategory.ABOUT, tr(R.string.settings_about), tr(R.string.about_body, dev.xvin.palimpsest.BuildConfig.VERSION_NAME), "about license версия лицензия") {
        Text(
            tr(R.string.about_body, dev.xvin.palimpsest.BuildConfig.VERSION_NAME),
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
        )
    }
    return list
}

/** «Check for updates», or the found update with its notes and an install button. */
@Composable
private fun UpdateBlock() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val update by graph.updater.available.collectAsStateWithLifecycle()
    val updateProgress by graph.updater.progress.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }
    val u = update
    if (u == null) {
        ActionRow(
            if (checking) tr(R.string.checking) else tr(R.string.update_check),
            tr(R.string.settings_update_check_hint, dev.xvin.palimpsest.BuildConfig.VERSION_NAME), enabled = !checking,
        ) {
            checking = true
            graph.appScope.launch {
                val found = runCatching { graph.updater.check() }.onFailure { nav.error(it, tr(R.string.update_check)) }.getOrNull()
                checking = false
                if (found == null) nav.toast(tr(R.string.update_up_to_date, dev.xvin.palimpsest.BuildConfig.VERSION_NAME))
            }
        }
    } else {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
                .clip(RoundedCornerShape(14.dp)).background(Ink.Paper2).padding(14.dp),
        ) {
            Text(tr(R.string.update_available, u.version), style = MaterialTheme.typography.titleMedium)
            if (u.notes.isNotBlank()) {
                Text(dev.xvin.palimpsest.data.update.AppUpdater.plainNotes(u.notes).take(1200), style = MaterialTheme.typography.bodySmall, color = Ink.Muted, modifier = Modifier.padding(top = 6.dp))
            }
            val p = updateProgress
            if (p != null) {
                androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            } else {
                Button(
                    onClick = {
                        // First update ever: Android wants "install unknown apps" allowed for us; ask before downloading.
                        if (!context.packageManager.canRequestPackageInstalls()) {
                            nav.toast(tr(R.string.update_allow_install))
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + context.packageName)),
                            )
                        } else {
                            graph.appScope.launch { runCatching { graph.updater.install(u) }.onFailure { nav.error(it, tr(R.string.update_install)) } }
                        }
                    },
                    modifier = Modifier.padding(top = 10.dp),
                ) { Text(tr(R.string.update_download_install)) }
            }
        }
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
