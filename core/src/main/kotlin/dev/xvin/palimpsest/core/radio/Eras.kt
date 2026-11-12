package dev.xvin.palimpsest.core.radio

/**
 * What music looked like in those years — the colour «Ностальгия» lends the app while it plays:
 * the players and phones of the time, not a palette picked for prettiness.
 */
enum class Era(val from: Int, val to: Int, val accent: Long, val ru: String, val en: String) {
    CASSETTE(Int.MIN_VALUE, 1989, 0xFFE8A33DL, "кассеты", "cassettes"),
    COMPACT_DISC(1990, 1999, 0xFF8F9BE8L, "компакт-диски", "compact discs"),
    WINAMP(2000, 2006, 0xFF8BD64EL, "Winamp", "Winamp"),
    GLOSS(2007, 2010, 0xFF3D8FE0L, "глянцевый iPod", "the glossy iPod"),
    HOLO(2011, 2013, 0xFF33B5E5L, "Holo", "Holo"),
    FLAT(2014, 2017, 0xFFFF4081L, "плоский дизайн", "flat design"),
    GRADIENT(2018, 2021, 0xFFB14FD8L, "градиенты", "gradients"),
    ;

    companion object {
        /** The era of a span is that of its middle year; recent years have none (they're now). */
        fun of(years: IntRange): Era? {
            val mid = (years.first + years.last) / 2
            return entries.firstOrNull { mid in it.from..it.to }
        }
    }
}
