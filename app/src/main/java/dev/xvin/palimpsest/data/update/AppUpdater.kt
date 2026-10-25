package dev.xvin.palimpsest.data.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import dev.xvin.palimpsest.BuildConfig
import dev.xvin.palimpsest.MainActivity
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.core.net.fetch
import dev.xvin.palimpsest.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** A published release that is newer than the installed app. */
data class AppRelease(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val pageUrl: String)

/**
 * Updates straight from the project's GitHub Releases: checks the latest release, offers it,
 * downloads the APK and hands it to the system installer. The release APK is signed with the same
 * key, so Android installs it over the current version and nothing is lost.
 */
class AppUpdater(private val context: Context, private val http: OkHttpClient) {
    @Serializable private data class GhRelease(
        @SerialName("tag_name") val tag: String = "",
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    )
    @Serializable private data class GhAsset(val name: String = "", val size: Long = 0, @SerialName("browser_download_url") val url: String = "")

    private val _available = MutableStateFlow<AppRelease?>(null)
    /** Set once a check finds something newer; Settings shows it. */
    val available: StateFlow<AppRelease?> = _available

    private val _progress = MutableStateFlow<Float?>(null)
    /** Download progress 0..1 while an update is being fetched. */
    val progress: StateFlow<Float?> = _progress

    private val prefs get() = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /**
     * Null when up to date. Asks the GitHub API first; it allows 60 requests an hour per address,
     * and a mobile network shares one address among many people, so it often says «limit reached».
     * Then the release page's redirect (`/releases/latest` → `/releases/tag/v…`) tells the version,
     * with no limit. Throws when neither answers — «up to date» would be a lie then.
     */
    suspend fun check(notify: Boolean = false): AppRelease? = withContext(Dispatchers.IO) {
        val release = fromApi() ?: fromRedirect() ?: throw IOException(tr(R.string.update_unreachable))
        if (!isNewer(release.version, BuildConfig.VERSION_NAME)) {
            _available.value = null
            return@withContext null
        }
        _available.value = release
        if (notify && prefs.getString(KEY_NOTIFIED, null) != release.version) {
            notify(release)
            prefs.edit().putString(KEY_NOTIFIED, release.version).apply()
        }
        release
    }

    /** The latest release from the API, or null when it didn't answer (rate limit, network). */
    private suspend fun fromApi(): AppRelease? = runCatching {
        val r = http.newCall(
            Request.Builder().url("${BuildConfig.UPDATE_API}/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json").header("Cache-Control", "no-cache").build(),
        ).fetch()
        if (!r.isSuccessful) return@runCatching null
        val gh = Http.json.decodeFromString<GhRelease>(r.body)
        val version = gh.tag.removePrefix("v")
        val apk = gh.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@runCatching null
        if (gh.draft || gh.prerelease) return@runCatching null
        AppRelease(version, changelogNotes(gh.tag, version) ?: gh.body.orEmpty().trim(), apk.url, apk.size, gh.htmlUrl)
    }.getOrNull()

    /** The latest release from where github.com redirects `/releases/latest`; the APK's name is known. */
    private suspend fun fromRedirect(): AppRelease? = runCatching {
        val web = "${BuildConfig.UPDATE_WEB}/${BuildConfig.GITHUB_REPO}"
        val noFollow = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val location = runInterruptible { noFollow.newCall(Request.Builder().url("$web/releases/latest").head().build()).execute().use { it.header("Location") } }
            ?: return@runCatching null
        val tag = location.substringAfter("/releases/tag/", "").substringBefore('?').takeIf { it.isNotBlank() } ?: return@runCatching null
        val version = tag.removePrefix("v")
        AppRelease(version, changelogNotes(tag, version).orEmpty(), "$web/releases/download/$tag/palimpsest-$version.apk", 0, "$web/releases/tag/$tag")
    }.getOrNull()

    /** Downloads the APK and starts the system install dialog. */
    suspend fun install(release: AppRelease) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val apk = File(dir, "palimpsest-${release.version}.apk")
        _progress.value = 0f
        try {
            http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val total = resp.body.contentLength().takeIf { it > 0 } ?: release.apkSize
                resp.body.byteStream().use { input ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) _progress.value = done.toFloat() / total
                        }
                    }
                }
            }
            if (release.apkSize > 0 && apk.length() != release.apkSize) throw IOException("APK size mismatch: ${apk.length()} ≠ ${release.apkSize}")

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("palimpsest.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = PendingIntent.getBroadcast(
                    context, id, Intent(context, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(status.intentSender)
            }
        } finally {
            _progress.value = null
        }
    }

    private fun notify(r: AppRelease) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 7, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(tr(R.string.update_available, r.version))
                .setContentText(r.notes.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trimStart('-', '*', ' ') ?: tr(R.string.update_tap))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * This version's part of the changelog in the app's language (the release text on GitHub is
     * in one language only). Null when it can't be fetched — then the release text is shown.
     */
    private suspend fun changelogNotes(tag: String, version: String): String? = runCatching {
        val file = if (context.resources.configuration.locales[0].language == "ru") "CHANGELOG.ru.md" else "CHANGELOG.md"
        // The API first, then the raw file (no rate limit there either).
        val urls = listOf(
            "${BuildConfig.UPDATE_API}/repos/${BuildConfig.GITHUB_REPO}/contents/$file?ref=$tag",
            "${BuildConfig.UPDATE_RAW}/${BuildConfig.GITHUB_REPO}/$tag/$file",
        )
        urls.firstNotNullOfOrNull { url ->
            val r = runCatching { http.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github.raw").build()).fetch() }.getOrNull()
            if (r != null && r.isSuccessful) dev.xvin.palimpsest.core.text.ReleaseNotes.section(r.body, version) else null
        }
    }.getOrNull()

    companion object {
        /** Release notes are Markdown; shown as plain text (see [dev.xvin.palimpsest.core.text.ReleaseNotes]). */
        fun plainNotes(md: String): String = dev.xvin.palimpsest.core.text.ReleaseNotes.plain(md)

        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 77
        private const val KEY_NOTIFIED = "notified_version"

        /** "0.10.1" > "0.9.3": compares numerically, part by part. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate); val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/** Receives the installer's verdict; when Android wants the user to confirm, shows its dialog. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts as the new version
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install failed"
                (context.applicationContext as dev.xvin.palimpsest.PalimpsestApp).graph.errors.report(IOException(msg), tr(R.string.update_install))
            }
        }
    }
}
