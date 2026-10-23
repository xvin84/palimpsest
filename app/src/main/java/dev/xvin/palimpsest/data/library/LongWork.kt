package dev.xvin.palimpsest.data.library

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy

/**
 * What hours-long work on the phone's own files (shrinking the library, the audio analysis) needs
 * to go on with the app closed and the screen off: it starts at once while the app is open, so
 * it may become a foreground service; it holds the CPU awake (a foreground service alone doesn't
 * — with the screen off the phone dozes off mid-file); and the app asks once to be let off
 * battery optimisation, which some phones (realme, Xiaomi…) otherwise enforce on any service.
 */
object LongWork {
    private const val TAG = "LongWork"

    val foregroundType: Int
        get() = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    /** Runs right away (expedited) when the system allows, or as normal work otherwise. */
    inline fun <reified W : CoroutineWorker> request(configure: OneTimeWorkRequest.Builder.() -> Unit = {}): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<W>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .apply(configure)
            .build()

    /** Makes [worker] a foreground service; says in the log when the system refuses. */
    suspend fun foreground(worker: CoroutineWorker, info: ForegroundInfo) {
        runCatching { worker.setForeground(info) }.onFailure { Log.w(TAG, "not a foreground service, may stop in the background: $it") }
    }

    /** [block] with the CPU kept awake (released at the end, or after 10 hours at most). */
    suspend fun <T> awake(context: Context, tag: String, block: suspend () -> T): T {
        val wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "palimpsest:$tag")
        wake.acquire(10 * 3600_000L)
        return try {
            block()
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    @Volatile private var asked = false

    fun isUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** The system's «Let the app run in the background?» dialog, once per launch, if it isn't allowed yet. */
    @SuppressLint("BatteryLife") // a personal player converting hours of audio on request
    fun askUnrestricted(context: Context) {
        if (asked || isUnrestricted(context)) return
        asked = true
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "battery optimisation dialog: $it") }
    }
}
