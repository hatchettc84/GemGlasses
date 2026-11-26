package com.lpecom.gemglasses.bobai.workmode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Work Mode as the wake hand-off needs it ([WorkModeCore] / [WorkModeController]). */
interface WakeWorkMode {
    val state: StateFlow<WorkModeState>
    fun hasMicPermission(): Boolean
    fun acknowledge()
    fun endAssistance()
    fun idleTimeout()
}

/** The Gemini Live voice session as the wake hand-off needs it. */
interface WakeVoice {
    val active: Boolean
    val activeFlow: Flow<Boolean>

    /** Clock time of the last user speech, Bob audio, tool call or live-view frame (0 if none). */
    fun lastActivityAt(): Long

    /** Opens (or resumes) the job session Bob's answers are logged against. */
    fun prepare()

    /** Starts the voice session; throws if it could not start. */
    fun start()
    fun stop()

    /** Asks Bob to greet the technician who just said "Hey Bob". */
    fun greet()
}

/**
 * The "Hey Bob" → voice → re-arm loop, free of Android so it is JVM tested.
 * It lives for the whole process ([WakeSessionCoordinator] is a singleton with
 * its own scope), not in a screen's ViewModel: the listening foreground
 * service keeps the process and the recognizer alive after the Activity is
 * gone, and a wake must still start voice then.
 *
 * - WAKE_DETECTED: acknowledge (→ ACTIVE), wait [settleMs] for the recognizer
 *   to release the microphone, start voice and ask Bob to greet. If voice
 *   can't start, go back to listening.
 * - A wake-word session ends by itself after [idleMs] with no user speech, no
 *   Bob audio and no live view ([WorkEvent.IdleTimeout]); nothing more is
 *   uploaded, and "Hey Bob" re-arms. It also ends, the same way, [maxMs] after it
 *   started however busy it is. Sessions started from the button keep running
 *   until stopped.
 * - When voice turns off while Work Mode is ACTIVE, wait [settleMs] (so Bob's
 *   own voice can't wake the detector) and re-arm.
 */
class WakeSessionCore(
    private val scope: CoroutineScope,
    private val workMode: WakeWorkMode,
    private val voice: WakeVoice,
    private val clock: () -> Long,
    private val settleMs: Long = MIC_HANDOFF_SETTLE_MS,
    private val idleMs: Long = IDLE_TIMEOUT_MS,
    private val maxMs: Long = WAKE_SESSION_MAX_MS,
    private val log: (String) -> Unit = {},
) {
    private var idleJob: Job? = null
    private var endingForIdle = false
    private var started = false

    /** Starts observing Work Mode and voice. Call once. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            workMode.state.map { it.phase }.distinctUntilChanged().collect { phase ->
                if (phase == WorkPhase.WAKE_DETECTED) onWake()
            }
        }
        scope.launch {
            var wasActive = voice.active
            voice.activeFlow.collect { active ->
                val ended = wasActive && !active
                wasActive = active
                if (ended) onVoiceEnded()
            }
        }
    }

    private fun onWake() {
        workMode.acknowledge()
        // Work Mode never arms while voice is on, but if a session is somehow
        // already running, just carry on with it: no second start, no notice.
        if (voice.active) return
        voice.prepare()
        if (!workMode.hasMicPermission()) {
            workMode.endAssistance()
            return
        }
        scope.launch {
            delay(settleMs)
            if (!workMode.state.value.isActive || voice.active) return@launch
            val result = runCatching { voice.start() }
            if (result.isFailure) {
                log("voice did not start after the wake word: ${result.exceptionOrNull()?.javaClass?.simpleName}")
                runCatching { voice.stop() }
                workMode.endAssistance() // back to listening for "Hey Bob"
                return@launch
            }
            voice.greet()
            watchIdle()
        }
    }

    private fun isWakeSession(): Boolean {
        val s = workMode.state.value
        return s.isActive && s.trigger == ActivationTrigger.WAKE_WORD && voice.active
    }

    private fun watchIdle() {
        idleJob?.cancel()
        val startedAt = clock()
        idleJob = scope.launch {
            var reachedMax = false
            while (true) {
                val now = clock()
                val last = maxOf(startedAt, voice.lastActivityAt())
                val idleLeft = idleMs - (now - last)
                val maxLeft = maxMs - (now - startedAt)
                if (maxLeft <= 0) { reachedMax = true; break }
                if (idleLeft <= 0) break
                delay(minOf(idleLeft, maxLeft))
                if (!isWakeSession()) return@launch
            }
            // Ended another way meanwhile: that end is not an idle one.
            if (!isWakeSession()) return@launch
            if (reachedMax) log("wake-word session reached its ${maxMs / 60_000} min maximum: ending it")
            else log("no speech for ${idleMs / 1000} s: ending the wake-word session")
            endingForIdle = true
            runCatching { voice.stop() }
                .onFailure { log("voice stop failed: ${it.javaClass.simpleName}") }
            if (voice.active) {
                // stop() was a no-op: whatever ends this session later is not an idle end.
                endingForIdle = false
                log("voice stop did nothing; the session end will not count as idle")
            }
        }
    }

    private suspend fun onVoiceEnded() {
        idleJob?.cancel()
        idleJob = null
        val idle = endingForIdle
        endingForIdle = false
        if (!workMode.state.value.isActive) return
        delay(settleMs)
        if (voice.active || !workMode.state.value.isActive) return
        if (idle) workMode.idleTimeout() else workMode.endAssistance()
    }

    companion object {
        /** Time for one microphone client to close its input before the other opens. */
        const val MIC_HANDOFF_SETTLE_MS = 300L

        /** A wake-word session with no speech either way for this long ends by itself. */
        const val IDLE_TIMEOUT_MS = 45_000L

        /** A wake-word session ends this long after it started, however busy (then as on idle). */
        const val WAKE_SESSION_MAX_MS = 600_000L
    }
}
