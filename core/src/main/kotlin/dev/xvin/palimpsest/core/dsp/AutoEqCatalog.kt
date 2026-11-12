package dev.xvin.palimpsest.core.dsp

import dev.xvin.palimpsest.core.library.SearchText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * AutoEQ's headphone corrections looked up by model, right in the app — instead of «go to GitHub,
 * find your model, copy the file». The index (~9 000 models, measured by oratory1990, crinacle and
 * others) is fetched once per run.
 */
class AutoEqCatalog(private val http: OkHttpClient) {
    /** One measured model: [path] is its folder in the repository (URL-encoded), [source] who measured it. */
    data class Entry(val name: String, val path: String, val source: String)

    private val lock = Mutex()
    private var index: List<Entry>? = null

    suspend fun search(query: String, limit: Int = 40): List<Entry> {
        val all = load()
        if (query.isBlank()) return emptyList()
        val q = SearchText.plain(query)
        return all.filter { SearchText.matches(query, it.name) }
            // Names that start with the query first, then oratory1990's (the reference measurements).
            .sortedWith(compareBy({ !SearchText.plain(it.name).startsWith(q) }, { it.source != "oratory1990" }, { it.name.length }))
            .take(limit)
    }

    suspend fun profile(e: Entry): EqProfile = withContext(Dispatchers.IO) {
        val file = e.path.substringAfterLast('/') + "%20ParametricEQ.txt"
        AutoEqParser.parse(get("$BASE/${e.path.removePrefix("./")}/$file"), e.name)
    }

    private suspend fun load(): List<Entry> = lock.withLock {
        index ?: withContext(Dispatchers.IO) { parse(get("$BASE/INDEX.md")) }.also { index = it }
    }

    private fun get(url: String): String = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) throw IOException("AutoEQ: HTTP ${r.code}")
        r.body.string()
    }

    companion object {
        private const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
        private val LINE = Regex("""^- \[(.+)\]\((\./[^)]+(?:\([^)]*\)[^)]*)*)\) by (\S+)""")

        /** «- [Name](./source/rig/Name) by source on rig» lines of the index. */
        fun parse(md: String): List<Entry> = md.lineSequence().mapNotNull { l ->
            LINE.find(l)?.let { m -> Entry(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
        }.toList()
    }
}
