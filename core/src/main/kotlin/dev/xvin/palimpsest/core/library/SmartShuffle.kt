package dev.xvin.palimpsest.core.library

import kotlin.random.Random

/**
 * Shuffle that sounds shuffled: a plain random order often puts two songs of one artist side by
 * side, which people hear as «not random». Each artist's songs are spread across the whole list at
 * roughly even gaps (with a little jitter), then the list is read in that order.
 */
object SmartShuffle {
    fun <T> order(items: List<T>, artistOf: (T) -> String, random: Random = Random.Default): List<T> {
        if (items.size < 3) return items.shuffled(random)
        val placed = ArrayList<Pair<Double, T>>(items.size)
        for ((_, songs) in items.groupBy { artistOf(it).lowercase() }) {
            val mine = songs.shuffled(random)
            val gap = 1.0 / mine.size
            val start = random.nextDouble() * gap
            mine.forEachIndexed { i, t -> placed += (start + i * gap + (random.nextDouble() - 0.5) * gap * 0.4) to t }
        }
        val out = placed.sortedBy { it.first }.map { it.second }.toMutableList()
        // What the spreading couldn't avoid (an artist with most of the songs): swap neighbours apart where possible.
        for (i in 1 until out.size) {
            if (artistOf(out[i]).equals(artistOf(out[i - 1]), true)) {
                val j = (i + 1 until out.size).firstOrNull { !artistOf(out[it]).equals(artistOf(out[i - 1]), true) } ?: continue
                out[i] = out[j].also { out[j] = out[i] }
            }
        }
        return out
    }
}
