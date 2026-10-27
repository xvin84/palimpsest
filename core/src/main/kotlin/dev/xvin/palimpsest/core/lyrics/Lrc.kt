package dev.xvin.palimpsest.core.lyrics

import dev.xvin.palimpsest.core.model.Lyrics

/** One sung word (with its trailing space) from enhanced LRC; an empty [text] marks where the line ends. */
data class LrcWord(val timeMs: Long, val text: String)

/** A lyric line; [words] is set when the lyrics carry word timings (karaoke). */
data class LrcLine(val timeMs: Long, val text: String, val words: List<LrcWord>? = null) {
    /**
     * How much of the line is sung at [positionMs], word by word: for every word in [words], 0..1.
     * A word fills over its own length (to the next word's start, or the line's end marker).
     */
    fun wordProgress(positionMs: Long, lineEndMs: Long): FloatArray {
        val w = words ?: return FloatArray(0)
        return FloatArray(w.size) { i ->
            val start = w[i].timeMs
            val end = w.getOrNull(i + 1)?.timeMs ?: lineEndMs
            when {
                positionMs <= start -> 0f
                positionMs >= end || end <= start -> 1f
                else -> (positionMs - start).toFloat() / (end - start)
            }
        }
    }
}

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

    /** Whether any line has word timings. */
    val hasWords: Boolean get() = lines.any { it.words != null }

    fun toLrc(): String = if (!isSynced) lines.joinToString("\n") { it.text } else
        lines.joinToString("\n") { l ->
            "[${Lrc.formatTime(l.timeMs)}]" + (l.words?.joinToString("") { "<${Lrc.formatTime(it.timeMs)}>${it.text}" } ?: l.text)
        }

    companion object {
        val EMPTY = ParsedLyrics(emptyList(), false)
    }
}

/**
 * [this] without what services send in place of lyrics or around them; null when nothing sung is left.
 * Every source goes through it, and so does what was saved earlier (a stand-in may already be cached
 * or written into a file's tag).
 */
fun Lyrics.cleaned(): Lyrics? {
    fun keep(text: String?) = text?.let(Lrc::clean)?.takeIf(Lrc::hasText)
    val s = keep(synced)
    val p = keep(plain)
    return if (s == null && p == null) null else copy(synced = s, plain = p)
}

object Lrc {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val leadingTags = Regex("""^(?:\[[\d:.]+]\s*)+""")

    /**
     * Lines that aren't lyrics: Yandex's «subscribe to Plus» (what an account without Plus gets, as
     * LRC and as text), NetEase's credits — as JSON lines or as «[00:00] 作词 : …» — and its
     * «instrumental» / «no lyrics yet» notes.
     */
    private val junk = listOf(
        // Every brace and bracket escaped: Android's regex engine (ICU) rejects a bare «}», which Java accepts.
        Regex("""^\{"t":-?\d+,"c":\[.*\]\}$"""),
        Regex("""подключите подписку на плюс|subscribe to (?:yandex )?plus""", RegexOption.IGNORE_CASE),
        Regex("""^(?:作词|作曲|编曲|制作人|混音|母带|和声|吉他|贝斯|鼓|弦乐|录音|监制|出品|Lyricist|Composer|Arranger|Producer)\s*[:：]""", RegexOption.IGNORE_CASE),
        Regex("""^(?:纯音乐，?请欣赏|暂无歌词|此歌曲为没有填词的纯音乐)"""),
    )

    /** [text] without the lines in [junk]. */
    fun clean(text: String): String = text.lineSequence()
        .filterNot { line -> val bare = line.trim().replace(leadingTags, "").trim(); junk.any { it.containsMatchIn(bare) } }
        .joinToString("\n")
        .trim()

    /** Whether there's a sung word in [text] (not just timestamps and pauses). */
    fun hasText(text: String): Boolean = parse(text).lines.any { l -> l.text.any(Char::isLetterOrDigit) }
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
            // Enhanced LRC: "<00:12.34>" before each word gives its start.
            val textPart = rest.replace(wordTag, "").trim()
            val words = if (times.size == 1 && wordTag.containsMatchIn(rest)) words(rest, times[0], offset) else null
            for (t in times) out += LrcLine((t - offset).coerceAtLeast(0), textPart, words)
        }
        out.sortBy { it.timeMs }
        return ParsedLyrics(out, isSynced = true)
    }

    private val wordTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    private fun fracMs(f: String) = when (f.length) {
        0 -> 0L
        1 -> f.toLong() * 100
        2 -> f.toLong() * 10
        else -> f.take(3).toLong()
    }

    /** "Hello <00:12.50>world <00:13.10>" → words; text before the first tag starts with the line. */
    private fun words(rest: String, lineMs: Long, offset: Long): List<LrcWord>? {
        val out = ArrayList<LrcWord>()
        var pos = 0
        var start = lineMs
        for (m in wordTag.findAll(rest)) {
            val text = rest.substring(pos, m.range.first)
            if (text.isNotEmpty()) out += LrcWord((start - offset).coerceAtLeast(0), text)
            start = m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1_000 + fracMs(m.groupValues[3])
            pos = m.range.last + 1
        }
        val tail = rest.substring(pos)
        out += LrcWord((start - offset).coerceAtLeast(0), tail.trimEnd())
        val fixed = out.mapIndexed { i, w -> if (i == 0) w.copy(text = w.text.trimStart()) else w }
        return fixed.takeIf { it.count { w -> w.text.isNotBlank() } >= 1 }
    }

    /**
     * NetEase «yrc» word timings — `[lineStart,lineDur](wordStart,wordDur,0)word…` in ms — as
     * enhanced LRC. Credit lines (JSON) are skipped. Null when there are no timed lines.
     */
    fun fromYrc(yrc: String): String? {
        val lineHead = Regex("""^\[(\d+),(\d+)]""")
        // A word runs up to the next timing, so «(oh)» in the text survives.
        val word = Regex("""\((\d+),(\d+),\d+\)(.*?)(?=\(\d+,\d+,\d+\)|$)""")
        val out = StringBuilder()
        for (raw in yrc.lineSequence()) {
            val head = lineHead.find(raw) ?: continue
            val body = raw.substring(head.range.last + 1)
            val ws = word.findAll(body).toList()
            if (ws.isEmpty()) continue
            out.append('[').append(formatTime(head.groupValues[1].toLong())).append(']')
            for (w in ws) out.append('<').append(formatTime(w.groupValues[1].toLong())).append('>').append(w.groupValues[3])
            val last = ws.last()
            out.append('<').append(formatTime(last.groupValues[1].toLong() + last.groupValues[2].toLong())).append('>')
            out.append('\n')
        }
        return out.toString().trim().takeIf { it.isNotEmpty() }
    }

    /** Whether [lrc] has word timings (enhanced LRC). */
    fun hasWords(lrc: String): Boolean = wordTag.containsMatchIn(lrc)

    /** Lyrics without word timings, for players that don't understand them. */
    fun stripWords(lrc: String): String = lrc.replace(wordTag, "")

    fun formatTime(ms: Long): String {
        val m = ms / 60_000
        val s = (ms / 1000) % 60
        val cs = (ms % 1000) / 10
        return "%02d:%02d.%02d".format(m, s, cs)
    }
}
