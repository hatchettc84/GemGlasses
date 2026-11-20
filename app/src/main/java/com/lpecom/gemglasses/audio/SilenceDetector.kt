package com.lpecom.gemglasses.audio

/**
 * Detects a dead audio route: a run of chunks whose PCM16 samples are all
 * exactly zero. A working microphone never produces perfect digital silence
 * for seconds at a time, even in a quiet room, so this means the route
 * (e.g. a Bluetooth SCO link that never started) is delivering nothing.
 */
class SilenceDetector(private val chunksToTrip: Int, private val graceChunks: Int = 0) {
    private var zeroRun = 0
    private var seen = 0

    /**
     * Feeds one chunk; returns true once [chunksToTrip] all-zero chunks arrived in a row.
     * The first [graceChunks] chunks never count: a Bluetooth SCO link reads
     * silence for a few seconds while it comes up.
     */
    fun isDead(pcm: ByteArray, length: Int = pcm.size): Boolean {
        if (seen < graceChunks) { seen++; return false }
        zeroRun = if (allZero(pcm, length)) zeroRun + 1 else 0
        return zeroRun >= chunksToTrip
    }

    fun reset() { zeroRun = 0 }

    private fun allZero(pcm: ByteArray, length: Int): Boolean {
        for (i in 0 until length) if (pcm[i].toInt() != 0) return false
        return true
    }
}
