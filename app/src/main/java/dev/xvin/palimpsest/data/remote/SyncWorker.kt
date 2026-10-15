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
        if (!s.autoSync) return Result.success()
        graph.scanner.scan()
        if (graph.yandex.isReady) {
            graph.remote.syncYandex()
            graph.remote.checkForUpdates()
            if (s.autoDownloadLikes) {
                val pending = graph.db.tracks().linkedTo(Service.YANDEX).filter { it.liked && it.path == null }
                graph.downloads.enqueue(pending.map { it.id })
            }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "daily-sync"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
