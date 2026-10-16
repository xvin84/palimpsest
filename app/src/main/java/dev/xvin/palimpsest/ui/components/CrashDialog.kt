package dev.xvin.palimpsest.ui.components

import android.content.ClipData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.xvin.palimpsest.CrashReporter
import dev.xvin.palimpsest.ui.theme.Ink
import kotlinx.coroutines.launch

/** Shows the previous crash once, so it can be copied and sent. */
@Composable
fun CrashDialog() {
    val ctx = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf(CrashReporter.pending(ctx)) }
    val text = report ?: return
    fun close() { CrashReporter.clear(ctx); report = null }
    AlertDialog(
        onDismissRequest = ::close,
        containerColor = Ink.Paper2,
        title = { Text("Прошлый запуск закончился падением") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Скопируй отчёт и пришли его — так проще всего найти причину.", color = Ink.Muted)
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = Ink.Text)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("crash", text))) }
                close()
            }) { Text("Скопировать") }
        },
        dismissButton = { TextButton(onClick = ::close) { Text("Закрыть") } },
    )
}
