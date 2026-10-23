package dev.xvin.palimpsest.core.yandex

import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.core.model.Quality
import kotlinx.coroutines.CancellationException

/**
 * «Is there a FLAC of this song?» for a file stored lossy: the linked Yandex track first, then
 * search results by artist, title and length (another release of the song often has a FLAC when
 * the linked one is MP3-only). Only a track that really gives a FLAC link counts. Shared by the
 * phone and the desktop.
 */
class FlacLookup(private val yandex: YandexMusicProvider) {
    sealed interface Match {
        data class Flac(val remoteId: String, val title: String, val artist: String) : Match
        /** Found on Yandex, but only in MP3/AAC. */
        data object LossyOnly : Match
        data object NotFound : Match
    }

    /**
     * Whether the account can get FLAC at all: without Yandex Plus every track comes as AAC 192,
     * so a search would find songs and then nothing to download. null: couldn't ask.
     */
    suspend fun accountHasPlus(): Boolean? = try {
        yandex.account().hasPlus
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** [rejected]: Yandex ids the user said aren't this song. */
    suspend fun find(title: String, artist: String, durationMs: Long, linkedId: String?, rejected: Set<String> = emptySet()): Match {
        val linked = linkedId?.takeIf { it !in rejected }
        val query = "$artist ${TrackMatcher.normalizeTitle(title)}".trim()
        val results = try {
            yandex.search(query).take(10)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val found = results
            .filter { it.available && it.id !in rejected && it.id != linked }
            .map { r -> r to TrackMatcher.score(title, artist, durationMs, r.title, r.artistLine, r.durationMs) }
            // ≥ 0.8: «almost certainly the same recording». The user still confirms by ear.
            .filter { it.second >= 0.8 }.sortedByDescending { it.second }.map { it.first }
        val candidates = (listOfNotNull(linked?.let { Triple(it, title, artist) }) + found.map { Triple(it.id, it.title, it.artistLine) })
            .distinctBy { it.first }.take(4)
        if (candidates.isEmpty()) return Match.NotFound
        for ((id, t, a) in candidates) if (hasFlac(id)) return Match.Flac(id, t, a)
        return Match.LossyOnly
    }

    /** Whether Yandex gives this track in FLAC (one link request, nothing downloaded). */
    suspend fun hasFlac(remoteId: String): Boolean = try {
        val codec = yandex.resolveStream(remoteId, Quality.LOSSLESS, forDownload = true).codec.lowercase()
        codec.startsWith("flac") || codec == "alac"
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
