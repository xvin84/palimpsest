package dev.xvin.palimpsest.data.library

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.xvin.palimpsest.PalimpsestApp
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * What to shrink the library to. FLAC16 keeps FLAC (lossless relative to a CD) and only touches
 * hi-res files; the AAC targets are lossy but, at 256 kbps, indistinguishable for nearly everyone.
 */
enum class CompressTarget(val ext: String, val bitrate: Int, val labelRes: Int, val descRes: Int) {
    FLAC16("flac", 0, R.string.compress_flac16, R.string.compress_flac16_desc),
    AAC256("m4a", 256_000, R.string.compress_aac256, R.string.compress_aac256_desc),
    AAC128("m4a", 128_000, R.string.compress_aac128, R.string.compress_aac128_desc),
    /** Only lossy files (MP3 256/320 and the like): a second lossy pass, so a small, honest loss. */
    LOSSY_AAC192("m4a", 192_000, R.string.compress_lossy192, R.string.compress_lossy192_desc),
}

data class CompressPlan(val target: CompressTarget, val tracks: List<TrackEntity>, val bytesBefore: Long, val bytesAfter: Long)

/**
 * Fitting the library into a size: [tracks] (least loved first) become AAC; [protectedCount]
 * favourites and often or recently played tracks are never touched. [fits] is false when even
 * converting everything else isn't enough.
 */
data class BudgetPlan(
    val budgetBytes: Long,
    val libraryBytes: Long,
    val tracks: List<TrackEntity>,
    val bytesAfter: Long,
    val protectedCount: Int,
    val fits: Boolean,
)

data class CompressState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String? = null,
    val savedBytes: Long = 0,
    val failed: Int = 0,
    /** «Artist — Title» of what was converted in this run (shown after a trial). */
    val converted: List<String> = emptyList(),
)

/**
 * «Сжать медиатеку»: converts files on the phone, offline. Tags, cover, lyrics, the service link
 * and ReplayGain move over; the old file is deleted or kept as a version, as the user chose.
 */
