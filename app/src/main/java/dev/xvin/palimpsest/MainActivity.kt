package dev.xvin.palimpsest

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dev.xvin.palimpsest.ui.AppRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val graph by lazy { (application as PalimpsestApp).graph }

    /** Bumped when something asks to show the full-screen player (notification tap, opened file). */
    val openPlayerRequests = mutableStateOf(0)
    /** Album to open (from a "new release" notification). */
    val openAlbumRequest = mutableStateOf<String?>(null)
    /** A Yandex Music, YouTube or Bandcamp link opened or shared into the app. */
    val openLinkRequest = mutableStateOf<dev.xvin.palimpsest.core.links.MusicLink?>(null)
    val openSettingsRequest = mutableStateOf(false)
    val hasStorageAccess = mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAccess() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        graph.player.connect()
        refreshAccess()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            AppRoot(graph = graph, activity = this)
        }
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // The language changed while we were running: start over so no screen keeps old strings.
        if (dev.xvin.palimpsest.i18n.Language.restartIfChanged(this)) return
        // Coming back from the "All files access" settings page.
        val before = hasStorageAccess.value
        refreshAccess()
        if (!before && hasStorageAccess.value) lifecycleScope.launch { graph.scanner.scan() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    fun refreshAccess() {
        hasStorageAccess.value = Environment.isExternalStorageManager() ||
            checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun requestAllFilesAccess() {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    fun requestReadOnlyAccess() = audioPermission.launch(Manifest.permission.READ_MEDIA_AUDIO)

    private fun handleIntent(intent: Intent?) {
        // Debug builds only: pretend the FLAC search found [debug_flac_source] for the track at
        // [debug_flac_track], to try the «same song?» flow without a Yandex account.
        if (BuildConfig.DEBUG && intent?.getStringExtra("debug_flac_track") != null) {
            val g = (application as PalimpsestApp).graph
            val trackPath = intent.getStringExtra("debug_flac_track")!!
            val source = java.io.File(intent.getStringExtra("debug_flac_source")!!)
            g.appScope.launch {
                val t = g.db.tracks().byPath(trackPath) ?: return@launch
                val copy = java.io.File(cacheDir, "debug-candidate.flac").also { source.copyTo(it, overwrite = true) }
                g.versions.addCandidate(t, copy, "FLAC (debug)")
            }
        }
        // Debug builds only: plan the storage budget at [debug_budget_mb] (the UI works in GB) and
        // convert what it picks, keeping the originals as versions.
        if (BuildConfig.DEBUG && intent?.hasExtra("debug_budget_mb") == true) {
            val g = (application as PalimpsestApp).graph
            val bytes = intent.getIntExtra("debug_budget_mb", 0) * 1_000_000L
            g.appScope.launch {
                val plan = g.compressor.budgetPlan(bytes)
                android.util.Log.i("Budget", "library ${plan.libraryBytes} → ${plan.bytesAfter}, protected ${plan.protectedCount}, fits ${plan.fits}: " + plan.tracks.joinToString { it.title })
                g.compressor.run(dev.xvin.palimpsest.data.library.CompressTarget.AAC256, keepOld = true, budgetBytes = bytes)
                android.util.Log.i("Budget", "done: ${g.compressor.state.value}")
            }
        }
        intent ?: return
        intent.getStringExtra(EXTRA_OPEN_ALBUM)?.let { openAlbumRequest.value = it; return }
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) { openSettingsRequest.value = true; return }
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            openPlayerRequests.value++
            return
        }
        val linkText = when {
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" -> intent.getStringExtra(Intent.EXTRA_TEXT)
            intent.action == Intent.ACTION_VIEW && intent.data?.scheme?.startsWith("http") == true -> intent.data.toString()
            else -> null
        }
        if (linkText != null) {
            val link = dev.xvin.palimpsest.core.links.MusicLink.find(linkText)
            if (link != null) openLinkRequest.value = link
            else android.widget.Toast.makeText(this, dev.xvin.palimpsest.i18n.tr(R.string.link_unknown), android.widget.Toast.LENGTH_LONG).show()
            setIntent(Intent(this, MainActivity::class.java))
            return
        }
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) { resolvePath(uri) }
            val track = path?.let { p -> withContext(Dispatchers.IO) { graph.db.tracks().byPath(p) } }
            graph.player.playExternal(uri, track)
            openPlayerRequests.value++
        }
        // Don't replay the same file on rotation.
        setIntent(Intent(this, MainActivity::class.java))
    }

    /** Best-effort mapping of a content:// URI back to a file path, to find it in the library. */
    private fun resolvePath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path?.let { runCatching { File(it).canonicalPath }.getOrDefault(it) }
        runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { if (File(it).exists()) return it }
            }
        }
        runCatching {
            if (DocumentsContract.isDocumentUri(this, uri) && uri.authority == "com.android.externalstorage.documents") {
                val (volume, rel) = DocumentsContract.getDocumentId(uri).split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val base = if (volume == "primary") Environment.getExternalStorageDirectory().absolutePath else "/storage/$volume"
                File(base, rel).takeIf { it.exists() }?.let { return it.absolutePath }
            }
        }
        // Many file managers use their own FileProvider with the real path in the URI.
        uri.path?.let { p ->
            val idx = p.indexOf("/storage/")
            if (idx >= 0) File(p.substring(idx)).takeIf { it.exists() }?.let { return it.absolutePath }
        }
        return null
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
        const val EXTRA_OPEN_ALBUM = "open_album"
        const val EXTRA_OPEN_SETTINGS = "open_settings"
    }
}
