package com.lpecom.gemglasses.audio

/**
 * Detects a dead audio route: a Bluetooth SCO link that never started and
 * delivers only chunks whose PCM16 samples are all exactly zero.
 *
 * Only a route that has never delivered real audio can be dead. RB Meta
 * glasses send exact zeros whenever nobody is talking, so once any non-zero
 * sample has arrived, later silence is a quiet room, not a dead link.
 */
class SilenceDetector(private val chunksToTrip: Int, private val graceChunks: Int = 0) {
    private var zeroRun = 0
    private var seen = 0
    private var heardAudio = false

    /**
     * Feeds one chunk; returns true once [chunksToTrip] all-zero chunks arrived
     * in a row before any real audio. The first [graceChunks] chunks never count
     * towards the run: a Bluetooth SCO link reads silence while it comes up.
     */
    fun isDead(pcm: ByteArray, length: Int = pcm.size): Boolean {
        if (heardAudio) return false
        if (!allZero(pcm, length)) {
            heardAudio = true
            return false
        }
        if (seen < graceChunks) { seen++; return false }
        zeroRun++
        return zeroRun >= chunksToTrip
    }

    fun reset() { zeroRun = 0 }

    private fun allZero(pcm: ByteArray, length: Int): Boolean {
        for (i in 0 until length) if (pcm[i].toInt() != 0) return false
        return true
    }
}
