package dev.xvin.palimpsest.core.radio

import dev.xvin.palimpsest.core.library.SmartShuffle
import dev.xvin.palimpsest.core.model.MusicProvider
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.model.SearchType
import kotlin.random.Random

/**
 * «Ностальгия»: songs of the years you pick (a year and three either side, or a span) — from your
 * own files tagged with those years, and from the services' «hits of <year>» collections, mixed.
 * A service song that says its year must be inside the span; reissues and «best of» compilations
 * carry the wrong year, so a song from such a collection without a year of its own is trusted to
 * the collection.
 */
class NostalgiaStation(
    private val years: IntRange,
    /** Your own files of those years (null: none, or leave them out). */
    private val local: StationSource?,
    /** Services to look the years' hits up on, best first. */
    private val providers: List<MusicProvider>,
    private val random: Random = Random.Default,
) : StationSource {
    private var pool = ArrayDeque<RemoteTrack>()
    private var loaded = false

    override suspend fun open(avoid: List<String>): List<RemoteTrack> {
        loaded = false
        pool.clear()
        return more(avoid)
    }

    override suspend fun more(avoid: List<String>): List<RemoteTrack> {
        if (!loaded) { load(avoid); loaded = true }
        val fromServices = List(minOf(BATCH, pool.size)) { pool.removeFirst() }
        // About a third from your own files, when there are any of those years.
        val mine = runCatching { local?.more(avoid) ?: emptyList() }.getOrDefault(emptyList()).take(BATCH / 3)
        val out = (fromServices.take(BATCH - mine.size) + mine).shuffled(random)
        if (out.isEmpty() && pool.isEmpty()) loaded = false // try the collections again next time
        return out
    }

    override suspend fun ended(track: RemoteTrack, playedSec: Double, finished: Boolean) {
        local?.ended(track, playedSec, finished)
    }

    private suspend fun load(avoid: List<String>) {
        val seen = avoid.toHashSet()
        val found = ArrayList<RemoteTrack>()
        // A few years across the span, so a wide one isn't all from its first year.
        val picks = years.shuffled(random).take(4)
        for (p in providers) {
            for (y in picks) {
                val lists = listOf("хиты $y", "$y hits").flatMap { q -> runCatching { p.searchAll(q, SearchType.PLAYLISTS).playlists.take(1) }.getOrDefault(emptyList()) }
                for (pl in lists) {
                    runCatching { p.playlistTracks(pl.id) }.getOrDefault(emptyList())
                        .filter { t -> t.available && (t.year == null || t.year in years) && "${t.service}:${t.id}" !in seen }
                        .let(found::addAll)
                }
            }
            if (found.size >= 60) break
        }
        pool = ArrayDeque(SmartShuffle.order(found.distinctBy { "${it.service}:${it.id}" }, { it.artists.firstOrNull().orEmpty() }, random))
    }

    companion object {
        const val SOURCE = "nostalgia"
        private const val BATCH = 9

        fun spec(years: IntRange, title: String) = StationSpec(SOURCE, listOf("from:${years.first}", "to:${years.last}"), title)

        fun yearsOf(spec: StationSpec): IntRange? {
            val from = spec.seeds.firstOrNull { it.startsWith("from:") }?.removePrefix("from:")?.toIntOrNull() ?: return null
            val to = spec.seeds.firstOrNull { it.startsWith("to:") }?.removePrefix("to:")?.toIntOrNull() ?: return null
            return from..to
        }

        /** The year and three either side — what «2012» means here. */
        fun around(year: Int) = (year - 3)..(year + 3)

    }
}
