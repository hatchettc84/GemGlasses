package com.lpecom.gemglasses.bobai.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ends the voice session after Bob's goodbye, once Bob called
 * `end_conversation`: [graceMs] after the next completed turn (so the goodbye
 * finishes playing), or after [fallbackMs] if no turn completes.
 * Pure coroutine timing, so it is JVM tested.
 */
class EndAfterReply(
    private val scope: CoroutineScope,
    private val graceMs: Long = GRACE_MS,
    private val fallbackMs: Long = FALLBACK_MS,
    private val end: () -> Unit,
) {
    private var pending: Job? = null

    @Volatile var requested = false
        private set

    /** Bob called end_conversation; its reply is still to come. */
    fun request() {
        if (requested) return
        requested = true
        schedule(fallbackMs)
    }

    /** A model turn completed (after the tool response was sent). */
    fun onTurnComplete() {
        if (requested) schedule(graceMs)
    }

    /** The session stopped another way: forget the request. */
    fun reset() {
        requested = false
        pending?.cancel()
        pending = null
    }

    private fun schedule(afterMs: Long) {
        pending?.cancel()
        pending = scope.launch {
            delay(afterMs)
            requested = false
            pending = null
            end()
        }
    }

    companion object {
        const val GRACE_MS = 1_500L
        const val FALLBACK_MS = 10_000L
    }
}
