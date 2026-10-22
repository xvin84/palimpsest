package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xvin.palimpsest.core.dsp.EqProfile
import dev.xvin.palimpsest.core.model.Quality
import dev.xvin.palimpsest.desktop.data.DesktopSettings
import dev.xvin.palimpsest.desktop.data.DesktopStyle
import dev.xvin.palimpsest.desktop.data.DesktopTheme
import dev.xvin.palimpsest.desktop.data.Rg
import dev.xvin.palimpsest.desktop.data.TrayStyle
import dev.xvin.palimpsest.desktop.tray.TrayArt
import dev.xvin.palimpsest.desktop.tray.TrayController
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.Image
import dev.xvin.palimpsest.desktop.playback.AudioEngine
import kotlinx.coroutines.launch

/** Settings are grouped as on the phone; each group has an icon and a line on what's inside. */
private enum class Category(val icon: ImageVector) {
    APPEARANCE(Icons.Rounded.Palette), SOUND(Icons.Rounded.GraphicEq), LIBRARY(Icons.Rounded.LibraryMusic),
    DOWNLOADS(Icons.Rounded.Download), WINDOW(Icons.Rounded.DesktopWindows), NETWORK(Icons.Rounded.Hub), PHONE(Icons.Rounded.Smartphone), ABOUT(Icons.Rounded.Info);

    val title: String get() = when (this) {
        APPEARANCE -> t("Оформление", "Appearance")
        SOUND -> t("Звук", "Sound")
        LIBRARY -> t("Медиатека", "Library")
        DOWNLOADS -> t("Загрузки", "Downloads")
        WINDOW -> t("Окно и трей", "Window and tray")
        NETWORK -> t("Сеть", "Network")
        PHONE -> t("Телефон", "Phone")
        ABOUT -> t("О программе", "About")
    }

    val summary: String get() = when (this) {
        APPEARANCE -> t("Стиль, тема, цвет из обложки, язык", "Style, theme, colour from the cover, language")
        SOUND -> t("Выравнивание громкости, эквалайзер", "Volume levelling, equalizer")
        LIBRARY -> t("Папки с музыкой, теги и тексты", "Music folders, tags and lyrics")
        DOWNLOADS -> t("Куда и в каком качестве", "Where to and in what quality")
        WINDOW -> t("Иконка в трее и что делает крестик", "The tray icon and what closing the window does")
        NETWORK -> t("Прокси для YouTube и Bandcamp", "A proxy for YouTube and Bandcamp")
        PHONE -> t("Лайки, плейлисты и история с телефона", "Likes, playlists and history with the phone")
        ABOUT -> t("Версия, горячие клавиши, лицензия", "Version, keys, licence")
    }
}

/** One setting: where it lives, what it's called and does (searchable, with a few extra words), and how it's drawn. */
private class Item(val category: Category, val title: String, val hint: String, val keywords: String, val content: @Composable () -> Unit) {
    fun matches(words: List<String>): Boolean {
        val hay = listOf(title, hint, keywords, category.title).joinToString(" ").lowercase().replace('ё', 'е')
        return words.all { it in hay }
    }
}

/**
 * «Настройки»: a search and the groups on the left, the chosen group on the right; in a narrow
 * window the groups first and a group on its own, as on the phone.
 */
