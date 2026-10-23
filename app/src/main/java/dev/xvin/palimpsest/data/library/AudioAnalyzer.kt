package dev.xvin.palimpsest.data.library

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.xvin.palimpsest.PalimpsestApp
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.core.analysis.AudioFeatures
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder

data class AnalysisState(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val current: String? = null)

/**
 * Tempo, key (stored as Camelot, e.g. «8A») and energy for every local track — for the offline
 * wave's moods and for transitions that flow. A minute from the middle of each file is decoded,
 * folded to mono at ~11 kHz and handed to [AudioFeatures]. Runs as a foreground job.
 */
class AudioAnalyzer(private val context: Context, private val db: AppDatabase) {
    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state

    fun start() {
        LongWork.askUnrestricted(context)
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, LongWork.request<AnalysisWorker>())
    }

    fun cancel() = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)

    suspend fun run() = withContext(Dispatchers.Default) {
        val todo = db.tracks().notAnalysed()
        _state.value = AnalysisState(true, 0, todo.size)
        try {
            todo.forEachIndexed { i, t ->
                currentCoroutineContext().ensureActive()
                _state.value = _state.value.copy(done = i, current = "${t.artist} — ${t.title}")
                val f = runCatching { analyze(File(t.path ?: return@forEachIndexed)) }.getOrNull()
                // A file that can't be read gets bpm 0 so it isn't retried every time.
                db.tracks().setAnalysis(t.id, f?.bpm ?: 0f, f?.camelot, f?.energy)
            }
        } finally {
            _state.value = _state.value.copy(running = false, done = _state.value.total, current = null)
        }
    }

    fun analyze(file: File): dev.xvin.palimpsest.core.analysis.Features? {
        val (pcm, rate) = decodeMono(file, WINDOW_US) ?: return null
        return AudioFeatures.analyze(pcm, rate)
    }

    /** Up to [lengthUs] of audio from about a third into the file, mono, decimated to ~11 kHz. */
    private fun decodeMono(file: File, lengthUs: Long): Pair<FloatArray, Int>? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val duration = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val startUs = if (duration > lengthUs * 3 / 2) (duration - lengthUs) / 3 else 0L
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val factor = (rate / 11025).coerceAtLeast(1)
            val outRate = rate / factor
            val out = FloatArray((lengthUs * outRate / 1_000_000).toInt())
            var outN = 0
            var acc = 0f; var accN = 0
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(fmt, null, null, 0)
            dec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            try {
                while (outN < out.size) {
                    if (!inputDone) {
                        val i = dec.dequeueInputBuffer(5_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                            if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = dec.dequeueOutputBuffer(info, 5_000)
                    if (o < 0) continue
                    val enc = dec.getOutputFormat(o).let { f -> if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT }
                    val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val samples: FloatArray = when (enc) {
                        AudioFormat.ENCODING_PCM_FLOAT -> buf.asFloatBuffer().let { fb -> FloatArray(fb.remaining()) { fb.get() } }
                        AudioFormat.ENCODING_PCM_32BIT -> buf.asIntBuffer().let { ib -> FloatArray(ib.remaining()) { ib.get() / 2147483648f } }
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> FloatArray(buf.remaining() / 3) {
                            val b0 = buf.get().toInt() and 0xff; val b1 = buf.get().toInt() and 0xff; val b2 = buf.get().toInt()
                            ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                        }
                        else -> buf.asShortBuffer().let { sb -> FloatArray(sb.remaining()) { sb.get() / 32768f } }
                    }
                    var k = 0
                    while (k + channels <= samples.size && outN < out.size) {
                        var m = 0f
                        for (c in 0 until channels) m += samples[k + c]
                        acc += m / channels; accN++
                        if (accN == factor) { out[outN++] = acc / factor; acc = 0f; accN = 0 }
                        k += channels
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    dec.releaseOutputBuffer(o, false)
                    if (eos) break
                }
            } finally {
                runCatching { dec.stop() }; dec.release()
            }
            return if (outN < outRate * 10) null else out.copyOf(outN) to outRate
        } finally {
            ex.release()
        }
    }

    companion object {
        private const val UNIQUE = "audio-analysis"
        private const val WINDOW_US = 60_000_000L
    }
}

class AnalysisWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as PalimpsestApp).graph
        LongWork.foreground(this, getForegroundInfo())
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        LongWork.awake(applicationContext, "analysis") {
            kotlinx.coroutines.coroutineScope {
                val ticker = launch { graph.analyzer.state.collect { s -> nm.notify(NOTIFICATION_ID, notification(s)) } }
                graph.analyzer.run()
                ticker.cancel()
            }
        }
        nm.cancel(NOTIFICATION_ID)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notification(AnalysisState(running = true)), LongWork.foregroundType)

    private fun notification(s: AnalysisState): android.app.Notification {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.health_fix_analyse), NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.health_fix_analyse))
            .setContentText(s.current ?: tr(R.string.dl_preparing))
            .setProgress(s.total.coerceAtLeast(1), s.done, s.total == 0)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "analysis"
        private const val NOTIFICATION_ID = 4712
    }
}
