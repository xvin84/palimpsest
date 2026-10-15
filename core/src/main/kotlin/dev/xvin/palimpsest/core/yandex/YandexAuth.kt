package dev.xvin.palimpsest.core.yandex

import dev.xvin.palimpsest.core.model.ProviderException
import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.core.net.fetch
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OAuth for Yandex Music using the "device code" flow: the app shows a short code, the user
 * opens ya.ru/device on any device, enters it and confirms. No password ever touches the app.
 *
 * Client id/secret are the public ones of the official Android app (same as yandex-music-api).
 */
class YandexAuth(private val http: OkHttpClient = Http.client) {

    suspend fun requestDeviceCode(deviceName: String = "Palimpsest"): YDeviceCode {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("device_id", randomDeviceId())
            .add("device_name", deviceName)
            .build()
        return post("$OAUTH/device/code", body)
    }

    /**
     * Polls until the user confirms the code. Returns null if the code expires.
     */
    suspend fun awaitToken(code: YDeviceCode): YToken? {
        val deadline = System.currentTimeMillis() + code.expires_in * 1000L
        val interval = code.interval.coerceAtLeast(2) * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval)
            val body = FormBody.Builder()
                .add("grant_type", "device_code")
                .add("code", code.device_code)
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .build()
            val resp = http.newCall(Request.Builder().url("$OAUTH/token").post(body).build()).fetch()
            val text = resp.body
            if (resp.isSuccessful) return Http.json.decodeFromString<YToken>(text)
            val err = runCatching { Http.json.decodeFromString<YOAuthError>(text) }.getOrNull()
            when (err?.error) {
                "authorization_pending", "slow_down" -> continue
                "expired_token" -> return null
                else -> throw ProviderException("Яндекс OAuth: ${err?.error_description ?: err?.error ?: resp.code}")
            }
        }
        return null
    }

    suspend fun refresh(refreshToken: String): YToken {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .build()
        return post("$OAUTH/token", body)
    }

    private suspend inline fun <reified T> post(url: String, body: FormBody): T {
        val resp = http.newCall(Request.Builder().url(url).post(body).build()).fetch()
        val text = resp.body
        if (!resp.isSuccessful) throw ProviderException("Яндекс OAuth ${resp.code}: $text")
        return Http.json.decodeFromString<T>(text)
    }

    private fun randomDeviceId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = java.security.SecureRandom()
        return (1..10).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    companion object {
        private const val OAUTH = "https://oauth.yandex.ru"
        const val CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d"
        private const val CLIENT_SECRET = "53bc75238f0c4d08a118e51fe9203300"

        /** Fallback: implicit-flow URL for a WebView login; token arrives in the redirect fragment. */
        const val IMPLICIT_LOGIN_URL = "$OAUTH/authorize?response_type=token&client_id=$CLIENT_ID"

        fun tokenFromRedirect(url: String): String? =
            Regex("""access_token=([^&]+)""").find(url)?.groupValues?.get(1)
    }
}
