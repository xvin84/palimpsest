package dev.xvin.palimpsest.data

import android.content.Context
import android.content.Intent
import dev.xvin.palimpsest.MainActivity
import dev.xvin.palimpsest.core.dsp.EqProfile
import dev.xvin.palimpsest.core.model.Quality
import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.settings.ReplayGainMode
import dev.xvin.palimpsest.data.settings.Settings
import dev.xvin.palimpsest.data.settings.VisualizerMode
import dev.xvin.palimpsest.data.settings.WavePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr

/** Settings worth carrying over. Tokens are deliberately left out of backups. */
@Serializable
private data class SettingsSnapshot(
    val musicRoots: List<String>,
    val downloadDir: String,
    val quality: Quality,
    val replayGain: ReplayGainMode,
    val replayGainPreampDb: Float,
    val eqEnabled: Boolean,
    val eqProfile: EqProfile,
    val customEqProfiles: List<EqProfile>,
    val deviceEq: Map<String, String>,
    val visualizer: VisualizerMode,
    val dynamicColor: Boolean,
    val keepVersions: Boolean,
    val autoDownloadLikes: Boolean,
    val fadeSec: Int,
    val waveDiversity: String,
    val waveMood: String,
    val waveLanguage: String,
)

/**
 * Library, history, playlists, versions and settings in one zip under
 * `Music/.palimpsest/backups` — outside the app's private storage, so it survives a reinstall.
 */
class Backups(private val context: Context, private val db: AppDatabase, private val settings: Settings) {

    suspend fun dir(): File {
        val root = settings.state.filterNotNull().first().musicRoots.first()
        return File(root, ".palimpsest/backups").apply { mkdirs() }
    }

    suspend fun list(): List<File> = withContext(Dispatchers.IO) {
        dir().listFiles { f -> f.name.endsWith(".zip") }?.sortedByDescending { it.lastModified() }.orEmpty()
    }

    suspend fun create(keep: Int = 5): File = withContext(Dispatchers.IO) {
        // Flush the WAL so the main db file is complete.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val s = settings.state.filterNotNull().first()
        val snapshot = SettingsSnapshot(
            s.musicRoots, s.downloadDir, s.quality, s.replayGain, s.replayGainPreampDb, s.eqEnabled, s.eqProfile,
            s.customEqProfiles, s.deviceEq, s.visualizer, s.dynamicColor, s.keepVersions, s.autoDownloadLikes, s.fadeSec,
            s.wave.diversity, s.wave.mood, s.wave.language,
        )
        val out = File(dir(), "palimpsest-" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date()) + ".zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(DB))
            context.getDatabasePath(DB_NAME).inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(SETTINGS))
            zip.write(Http.json.encodeToString(snapshot).toByteArray())
            zip.closeEntry()
        }
        list().drop(keep).forEach { it.delete() }
        out
    }

    /** Restores [backup] and restarts the app (the database can't be swapped while open). */
    suspend fun restore(backup: File) = withContext(Dispatchers.IO) {
        ZipFile(backup).use { zip ->
            val snap = zip.getEntry(SETTINGS)?.let { e -> zip.getInputStream(e).use { Http.json.decodeFromString<SettingsSnapshot>(it.readBytes().decodeToString()) } }
            snap?.let { s ->
                settings.setMusicRoots(s.musicRoots); settings.setDownloadDir(s.downloadDir); settings.setQuality(s.quality)
                settings.setReplayGain(s.replayGain); settings.setReplayGainPreamp(s.replayGainPreampDb); settings.setEqEnabled(s.eqEnabled)
                settings.setEqProfile(s.eqProfile); settings.setCustomEqProfiles(s.customEqProfiles); settings.setDeviceEq(s.deviceEq)
                settings.setVisualizer(s.visualizer); settings.setDynamicColor(s.dynamicColor); settings.setKeepVersions(s.keepVersions)
                settings.setAutoDownloadLikes(s.autoDownloadLikes); settings.setFadeSec(s.fadeSec)
                settings.setWave((settings.state.value?.wave ?: WavePrefs()).copy(diversity = s.waveDiversity, mood = s.waveMood, language = s.waveLanguage))
            }
            val entry = zip.getEntry(DB) ?: error(tr(R.string.backup_no_db))
            db.close()
            val target = context.getDatabasePath(DB_NAME)
            File(target.path + "-wal").delete(); File(target.path + "-shm").delete()
            zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        val restart = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(restart)
        Runtime.getRuntime().exit(0)
    }

    private companion object {
        const val DB_NAME = "palimpsest.db"
        const val DB = "palimpsest.db"
        const val SETTINGS = "settings.json"
    }
}
