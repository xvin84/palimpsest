package dev.xvin.palimpsest.core.render

/** The typefaces a video frame uses: Literata, Unbounded, Onest, JetBrains Mono (all variable, all with Cyrillic). */
enum class Face { SERIF, DISPLAY, SANS, MONO }

enum class Align { START, CENTER, END }

data class Font(val face: Face, val size: Float, val weight: Int = 400)

/**
 * What a video frame is drawn with: the phone wraps an android.graphics.Canvas, the computer a
 * Skia canvas. Coordinates are in the frame's own units (1080 × 1920 or 1920 × 1080); the
 * platform scales for the output size. Colours are ARGB ints.
 */
interface Pen {
    fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float = 0f)
    fun stroke(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float = 0f)
    /** A line with round ends. */
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float)
    fun circle(cx: Float, cy: Float, r: Float, color: Int)
    fun verticalGradient(l: Float, t: Float, r: Float, b: Float, top: Int, bottom: Int)
    /** A soft round glow: [inner] at the centre fading to [outer] at [radius]. */
    fun glow(cx: Float, cy: Float, radius: Float, inner: Int, outer: Int)
    /** A soft shadow under a rounded rectangle (drawn before the thing that casts it). */
    fun shadow(l: Float, t: Float, r: Float, b: Float, radius: Float, blur: Float, color: Int)
    /** The cover, centre-cropped into the rectangle; without one, a flat [fallback]. */
    fun cover(l: Float, t: Float, r: Float, b: Float, radius: Float, fallback: Int)
    /** The cover blurred into a backdrop, at [alpha]. */
    fun blurredCover(l: Float, t: Float, r: Float, b: Float, alpha: Float)
    /** One line of text; [y] is the baseline. */
    fun text(s: String, x: Float, y: Float, font: Font, color: Int, align: Align = Align.START, shadow: Boolean = false)
    fun width(s: String, font: Font): Float
    /** Distance from the baseline up to the top of capitals and down to descenders, both positive. */
    fun ascent(font: Font): Float
    fun descent(font: Font): Float

    fun save()
    fun restore()
    fun clip(l: Float, t: Float, r: Float, b: Float)
    fun translate(dx: Float, dy: Float)
    fun scale(s: Float)
    fun rotate(degrees: Float, px: Float, py: Float)
}

object Colors {
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    fun alpha(c: Int, a: Float) = ((a.coerceIn(0f, 1f) * ((c ushr 24) and 0xFF)).toInt() shl 24) or (c and 0xFFFFFF)
    fun lerp(a: Int, b: Int, t: Float): Int {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt()
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