@Composable
fun SettingsScreen() {
    val graph = LocalGraph.current
    val s by graph.settings.state.collectAsState()
    var category by remember { mutableStateOf<Category?>(null) }
    var query by remember { mutableStateOf("") }
    val items = settingItems(s)
    val words = query.lowercase().replace('ё', 'е').split(' ').filter { it.isNotBlank() }
    val ffmpegOk = remember { AudioEngine.ffmpegAvailable() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        val search = @Composable {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = if (query.isEmpty()) null else { { IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, t("Очистить", "Clear")) } } },
                placeholder = { Text(t("Поиск по настройкам", "Search settings"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                shape = RoundedCornerShape(14.dp),
            )
        }
        val shown = when {
            words.isNotEmpty() -> items.filter { it.matches(words) }
            else -> items.filter { it.category == (category ?: if (wide) Category.APPEARANCE else null) }
        }
        val pane = @Composable { modifier: Modifier ->
            LazyColumn(modifier.padding(horizontal = 28.dp, vertical = 20.dp)) {
                if (!ffmpegOk) item { FfmpegWarning() }
                if (words.isNotEmpty()) {
                    item { Text(t("Найдено", "Found"), style = MaterialTheme.typography.displayMedium) }
                    if (shown.isEmpty()) item { Text(t("Ничего не нашлось", "Nothing found"), color = LocalPal.current.muted, modifier = Modifier.padding(vertical = 12.dp)) }
                    shown.groupBy { it.category }.forEach { (c, list) ->
                        item { SectionHeader(c.title) }
                        items(list) { it.content() }
                    }
                } else {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!wide) IconButton(onClick = { category = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, t("Назад", "Back")) }
                            Text(shown.firstOrNull()?.category?.title.orEmpty(), style = MaterialTheme.typography.displayMedium)
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                    items(shown) { it.content() }
                    item { Spacer(Modifier.height(40.dp)) }
                }
            }
        }

        if (wide) Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 20.dp, end = 8.dp, bottom = 20.dp)) {
                Text(t("Настройки", "Settings"), style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
                search()
                Spacer(Modifier.height(10.dp))
                Category.entries.forEach { c -> CategoryRow(c, selected = words.isEmpty() && c == (category ?: Category.APPEARANCE), arrow = false) { query = ""; category = c } }
            }
            pane(Modifier.weight(1f).fillMaxHeight())
        } else if (category == null && words.isEmpty()) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 20.dp)) {
                item { Text(t("Настройки", "Settings"), style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(bottom = 12.dp)) }
                if (!ffmpegOk) item { FfmpegWarning() }
                item { search() }
                item { Spacer(Modifier.height(10.dp)) }
                items(Category.entries) { c -> CategoryRow(c, selected = false, arrow = true) { category = c } }
            }
        } else Column(Modifier.fillMaxSize()) {
            if (words.isNotEmpty()) Box(Modifier.padding(start = 28.dp, end = 28.dp, top = 20.dp)) { search() }
            pane(Modifier.weight(1f))
        }
    }
}

@Composable
private fun FfmpegWarning() {
    Text(
        t("Не найден ffmpeg — без него музыка не играет. Установи ffmpeg (Linux: пакет ffmpeg) или возьми сборку с ним со страницы релизов.",
            "ffmpeg wasn’t found — music can’t play without it. Install ffmpeg (Linux: the ffmpeg package) or use a release build that bundles it."),
        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun CategoryRow(c: Category, selected: Boolean, arrow: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val pal = LocalPal.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) pal.paper3 else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(c.icon, null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.titleMedium)
            Text(c.summary, color = pal.muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (arrow) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = pal.faint)
    }
}

@Composable
private fun Setting(title: String, hint: String?, content: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().widthIn(max = 680.dp).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        hint?.let { Text(it, color = LocalPal.current.muted, fontSize = 13.sp) }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Toggle(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.widthIn(max = 680.dp).fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(hint, color = LocalPal.current.muted, fontSize = 13.sp) }
        Switch(checked, onChange)
    }
}

