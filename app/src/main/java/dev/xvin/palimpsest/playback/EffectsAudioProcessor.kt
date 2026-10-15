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
}

/**
 * Sits in ExoPlayer's audio sink chain. Converts 16-bit or float PCM to float, runs EQ +
 * ReplayGain + limiter, taps the spectrum analyser, and writes back in the input encoding.
 * Formats it can't handle (e.g. 24-bit packed) pass through untouched.
 */
@UnstableApi
class EffectsAudioProcessor(private val fx: AudioEffects) : BaseAudioProcessor() {
    private var floatBuf = FloatArray(0)

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

        val out = replaceOutputBuffer(remaining)
        if (!needEq && !needTap) {
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
