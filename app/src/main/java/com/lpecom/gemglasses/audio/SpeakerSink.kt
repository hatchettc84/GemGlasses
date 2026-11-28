package com.lpecom.gemglasses.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays the assistant's PCM (16-bit / 24 kHz / mono) through the glasses
 * speaker. The track is opened once and kept alive across session reconnects so
 * the transition is inaudible. On barge-in, [flush] clears queued audio
 * immediately so the assistant stops talking over the user.
 */
@Singleton
class SpeakerSink @Inject constructor() {

    @Volatile private var track: AudioTrack? = null

    /** Frames written since the track's playback head was last reset (open or flush). */
    private val framesWritten = java.util.concurrent.atomic.AtomicLong()

    private val minBuffer = AudioTrack.getMinBufferSize(
        AudioSpec.OUTPUT_SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )

    fun open() {
        if (track != null) return
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioSpec.OUTPUT_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, AudioSpec.OUTPUT_SAMPLE_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
        framesWritten.set(0)
        Log.i(TAG, "speaker opened")
    }

    /** Queues a chunk of assistant audio for playback. */
    fun write(pcm: ByteArray) {
        val t = track ?: return
        val written = t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        if (written > 0) framesWritten.addAndGet((written / BYTES_PER_FRAME).toLong())
    }

    /**
     * Milliseconds of written audio that has not played yet (0 when closed). Used to end a
     * session only after Bob's goodbye has actually been heard.
     */
    fun pendingPlaybackMs(): Long {
        val t = track ?: return 0
        val head = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrNull() ?: return 0
        val queued = (framesWritten.get() - head).coerceAtLeast(0)
        return queued * 1000 / AudioSpec.OUTPUT_SAMPLE_RATE
    }

    /** Barge-in: drop everything queued so the assistant goes silent at once. */
    fun flush() {
        val t = track ?: return
        t.pause()
        t.flush()
        framesWritten.set(0) // flush resets the playback head
        t.play()
    }

    fun close() {
        track?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            it.release()
        }
        track = null
        framesWritten.set(0)
    }

    private companion object {
        const val TAG = "SpeakerSink"
        const val BYTES_PER_FRAME = 2 // 16-bit mono
    }
}
