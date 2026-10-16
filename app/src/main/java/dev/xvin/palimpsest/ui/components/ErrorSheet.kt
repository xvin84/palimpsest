package dev.xvin.palimpsest.ui.components

import android.content.ClipData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.xvin.palimpsest.errors.ErrorInfo
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import kotlinx.coroutines.launch
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr

/** What went wrong, what to try, and the technical details one tap away (copyable). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorSheet(info: ErrorInfo, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showDetails by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.Paper2) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            info.context?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.Faint) }
            Text(info.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(info.explanation, color = Ink.Muted)
            if (info.hints.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(tr(R.string.error_what_to_do), style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                info.hints.forEach { h ->
                    Row(Modifier.padding(top = 6.dp)) {
                        Text("•  ", color = LocalAccent.current)
                        Text(h, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (showDetails) {
                SelectionContainer {
                    Text(
                        info.details,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Ink.Muted,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            Row {
                Button(onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Palimpsest error", "${info.title}\n${info.explanation}\n\n${info.details}"))) }
                    copied = true
                }) { Text(if (copied) tr(R.string.copied) else tr(R.string.copy_details)) }
                Spacer(Modifier.padding(4.dp))
                OutlinedButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) tr(R.string.hide) else tr(R.string.details_full)) }
            }
            TextButton(onClick = onDismiss) { Text(tr(R.string.close)) }
        }
    }
}
