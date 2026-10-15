package dev.xvin.palimpsest.playback

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Loudness envelope of a file for the waveform seek bar. Computed once by decoding the audio
 * with the platform codecs, then cached next to the app's other caches (a few hundred bytes).
 */
class Waveform(private val cacheDir: File) {

    suspend fun get(trackId: Long, file: File, bins: Int = BINS): FloatArray? = withContext(Dispatchers.IO) {
        val cache = File(cacheDir, "wave/${trackId}_${file.lastModified()}_${bins}_v2.bin")
        if (cache.isFile) return@withContext runCatching { read(cache, bins) }.getOrNull()
        val wave = runCatching { compute(file, bins) }.onFailure { Log.w("Waveform", "failed for ${file.name}: $it") }.getOrNull()
            ?: return@withContext null
        cache.parentFile?.let { dir ->
            dir.mkdirs()
            dir.listFiles { f -> f.name.startsWith("${trackId}_") }?.forEach { it.delete() }
        }
        runCatching { write(cache, wave) }
        wave
    }

    private suspend fun compute(file: File, bins: Int): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }

            // RMS per bin reads better than raw peaks: brickwalled masters don't become a solid block.
            val sumSq = DoubleArray(bins)
            val count = LongArray(bins)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIdx >= 0) {
                    val out = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                    val bin = ((info.presentationTimeUs.toDouble() / durationUs) * bins).toInt().coerceIn(0, bins - 1)
                    // Sub-sample: every 8th frame is plenty for a 120-bar overview.
                    val step = channels * 8
                    if (isFloat) {
                        val floats = out.asFloatBuffer()
                        var i = 0
                        while (i < info.size / 4) { val v = abs(floats.get(i)).toDouble(); sumSq[bin] += v * v; count[bin]++; i += step }
                    } else {
                        val shorts = out.asShortBuffer()
                        var i = 0
                        while (i < info.size / 2) { val v = abs(shorts.get(i).toInt()) / 32768.0; sumSq[bin] += v * v; count[bin]++; i += step }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val rms = FloatArray(bins) { if (count[it] > 0) sqrt(sumSq[it] / count[it]).toFloat() else 0f }
            // Light smoothing so ~1 s bins read as a contour rather than beat-by-beat jitter.
            val smooth = FloatArray(bins) { i ->
                0.25f * rms[(i - 1).coerceAtLeast(0)] + 0.5f * rms[i] + 0.25f * rms[(i + 1).coerceAtMost(bins - 1)]
            }
            val max = smooth.maxOrNull()?.takeIf { it > 0 } ?: 1f
            return FloatArray(bins) { (smooth[it] / max).coerceIn(0f, 1f) }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun write(f: File, w: FloatArray) = DataOutputStream(f.outputStream().buffered()).use { out ->
        out.writeInt(w.size); w.forEach { out.writeByte((it * 255).toInt()) }
    }

    private fun read(f: File, bins: Int): FloatArray = DataInputStream(f.inputStream().buffered()).use { inp ->
        val n = inp.readInt()
        require(n == bins)
        FloatArray(n) { inp.readUnsignedByte() / 255f }
    }

    companion object {
        const val BINS = 120
    }
}
