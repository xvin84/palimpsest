package dev.xvin.palimpsest.core.lyrics

data class LrcLine(val timeMs: Long, val text: String)

/** Parsed lyrics: either time-synced lines or plain text. */
data class ParsedLyrics(val lines: List<LrcLine>, val isSynced: Boolean) {
    /** Index of the line that should be highlighted at [positionMs], or -1 before the first line. */
    fun indexAt(positionMs: Long): Int {
        if (!isSynced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    fun toLrc(): String = if (!isSynced) lines.joinToString("\n") { it.text } else
        lines.joinToString("\n") { "[${Lrc.formatTime(it.timeMs)}]${it.text}" }

    companion object {
        val EMPTY = ParsedLyrics(emptyList(), false)
    }
}

object Lrc {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("""^\[(ar|ti|al|by|au|length|re|ve|#):.*]$""", RegexOption.IGNORE_CASE)

    fun looksSynced(text: String): Boolean = text.lineSequence().any { timeTag.containsMatchIn(it.trimStart().take(12)) }

    fun parse(text: String): ParsedLyrics {
        if (text.isBlank()) return ParsedLyrics.EMPTY
        if (!looksSynced(text)) {
            return ParsedLyrics(text.trim().lines().map { LrcLine(0, it.trimEnd()) }, isSynced = false)
        }
        var offset = 0L
        val out = ArrayList<LrcLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            offsetTag.find(line)?.let { offset = it.groupValues[1].toLong() }
            if (metaTag.matches(line)) continue
            // A line may carry several timestamps: "[00:12.00][01:30.00]chorus"
            var rest = line
            val times = mutableListOf<Long>()
            while (true) {
                val m = timeTag.matchAt(rest, 0) ?: break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                times += min * 60_000 + sec * 1_000 + frac
                rest = rest.substring(m.range.last + 1)
            }
            if (times.isEmpty()) continue
            // Strip word-level "<00:12.34>" timings from enhanced LRC.
            val textPart = rest.replace(Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>"""), "").trim()
            for (t in times) out += LrcLine((t - offset).coerceAtLeast(0), textPart)
        }
        out.sortBy { it.timeMs }
        return ParsedLyrics(out, isSynced = true)
    }

    fun formatTime(ms: Long): String {
        val m = ms / 60_000
        val s = (ms / 1000) % 60
        val cs = (ms % 1000) / 10
        return "%02d:%02d.%02d".format(m, s, cs)
    }
}
