package dev.xvin.palimpsest.core.radio

import dev.xvin.palimpsest.core.lyrics.GeniusLyrics
import dev.xvin.palimpsest.core.lyrics.LrcLibClient
import dev.xvin.palimpsest.core.lyrics.NetEaseLyrics
import dev.xvin.palimpsest.core.lyrics.Lrc
import dev.xvin.palimpsest.core.lyrics.cleaned
import dev.xvin.palimpsest.core.model.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * What a radio station says is on air («Artist - Song»), and its lyrics. Stations send only that line,
 * never lyrics; those are looked up like for any song. Without timings: where in the song the
 * station is, nobody knows — so synced lyrics are shown as plain text.
 */
object OnAir {
    /** «Artist - Song» → artist, song; null when the line isn't a song (a jingle, the station's slogan). */
    fun split(line: String?): Pair<String, String>? {
        val s = line?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val i = listOf(" - ", " – ", " — ").map { s.indexOf(it) }.filter { it > 0 }.minOrNull() ?: return null
        val artist = s.substring(0, i).trim()
        val title = s.substring(i + 3).trim().replace(Regex("""\s*[(\[](?:feat|ft|prod)\.?[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "").trim()
        return if (artist.isEmpty() || title.isEmpty()) null else artist to title
    }

    /** The song's lyrics as plain lines, from LRCLIB, NetEase or Genius — the first that has them; null when none does. */
    suspend fun lyrics(artist: String, title: String, lrclib: LrcLibClient, netEase: NetEaseLyrics, genius: GeniusLyrics): List<String>? = coroutineScope {
        val sources: List<suspend () -> Lyrics?> = listOf(
            { lrclib.find(artist, title, null, null) },
            { netEase.find(artist, title, 0) },
            { genius.find(artist, title) },
        )
        val found: List<Lyrics?> = sources.map { s -> async(Dispatchers.IO) { runCatching { withTimeout(15_000) { s() } }.getOrNull()?.cleaned() } }.awaitAll()
        found.firstNotNullOfOrNull { l ->
            val text = l?.plain ?: l?.synced ?: return@firstNotNullOfOrNull null
            Lrc.parse(text).lines.map { it.text }.filter { it.isNotBlank() }.takeIf { it.size >= 2 }
        }
    }
}
