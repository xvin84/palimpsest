package dev.xvin.palimpsest.core

import dev.xvin.palimpsest.core.covers.CoverTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverToneTest {
    private fun cover(vararg parts: Pair<Int, Int>): IntArray = parts.flatMap { (argb, n) -> List(n) { argb } }.toIntArray()

    @Test
    fun blackAndWhiteCoverWithALittleSkinIsNeutral() {
        // The Measora cover: mostly black and white, a face in a warm tint.
        val px = cover(0xFF000000.toInt() to 1000, 0xFFFFFFFF.toInt() to 900, 0xFF808080.toInt() to 200, 0xFFE0A080.toInt() to 120)
        assertEquals(CoverTone.NEUTRAL, CoverTone.of(px))
        assertTrue(CoverTone.isNeutral(CoverTone.of(px)))
    }

    @Test
    fun theBiggestHueWinsOverTheMostVividSpot() {
        val navy = 0xFF1E3A8A.toInt()
        val px = cover(navy to 1200, 0xFFFF2020.toInt() to 150, 0xFF000000.toInt() to 300)
        val tone = CoverTone.of(px)
        assertFalse(CoverTone.isNeutral(tone))
        assertTrue("blue expected, got ${Integer.toHexString(tone)}", (tone and 0xFF) > (tone shr 16 and 0xFF))
    }

    @Test
    fun aRedCoverStaysRed() {
        val tone = CoverTone.of(cover(0xFF8B1A1A.toInt() to 1500, 0xFF101010.toInt() to 500))
        assertTrue((tone shr 16 and 0xFF) > 0x70 && (tone and 0xFF) < 0x40)
    }
}
