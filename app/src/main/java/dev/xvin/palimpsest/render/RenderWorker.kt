package dev.xvin.palimpsest.render

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.xvin.palimpsest.PalimpsestApp
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr
import kotlinx.coroutines.CancellationException

/** Renders a video in the background with a progress notification; the result opens from a notification. */
class RenderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as PalimpsestApp).graph
        val track = graph.db.tracks().get(inputData.getLong(KEY_TRACK, -1)) ?: return Result.failure()
        val mode = RenderMode.valueOf(inputData.getString(KEY_MODE) ?: RenderMode.COVER.name)
        val aspect = RenderAspect.valueOf(inputData.getString(KEY_ASPECT) ?: RenderAspect.VERTICAL.name)
        val quality = RenderQuality.valueOf(inputData.getString(KEY_QUALITY) ?: RenderQuality.HIGH.name)
        val title = "${track.artist} — ${track.title}"
        setForeground(foreground(title, 0f))
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        var lastPercent = -1
        // Keep the CPU awake: some phones pause background work for long enough to break an export.
        val wake = applicationContext.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "palimpsest:render").apply { acquire(3 * 3600_000L) }
        return try {
            val uri = VideoRenderer(applicationContext, graph).render(track, mode, aspect, quality) { p ->
                val percent = (p * 100).toInt()
                if (percent != lastPercent) { lastPercent = percent; nm.notify(PROGRESS_ID, notification(title, p)) }
            }
            done(title, uri)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.errors.report(e, tr(R.string.render_ctx, title))
            Result.failure()
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    private fun channel() {
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.render_channel), NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(title: String, p: Float): android.app.Notification {
        channel()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.render_progress_title))
            .setContentText("$title · ${(p * 100).toInt()}%")
            .setProgress(100, (p * 100).toInt(), p <= 0f)
            .addAction(0, tr(R.string.cancel), cancel)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun foreground(title: String, p: Float) = ForegroundInfo(
        PROGRESS_ID, notification(title, p),
        if (android.os.Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private fun done(title: String, uri: Uri) {
        channel()
        val ctx = applicationContext
        val view = PendingIntent.getActivity(
            ctx, 11, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val share = PendingIntent.getActivity(
            ctx, 12,
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        ctx.getSystemService(NotificationManager::class.java).notify(
            DONE_ID,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(tr(R.string.render_done))
                .setContentText(title)
                .setContentIntent(view)
                .addAction(0, tr(R.string.share), share)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val KEY_TRACK = "track"
        private const val KEY_MODE = "mode"
        private const val KEY_ASPECT = "aspect"
        private const val KEY_QUALITY = "quality"
        private const val CHANNEL = "render"
        private const val PROGRESS_ID = 61
        private const val DONE_ID = 62

        fun start(context: Context, trackId: Long, mode: RenderMode, aspect: RenderAspect, quality: RenderQuality) {
            val req = OneTimeWorkRequestBuilder<RenderWorker>()
                .setInputData(workDataOf(KEY_TRACK to trackId, KEY_MODE to mode.name, KEY_ASPECT to aspect.name, KEY_QUALITY to quality.name))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("render", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
