package dev.xvin.palimpsest.core.library

/**
 * Splits an artist tag like "1.Kla$, Цинк Уродов" or "1.Kla$;Czar feat. X" into individual
 * artists. Conservative on purpose: "&" and " x " are left alone because they are far more
 * often part of a single name ("Simon & Garfunkel") than a separator in Murglar/Yandex tags.
 */
object ArtistNames {
    /** Real names that contain a separator character. */
    private val KEEP_WHOLE = listOf(
        "Tyler, The Creator",
        "Earth, Wind & Fire",
        "Crosby, Stills, Nash & Young",
        "Emerson, Lake & Palmer",
        "Blood, Sweat & Tears",
        "Peter, Paul and Mary",
        "AC/DC",
    )

    private val separators = Regex("""\s*(?:[,;]|\s(?:feat\.?|ft\.?|featuring|при участии)\s|\s×\s)\s*""", RegexOption.IGNORE_CASE)

    fun split(line: String): List<String> {
        if (line.isBlank()) return emptyList()
        // Protect known names by swapping them for placeholders while splitting.
        var work = line
        val protected = mutableMapOf<String, String>()
        KEEP_WHOLE.forEachIndexed { i, name ->
            val idx = work.indexOf(name, ignoreCase = true)
            if (idx >= 0) {
                val key = "\u0000$i\u0000"
                protected[key] = work.substring(idx, idx + name.length)
                work = work.replaceRange(idx, idx + name.length, key)
            }
        }
        return work.split(separators)
            .map { part -> protected.entries.fold(part) { acc, (k, v) -> acc.replace(k, v) }.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
    }
}
