package dev.xvin.palimpsest.desktop.playback

import dev.xvin.palimpsest.core.dsp.Equalizer
import dev.xvin.palimpsest.core.dsp.SpectrumAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/**
 * Desktop playback: ffmpeg decodes any format to 44.1 kHz stereo PCM, the shared :core DSP
 * (the same equalizer, limiter and spectrum analyser as on Android) processes it, and
 * javax.sound plays it.
 */
class AudioEngine {
    data class State(val file: File? = null, val playing: Boolean = false, val positionMs: Long = 0, val ended: Boolean = false, val error: String? = null)

    /** 0..1, applied after the EQ and ReplayGain. */
    @Volatile var volume: Float = 1f

    /** Bumped on every natural end, so listeners see each end even when two in a row look alike. */
    @Volatile var endCount = 0
        private set

    val equalizer = Equalizer().apply { configure(RATE, CHANNELS) }
    val spectrum = SpectrumAnalyzer(2048, 48).apply { sampleRate = RATE }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var line: SourceDataLine? = null
    private var process: Process? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var generation = 0
    @Volatile private var paused = false
    @Volatile private var framesWritten = 0L
    private var startMs = 0L

    fun play(file: File, fromMs: Long = 0) {
        stopInternal()
        val gen = ++generation
        paused = false
        startMs = fromMs
        framesWritten = 0
        val fmt = AudioFormat(RATE.toFloat(), 16, CHANNELS, true, false)
        val l = AudioSystem.getSourceDataLine(fmt).apply { open(fmt, RATE * 4 / 5); start() } // ~200 ms buffer
        line = l
        val p = try {
            ProcessBuilder(
            ffmpeg, "-v", "quiet", "-ss", "%.3f".format(java.util.Locale.ROOT, fromMs / 1000.0),
            "-i", file.absolutePath, "-vn", "-f", "s16le", "-ac", "$CHANNELS", "-ar", "$RATE", "-",
            ).redirectErrorStream(false).start()
        } catch (e: java.io.IOException) {
            l.close(); line = null
            _state.value = State(file, error = "ffmpeg not found")
            return
        }
        process = p
        _state.value = State(file, playing = true, positionMs = fromMs)
        worker = thread(name = "palimpsest-audio", isDaemon = true) { pump(gen, p.inputStream, l) }
    }

    private fun pump(gen: Int, input: InputStream, l: SourceDataLine) {
        val bytes = ByteArray(4096 * 4)
        val floats = FloatArray(4096 * 2)
        try {
            while (gen == generation) {
                if (paused) { Thread.sleep(20); continue }
                var n = 0
                while (n < bytes.size) {
                    val r = input.read(bytes, n, bytes.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n <= 0) break
                val samples = n / 2
                for (i in 0 until samples) {
                    floats[i] = ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f
                }
                val frames = samples / CHANNELS
                if (!equalizer.isTransparent) equalizer.process(floats, frames)
                spectrum.push(floats, frames, CHANNELS)
                val v = volume
                if (v < 0.999f) for (i in 0 until samples) floats[i] *= v * v
                for (i in 0 until samples) {
                    val v = (floats[i] * 32767f).toInt().coerceIn(-32768, 32767)
                    bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte()
                }
                if (gen != generation) break
                l.write(bytes, 0, samples * 2)
                framesWritten += frames
                _state.value = _state.value.copy(positionMs = position())
            }
            if (gen == generation) {
                l.drain()
                endCount++
                _state.value = _state.value.copy(playing = false, ended = true)
            }
        } catch (_: Exception) {
        }
    }

    /** Played position: frames handed to the line minus what's still buffered in it. */
    private fun position(): Long {
        val buffered = line?.let { (it.bufferSize - it.available()) / 4 } ?: 0
        return startMs + (framesWritten - buffered).coerceAtLeast(0) * 1000 / RATE
    }

    fun toggle() {
        val s = _state.value
        if (s.file == null) return
        if (s.ended) { play(s.file); return }
        paused = !paused
        if (paused) line?.stop() else line?.start()
        _state.value = s.copy(playing = !paused)
    }

    fun seek(ms: Long) {
        val f = _state.value.file ?: return
        val wasPaused = paused
        play(f, ms)
        if (wasPaused) toggle()
    }

    fun stop() { stopInternal(); _state.value = State() }

    private fun stopInternal() {
        generation++
        process?.destroy()
        process = null
        line?.run { stop(); flush(); close() }
        line = null
        worker = null
    }

    companion object {
        /** A bundled ffmpeg (packaged builds put it in the app's resources), else the one on PATH. */
        val ffmpeg: String by lazy {
            val dir = System.getProperty("compose.application.resources.dir")?.let(::File)
            val exe = if (System.getProperty("os.name").lowercase().contains("win")) "ffmpeg.exe" else "ffmpeg"
            dir?.let { File(it, exe) }?.takeIf { it.isFile }?.also { it.setExecutable(true) }?.absolutePath ?: "ffmpeg"
        }

        fun ffmpegAvailable(): Boolean = runCatching { ProcessBuilder(ffmpeg, "-version").start().waitFor() == 0 }.getOrDefault(false)

        const val RATE = 44_100
        const val CHANNELS = 2
    }
}
