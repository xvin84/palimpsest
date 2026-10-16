package dev.xvin.palimpsest.core.dsp

/**
 * Parses the "ParametricEQ.txt" format used by AutoEQ (github.com/jaakkopasanen/AutoEq)
 * and by Equalizer APO:
 *
 * ```
 * Preamp: -6.2 dB
 * Filter 1: ON LSC Fc 105 Hz Gain 2.5 dB Q 0.70
 * Filter 2: ON PK Fc 200 Hz Gain -3.0 dB Q 0.50
 * ```
 */
object AutoEqParser {
    private val preampRe = Regex("""^\s*Preamp:\s*(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)
    private val filterRe = Regex(
        """^\s*Filter(?:\s+\d+)?:\s*(ON|OFF)\s+([A-Z]+)(?:\s+[\d.]+\s*dB)?\s+Fc\s+([\d.]+)\s*Hz(?:\s+Gain\s+(-?[\d.]+)\s*dB)?(?:\s+Q\s+([\d.]+))?""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String, name: String): EqProfile {
        var preamp = 0.0
        val filters = mutableListOf<EqFilter>()
        for (line in text.lineSequence()) {
            val pre = preampRe.find(line)
            if (pre != null) {
                preamp = pre.groupValues[1].toDouble()
                continue
            }
            val m = filterRe.find(line) ?: continue
            val type = when (m.groupValues[2].uppercase()) {
                "PK", "PEQ" -> FilterType.PEAK
                "LS", "LSC", "LSQ" -> FilterType.LOW_SHELF
                "HS", "HSC", "HSQ" -> FilterType.HIGH_SHELF
                else -> continue
            }
            filters += EqFilter(
                type = type,
                freqHz = m.groupValues[3].toDouble(),
                gainDb = m.groupValues[4].toDoubleOrNull() ?: 0.0,
                q = m.groupValues[5].toDoubleOrNull() ?: if (type == FilterType.PEAK) 1.0 else 0.71,
                enabled = m.groupValues[1].equals("ON", ignoreCase = true),
            )
        }
        require(filters.isNotEmpty()) { "No filters found" }
        return EqProfile(name = name, preampDb = preamp, filters = filters)
    }

    fun format(profile: EqProfile): String = buildString {
        appendLine("Preamp: %.1f dB".format(java.util.Locale.ROOT, profile.preampDb))
        profile.filters.forEachIndexed { i, f ->
            appendLine(
                "Filter %d: %s %s Fc %.0f Hz Gain %.1f dB Q %.2f".format(
                    java.util.Locale.ROOT, i + 1, if (f.enabled) "ON" else "OFF", f.type.code, f.freqHz, f.gainDb, f.q,
                ),
            )
        }
    }
}
