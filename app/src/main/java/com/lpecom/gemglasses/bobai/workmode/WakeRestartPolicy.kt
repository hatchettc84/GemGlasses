package com.lpecom.gemglasses.bobai.workmode

/**
 * When to restart the recognizer after each listening window ends, and when
 * to give up. Pure (times are passed in) so it is JVM tested.
 *
 * - A result or an ordinary silence timeout restarts after [RESTART_MS].
 * - A silence error that comes back almost at once (under [FAST_SILENCE_MS]
 *   after listening started) is treated as a real error, so a muted or
 *   blocked microphone can't spin the recognizer every 250 ms.
 * - [MAX_ERRORS] errors inside [ERROR_WINDOW_MS] wait [BACKOFF_MS] (one
 *   backoff cycle). After [MAX_BACKOFFS] cycles with no healthy window in
 *   between, it gives up and Work Mode reports "Hey Bob" unavailable.
 */
class WakeRestartPolicy {

    sealed interface Decision {
        data class Restart(val delayMs: Long) : Decision
        data object GiveUp : Decision
    }

    private val recentErrors = ArrayDeque<Long>()
    private var backoffs = 0
    private var listenStartedAt = 0L

    fun reset() {
        recentErrors.clear()
        backoffs = 0
    }

    fun onListen(now: Long) {
        listenStartedAt = now
    }

    /** A final result that wasn't the wake phrase: the recognizer is healthy. */
    fun onResult(): Decision {
        backoffs = 0
        return Decision.Restart(RESTART_MS)
    }

    /** ERROR_NO_MATCH or ERROR_SPEECH_TIMEOUT. */
    fun onSilence(now: Long): Decision {
        if (now - listenStartedAt < FAST_SILENCE_MS) return onError(now)
        backoffs = 0
        return Decision.Restart(RESTART_MS)
    }

    fun onError(now: Long): Decision {
        recentErrors.addLast(now)
        while (recentErrors.isNotEmpty() && now - recentErrors.first() > ERROR_WINDOW_MS) recentErrors.removeFirst()
        if (recentErrors.size < MAX_ERRORS) return Decision.Restart(RESTART_MS)
        recentErrors.clear()
        backoffs++
        return if (backoffs > MAX_BACKOFFS) Decision.GiveUp else Decision.Restart(BACKOFF_MS)
    }

    companion object {
        const val RESTART_MS = 250L
        const val BACKOFF_MS = 5_000L
        const val ERROR_WINDOW_MS = 30_000L
        const val MAX_ERRORS = 5
        const val MAX_BACKOFFS = 3
        const val FAST_SILENCE_MS = 1_000L
    }
}
