package dev.xvin.palimpsest.data.remote

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.xvin.palimpsest.PalimpsestApp
import dev.xvin.palimpsest.core.model.Service
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Daily housekeeping on Wi-Fi: rescan the music folders, pull Yandex likes and playlists,
 * look for re-uploaded tracks, and (optionally) download new likes for offline listening.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as PalimpsestApp).graph
        val s = graph.settings.state.filterNotNull().first()
        if (s.checkUpdates) runCatching { checkAppUpdateDaily(graph) }
        if (!s.autoSync) return Result.success()
        graph.scanner.scan()
        graph.scrobbler.flush()
        // Weekly safety copy of the library and settings.
        runCatching {
            val last = graph.backups.list().firstOrNull()?.lastModified() ?: 0
            if (System.currentTimeMillis() - last > 7L * 86_400_000) graph.backups.create()
        }
        if (graph.yandex.isReady) {
            graph.syncYandex() // also downloads new likes when that's on
            graph.remote.checkForUpdates()
            runCatching { graph.releases.check() } // at most once a day
        }
        return Result.success()
    }

    /** At most once a day; the notification for a given version is shown only once. */
    private suspend fun checkAppUpdateDaily(graph: dev.xvin.palimpsest.AppGraph) {
        val prefs = applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("last_check", 0) < 20 * 3600_000L) return
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        graph.updater.check(notify = true)
    }

    companion object {
        private const val NAME = "daily-sync"

        fun schedule(context: Context) {
            // Every 3 hours so likes from the official app show up (and download) the same day.
            val req = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
