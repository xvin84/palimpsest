package dev.xvin.palimpsest.data.library

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.xvin.palimpsest.PalimpsestApp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Once a week, while charging: if the library grew past the storage budget, the least played
 * tracks become AAC 256 (favourites and often played ones stay as they are).
 */
class BudgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as PalimpsestApp).graph
        val s = graph.settings.flow.first()
        if (s.budgetGb <= 0 || !s.budgetAuto) return Result.success()
        val plan = graph.compressor.budgetPlan(s.budgetGb * 1_000_000_000L)
        if (plan.tracks.isNotEmpty()) graph.compressor.startBudget()
        return Result.success()
    }

    companion object {
        private const val NAME = "storage-budget"

        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<BudgetWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
