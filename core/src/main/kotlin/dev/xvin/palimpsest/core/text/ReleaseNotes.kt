package dev.xvin.palimpsest.core.text

/**
 * Release notes are Markdown; shown as plain text: no heading marks or emphasis, bullets as •,
 * and lines the source wrapped for width joined back — a phone screen wraps them itself.
 */
object ReleaseNotes {
    private val heading = Regex("^#{1,6}\\s+")
    private val bullet = Regex("^\\s*[-*]\\s+")

    fun plain(md: String): String {
        val out = ArrayList<String>()
        var joinable = false // the last line is text a wrapped line may continue
        for (raw in md.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> { out += ""; joinable = false }
                heading.containsMatchIn(line) -> { out += clean(line.replace(heading, "")); joinable = false }
                bullet.containsMatchIn(line) -> { out += "• " + clean(line.replace(bullet, "")); joinable = true }
                joinable -> out[out.lastIndex] = out.last() + " " + clean(line)
                else -> { out += clean(line); joinable = true }
            }
        }
        return out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun clean(s: String) = s.replace("**", "").replace("`", "")

    /**
     * The part of a CHANGELOG for [version]: from its «## [0.4.2]» (or «## 0.4.2») heading to the
     * next version's heading. Null if the version isn't there.
     */
    fun section(changelog: String, version: String): String? {
        val lines = changelog.lines()
        val head = Regex("^##\\s+\\[?v?([0-9][^\\]\\s]*)\\]?")
        val start = lines.indexOfFirst { head.find(it)?.groupValues?.get(1) == version }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull { head.containsMatchIn(lines[it]) } ?: lines.size
        return lines.subList(start + 1, end).joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }
}
