package dev.xvin.palimpsest.core

import dev.xvin.palimpsest.core.radio.Era
import dev.xvin.palimpsest.core.radio.NostalgiaStation
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class NostalgiaTest {
    @Test
    fun eraFollowsTheMiddleYear() {
        assertEquals(Era.HOLO, Era.of(NostalgiaStation.around(2012)))
        assertEquals(Era.GLOSS, Era.of(NostalgiaStation.around(2008)))
        assertEquals(Era.WINAMP, Era.of(2000..2006))
        assertEquals(Era.CASSETTE, Era.of(1975..1985))
        assertNull(Era.of(NostalgiaStation.around(2024)))
    }
}
