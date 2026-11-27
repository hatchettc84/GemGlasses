package com.lpecom.gemglasses.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {
    private val zeros = ByteArray(640)
    private val quietNoise = ByteArray(640).also { it[100] = 1 }

    @Test fun `trips after the configured run of all-zero chunks`() {
        val d = SilenceDetector(3)
        assertFalse(d.isDead(zeros))
        assertFalse(d.isDead(zeros))
        assertTrue(d.isDead(zeros))
    }

    @Test fun `once real audio has arrived, later silence never trips`() {
        // Seen on RB Meta glasses: the mic works, then sends exact zeros
        // whenever nobody is talking. That's a quiet room, not a dead route.
        val d = SilenceDetector(3)
        d.isDead(zeros); d.isDead(zeros)
        assertFalse(d.isDead(quietNoise))
        repeat(10) { assertFalse(d.isDead(zeros)) }
    }

    @Test fun `audio during the grace period also proves the route works`() {
        val d = SilenceDetector(2, graceChunks = 3)
        d.isDead(zeros); d.isDead(quietNoise); d.isDead(zeros)
        repeat(5) { assertFalse(d.isDead(zeros)) }
    }

    @Test fun `silence during the start-up grace period never counts`() {
        val d = SilenceDetector(2, graceChunks = 3)
        repeat(3) { assertFalse(d.isDead(zeros)) }
        assertFalse(d.isDead(zeros))
        assertTrue(d.isDead(zeros))
    }

    @Test fun `only the read length is inspected`() {
        val d = SilenceDetector(1)
        assertTrue(d.isDead(quietNoise, length = 50))
    }
}
