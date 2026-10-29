package dev.xvin.palimpsest.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.xvin.palimpsest.core.dsp.Equalizer
import dev.xvin.palimpsest.core.dsp.SpectrumAnalyzer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shared audio effects state. Lives in the app process, so the UI can drive the EQ and read
 * the spectrum without any IPC.
 */
class AudioEffects {
    val equalizer = Equalizer()
    val spectrum = SpectrumAnalyzer(fftSize = 2048, bands = 48)

    /** The analyzer only runs while some visualiser is on screen. */
    @Volatile var analyzerActive = false

    /**
     * A fade over everything else, glided sample by sample in the audio chain (stepping the
     * player's volume instead is heard as a flutter): where it's going and how long 0→1 takes.
     */
    @Volatile var fadeTarget = 1f
        private set
    @Volatile var fadeMs = 1L
        private set
    @Volatile internal var fadeSnap = false

    fun fadeTo(target: Float, ms: Long) { fadeMs = ms.coerceAtLeast(1); fadeTarget = target.coerceIn(0f, 1f) }

    /** Straight to [value], with no glide (before a fade-in starts from silence). */
    fun fadeSet(value: Float) { fadeTarget = value.coerceIn(0f, 1f); fadeSnap = true }
}

/**
 * Sits in ExoPlayer's audio sink chain. Converts 16-bit or float PCM to float, runs EQ +
 * ReplayGain + limiter, taps the spectrum analyser, and writes back in the input encoding.
 * Formats it can't handle (e.g. 24-bit packed) pass through untouched.
 */
@UnstableApi
class EffectsAudioProcessor(private val fx: AudioEffects) : BaseAudioProcessor() {
    private var floatBuf = FloatArray(0)
    private var fade = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        fx.equalizer.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        fx.spectrum.sampleRate = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val fmt = inputAudioFormat
        val ch = fmt.channelCount
        val is16 = fmt.encoding == C.ENCODING_PCM_16BIT
        val bytesPerSample = if (is16) 2 else 4
        val samples = remaining / bytesPerSample
        val frames = samples / ch
        val eq = fx.equalizer
        val needEq = !eq.isTransparent
        val needTap = fx.analyzerActive
        if (fx.fadeSnap) { fx.fadeSnap = false; fade = fx.fadeTarget }
        val target = fx.fadeTarget
        val needFade = fade != target || target < 0.9999f

        val out = replaceOutputBuffer(remaining)
        if (!needEq && !needTap && !needFade) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        if (floatBuf.size < samples) floatBuf = FloatArray(samples)
        val buf = floatBuf
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        if (is16) {
            for (i in 0 until samples) buf[i] = src.getShort() / 32768f
        } else {
            for (i in 0 until samples) buf[i] = src.getFloat()
        }
        if (needEq) eq.process(buf, frames)
        if (needFade) {
            // Linear in the gain's square root, squared: loudness follows the ear, not the meter.
            val step = 1000f / (fx.fadeMs * fmt.sampleRate)
            for (f in 0 until frames) {
                fade = if (fade < target) minOf(target, fade + step) else maxOf(target, fade - step)
                val g = fade * fade
                for (c in 0 until ch) buf[f * ch + c] *= g
            }
        }
        if (needTap) fx.spectrum.push(buf, frames, ch)
        if (is16) {
            for (i in 0 until samples) {
                val v = (buf[i] * 32767f).toInt().coerceIn(-32768, 32767)
                out.putShort(v.toShort())
            }
        } else {
            for (i in 0 until samples) out.putFloat(buf[i])
        }
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        fx.equalizer.reset()
    }

    override fun onReset() {
        fx.equalizer.reset()
        fx.spectrum.clear()
    }
}
