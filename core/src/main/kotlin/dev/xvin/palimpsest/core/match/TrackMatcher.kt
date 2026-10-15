package dev.xvin.palimpsest.core.match

import java.text.Normalizer
import kotlin.math.abs

/**
 * Fuzzy matching of the same song across services and local files ("Artist - Title" from a
 * Spotify playlist → a Yandex or YouTube track; duplicates in the library).
 */
object TrackMatcher {
    private val bracketed = Regex("""\s*[(\[](?:feat\.?|ft\.?|prod\.?|при уч\.?|with)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val featTail = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val noise = Regex("""\s*[(\[](?:official\s*(?:music\s*)?(?:video|audio)|lyrics?|lyric video|audio|hq|hd|4k|mv|клип)[)\]]""", RegexOption.IGNORE_CASE)
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    fun normalizeTitle(s: String): String = normalize(s.replace(noise, "").replace(bracketed, "").replace(featTail, ""))

    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s.lowercase().replace('ё', 'е'), Normalizer.Form.NFD)
        // Strip accents from Latin letters only ("Beyoncé" → "beyonce"); marks on Cyrillic (й)
        // and kana (ド) are part of the letter and must survive.
        val sb = StringBuilder(decomposed.length)
        var base = ' '
        for (c in decomposed) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) {
                if (base.code < 0x250) continue
            } else base = c
            sb.append(c)
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFC).replace(nonWord, " ").trim()
    }

    fun artistSet(artists: String): Set<String> =
        artists.split(Regex("""\s*(?:,|&|\band\b|\bи\b|\bx\b|×|feat\.?|ft\.?|;|/)\s*""", RegexOption.IGNORE_CASE))
            .map { normalize(it) }.filter { it.isNotEmpty() }.toSet()

    /**
     * Similarity score in 0..1. ≥ 0.8 is "almost certainly the same recording",
     * 0.6..0.8 "same song, maybe a different version".
     */
    fun score(
        titleA: String, artistsA: String, durationA: Long,
        titleB: String, artistsB: String, durationB: Long,
    ): Double {
        val t = similarity(normalizeTitle(titleA), normalizeTitle(titleB))
        val sa = artistSet(artistsA); val sb = artistSet(artistsB)
        val a = when {
            sa.isEmpty() || sb.isEmpty() -> 0.5
            else -> sa.intersect(sb).size.toDouble() / minOf(sa.size, sb.size)
        }
        val d = if (durationA <= 0 || durationB <= 0) 0.5 else {
            val diff = abs(durationA - durationB) / 1000.0
            when {
                diff <= 2 -> 1.0
                diff <= 6 -> 0.7
                diff <= 15 -> 0.3
                else -> 0.0
            }
        }
        return t * 0.5 + a * 0.3 + d * 0.2
    }

    /** Normalised Levenshtein similarity in 0..1. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            cur.copyInto(prev)
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
