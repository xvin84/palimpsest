package dev.xvin.palimpsest.errors

import android.content.Context
import android.os.Build
import androidx.media3.common.PlaybackException
import dev.xvin.palimpsest.BuildConfig
import dev.xvin.palimpsest.core.model.AuthRequiredException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileNotFoundException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr

/** A failure explained for humans: what happened, what to try, and the raw details to copy. */
data class ErrorInfo(
    val title: String,
    val explanation: String,
    val hints: List<String>,
    val details: String,
    val at: Long = System.currentTimeMillis(),
    val context: String? = null,
)

object ErrorExplainer {
    fun explain(t: Throwable, context: String? = null, online: Boolean = true): ErrorInfo {
        val chain = generateSequence(t) { it.cause }.take(8).toList()
        val text = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
        val lower = text.lowercase()
        val youtube = "youtube" in lower || "googlevideo" in lower || "newpipe" in lower || context?.contains("YouTube") == true
        val spotify = "spotify" in lower || context?.contains("Spotify") == true
        val details = details(t, context)

        fun info(title: String, explanation: String, vararg hints: String) = ErrorInfo(title, explanation, hints.toList(), details, context = context)

        val playback = chain.firstNotNullOfOrNull { it as? PlaybackException }
        val networkish = chain.any { it is java.io.IOException } || playback != null
        return when {
            !online && networkish ->
                info(tr(R.string.err_offline_title), tr(R.string.err_offline_expl),
                    tr(R.string.err_offline_hint_1), tr(R.string.err_offline_hint_2))
            chain.any { it is AuthRequiredException } || "401" in lower ->
                info(tr(R.string.err_auth_title), tr(R.string.err_auth_expl),
                    tr(R.string.err_auth_hint))
            chain.any { it is UnknownHostException } || "unable to resolve host" in lower ->
                info(tr(R.string.err_dns_title), tr(R.string.err_dns_expl),
                    tr(R.string.err_dns_hint),
                    if (youtube || spotify) tr(R.string.err_blocked_hint) else tr(R.string.err_vpn_hint))
            chain.any { it is SocketTimeoutException || it is ConnectException } || "timeout" in lower || "network is unreachable" in lower || "failed to connect" in lower ->
                info(tr(R.string.err_timeout_title), tr(R.string.err_timeout_expl),
                    tr(R.string.err_timeout_hint),
                    if (youtube || spotify) tr(R.string.err_timeout_blocked) else tr(R.string.err_timeout_vpn))
            chain.any { it is SSLException } ->
                info(tr(R.string.err_ssl_title), tr(R.string.err_ssl_expl),
                    tr(R.string.err_ssl_hint_1), tr(R.string.err_ssl_hint_2))
            "429" in lower || "recaptcha" in lower || "too many" in lower ->
                info(tr(R.string.err_rate_title), tr(R.string.err_rate_expl),
                    tr(R.string.err_rate_hint), if (youtube) tr(R.string.err_rate_youtube) else tr(R.string.err_rate_other))
            "403" in lower ->
                info(tr(R.string.err_forbidden_title), if (youtube) tr(R.string.err_forbidden_youtube) else tr(R.string.err_forbidden_other),
                    if (youtube) tr(R.string.err_forbidden_youtube_hint) else tr(R.string.err_forbidden_plus_hint),
                    tr(R.string.err_send_details))
            "404" in lower || chain.any { it is FileNotFoundException && "/storage" !in (it.message ?: "") } ->
                info(tr(R.string.err_not_found_title), tr(R.string.err_not_found_expl), tr(R.string.err_not_found_hint))
            "enospc" in lower || "no space" in lower ->
                info(tr(R.string.err_space_title), tr(R.string.err_space_expl), tr(R.string.err_space_hint))
            "eacces" in lower || "permission denied" in lower || chain.any { it is SecurityException } ->
                info(tr(R.string.err_access_title), tr(R.string.err_access_expl),
                    tr(R.string.err_access_hint))
            "cannotwrite" in lower || "cannotread" in lower || "jaudiotagger" in lower ->
                info(tr(R.string.err_tags_title), tr(R.string.err_tags_expl),
                    tr(R.string.err_tags_hint_1), tr(R.string.err_tags_hint_2))
            playback != null && playback.errorCode in PlaybackException.ERROR_CODE_DECODING_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                info(tr(R.string.err_decode_title), tr(R.string.err_decode_expl), tr(R.string.err_decode_hint_1), tr(R.string.err_decode_hint_2))
            playback != null && playback.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
                info(tr(R.string.err_io_title), tr(R.string.err_io_expl),
                    tr(R.string.err_io_hint),
                    if (youtube) tr(R.string.err_io_youtube) else tr(R.string.err_io_download))
            else -> info(tr(R.string.err_generic_title), t.message?.take(200) ?: t.javaClass.simpleName, tr(R.string.err_generic_hint), tr(R.string.err_generic_send))
        }
    }

    private fun details(t: Throwable, context: String?): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val trace = sw.toString().lineSequence().take(40).joinToString("\n")
        return buildString {
            appendLine("Palimpsest ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()))
            context?.let { appendLine(tr(R.string.err_where, it)) }
            appendLine()
            append(trace)
        }
    }
}

/**
 * Collects errors from everywhere (playback, downloads, sync, search…), keeps a journal on
 * disk for Settings → «Журнал ошибок», and asks the UI to show the ones worth showing.
 */
class ErrorCenter(private val context: Context) {
    private val file = File(context.filesDir, "errors.log")
    private val _log = MutableStateFlow(load())
    val log: StateFlow<List<ErrorInfo>> = _log

    private val _shown = MutableSharedFlow<ErrorInfo>(extraBufferCapacity = 8)
    /** Errors the UI should surface (as a snackbar with «Подробнее», or straight away). */
    val shown: SharedFlow<ErrorInfo> = _shown

    /** Returns null when [t] is just a cancellation — nothing to show or log. */
    fun report(t: Throwable, context: String? = null, show: Boolean = true): ErrorInfo? {
        // A cancelled job (screen closed, user skipped) is not a failure; OkHttp surfaces it as a bare InterruptedIOException.
        if (isCancellation(t)) return null
        val info = ErrorExplainer.explain(t, context, online())
        _log.update { (listOf(info) + it).take(MAX) }
        runCatching { persist() }
        if (show) _shown.tryEmit(info)
        return info
    }

    private fun isCancellation(t: Throwable): Boolean = generateSequence(t) { it.cause }.take(8).any {
        it is kotlinx.coroutines.CancellationException ||
            (it is java.io.InterruptedIOException && it !is SocketTimeoutException && it.message?.contains("timeout", true) != true)
    }

    private fun online(): Boolean = runCatching {
        val cm = this.context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    fun clear() {
        _log.value = emptyList()
        file.delete()
    }

    private fun persist() {
        file.writeText(_log.value.joinToString(SEP) { listOf(it.at, it.title, it.explanation, it.hints.joinToString("\u0001"), it.context.orEmpty(), it.details).joinToString(FIELD) })
    }

    private fun load(): List<ErrorInfo> = runCatching {
        if (!file.isFile) return emptyList()
        file.readText().split(SEP).mapNotNull { rec ->
            val f = rec.split(FIELD)
            if (f.size < 6) null else ErrorInfo(f[1], f[2], f[3].split("\u0001").filter { it.isNotEmpty() }, f[5], f[0].toLongOrNull() ?: 0, f[4].ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val MAX = 100
        private const val SEP = "\u0002\n"
        private const val FIELD = "\u0003"
    }
}
