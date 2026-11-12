package dev.xvin.palimpsest.core.radio

import dev.xvin.palimpsest.core.analysis.AudioFeatures
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.model.Service
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/** A song of your own the wave can pick, with what's known about how you listen to it. */
data class WaveCandidate(
    val id: String,
    val title: String,
    val artists: List<String>,
    val durationMs: Long = 0,
    val liked: Boolean = false,
    val plays: Int = 0,
    val lastPlayedAt: Long? = null,
    /** Times it was skipped in its first half minute, and when last. */
    val skips: Int = 0,
    val lastSkippedAt: Long? = null,
    val bpm: Float? = null,
    val musicalKey: String? = null,
    val energy: Float? = null,
)

/** Why the wave picked a song — shown under it as «why this one». */
enum class WaveReason { FAVOURITE, ARTIST_YOU_LOVE, NEW_TO_YOU, LONG_AGO, OFTEN, FLOWS_ON }

/** What the offline wave should feel like; songs that don't fit are rare, not banned. */
enum class WaveMoodKind { ANY, CALM, ENERGETIC, FOCUS, SAD }

/**
 * The wave from your own files. Not a fixed list: it picks a few songs at a time and learns as it
 * plays — a song skipped in its first half minute makes that artist rarer for the rest of the
 * session and the song itself rarer for weeks; «not this» bans a song or an artist for good; the
 * same artist doesn't come back within the next few songs; [novelty] leans between the songs you
 * know (0) and the ones you hardly ever play (1). With audio analysed it also follows a mood and
 * orders each batch so tempo, key and energy flow on.
 */
