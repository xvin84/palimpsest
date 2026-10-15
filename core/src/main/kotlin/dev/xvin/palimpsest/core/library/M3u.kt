package dev.xvin.palimpsest.core.library

/**
 * M3U/M3U8 playlists written by other players. Paths are often from another machine
 * (e.g. `/home/user/Music/...` on a PC), so entries are matched against the library by the
 * longest common path suffix rather than by exact path.
 */
object M3u {
    fun entries(text: String): List<String> =
        text.lineSequence()
            .map { it.trim().removePrefix("﻿") }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.replace('\\', '/').replace(Regex("/{2,}"), "/") }
            .toList()

    /**
     * Picks the library path that shares the longest tail of path segments with [entry]
     * (at least the file name). Returns null when nothing matches.
     */
    fun match(entry: String, byFileName: Map<String, List<String>>): String? {
        val name = entry.substringAfterLast('/')
        val candidates = byFileName[name.lowercase()] ?: return null
        if (candidates.size == 1) return candidates.first()
        val parts = entry.split('/').reversed()
        return candidates.maxByOrNull { c ->
            val cp = c.split('/').reversed()
            parts.zip(cp).takeWhile { (a, b) -> a.equals(b, ignoreCase = true) }.size
        }
    }

    fun indexByFileName(paths: Collection<String>): Map<String, List<String>> =
        paths.groupBy { it.substringAfterLast('/').lowercase() }
}
