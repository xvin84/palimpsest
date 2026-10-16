package dev.xvin.palimpsest.data

import android.content.Context
import android.os.StatFs
import androidx.media3.datasource.cache.SimpleCache
import coil3.SingletonImageLoader
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.library.VersionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Where the space goes. Sizes in bytes. */
data class StorageReport(
    val libraryBytes: Long,
    val libraryFiles: Int,
    val versionsBytes: Long,
    val versionsCount: Int,
    val streamCacheBytes: Long,
    val imagesBytes: Long,
    val otherCacheBytes: Long,
    val backupsBytes: Long,
    val backupsCount: Int,
    val freeBytes: Long,
    val totalBytes: Long,
)

/**
 * «Память»: measures what the app keeps on the phone and frees it on request. Music files of the
 * library are never deleted here; only caches, archived versions and surplus backups.
 */
class Storage(
    private val context: Context,
    private val db: AppDatabase,
    private val versions: VersionRepository,
    private val backups: Backups,
    private val streamCache: () -> SimpleCache,
) {
    private val cacheDir get() = context.cacheDir
    private val imageDirs get() = listOf(File(cacheDir, "coil"), File(cacheDir, "art"))
    private val otherDirs get() = listOf(File(cacheDir, "wave"), File(context.filesDir, "translations"), File(cacheDir, "share"))

    suspend fun report(): StorageReport = withContext(Dispatchers.IO) {
        val paths = db.tracks().allPaths()
        val archived = db.versions().archived()
        val backupFiles = runCatching { backups.list() }.getOrDefault(emptyList())
        val stat = StatFs(paths.firstOrNull()?.let { File(it).parent } ?: android.os.Environment.getExternalStorageDirectory().path)
        StorageReport(
            libraryBytes = paths.sumOf { File(it).length() },
            libraryFiles = paths.size,
            versionsBytes = archived.sumOf { File(it.path).length() },
            versionsCount = archived.size,
            streamCacheBytes = runCatching { streamCache().cacheSpace }.getOrDefault(0L),
            imagesBytes = imageDirs.sumOf(::sizeOf),
            otherCacheBytes = otherDirs.sumOf(::sizeOf),
            backupsBytes = backupFiles.sumOf { it.length() },
            backupsCount = backupFiles.size,
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
        )
    }

    suspend fun clearStreamCache() = withContext(Dispatchers.IO) {
        val cache = streamCache()
        cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
    }

    suspend fun clearImages() = withContext(Dispatchers.IO) {
        SingletonImageLoader.get(context).let { it.diskCache?.clear(); it.memoryCache?.clear() }
        imageDirs.forEach { it.deleteRecursively() }
    }

    suspend fun clearOther() = withContext(Dispatchers.IO) { otherDirs.forEach { it.deleteRecursively() } }

    /** Deletes archived versions older than [olderThanDays] (all of them when null). Returns how many. */
    suspend fun deleteVersions(olderThanDays: Int?): Int = withContext(Dispatchers.IO) {
        val cutoff = olderThanDays?.let { System.currentTimeMillis() - it * 86_400_000L } ?: Long.MAX_VALUE
        val old = db.versions().archived().filter { it.createdAt < cutoff }
        old.forEach { versions.delete(it) }
        old.size
    }

    /** Keeps only the newest backup. */
    suspend fun pruneBackups() = withContext(Dispatchers.IO) { backups.list().drop(1).forEach { it.delete() } }

    private fun sizeOf(f: File): Long = if (!f.exists()) 0 else f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
