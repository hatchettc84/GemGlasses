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

    @Test fun `any non-zero sample resets the run`() {
        val d = SilenceDetector(3)
        d.isDead(zeros); d.isDead(zeros)
        assertFalse(d.isDead(quietNoise))
        assertFalse(d.isDead(zeros))
        assertFalse(d.isDead(zeros))
        assertTrue(d.isDead(zeros))
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
