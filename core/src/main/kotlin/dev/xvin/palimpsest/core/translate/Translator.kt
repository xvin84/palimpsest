package dev.xvin.palimpsest.core.translate

import dev.xvin.palimpsest.core.model.ProviderException
import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.core.net.fetch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Line-by-line lyrics translation through Google Translate's public web endpoint (no key).
 * Lines are sent together, separated by newlines, and mapped back one-to-one.
 */
class Translator(private val http: OkHttpClient = Http.client) {
    data class Result(val lines: List<String>, val sourceLanguage: String?)

    suspend fun translate(lines: List<String>, target: String = "ru"): Result {
        if (lines.isEmpty()) return Result(emptyList(), null)
        val out = ArrayList<String>(lines.size)
        var detected: String? = null
        // Keep requests modest; the endpoint dislikes very long bodies.
        for (chunk in chunked(lines, 3_500)) {
            val body = FormBody.Builder().add("q", chunk.joinToString("\n")).build()
            val r = http.newCall(
                Request.Builder()
                    .url("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$target&dt=t")
                    .post(body)
                    .header("User-Agent", "Mozilla/5.0")
                    .build(),
            ).fetch()
            if (!r.isSuccessful) throw ProviderException("Перевод не удался: HTTP ${r.code}")
            val (text, lang) = parse(r.body)
            detected = detected ?: lang
            val translated = text.split("\n")
            // If the service merged or split lines, fall back to padding so indices still line up.
            out += List(chunk.size) { i -> translated.getOrNull(i)?.trim().orEmpty() }
        }
        return Result(out, detected)
    }

    companion object {
        internal fun parse(json: String): Pair<String, String?> {
            val root = Http.json.parseToJsonElement(json).jsonArray
            val text = (root[0] as? JsonArray).orEmpty().joinToString("") { seg ->
                (seg as? JsonArray)?.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val lang = root.getOrNull(2)?.jsonPrimitive?.contentOrNull
            return text to lang
        }

        private fun chunked(lines: List<String>, maxChars: Int): List<List<String>> {
            val out = ArrayList<List<String>>()
            var cur = ArrayList<String>()
            var size = 0
            for (l in lines) {
                if (size + l.length + 1 > maxChars && cur.isNotEmpty()) { out += cur; cur = ArrayList(); size = 0 }
                cur += l.ifBlank { "…" }
                size += l.length + 1
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }
    }
}
