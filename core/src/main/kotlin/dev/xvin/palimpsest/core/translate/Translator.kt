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
 * Line-by-line lyrics translation through Google Translate's public web endpoints (no key).
 * The Chrome-extension endpoint takes every line as its own parameter and answers one-to-one;
 * the older "gtx" endpoint (lines joined by newlines) is the fallback when the first one is
 * rate-limited.
 */
class Translator(private val http: OkHttpClient = Http.client) {
    data class Result(val lines: List<String>, val sourceLanguage: String?)

    suspend fun translate(lines: List<String>, target: String = "ru"): Result {
        if (lines.isEmpty()) return Result(emptyList(), null)
        val out = ArrayList<String>(lines.size)
        val languages = ArrayList<String>()
        // Keep requests modest; the endpoints dislike very long bodies.
        for (chunk in chunked(lines, 3_500)) {
            val (texts, langs) = runCatching { perLine(chunk, target) }.getOrElse { joined(chunk, target) }
            languages += langs
            out += List(chunk.size) { i -> texts.getOrNull(i)?.trim().orEmpty() }
        }
        val detected = languages.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return Result(out, detected)
    }

    private suspend fun perLine(chunk: List<String>, target: String): Pair<List<String>, List<String>> {
        val body = FormBody.Builder().apply { chunk.forEach { add("q", it) } }.build()
        val r = http.newCall(
            Request.Builder()
                .url("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=$target")
                .post(body)
                .header("User-Agent", "Mozilla/5.0")
                .build(),
        ).fetch()
        if (!r.isSuccessful) throw ProviderException("Translation failed: HTTP ${r.code}")
        return parsePerLine(r.body)
    }

    private suspend fun joined(chunk: List<String>, target: String): Pair<List<String>, List<String>> {
        val body = FormBody.Builder().add("q", chunk.joinToString("\n")).build()
        val r = http.newCall(
            Request.Builder()
                .url("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$target&dt=t")
                .post(body)
                .header("User-Agent", "Mozilla/5.0")
                .build(),
        ).fetch()
        if (!r.isSuccessful) throw ProviderException("Translation failed: HTTP ${r.code}")
        val (text, lang) = parse(r.body)
        // If the service merged or split lines, the caller pads so indices still line up.
        return text.split("\n") to listOfNotNull(lang)
    }

    companion object {
        /** `[["перевод","en"], …]` (auto-detected source) or `["перевод", …]`. */
        internal fun parsePerLine(json: String): Pair<List<String>, List<String>> {
            val root = Http.json.parseToJsonElement(json).jsonArray
            val texts = ArrayList<String>(); val langs = ArrayList<String>()
            for (item in root) {
                if (item is JsonArray) {
                    texts += item.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
                    item.getOrNull(1)?.jsonPrimitive?.contentOrNull?.let { langs += it }
                } else {
                    texts += item.jsonPrimitive.contentOrNull.orEmpty()
                }
            }
            return texts to langs
        }

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
