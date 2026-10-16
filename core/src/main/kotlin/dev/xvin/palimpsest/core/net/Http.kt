package dev.xvin.palimpsest.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Sites that serve pages to browsers only (Genius) get a desktop browser's User-Agent. */
    const val BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }
}

/** A finished HTTP exchange with the body already read. */
class HttpText(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Executes the call and reads the whole body on the IO dispatcher. Reading a body is network
 * I/O too, so it must never happen on the caller's (possibly main) thread. Cancelling the
 * coroutine interrupts the request.
 */
suspend fun Call.fetch(): HttpText = withContext(Dispatchers.IO) {
    runInterruptible { execute().use { HttpText(it.code, it.body.string()) } }
}
