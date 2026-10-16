package dev.xvin.palimpsest.render

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.xvin.palimpsest.core.dsp.EqProfile
import dev.xvin.palimpsest.core.dsp.Equalizer
import dev.xvin.palimpsest.core.dsp.SpectrumAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/** Visualiser levels for every video frame, computed ahead of time from the processed audio. */
class SpectrumTimeline(val fps: Int, val bands: Int, private val levels: Array<FloatArray>, val durationUs: Long, val sampleRate: Int) {
    private val silent = FloatArray(bands)

    fun at(timeUs: Long): FloatArray = levels.getOrNull((timeUs * fps / 1_000_000L).toInt()) ?: silent

    companion object {
        /**
         * Decodes [file], runs it through the same EQ (+ ReplayGain gain) as playback, and samples
         * the spectrum once per video frame with the in-app visualiser's smoothing, so the video
         * moves exactly like the player does.
         */
        suspend fun analyze(
            file: File, fps: Int, bands: Int,
            eqProfile: EqProfile, eqEnabled: Boolean, gainDb: Double,
            onProgress: (Float) -> Unit,
        ): SpectrumTimeline = withContext(Dispatchers.Default) {
            val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track in ${file.name}")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val durationUs = inFormat.getLong(MediaFormat.KEY_DURATION)
            val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(inFormat, null, null, 0)
            decoder.start()

            val total = ((durationUs * fps) / 1_000_000L).toInt() + 1
            val out = ArrayList<FloatArray>(total)
            val analyzer = SpectrumAnalyzer(fftSize = 2048, bands = bands)
            val eq = Equalizer().apply { setProfile(eqProfile); enabled = eqEnabled; setGainDb(gainDb) }
            val smooth = FloatArray(bands)
            var peak = 0.3f
            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var framesDone = 0L
            var floats = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            try {
                eq.configure(sampleRate, channels)
                analyzer.sampleRate = sampleRate
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val i = decoder.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = decoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = decoder.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            eq.configure(sampleRate, channels)
                            analyzer.sampleRate = sampleRate
                        }
                        o >= 0 -> {
                            val buf = decoder.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val samples = info.size / (if (isFloat) 4 else 2)
                            if (floats.size < samples) floats = FloatArray(samples)
                            if (isFloat) for (k in 0 until samples) floats[k] = buf.getFloat()
                            else for (k in 0 until samples) floats[k] = buf.getShort() / 32768f
                            decoder.releaseOutputBuffer(o, false)
                            val frames = samples / channels
                            eq.process(floats, frames)
                            // Feed in slices so each video frame sees exactly the audio up to its time.
                            var pos = 0
                            while (pos < frames) {
                                val nextFrameAt = ((out.size + 1).toLong() * sampleRate / fps) - framesDone
                                val take = minOf(frames - pos, nextFrameAt.toInt().coerceAtLeast(1))
                                if (pos == 0 && take == frames) analyzer.push(floats, frames, channels)
                                else analyzer.push(floats.copyOfRange(pos * channels, (pos + take) * channels), take, channels)
                                pos += take
                                framesDone += take
                                if (framesDone * fps >= (out.size + 1).toLong() * sampleRate) {
                                    val target = analyzer.compute()
                                    peak = maxOf(peak * 0.997f, target.max(), 0.15f)
                                    for (b in 0 until bands) {
                                        val v = (target[b] / peak).coerceIn(0f, 1f).let { it * it }
                                        smooth[b] = if (v > smooth[b]) smooth[b] + (v - smooth[b]) * 0.6f else smooth[b] * 0.90f + v * 0.10f
                                    }
                                    out += smooth.copyOf()
                                    if (out.size % 30 == 0) onProgress((out.size.toFloat() / total).coerceAtMost(1f))
                                }
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            } finally {
                runCatching { decoder.stop() }
                decoder.release()
                extractor.release()
            }
            SpectrumTimeline(fps, bands, out.toTypedArray(), durationUs, sampleRate)
        }
    }
}