/** The style switch: a small drawing of each style; one click applies it to the whole window. */
@Composable
private fun StylePicker(current: DesktopStyle, onPick: (DesktopStyle) -> Unit) {
    val pal = LocalPal.current
    val primary = MaterialTheme.colorScheme.primary
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DesktopStyle.entries.forEach { style ->
            val selected = style == current
            Column(
                Modifier.width(190.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (selected) primary.copy(alpha = 0.14f) else pal.paper2)
                    .border(2.dp, if (selected) primary else Color.Transparent, RoundedCornerShape(16.dp))
                    .clickable { onPick(style) }
                    .padding(10.dp),
            ) {
                StylePreview(style, Modifier.fillMaxWidth().aspectRatio(1.4f))
                Spacer(Modifier.height(8.dp))
                Text(if (style == DesktopStyle.LANE) "Lane" else "Palimpsest", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (style == DesktopStyle.LANE) t("Фон в цвете обложки, жирный гротеск, белые кнопки", "Backgrounds in the cover’s colour, a bold sans, white buttons")
                    else t("Чернила и бумага, засечки, акцент из обложки", "Ink and paper, a serif, the accent from the cover"),
                    color = pal.muted, fontSize = 12.sp,
                )
            }
        }
    }
}

/** The tray icon's looks, each drawn by the code that draws the real icon, as it looks while playing. */
@Composable
private fun TrayStylePicker(current: TrayStyle, onPick: (TrayStyle) -> Unit) {
    val pal = LocalPal.current
    val primary = MaterialTheme.colorScheme.primary
    val accent = LocalAccent.current.toArgb()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TrayStyle.entries.forEach { style ->
            val selected = style == current
            val (name, desc) = when (style) {
                TrayStyle.LETTER -> t("Буква", "Letter") to t("P с точкой, которая дышит в цвете обложки", "A P with a dot that breathes in the cover’s colour")
                TrayStyle.BARS -> t("Эквалайзер", "Bars") to t("Столбики пляшут под музыку", "Bars that dance to the music")
                TrayStyle.VINYL -> t("Пластинка", "Record") to t("Крутится; наклейка в цвете обложки", "Spins; the label takes the cover’s colour")
                TrayStyle.MONO -> t("Простая", "Plain") to t("Одноцветная, под любую панель", "One colour, fits any panel")
            }
            val image = remember(style, accent) {
                TrayArt.draw(TrayArt.Look(style, playing = true, accent = accent, cover = accent, levels = listOf(0.5f, 0.95f, 0.7f, 0.35f), phase = 0.15f), 96).toComposeImageBitmap()
            }
            Column(
                Modifier.width(150.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (selected) primary.copy(alpha = 0.14f) else pal.paper2)
                    .border(2.dp, if (selected) primary else Color.Transparent, RoundedCornerShape(16.dp))
                    .clickable { onPick(style) }
                    .padding(10.dp),
            ) {
                // On a panel-like strip, so it reads the way the tray shows it.
                Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1C1F)), contentAlignment = Alignment.Center) {
                    Image(image, null, Modifier.size(40.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(desc, color = pal.muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

/** A tiny drawing of the window in each style: sidebar, cover, title lines and the play button. */
@Composable
private fun StylePreview(style: DesktopStyle, modifier: Modifier) {
    val accent = LocalAccent.current
    val tint = LocalTint.current
    Canvas(modifier.clip(RoundedCornerShape(10.dp))) {
        val w = size.width
        val h = size.height
        when (style) {
            DesktopStyle.PALIMPSEST -> {
                drawRect(Color(0xFF0F0E0C))
                drawRect(Color(0xFF171513), size = Size(w * 0.22f, h))
                for (i in 0 until 4) drawRect(Color(0xFF6A635A), Offset(w * 0.04f, h * (0.12f + i * 0.1f)), Size(w * 0.13f, h * 0.03f))
                val side = h * 0.46f
                drawRoundRect(accent.copy(alpha = 0.85f), Offset(w * 0.3f, h * 0.1f), Size(side, side), CornerRadius(w * 0.02f))
                drawRect(Color(0xFFEDE6DA), Offset(w * 0.3f + side + w * 0.04f, h * 0.2f), Size(w * 0.3f, h * 0.06f))
                drawRect(Color(0xFF9C9488), Offset(w * 0.3f + side + w * 0.04f, h * 0.31f), Size(w * 0.2f, h * 0.04f))
                for (k in 0 until 3) drawRect(Color(0xFF2E2A26), Offset(w * 0.3f, h * (0.64f + k * 0.07f)), Size(w * 0.62f, h * 0.035f))
                drawRect(Color(0xFF171513), Offset(0f, h * 0.86f), Size(w, h * 0.14f))
                drawCircle(accent, h * 0.05f, Offset(w / 2, h * 0.93f))
            }
            DesktopStyle.LANE -> {
                drawRect(Brush.verticalGradient(listOf(lerp(Color(0xFF0B0B0D), tint, 0.55f), Color(0xFF0B0B0D))))
                drawRect(lerp(Color(0xFF151518), tint, 0.2f), size = Size(w * 0.22f, h))
                for (i in 0 until 4) drawRect(Color(0xFF6E6E76), Offset(w * 0.04f, h * (0.12f + i * 0.1f)), Size(w * 0.13f, h * 0.03f))
                val side = h * 0.46f
                drawRoundRect(tint, Offset(w * 0.3f, h * 0.1f), Size(side, side), CornerRadius(w * 0.02f))
                drawRect(Color.White, Offset(w * 0.3f + side + w * 0.04f, h * 0.18f), Size(w * 0.32f, h * 0.08f))
                drawRect(Color(0xFFA7A7AF), Offset(w * 0.3f + side + w * 0.04f, h * 0.31f), Size(w * 0.2f, h * 0.04f))
                for (k in 0 until 3) drawRect(Color.White.copy(alpha = 0.12f), Offset(w * 0.3f, h * (0.64f + k * 0.07f)), Size(w * 0.62f, h * 0.035f))
                drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, h * 0.86f), Size(w, h * 0.14f))
                drawRoundRect(Color.White, Offset(w * 0.43f, h * 0.895f), Size(w * 0.14f, h * 0.07f), CornerRadius(h * 0.035f))
            }
        }
    }
}

/** Every option, by group. Built on each composition, so it shows the current values and language. */
@Composable
private fun settingItems(s: DesktopSettings): List<Item> {
    val graph = LocalGraph.current
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    val list = mutableListOf<Item>()
    fun add(c: Category, title: String, hint: String, keywords: String = "", content: @Composable () -> Unit) { list += Item(c, title, hint, keywords, content) }
    fun toggle(c: Category, title: String, hint: String, checked: Boolean, keywords: String = "", onChange: (Boolean) -> Unit) =
        add(c, title, hint, keywords) { Toggle(title, hint, checked, onChange) }

    // --- Appearance ---
    val styleTitle = t("Стиль", "Style")
    add(Category.APPEARANCE, styleTitle, t("Как выглядит всё окно", "How the whole window looks"), "style стиль lane palimpsest дизайн design look") {
        Setting(styleTitle, null) { StylePicker(s.style) { st -> graph.settings.update { it.copy(style = st) } } }
    }
    val themeTitle = t("Тема", "Theme")
    add(Category.APPEARANCE, themeTitle, t("Тёмная, чёрная или светлая", "Dark, black or light"), "theme dark light amoled тема тёмная светлая чёрная") {
        Setting(themeTitle, t("Тёмная, чёрная или светлая", "Dark, black or light")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(DesktopTheme.DARK to t("Тёмная", "Dark"), DesktopTheme.AMOLED to t("Чёрная", "Black"), DesktopTheme.LIGHT to t("Светлая", "Light"))
                    .forEach { (th, l) -> Pill(l, s.theme == th, { graph.settings.update { it.copy(theme = th) } }) }
            }
        }
    }
    toggle(Category.APPEARANCE, t("Цвет из обложки", "Colour from the cover"), t("Акцент подстраивается под текущий трек", "The accent follows the current track"), s.dynamicColor, "color colour accent цвет акцент") { v ->
        graph.settings.update { it.copy(dynamicColor = v) }
    }
    val langTitle = t("Язык", "Language")
    val langHint = t("Меняется сразу, без перезапуска", "Changes right away, no restart")
    add(Category.APPEARANCE, langTitle, langHint, "language язык english русский") {
        Setting(langTitle, langHint) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to t("Как в системе", "System"), "ru" to "Русский", "en" to "English").forEach { (tag, l) ->
                    Pill(l, s.language == tag, { language = tag; graph.settings.update { it.copy(language = tag) } })
                }
            }
        }
    }

    // --- Sound ---
    val rgTitle = t("Выравнивание громкости (ReplayGain)", "Volume levelling (ReplayGain)")
    val rgHint = t("Тихие и громкие треки звучат одинаково — по меткам ReplayGain в файлах", "Quiet and loud tracks play at the same level, from the ReplayGain tags in files")
    add(Category.SOUND, rgTitle, rgHint, "replaygain volume loudness громкость") {
        Setting(rgTitle, rgHint) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Rg.OFF to t("Выкл.", "Off"), Rg.TRACK to t("По треку", "Track"), Rg.ALBUM to t("По альбому", "Album"))
                    .forEach { (r, l) -> Pill(l, s.replayGain == r, { graph.settings.update { it.copy(replayGain = r) } }) }
            }
        }
    }
    val preampTitle = t("Предусиление", "Preamp")
    val preampHint = t("Добавляется к ReplayGain; если тихо — прибавь", "Added on top of ReplayGain; raise it if it’s too quiet")
    add(Category.SOUND, preampTitle, preampHint, "preamp gain предусиление громкость") {
        Setting(preampTitle, preampHint) {
            Text("%+.1f dB".format(s.preampDb), color = pal.muted, fontSize = 13.sp)
            Slider(s.preampDb, { v -> graph.settings.update { it.copy(preampDb = (v * 2).toInt() / 2f) } }, valueRange = -6f..12f, modifier = Modifier.widthIn(max = 500.dp))
        }
    }
    val eqTitle = t("Эквалайзер", "Equalizer")
    val eqHint = t("Тот же 10-полосный эквалайзер, что и на телефоне", "The same 10-band equalizer as on the phone")
    add(Category.SOUND, eqTitle, eqHint, "eq equalizer эквалайзер басы bass") {
        Toggle(eqTitle, eqHint, s.eqEnabled) { v -> graph.settings.update { it.copy(eqEnabled = v) } }
        if (s.eqEnabled) FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EqProfile.PRESETS.forEach { p -> Pill(p.name, s.eqProfile == p.name, { graph.settings.update { it.copy(eqProfile = p.name) } }) }
        }
    }

    // --- Library ---
    val foldersTitle = t("Папки с музыкой", "Music folders")
    val foldersHint = t("Подпапки учитываются, папки с точкой в начале — нет.", "Subfolders are included; folders starting with a dot are skipped.")
    add(Category.LIBRARY, foldersTitle, foldersHint, "folders папки путь path") {
        var newRoot by remember { mutableStateOf("") }
        Setting(foldersTitle, foldersHint) {
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
        }
    }
    val scanTitle = t("Обновить медиатеку", "Rescan library")
    val scanHint = t("Найти новые и удалённые файлы. «Перечитать все теги» — заново прочитать теги каждого файла.", "Find new and removed files. «Re-read all tags» reads every file’s tags again.")
    add(Category.LIBRARY, scanTitle, scanHint, "scan rescan tags сканировать теги обновить") {
        Setting(scanTitle, scanHint) {
            Row {
                TextButton(onClick = { scope.launch { graph.library.scan() } }) { Text(scanTitle) }
                TextButton(onClick = { scope.launch { graph.library.scan(force = true) } }) { Text(t("Перечитать все теги", "Re-read all tags")) }
            }
        }
    }
    toggle(Category.LIBRARY, t("Сохранять тексты в файлы", "Save lyrics into files"), t("Найденный текст сразу пишется в тег файла", "Found lyrics go straight into the file’s tag"), s.embedLyrics, "lyrics tags lrc текст теги") { v ->
        graph.settings.update { it.copy(embedLyrics = v) }
    }

    // --- Downloads ---
    val dirTitle = t("Папка загрузок", "Download folder")
    val dirHint = t("Внутри будут папки «Яндекс», «YouTube», «Bandcamp».", "Inside go «Яндекс», «YouTube» and «Bandcamp» folders.")
    add(Category.DOWNLOADS, dirTitle, dirHint, "download folder загрузки папка путь") {
        var dir by remember(s.downloadDir) { mutableStateOf(s.downloadDir) }
        Setting(dirTitle, dirHint) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(dir, { dir = it }, Modifier.weight(1f), singleLine = true)
                TextButton(enabled = dir != s.downloadDir, onClick = { graph.settings.update { it.copy(downloadDir = dir.trim()) } }) { Text(t("Сохранить", "Save")) }
            }
        }
    }
    val qualityTitle = t("Качество", "Quality")
    val qualityHint = t("Для стриминга и загрузок; lossless — FLAC, если он есть у сервиса.", "For streaming and downloads; lossless is FLAC where available.")
    add(Category.DOWNLOADS, qualityTitle, qualityHint, "quality flac lossless качество") {
        Setting(qualityTitle, qualityHint) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Quality.LOSSLESS to "Lossless", Quality.HIGH to t("Высокое", "High"), Quality.NORMAL to t("Экономия", "Saver"))
                    .forEach { (q, l) -> Pill(l, s.quality == q, { graph.settings.update { it.copy(quality = q) } }) }
            }
        }
    }

    // --- Window and tray ---
    val tray = LocalTray.current
    val trayKind by (tray?.kind ?: kotlinx.coroutines.flow.MutableStateFlow(TrayController.Kind.NONE)).collectAsState()
    val noTray = s.trayIcon && trayKind == TrayController.Kind.NONE
    val trayTitle = t("Иконка в трее", "Tray icon")
    val trayHint = if (noTray) t("На этом рабочем столе нет трея — иконке некуда встать.", "This desktop has no tray for the icon to sit in.")
        else t("Клик — показать или спрятать окно, средняя кнопка — пауза, колёсико — громкость, правый клик — меню.",
            "Click to show or hide the window, middle-click to pause, wheel for volume, right-click for the menu.")
    toggle(Category.WINDOW, trayTitle, trayHint, s.trayIcon, "tray icon трей иконка панель menu bar") { v -> graph.settings.update { it.copy(trayIcon = v) } }
    val trayStyleTitle = t("Как выглядит иконка", "How the icon looks")
    val trayStyleHint = t("Пока играет музыка, иконка живёт; на паузе замирает.", "It moves while music plays and holds still when paused.")
    add(Category.WINDOW, trayStyleTitle, trayStyleHint, "tray icon style стиль иконки трей эквалайзер пластинка") {
        Setting(trayStyleTitle, trayStyleHint) { TrayStylePicker(s.trayStyle) { st -> graph.settings.update { it.copy(trayStyle = st) } } }
    }
    toggle(Category.WINDOW, t("Анимировать иконку", "Animate the icon"),
        t("Если панель плохо переносит живую иконку, выключи: она будет меняться только на паузе и со сменой трека.",
            "If your panel struggles with a moving icon, turn this off: it will change only on pause and with the track."),
        s.trayAnimate, "animate animation анимация движение") { v -> graph.settings.update { it.copy(trayAnimate = v) } }
    val closeTitle = t("Закрывать в трей", "Close to the tray")
    val closeHint = t("Крестик прячет окно, а музыка играет дальше. Выйти совсем — «Выйти» в меню иконки.",
        "Closing hides the window and the music plays on. To quit, use «Quit» in the icon's menu.")
    toggle(Category.WINDOW, closeTitle, closeHint, s.closeToTray && s.trayIcon, "close tray закрыть трей крестик выход background фон") { v ->
        graph.settings.update { it.copy(closeToTray = v, trayIcon = it.trayIcon || v) }
    }

    // --- Network ---
    val proxyTitle = t("Прокси", "Proxy")
    val proxyHint = t("Только для YouTube, Spotify, Bandcamp и баз обложек (например 127.0.0.1:10808 или socks://host:port). Яндекс ходит напрямую.", "Only for YouTube, Spotify, Bandcamp and cover databases (e.g. 127.0.0.1:10808 or socks://host:port). Yandex goes direct.")
    add(Category.NETWORK, proxyTitle, proxyHint, "proxy vpn socks youtube прокси") {
        var proxy by remember(s.proxy) { mutableStateOf(s.proxy.orEmpty()) }
        Setting(proxyTitle, proxyHint) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(proxy, { proxy = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("host:port") })
                TextButton(enabled = proxy.trim() != s.proxy.orEmpty(), onClick = { graph.settings.update { it.copy(proxy = proxy.trim().ifBlank { null }) } }) { Text(t("Сохранить", "Save")) }
            }
        }
    }

    // --- Phone ---
    val lanTitle = t("Синхронизация с телефоном", "Sync with the phone")
    val lanHint = t("Лайки, плейлисты и история — по домашней сети. На телефоне: Настройки → Резервная копия → Синхронизация с компьютером.",
        "Likes, playlists and history over the home network. On the phone: Settings → Backup → Sync with the computer.")
    add(Category.PHONE, lanTitle, lanHint, "sync phone lan wifi синхронизация телефон код") {
        val lan by graph.lanSync.status.collectAsState()
        Toggle(lanTitle, lanHint, s.lanSync) { v -> graph.settings.update { it.copy(lanSync = v) }; graph.lanSync.setEnabled(v) }
        if (lan.running) Column(Modifier.padding(bottom = 8.dp)) {
            Text(t("Код для нового телефона: ", "Code for a new phone: ") + lan.code, style = MaterialTheme.typography.titleLarge)
            Text(t("Компьютер: ", "Computer: ") + graph.lanSync.name + " · " + s.lanTokens.size + t(" телефон(ов) подключено", " phone(s) paired"), color = pal.muted, fontSize = 13.sp)
            lan.last?.let { Text(it, color = pal.muted, fontSize = 13.sp) }
            if (s.lanTokens.isNotEmpty()) TextButton(onClick = { graph.settings.update { it.copy(lanTokens = emptyMap()) } }) { Text(t("Отключить все телефоны", "Unpair all phones")) }
        }
        lan.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
    }

    // --- About ---
    val version = System.getProperty("jpackage.app-version") ?: "dev"
    val aboutTitle = "Palimpsest $version"
    val aboutHint = t("Palimpsest для компьютера. Свободная программа под GPL-3.0.", "Palimpsest for the desktop. Free software under GPL-3.0.")
    add(Category.ABOUT, aboutTitle, aboutHint, "version версия gpl license лицензия") { Setting(aboutTitle, aboutHint) }
    val keysTitle = t("Горячие клавиши", "Keys")
    val keysHint = t("Пробел — пауза, ←/→ — перемотка на 5 с, Ctrl+←/→ — трек назад/вперёд, Ctrl+F — поиск, Esc — назад. Двойной клик по треку — играть, правый клик — меню.",
        "Space — pause, ←/→ — seek 5 s, Ctrl+←/→ — previous/next track, Ctrl+F — search, Esc — back. Double-click a track to play it, right-click for its menu.")
    add(Category.ABOUT, keysTitle, keysHint, "keys shortcuts hotkeys клавиши горячие") { Setting(keysTitle, keysHint) }
    return list
}
