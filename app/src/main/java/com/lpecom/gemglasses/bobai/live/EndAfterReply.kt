package com.lpecom.gemglasses.bobai.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ends the voice session after Bob's goodbye, once Bob called `end_conversation`.
 *
 * - Goodbye audio ([onAudio]) arrives after the tool call, then its turn completes: wait until the
 *   speaker has played everything queued ([pendingPlaybackMs] reaches 0), then [graceMs], then end.
 * - If a turn completes before any goodbye audio (the turn that carried the tool call), keep
 *   waiting for the next reply.
 * - [fallbackMs] after the tool call with no completed goodbye turn: end anyway, but let any
 *   goodbye audio already queued finish playing first (plus [graceMs]).
 * - Waiting for the speaker to drain never takes longer than [maxDrainMs], in case a route
 *   reports its playback position badly.
 *
 * Calls come from the Live event collector and from stop() on any thread; all state is guarded
 * by one lock, and a generation number makes sure a cancelled or replaced timer never ends a
 * later session. Pure coroutine timing, so it is JVM tested.
 */
class EndAfterReply(
    private val scope: CoroutineScope,
    private val pendingPlaybackMs: () -> Long,
    private val graceMs: Long = GRACE_MS,
    private val fallbackMs: Long = FALLBACK_MS,
    private val maxDrainMs: Long = MAX_DRAIN_MS,
    private val end: () -> Unit,
) {
    private val lock = Any()
    private var pending: Job? = null
    private var generation = 0L
    private var sawAudio = false
    private var draining = false
    private var _requested = false

    /** Bob called end_conversation and the session has not ended yet. */
    val requested: Boolean get() = synchronized(lock) { _requested }

    /** Bob called end_conversation; its reply is still to come. */
    fun request() = synchronized(lock) {
        if (_requested) return@synchronized
        _requested = true
        sawAudio = false
        draining = false
        schedule(drainFirst = null, afterMs = fallbackMs)
    }

    /** A chunk of Bob's audio was written to the speaker. */
    fun onAudio() = synchronized(lock) {
        if (_requested) sawAudio = true
    }

    /** A model turn completed (after the tool response was sent). */
    fun onTurnComplete() = synchronized(lock) {
        // No goodbye audio yet: this was the tool call's own turn; wait for the reply.
        if (!_requested || !sawAudio || draining) return@synchronized
        draining = true
        schedule(drainFirst = true, afterMs = 0)
    }

    /** The session stopped another way: forget the request. */
    fun reset() = synchronized(lock) {
        _requested = false
        sawAudio = false
        draining = false
        generation++
        pending?.cancel()
        pending = null
    }

    /**
     * Must hold [lock]. [drainFirst] null means "decide when the timer fires" (the fallback):
     * drain if goodbye audio arrived by then.
     */
    private fun schedule(drainFirst: Boolean?, afterMs: Long) {
        val gen = ++generation
        pending?.cancel()
        pending = scope.launch {
            delay(afterMs)
            val drain = drainFirst ?: synchronized(lock) { generation == gen && sawAudio }
            if (drain) {
                var waited = 0L
                while (waited < maxDrainMs) {
                    val left = pendingPlaybackMs()
                    if (left <= 0) break
                    val step = minOf(left, maxDrainMs - waited)
                    delay(step)
                    waited += step
                }
                delay(graceMs)
            }
            val go = synchronized(lock) {
                if (generation != gen) {
                    false
                } else {
                    _requested = false
                    sawAudio = false
                    draining = false
                    pending = null
                    true
                }
            }
            if (go) end()
        }
    }

    companion object {
        const val GRACE_MS = 1_500L
        const val FALLBACK_MS = 10_000L
        const val MAX_DRAIN_MS = 15_000L
    }
}
