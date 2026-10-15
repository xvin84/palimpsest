package dev.xvin.palimpsest.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.xvin.palimpsest.data.settings.VisualizerMode
import dev.xvin.palimpsest.playback.AudioEffects
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Real-time spectrum drawn from PCM that flows through our own audio processor — no
 * microphone permission (unlike android.media.audiofx.Visualizer).
 */
@Composable
fun Visualizer(
    effects: AudioEffects,
    mode: VisualizerMode,
    color: Color,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    if (mode == VisualizerMode.OFF) return
    val levels = remember { FloatArray(effects.spectrum.bands) }
    var frame by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        effects.analyzerActive = true
        onDispose { effects.analyzerActive = false }
    }
    // Automatic gain: quiet masters and loud ones both fill the height.
    val peak = remember { floatArrayOf(0.3f) }

    LaunchedEffect(playing) {
        while (true) {
            withFrameNanos { t ->
                val target = if (playing) effects.spectrum.compute() else null
                if (target != null) peak[0] = maxOf(peak[0] * 0.997f, target.max(), 0.15f)
                for (i in levels.indices) {
                    val v = target?.get(i)?.let { (it / peak[0]).coerceIn(0f, 1f).let { x -> x * x } } ?: 0f
                    // Fast attack, slow release: bars jump with the beat and fall gracefully.
                    levels[i] = if (v > levels[i]) levels[i] + (v - levels[i]) * 0.6f else levels[i] * 0.90f + v * 0.10f
                }
                frame = t
            }
            if (!playing && levels.all { it < 0.01f }) break
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame // read state so each frame redraws
        when (mode) {
            VisualizerMode.BARS -> bars(levels, color)
            VisualizerMode.MIRROR -> mirror(levels, color)
            VisualizerMode.RING -> ring(levels, color)
            VisualizerMode.OFF -> Unit
        }
    }
}

private fun DrawScope.bars(levels: FloatArray, color: Color) {
    val n = levels.size
    val gap = size.width / n * 0.28f
    val w = size.width / n - gap
    val brush = Brush.verticalGradient(listOf(color, color.copy(alpha = 0.25f)))
    for (i in 0 until n) {
        val h = (levels[i] * size.height).coerceAtLeast(w * 0.6f)
        drawRoundRect(
            brush = brush,
            topLeft = Offset(i * (w + gap) + gap / 2, size.height - h),
            size = Size(w, h),
            cornerRadius = CornerRadius(w / 2, w / 2),
        )
    }
}

private fun DrawScope.mirror(levels: FloatArray, color: Color) {
    val n = levels.size
    val step = size.width / n
    val mid = size.height / 2
    for (i in 0 until n) {
        val h = (levels[i] * mid).coerceAtLeast(1.5f)
        val x = i * step + step / 2
        drawLine(color.copy(alpha = 0.35f + levels[i] * 0.65f), Offset(x, mid - h), Offset(x, mid + h), strokeWidth = step * 0.55f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.ring(levels: FloatArray, color: Color) {
    val n = levels.size
    val c = center
    val r0 = min(size.width, size.height) * 0.36f
    val maxLen = min(size.width, size.height) * 0.14f
    // Mirror left/right so the ring is symmetric: bass at the top, highs at the bottom.
    for (k in 0 until n * 2) {
        val i = if (k < n) k else 2 * n - 1 - k
        val a = (k.toFloat() / (n * 2)) * 2 * PI.toFloat() - PI.toFloat() / 2
        val len = levels[i] * maxLen + 2f
        val dx = cos(a); val dy = sin(a)
        drawLine(
            color.copy(alpha = 0.4f + levels[i] * 0.6f),
            Offset(c.x + dx * r0, c.y + dy * r0),
            Offset(c.x + dx * (r0 + len), c.y + dy * (r0 + len)),
            strokeWidth = 4f,
            cap = StrokeCap.Round,
        )
    }
}
