package dev.xvin.palimpsest.core.scrobble

import dev.xvin.palimpsest.core.model.ProviderException
import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.core.net.fetch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * ListenBrainz (listenbrainz.org) — an open, MusicBrainz-run alternative to Last.fm.
 * Needs only the user's token from listenbrainz.org/settings; no app keys.
 */
class ListenBrainz(private val http: OkHttpClient = Http.client, private val base: String = "https://api.listenbrainz.org") {
    data class Listen(val artist: String, val title: String, val album: String?, val listenedAtSec: Long, val durationMs: Long)

    /** Returns the user name the token belongs to, or null if the token is invalid. */
    suspend fun validate(token: String): String? {
        val r = http.newCall(Request.Builder().url("$base/1/validate-token").header("Authorization", "Token $token").build()).fetch()
        if (!r.isSuccessful) return null
        val o = Http.json.parseToJsonElement(r.body) as? JsonObject ?: return null
        return if (o["valid"]?.jsonPrimitive?.booleanOrNull == true) o["user_name"]?.jsonPrimitive?.contentOrNull else null
    }

    /** Submits finished listens ("import" accepts batches; ListenBrainz de-duplicates by timestamp). */
    suspend fun submit(token: String, listens: List<Listen>) {
        if (listens.isEmpty()) return
        val body = buildJsonObject {
            put("listen_type", if (listens.size == 1) "single" else "import")
            put(
                "payload",
                buildJsonArray {
                    listens.forEach { l ->
                        add(
                            buildJsonObject {
                                put("listened_at", l.listenedAtSec)
                                put(
                                    "track_metadata",
                                    buildJsonObject {
                                        put("artist_name", l.artist)
                                        put("track_name", l.title)
                                        l.album?.let { put("release_name", it) }
                                        put(
                                            "additional_info",
                                            buildJsonObject {
                                                put("media_player", "Palimpsest")
                                                put("submission_client", "Palimpsest")
                                                if (l.durationMs > 0) put("duration_ms", l.durationMs)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        val r = http.newCall(
            Request.Builder().url("$base/1/submit-listens")
                .header("Authorization", "Token $token")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build(),
        ).fetch()
        if (!r.isSuccessful) throw ProviderException("ListenBrainz ${r.code}: ${r.body.take(200)}")
    }
}
