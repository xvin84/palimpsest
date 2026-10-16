package dev.xvin.palimpsest.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr

/** A track from a service (search, artist page, album, radio) that isn't necessarily in the library. */
@Composable
fun RemoteTrackRow(
    r: RemoteTrack,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
) {
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onPlay, onLongClick = onMore).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text("$index", style = MaterialTheme.typography.labelMedium, color = Ink.Faint, modifier = Modifier.width(28.dp))
        } else {
            Artwork(r.coverUrl, r.album ?: r.title, Modifier.size(48.dp), corner = 6.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (r.available) Ink.Text else Ink.Faint)
            Text(
                listOfNotNull(r.artistLine.ifBlank { null }, r.album.takeIf { index == null }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(formatDuration(r.durationMs), style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
        IconButton(onClick = onDownload, enabled = r.available) { Icon(Icons.Rounded.Download, tr(R.string.download), tint = Ink.Muted) }
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, tr(R.string.more), tint = Ink.Muted) }
    }
}
