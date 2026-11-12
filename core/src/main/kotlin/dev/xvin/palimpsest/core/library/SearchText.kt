package dev.xvin.palimpsest.core.library

/**
 * Search that doesn't care which alphabet you typed in: «vektor» finds «Вектор А», «эминем» finds
 * «Eminem». Both the query and what's searched are folded to one Latin spelling (Cyrillic
 * transliterated, and the usual variants of writing Russian names in Latin evened out).
 */
object SearchText {
    private val CYR = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sh",
        'ъ' to "", 'ы' to "i", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        'і' to "i", 'ї' to "i", 'є' to "e", 'ґ' to "g",
    )

    /** Plain lower case, «ё» as «е» — for exact and prefix ranking. */
    fun plain(s: String?): String = s.orEmpty().lowercase().replace('ё', 'е').trim()

    /** One Latin spelling for both alphabets. */
    fun fold(s: String?): String {
        val sb = StringBuilder()
        for (c in plain(s)) sb.append(CYR[c] ?: c)
        // The common variants of the same sound in Latin spellings of Russian names.
        return sb.toString()
            .replace("kh", "h").replace("ts", "c").replace("tz", "c").replace("sch", "sh").replace("ck", "k")
            .replace("ph", "f").replace('w', 'v').replace('x', 'h').replace('j', 'y').replace("yo", "e")
            .replace('y', 'i')
    }

    /** Every word of [query] appears in [fields], in either alphabet. */
    fun matches(query: String, vararg fields: String?): Boolean {
        val words = plain(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        val plainHay = fields.joinToString(" ") { plain(it) }
        if (words.all { it in plainHay }) return true
        val hay = fields.joinToString(" ") { fold(it) }
        return words.all { fold(it) in hay }
    }
}