class Compressor(
    private val context: Context,
    private val db: AppDatabase,
    private val versions: VersionRepository,
) {
    private val _state = MutableStateFlow(CompressState())
    val state: StateFlow<CompressState> = _state

    private val lossless = setOf("flac", "alac", "wav", "ape", "wv", "aiff", "aif")

    /** Whether converting [t] to [target] makes it noticeably smaller (and isn't lossy → lossy for little). */
    fun applies(t: TrackEntity, target: CompressTarget): Boolean {
        val path = t.path ?: return false
        val codec = (t.codec ?: File(path).extension).lowercase()
        val isLossless = lossless.any { codec.startsWith(it) }
        return when (target) {
            CompressTarget.FLAC16 -> codec.startsWith("flac") && (t.bitsPerSample ?: 16) > 16
            CompressTarget.LOSSY_AAC192 -> !isLossless && (t.bitrateKbps ?: 0) * 1000 >= 250_000
            else -> isLossless || (t.bitrateKbps ?: 0) * 1000 > target.bitrate * 3 / 2
        }
    }

    /** Rough size after conversion: AAC is bitrate × length; 24 → 16-bit FLAC is about 60 %. */
    private fun estimate(t: TrackEntity, target: CompressTarget): Long = when (target) {
        CompressTarget.FLAC16 -> t.sizeBytes * 6 / 10
        else -> t.durationMs / 1000 * target.bitrate / 8 + 150_000
    }

    suspend fun plan(target: CompressTarget): CompressPlan = withContext(Dispatchers.IO) {
        val tracks = db.tracks().all().filter { applies(it, target) && File(it.path!!).isFile }
        CompressPlan(target, tracks, tracks.sumOf { it.sizeBytes }, tracks.sumOf { estimate(it, target) })
    }

    /**
     * What to convert to [target] so the library fits into [budgetBytes]. Favourites, tracks played
     * 5+ times or more than once in the last 30 days, and the most played quarter stay as they are; of the rest,
     * the least played (and longest unheard) go first.
     */
    suspend fun budgetPlan(budgetBytes: Long, target: CompressTarget = CompressTarget.AAC256): BudgetPlan = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val local = db.tracks().all().filter { it.path != null && File(it.path).isFile }
        val libraryBytes = local.sumOf { it.sizeBytes }
        val stats = db.plays().trackStats().associateBy { it.trackId }
        val topPlays = stats.values.map { it.plays }.sortedDescending().let { it.getOrNull(it.size / 4) ?: Int.MAX_VALUE }.coerceAtLeast(2)
        fun protected(t: TrackEntity): Boolean {
            val st = stats[t.id]
            return t.liked || (st != null && (st.plays >= 5 || st.plays >= topPlays || (st.plays >= 2 && now - st.lastPlayed < 30 * 86_400_000L)))
        }
        val (keep, candidates) = local.partition { protected(it) }
        val order = candidates.filter { applies(it, target) }.sortedWith(
            compareBy<TrackEntity> { stats[it.id]?.plays ?: 0 }
                .thenBy { stats[it.id]?.lastPlayed ?: 0L }
                .thenByDescending { it.sizeBytes - estimate(it, target) },
        )
        var total = libraryBytes
        val chosen = ArrayList<TrackEntity>()
        for (t in order) {
            if (total <= budgetBytes) break
            chosen += t
            total -= (t.sizeBytes - estimate(t, target)).coerceAtLeast(0)
        }
        BudgetPlan(budgetBytes, libraryBytes, chosen, total, keep.size, total <= budgetBytes)
    }

    /** Converts what [budgetPlan] picks for the budget in the settings (AAC 256, originals not kept). */
    fun startBudget() {
        val req = OneTimeWorkRequestBuilder<CompressWorker>()
            .setInputData(workDataOf(CompressWorker.KEY_TARGET to CompressTarget.AAC256.name, CompressWorker.KEY_KEEP to false, CompressWorker.KEY_BUDGET to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
    }

    /** [limit]: convert only the first few (a trial run to listen to before doing the rest). */
    fun start(target: CompressTarget, keepOld: Boolean, limit: Int = 0) {
        LongWork.askUnrestricted(context)
        val req = LongWork.request<CompressWorker> {
            setInputData(workDataOf(CompressWorker.KEY_TARGET to target.name, CompressWorker.KEY_KEEP to keepOld, CompressWorker.KEY_LIMIT to limit))
        }
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
    }

    fun cancel() = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)

    suspend fun run(target: CompressTarget, keepOld: Boolean, limit: Int = 0, budgetBytes: Long = 0) {
        val full = if (budgetBytes > 0) budgetPlan(budgetBytes, target).let { CompressPlan(target, it.tracks, 0, 0) } else plan(target)
        val plan = if (limit > 0) full.copy(tracks = full.tracks.take(limit)) else full
        _state.value = CompressState(running = true, total = plan.tracks.size)
        try {
            plan.tracks.forEachIndexed { i, t ->
                currentCoroutineContext().ensureActive()
                _state.value = _state.value.copy(done = i, current = "${t.artist} — ${t.title}")
                runCatching { convert(t, target, keepOld) }
                    .onSuccess { saved -> _state.value = _state.value.copy(savedBytes = _state.value.savedBytes + saved, converted = _state.value.converted + "${t.artist} — ${t.title}") }
                    .onFailure { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        android.util.Log.w("Compressor", "${t.title}: $e", e)
                        _state.value = _state.value.copy(failed = _state.value.failed + 1)
                    }
            }
        } finally {
            _state.value = _state.value.copy(running = false, done = _state.value.total, current = null)
        }
    }

    /** Converts one file and puts it in place. Returns the bytes saved. */
    suspend fun convert(t: TrackEntity, target: CompressTarget, keepOld: Boolean): Long {
        val src = File(t.path!!)
        val before = src.length()
        val tmp = File(src.parentFile, ".compress-${t.id}.${target.ext}")
        try {
            when (target) {
                CompressTarget.FLAC16 -> withContext(Dispatchers.IO) { flac16(src, tmp) }
                else -> aac(src, tmp, target.bitrate)
            }
            withContext(Dispatchers.IO) { copyTags(src, tmp) }
            val note = tr(R.string.compress_note, tr(target.labelRes))
            val dest = File(src.parentFile, src.nameWithoutExtension + "." + target.ext)
            versions.installNewAudio(t, tmp, dest, note, keepOld)
            return before - (db.tracks().get(t.id)?.path?.let { File(it).length() } ?: before)
        } finally {
            tmp.delete()
        }
    }

    private fun copyTags(src: File, dst: File) {
        val d = TagIO.read(src)
        val extra = buildMap {
            d.replayGainTrackDb?.let { put("REPLAYGAIN_TRACK_GAIN", "%.2f dB".format(java.util.Locale.ROOT, it)) }
            d.replayGainAlbumDb?.let { put("REPLAYGAIN_ALBUM_GAIN", "%.2f dB".format(java.util.Locale.ROOT, it)) }
        }
        val cover = TagIO.coverBytes(src)
        TagIO.write(
            dst,
            TagEdit(
                title = d.title, artist = d.artist, album = d.album, albumArtist = d.albumArtist,
                year = d.year?.toString(), genre = d.genre, trackNumber = d.trackNumber?.toString(), discNumber = d.discNumber?.toString(),
                lyrics = d.lyrics, url = d.url, cover = cover,
                coverMime = if (cover != null && cover.size > 3 && cover[0] == 0x89.toByte()) "image/png" else "image/jpeg",
                extra = extra,
            ),
        )
    }

    /** Lossy AAC in an .m4a through Media3 Transformer (hardware or software encoder). */
    private suspend fun aac(src: File, dst: File, bitrate: Int) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val encoders = DefaultEncoderFactory.Builder(context)
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(bitrate).build())
                .build()
            val transformer = Transformer.Builder(context)
                .setLooper(Looper.getMainLooper())
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoders)
                .setMaxDelayBetweenMuxerSamplesMs(androidx.media3.common.C.TIME_UNSET)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src))).setRemoveVideo(true).build()
            transformer.start(item, dst.absolutePath)
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
        }
    }

    /**
     * Hi-res FLAC → 16-bit FLAC at the same sample rate: decoded to 16-bit PCM (float or 24/32-bit
     * output, where a decoder gives it, is reduced with TPDF dither), re-encoded by Android's own
     * FLAC encoder and written as a plain .flac. Sample count is patched into STREAMINFO, and a
     * result of the wrong length is refused.
     */
    private fun flac16(src: File, dst: File) {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(ti)
        val inFmt = ex.getTrackFormat(ti)
        val rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        // The decoder's own 16-bit output: on some devices a float request is acknowledged in the
        // output format while the buffers still hold 16-bit samples.
        dec.configure(inFmt, null, null, 0)
        dec.start()
        val encFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 8)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        enc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()

        var header: ByteArray? = null
        val tmpFrames = File(dst.parentFile, dst.name + ".frames")
        val frameOut = tmpFrames.outputStream().buffered(1 shl 16)
        var totalSamples = 0L
        val info = MediaCodec.BufferInfo()
        val pending = java.util.ArrayDeque<ShortArray>()
        var pendingPos = 0
        var inputDone = false
        var decoderDone = false
        var encoderInputDone = false
        var encoderDone = false
        val rnd = Random(7)
        try {
            while (!encoderDone) {
                // 1. File → decoder.
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val buf = dec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                // 2. Decoder → 16-bit PCM with dither.
                if (!decoderDone && pending.size < 8) {
                    val o = dec.dequeueOutputBuffer(info, 5_000)
                    if (o >= 0) {
                        // What the decoder really gives (it may ignore the float request): read per buffer.
                        val enc = dec.getOutputFormat(o).let { f -> if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT }
                        val buf = dec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder())
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        fun dither(v: Float) = Math.round(v * 32767f + (rnd.nextFloat() - rnd.nextFloat())).coerceIn(-32768, 32767).toShort()
                        val pcm = when (enc) {
                            AudioFormat.ENCODING_PCM_FLOAT -> buf.asFloatBuffer().let { fb -> ShortArray(fb.remaining()) { dither(fb.get()) } }
                            AudioFormat.ENCODING_PCM_32BIT -> buf.asIntBuffer().let { ib -> ShortArray(ib.remaining()) { dither(ib.get() / 2147483648f) } }
                            AudioFormat.ENCODING_PCM_24BIT_PACKED -> ShortArray(buf.remaining() / 3) {
                                val b0 = buf.get().toInt() and 0xff; val b1 = buf.get().toInt() and 0xff; val b2 = buf.get().toInt()
                                dither(((b2 shl 16) or (b1 shl 8) or b0) / 8388608f)
                            }
                            else -> buf.asShortBuffer().let { sb -> ShortArray(sb.remaining()) { sb.get() } }
                        }
                        if (pcm.isNotEmpty()) pending.add(pcm)
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                    }
                }
                // 3. PCM → encoder.
                if (!encoderInputDone) {
                    if (pending.isNotEmpty() || decoderDone) {
                        val i = enc.dequeueInputBuffer(5_000)
                        if (i >= 0) {
                            val buf = enc.getInputBuffer(i)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            buf.clear()
                            var written = 0
                            val sb = buf.asShortBuffer()
                            while (pending.isNotEmpty() && sb.remaining() >= channels) {
                                val head = pending.first()
                                val take = minOf(head.size - pendingPos, sb.remaining() / channels * channels)
                                sb.put(head, pendingPos, take)
                                pendingPos += take; written += take
                                if (pendingPos >= head.size) { pending.removeFirst(); pendingPos = 0 }
                            }
                            totalSamples += written / channels
                            val eos = decoderDone && pending.isEmpty()
                            enc.queueInputBuffer(i, 0, written * 2, totalSamples * 1_000_000 / rate, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if (eos) encoderInputDone = true
                        }
                    }
                }
                // 4. Encoder → file.
                val o = enc.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    val buf = enc.getOutputBuffer(o)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size).also { buf.get(it) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { if (header == null) header = bytes }
                    else frameOut.write(bytes)
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                }
            }
            frameOut.close()
            // Safety net: the result must be as long as the original, or the original stays.
            val expected = if (inFmt.containsKey(MediaFormat.KEY_DURATION)) inFmt.getLong(MediaFormat.KEY_DURATION) * rate / 1_000_000 else totalSamples
            if (expected > 0 && kotlin.math.abs(totalSamples - expected) > expected / 100) {
                throw java.io.IOException("length mismatch: $totalSamples samples instead of $expected")
            }
            val streamInfo = streamInfo(header ?: encFmt.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }, rate, channels, totalSamples)
            dst.outputStream().buffered(1 shl 16).use { out ->
                out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
                out.write(streamInfo)
                tmpFrames.inputStream().use { it.copyTo(out) }
            }
        } finally {
            frameOut.close(); tmpFrames.delete()
            runCatching { dec.stop() }; dec.release()
            runCatching { enc.stop() }; enc.release()
            ex.release()
        }
    }

    /**
     * The STREAMINFO block (with its «last block» header) from what the encoder gave, or built
     * from scratch: sample count filled in, frame sizes and MD5 left as «unknown».
     */
    private fun streamInfo(fromEncoder: ByteArray?, rate: Int, channels: Int, totalSamples: Long): ByteArray {
        var si: ByteArray? = null
        if (fromEncoder != null) {
            var p = if (fromEncoder.size >= 4 && String(fromEncoder, 0, 4, Charsets.ISO_8859_1) == "fLaC") 4 else 0
            while (p + 4 <= fromEncoder.size) {
                val type = fromEncoder[p].toInt() and 0x7f
                val len = ((fromEncoder[p + 1].toInt() and 0xff) shl 16) or ((fromEncoder[p + 2].toInt() and 0xff) shl 8) or (fromEncoder[p + 3].toInt() and 0xff)
                if (type == 0 && len == 34 && p + 38 <= fromEncoder.size) { si = fromEncoder.copyOfRange(p + 4, p + 38); break }
                p += 4 + len
            }
        }
        val body = si ?: ByteArray(34).also { b ->
            b[0] = 0x10; b[1] = 0x00; b[2] = 0x10; b[3] = 0x00 // block size 4096
            val bits = (rate.toLong() shl 12) or ((channels - 1).toLong() shl 9) or (15L shl 4)
            b[10] = (bits shr 24).toByte(); b[11] = (bits shr 16).toByte(); b[12] = (bits shr 8).toByte(); b[13] = bits.toByte()
        }
        // min/max frame size: unknown.
        for (i in 4..9) body[i] = 0
        body[13] = ((body[13].toInt() and 0xf0) or ((totalSamples shr 32).toInt() and 0x0f)).toByte()
        body[14] = (totalSamples shr 24).toByte(); body[15] = (totalSamples shr 16).toByte()
        body[16] = (totalSamples shr 8).toByte(); body[17] = totalSamples.toByte()
        for (i in 18 until 34) body[i] = 0 // MD5 unknown
        return byteArrayOf(0x80.toByte(), 0, 0, 34) + body
    }

    companion object {
        const val UNIQUE = "compress"
    }
}

class CompressWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as PalimpsestApp).graph
        val target = runCatching { CompressTarget.valueOf(inputData.getString(KEY_TARGET)!!) }.getOrNull() ?: return Result.failure()
        val keep = inputData.getBoolean(KEY_KEEP, false)
        val limit = inputData.getInt(KEY_LIMIT, 0)
        val budget = if (inputData.getBoolean(KEY_BUDGET, false)) {
            val gb = graph.settings.state.value?.budgetGb ?: graph.settings.flow.first().budgetGb
            if (gb <= 0) return Result.success()
            gb * 1_000_000_000L
        } else 0L
        LongWork.foreground(this, getForegroundInfo())
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        LongWork.awake(applicationContext, "compress") {
            kotlinx.coroutines.coroutineScope {
                val ticker = launch {
                    graph.compressor.state.collect { s -> nm.notify(NOTIFICATION_ID, notification(s)) }
                }
                graph.compressor.run(target, keep, limit, budget)
                ticker.cancel()
            }
        }
        nm.cancel(NOTIFICATION_ID)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notification(CompressState(running = true)), LongWork.foregroundType)

    private fun notification(s: CompressState): android.app.Notification {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.compress_title), NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.compress_title))
            .setContentText(s.current ?: tr(R.string.dl_preparing))
            .setProgress(s.total.coerceAtLeast(1), s.done, s.total == 0)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val KEY_TARGET = "target"
        const val KEY_KEEP = "keep"
        const val KEY_LIMIT = "limit"
        const val KEY_BUDGET = "budget"
        private const val CHANNEL = "compress"
        private const val NOTIFICATION_ID = 4711
    }
}