class LocalWave(
    private val candidates: suspend () -> List<WaveCandidate>,
    private val banned: suspend () -> Set<String> = { emptySet() },
    private val novelty: () -> Float = { 0.5f },
    private val mood: WaveMoodKind = WaveMoodKind.ANY,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val onSkip: suspend (id: String) -> Unit = {},
) : StationSource {
    override val allowsRepeats: Boolean get() = true

    private val sessionSkips = HashMap<String, Int>()
    private val recentArtists = ArrayDeque<String>()
    private val reasons = HashMap<String, WaveReason>()
    private var lastPicked: WaveCandidate? = null

    /** Why [id] was picked, if this wave picked it. */
    fun reasonFor(id: String): WaveReason? = reasons[id]

    override suspend fun open(avoid: List<String>): List<RemoteTrack> {
        sessionSkips.clear(); recentArtists.clear(); lastPicked = null
        return pick(avoid)
    }

    override suspend fun more(avoid: List<String>): List<RemoteTrack> = pick(avoid)

    override suspend fun ended(track: RemoteTrack, playedSec: Double, finished: Boolean) {
        if (finished || playedSec >= SKIP_SEC) return
        track.artists.forEach { a -> val k = a.lowercase(); sessionSkips[k] = (sessionSkips[k] ?: 0) + 1 }
        onSkip(track.id)
    }

    private suspend fun pick(avoid: List<String>): List<RemoteTrack> {
        val all = candidates()
        if (all.isEmpty()) return emptyList()
        val bans = banned()
        val t = now()
        val heardRecently = avoid.mapNotNullTo(HashSet()) { k -> k.takeIf { it.startsWith("${Service.LOCAL}:") }?.substringAfter(':') }
        val pool = all.filter { c -> "t:${c.id}" !in bans && c.artists.none { "a:${it.lowercase()}" in bans } }
        if (pool.isEmpty()) return emptyList()

        // How much you listen to each artist: plays, minus what you skipped of theirs.
        val affinity = HashMap<String, Double>()
        for (c in pool) for (a in c.artists) {
            val k = a.lowercase()
            affinity[k] = (affinity[k] ?: 0.0) + c.plays - 0.5 * c.skips
        }
        val maxAffinity = affinity.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val n = novelty().coerceIn(0f, 1f).toDouble()
        // Heard in the last hours: left out, unless that's the whole library (a small one, or a long day).
        val fresh = pool.filter { c -> (c.lastPlayedAt?.let { t - it >= HOURS } ?: true) && c.id !in heardRecently }
        val from = if (fresh.size >= BATCH) fresh else pool.filter { it.id !in heardRecently }.ifEmpty { pool }

        fun weightOf(c: WaveCandidate): Pair<Double, WaveReason> {
            val since = c.lastPlayedAt?.let { t - it } ?: Long.MAX_VALUE
            val artist = c.artists.maxOfOrNull { (affinity[it.lowercase()] ?: 0.0).coerceAtLeast(0.0) } ?: 0.0
            val known = if (c.plays == 0) 0.4 + 1.6 * n else (0.4 + ln(1.0 + c.plays)) * (1.4 - n)
            var w = known + (if (c.liked) 1.5 else 0.0) + 2.0 * artist / maxAffinity * (1.2 - 0.6 * n)
            w *= when {
                since < HOURS -> 0.05
                since < 2 * DAY -> 0.25
                since > 60 * DAY && c.plays > 0 -> 1.4 // forgotten favourites come back
                else -> 1.0
            }
            // Skipped songs fade for a while: each skip halves it, the effect wearing off over a month.
            if (c.skips > 0) {
                val age = c.lastSkippedAt?.let { (t - it).toDouble() / (30 * DAY) }?.coerceIn(0.0, 1.0) ?: 1.0
                w *= 0.5.pow(c.skips.coerceAtMost(4) * (1 - age))
            }
            // Skipped this session: that artist much rarer until the wave is shaken up.
            val skippedNow = c.artists.sumOf { sessionSkips[it.lowercase()] ?: 0 }
            w *= 0.25.pow(skippedNow.coerceAtMost(3))
            w *= fit(c)
            val reason = when {
                c.liked -> WaveReason.FAVOURITE
                c.plays == 0 -> WaveReason.NEW_TO_YOU
                since > 60 * DAY -> WaveReason.LONG_AGO
                artist / maxAffinity > 0.5 -> WaveReason.ARTIST_YOU_LOVE
                else -> WaveReason.OFTEN
            }
            return w * (0.6 + random.nextDouble() * 0.8) to reason
        }

        // Weighted sampling without replacement (Efraimidis–Spirakis), a few more than needed so
        // the artist rule and the flow have something to choose from.
        val scored = from.mapNotNull { c -> weightOf(c).takeIf { it.first > 0 }?.let { (w, r) -> Triple(c, r, random.nextDouble().pow(1.0 / w)) } }
            .sortedByDescending { it.third }
        val picked = ArrayList<Pair<WaveCandidate, WaveReason>>()
        for ((c, r, _) in scored) {
            if (picked.size >= BATCH) break
            val artists = c.artists.map { it.lowercase() }
            val tooSoon = artists.any { it in recentArtists } || picked.any { p -> p.first.artists.any { it.lowercase() in artists } }
            if (tooSoon && scored.size > BATCH * 2) continue
            picked += c to r
        }
        // The rule can't be kept in a library of few artists: fill up with the best of the rest.
        if (picked.size < BATCH) scored.map { it.first to it.second }.filter { p -> picked.none { it.first.id == p.first.id } }.take(BATCH - picked.size).let(picked::addAll)

        val ordered = flow(picked)
        for ((c, r) in ordered) {
            reasons[c.id] = r
            c.artists.forEach { recentArtists.addLast(it.lowercase()) }
            while (recentArtists.size > ARTIST_GAP) recentArtists.removeFirst()
        }
        lastPicked = ordered.lastOrNull()?.first ?: lastPicked
        return ordered.map { (c, _) -> RemoteTrack(Service.LOCAL, c.id, c.title, c.artists, durationMs = c.durationMs) }
    }

    /**
     * Greedy chain through the picks from the last song played: each next one is the smoothest of a
     * few still-likely ones. Marked «flows on» when it was chosen for that rather than its place.
     */
    private fun flow(picked: List<Pair<WaveCandidate, WaveReason>>): List<Pair<WaveCandidate, WaveReason>> {
        if (picked.count { (it.first.bpm ?: 0f) > 0 } < picked.size / 2) return picked
        val left = picked.toMutableList()
        val out = ArrayList<Pair<WaveCandidate, WaveReason>>(picked.size)
        var a = lastPicked
        while (left.isNotEmpty()) {
            val window = left.take(LOOKAHEAD)
            val next = if (a == null) window.first() else window.minBy { b ->
                AudioFeatures.transitionCost(a!!.bpm, a!!.musicalKey, a!!.energy, b.first.bpm, b.first.musicalKey, b.first.energy) +
                    left.indexOf(b) * 0.15 + random.nextDouble() * 0.3
            }
            left.remove(next)
            out += if (a != null && left.isNotEmpty() && next !== window.first()) next.first to WaveReason.FLOWS_ON else next
            a = next.first
        }
        return out
    }

    private fun fit(c: WaveCandidate): Double {
        if (mood == WaveMoodKind.ANY) return 1.0
        val bpm = c.bpm?.takeIf { it > 0 } ?: return 0.25
        val e = c.energy ?: 0.5f
        val minor = c.musicalKey?.endsWith("A") == true
        val score = when (mood) {
            WaveMoodKind.CALM -> (1 - e) * 1.6 + (if (bpm < 100) 0.6 else if (bpm < 115) 0.2 else 0.0)
            WaveMoodKind.ENERGETIC -> e * 1.6 + (if (bpm in 118f..175f) 0.6 else 0.0)
            WaveMoodKind.FOCUS -> (1 - abs(e - 0.55) * 3).coerceAtLeast(0.0) + (if (bpm in 85f..125f) 0.6 else 0.0)
            WaveMoodKind.SAD -> (if (minor) 0.9 else 0.0) + (1 - e) * 0.9 + (if (bpm < 105) 0.4 else 0.0)
            WaveMoodKind.ANY -> 1.0
        }
        return (score * score).coerceAtLeast(0.02)
    }

    companion object {
        /** Played less than this and not to the end: a skip. */
        const val SKIP_SEC = 30.0
        private const val BATCH = 8
        private const val ARTIST_GAP = 4
        private const val LOOKAHEAD = 6
        private const val HOURS = 6 * 3_600_000L
        private const val DAY = 86_400_000L
    }
}
