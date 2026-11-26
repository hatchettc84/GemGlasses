package com.lpecom.gemglasses.bobai.workmode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What Work Mode needs from Android, behind an interface so [WorkModeCore] runs on the JVM. */
interface WorkModePlatform {
    fun hasMicPermission(): Boolean

    /** Which microphone audio would come from, without opening it. */
    fun probeRoute(): MicRoute

    /** Starts the "listening" foreground service. Returns false if Android refused. */
    fun startListenService(): Boolean

    fun stopListenService()
}

/** The Gemini Live voice session, as far as Work Mode cares. */
interface VoiceLink {
    val active: Boolean

    fun stop()
}

/**
 * Work Mode's decisions, free of Android. [WorkModeController] wires it to
 * the real recognizer, foreground service and voice session.
 *
 * Rules it enforces itself, so they don't depend on who calls it in what order:
 * - The recognizer is never started while a voice session is on. If voice is
 *   on when Work Mode is enabled or re-armed, Work Mode goes straight to
 *   ACTIVE with the engine stopped; it re-arms once voice is off
 *   ([endAssistance]).
 * - The "listening" foreground service only runs while Work Mode is on and
 *   usable. It is started only after arming succeeds (still inside the
 *   enabling tap), and stopped whenever Work Mode becomes UNAVAILABLE or
 *   DISABLED, so the notification never claims to listen when nothing is.
 * - Turning Work Mode off during a session a wake word started also ends that
 *   session: it was relying on Work Mode's foreground service for the mic.
 */
class WorkModeCore(
    private val engine: WakeWordEngine,
    private val platform: WorkModePlatform,
    private val voice: VoiceLink,
    private val log: (String) -> Unit = {},
) {
    private val handoff = WakeHandoff(engine, ::dispatch)
    private val _state = MutableStateFlow(WorkModeState())
    val state: StateFlow<WorkModeState> = _state.asStateFlow()

    private var serviceRunning = false

    /** Call from the user's tap, after the RECORD_AUDIO request has been answered. */
    fun enable() {
        dispatch(WorkEvent.Enable)
        arm()
    }

    fun disable() {
        val s = _state.value
        val wakeSession = s.phase == WorkPhase.WAKE_DETECTED ||
            (s.phase == WorkPhase.ACTIVE && s.trigger == ActivationTrigger.WAKE_WORD)
        engine.stop()
        dispatch(WorkEvent.Disable)
        // The service is gone now, so a session the wake word started (perhaps
        // with the screen locked) would be left with no microphone access.
        if (wakeSession && voice.active) voice.stop()
    }

    /** Manual fallback; always available while Work Mode is on. Frees the microphone first. */
    fun startAssistance(): Boolean {
        val s = _state.value
        if (s.canStartManually || s.phase == WorkPhase.WAKE_DETECTED) engine.stop()
        dispatch(WorkEvent.ManualActivate)
        return _state.value.isActive
    }

    /** Bob is answering the wake word: WAKE_DETECTED -> ACTIVE. */
    fun acknowledge() = dispatch(WorkEvent.Acknowledged)

    /** Call once the voice session is off. If voice is still on, Work Mode stays ACTIVE. */
    fun endAssistance() {
        dispatch(WorkEvent.SessionEnded)
        if (_state.value.phase == WorkPhase.IDLE_TIMEOUT) arm()
    }

    /** Re-check permission and route when the app returns to the foreground. */
    fun recheck() {
        val s = _state.value
        if (!s.workModeOn) return
        if (!platform.hasMicPermission()) {
            engine.stop()
            dispatch(WorkEvent.ResourceLost(NO_PERMISSION))
        } else if (s.phase == WorkPhase.UNAVAILABLE && s.unavailableReason == NO_PERMISSION) {
            dispatch(WorkEvent.Disable)
            enable()
        } else if (s.phase == WorkPhase.UNAVAILABLE) {
            // Refresh the reported route without changing anything else.
            _state.update { it.copy(route = platform.probeRoute()) }
        }
    }

    private fun arm() {
        if (!platform.hasMicPermission()) {
            dispatch(WorkEvent.EngineFailed(NO_PERMISSION))
            return
        }
        if (voice.active) {
            // A Gemini Live session holds the mic. Don't open the recognizer
            // next to it; count the session as Work Mode assistance instead,
            // and re-arm when it ends.
            engine.stop()
            dispatch(WorkEvent.VoiceBusy)
            ensureService()
            return
        }
        val route = platform.probeRoute()
        if (route.kind == RouteKind.NONE) {
            dispatch(WorkEvent.EngineFailed(NO_MICROPHONE, route))
            return
        }
        when (val r = handoff.arm(route)) {
            EngineStart.Started -> {
                dispatch(WorkEvent.EngineReady(route))
                ensureService()
            }
            is EngineStart.Failed -> dispatch(WorkEvent.EngineFailed(r.reason, route))
        }
    }

    private fun ensureService() {
        if (serviceRunning || !_state.value.workModeOn) return
        serviceRunning = platform.startListenService()
    }

    private fun dispatch(e: WorkEvent) {
        _state.update { WorkModeMachine.reduce(it, e) }
        val phase = _state.value.phase
        log("$e -> $phase")
        // Nothing is listening now, so don't leave the "listening" notification
        // (or its wakelock) up. On DISABLED stop it even if this process didn't
        // start it, e.g. "Turn off" on a notification left from before.
        if (phase == WorkPhase.UNAVAILABLE) engine.stop()
        if ((phase == WorkPhase.UNAVAILABLE && serviceRunning) || phase == WorkPhase.DISABLED) {
            serviceRunning = false
            platform.stopListenService()
        }
    }

    companion object {
        const val NO_PERMISSION = "Microphone permission not granted"
        const val NO_MICROPHONE = "No microphone is available"
    }
}
