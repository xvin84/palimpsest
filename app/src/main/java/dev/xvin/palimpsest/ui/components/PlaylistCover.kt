package dev.xvin.palimpsest.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.xvin.palimpsest.data.db.PlaylistEntity
import dev.xvin.palimpsest.data.db.TrackEntity

/**
 * A playlist's face: the picture you chose, else four covers from different albums in it, else the
 * one cover there is, else one generated from the name — so a list of playlists isn't a column of
 * the same grey icon.
 */
@Composable
fun PlaylistCover(playlist: PlaylistEntity, tracks: List<TrackEntity>, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    val custom = playlist.coverUrl
    if (custom != null) {
        Artwork(custom, playlist.name, modifier, corner)
        return
    }
    val faces = remember(tracks) { collageOf(tracks) }
    when {
        faces.size >= 4 -> Column(modifier.clip(RoundedCornerShape(corner))) {
            for (row in 0..1) Row(Modifier.fillMaxWidth().weight(1f)) {
                for (col in 0..1) TrackArtwork(faces[row * 2 + col], Modifier.weight(1f).fillMaxSize(), corner = 0.dp)
            }
        }
        faces.isNotEmpty() -> TrackArtwork(faces.first(), modifier, corner)
        else -> Box(modifier) { Artwork(null, playlist.name, Modifier.fillMaxSize(), corner) }
    }
}

/** Up to four tracks with a picture each, from different albums — the same album four times says nothing. */
private fun collageOf(tracks: List<TrackEntity>): List<TrackEntity> =
    tracks.asSequence()
        .filter { it.path != null || it.coverUrl != null }
        .distinctBy { it.albumKey }
        .take(4)
        .toList()
